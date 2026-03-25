package org.onekash.mcp.calendar

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredResource
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import org.onekash.mcp.calendar.caldav.CalDavCredentials
import org.onekash.mcp.calendar.caldav.OkHttpCalDavClient
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.security.CredentialManager
import org.onekash.mcp.calendar.ratelimit.RateLimiter
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import org.onekash.mcp.calendar.logging.McpLogger
import org.onekash.mcp.calendar.validation.InputValidator
import org.onekash.mcp.calendar.validation.InputValidator.ValidationResult

// Global rate limiter instance
private val rateLimiter = RateLimiter(readLimit = 60, writeLimit = 20, windowMs = 60_000)

/**
 * Apple Calendar MCP Server
 *
 * Provides AI assistants with access to iCloud Calendar via CalDAV.
 *
 * Security:
 * - Input validation on all parameters
 * - Credential masking in logs
 * - Secure error handling (no sensitive data leakage)
 *
 * Tools:
 * - list_calendars: List all calendars from iCloud
 * - get_events: Get events in a date range
 * - create_event: Create a new event
 * - update_event: Update an existing event
 * - delete_event: Delete an event
 */
// iCloud CalDAV base URL
private const val ICLOUD_CALDAV_URL = "https://caldav.icloud.com"

fun main(args: Array<String>) {
    runBlocking {
    // Check credential configuration at startup (but don't expose them)
    val credentialsConfigured = CredentialManager.isConfigured()
    if (!credentialsConfigured) {
        System.err.println("Warning: iCloud credentials not configured. Set ICLOUD_USERNAME and ICLOUD_PASSWORD environment variables.")
    }

    // Create CalendarService with iCloud CalDAV client
    val (calendarService, calDavClient) = createCalendarService()

    // Register shutdown hook for graceful cleanup
    if (calDavClient != null) {
        Runtime.getRuntime().addShutdownHook(Thread {
            calDavClient.close()
        })
    }

    val server = Server(
        serverInfo = Implementation(
            name = "icloud-calendar-mcp",
            version = "3.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = true),
                logging = buildJsonObject { }  // Enable MCP logging capability
            )
        )
    )

    // Create audit logger for CUD operations (MCP08 compliance)
    val logger = McpLogger(server)

    // Register tools with security measures
    registerTools(server, calendarService, logger)

    // Register resources for browsing calendars
    registerResources(server, calendarService)

    // Start server with STDIO transport (for Claude Desktop)
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )
    val session = server.createSession(transport)

    // Keep the process alive until the transport closes
    session.onClose {
        // Session closed, process will exit
    }

    // Block until stdin is closed (transport handles read loop internally)
    // The coroutine scope keeps the process alive while the session is active
    kotlinx.coroutines.awaitCancellation()
    }
}

/**
 * Create CalendarService with iCloud CalDAV client.
 * Returns null if credentials are not configured.
 */
private fun createCalendarService(): Pair<CalendarService?, OkHttpCalDavClient?> {
    return try {
        val credentials = CredentialManager.loadFromEnvironment() ?: return Pair(null, null)
        val client = OkHttpCalDavClient(
            baseUrl = ICLOUD_CALDAV_URL,
            credentials = CalDavCredentials(
                username = credentials.username,
                password = credentials.password
            )
        )
        Pair(CalendarService(client), client)
    } catch (e: Exception) {
        System.err.println("Failed to initialize CalendarService: ${e.message}")
        Pair(null, null)
    }
}

private fun registerTools(server: Server, calendarService: CalendarService?, logger: McpLogger) {
    // ═══════════════════════════════════════════════════════════════════
    // TOOL: list_calendars
    // ═══════════════════════════════════════════════════════════════════
    server.addTool(
        name = "list_calendars",
        description = "List all calendars from the connected iCloud account",
        title = "List Calendars",
        inputSchema = ToolSchema(),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("calendars", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of calendars"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                })
            },
            required = listOf("calendars")
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true
        )
    ) { _ ->
        SecureErrorHandler.safeExecute("list_calendars") {
            // Check rate limit (read operation)
            val rateResult = rateLimiter.acquireReadWithStatus()
            if (!rateResult.allowed) {
                return@safeExecute SecureErrorHandler.rateLimitError(rateResult.retryAfterMs)
            }

            // Check credentials
            if (calendarService == null) {
                return@safeExecute SecureErrorHandler.authenticationError(
                    "iCloud credentials not configured"
                )
            }

            when (val result = calendarService.listCalendars()) {
                is ServiceResult.Success -> {
                    val calendarsJson = buildJsonObject {
                        putJsonArray("calendars") {
                            result.data.forEach { cal ->
                                addJsonObject {
                                    put("id", cal.id)
                                    put("name", cal.name)
                                    cal.color?.let { put("color", it) }
                                    put("readOnly", cal.readOnly)
                                    if (cal.supportedComponents.isNotEmpty()) {
                                        putJsonArray("supportedComponents") {
                                            cal.supportedComponents.forEach { add(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = calendarsJson.toString()))
                    )
                }
                is ServiceResult.Error -> {
                    SecureErrorHandler.serviceError(result.code, result.message)
                }
            }
        }.getOrElse { exception ->
            SecureErrorHandler.internalError(exception as Exception)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOOL: get_events
    // ═══════════════════════════════════════════════════════════════════
    server.addTool(
        name = "get_events",
        description = "Get events from a calendar within a date range",
        title = "Get Events",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("calendar_id", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Calendar ID"))
                })
                put("start_date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Start date (YYYY-MM-DD)"))
                })
                put("end_date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("End date (YYYY-MM-DD)"))
                })
            },
            required = listOf("calendar_id", "start_date", "end_date")
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("events", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("List of events"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("object")) })
                })
            },
            required = listOf("events")
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true
        )
    ) { request ->
        SecureErrorHandler.safeExecute("get_events") {
            // Check rate limit (read operation)
            val rateResult = rateLimiter.acquireReadWithStatus()
            if (!rateResult.allowed) {
                return@safeExecute SecureErrorHandler.rateLimitError(rateResult.retryAfterMs)
            }

            val args = request.arguments ?: buildJsonObject { }
            val calendarId = args["calendar_id"]?.jsonPrimitive?.content
            val startDate = args["start_date"]?.jsonPrimitive?.content
            val endDate = args["end_date"]?.jsonPrimitive?.content

            // Validate inputs
            val errors = InputValidator.collectErrors(
                InputValidator.validateCalendarId(calendarId),
                InputValidator.validateDate(startDate, "start_date"),
                InputValidator.validateDate(endDate, "end_date")
            )

            if (errors.isNotEmpty()) {
                return@safeExecute SecureErrorHandler.validationError(errors)
            }

            // Check service
            if (calendarService == null) {
                return@safeExecute SecureErrorHandler.authenticationError(
                    "iCloud credentials not configured"
                )
            }

            when (val result = calendarService.getEvents(calendarId!!, startDate!!, endDate!!)) {
                is ServiceResult.Success -> {
                    val eventsJson = buildJsonObject {
                        putJsonArray("events") {
                            result.data.forEach { event ->
                                addJsonObject {
                                    put("uid", event.uid)
                                    put("summary", event.summary)
                                    event.description?.let { put("description", it) }
                                    event.location?.let { put("location", it) }
                                    put("isAllDay", event.isAllDay)
                                    if (event.isAllDay) {
                                        event.startDate?.let { put("startDate", it) }
                                        event.endDate?.let { put("endDate", it) }
                                    } else {
                                        event.startTime?.let { put("startTime", it) }
                                        event.endTime?.let { put("endTime", it) }
                                    }
                                    event.rrule?.let { put("rrule", it) }
                                    event.status?.let { put("status", it) }
                                    event.url?.let { put("url", it) }
                                    if (event.categories.isNotEmpty()) {
                                        putJsonArray("categories") {
                                            event.categories.forEach { add(it) }
                                        }
                                    }
                                    event.priority?.let { put("priority", it) }
                                    event.organizer?.let { put("organizer", it) }
                                    if (event.attendeeCount > 0) put("attendeeCount", event.attendeeCount)
                                }
                            }
                        }
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = eventsJson.toString()))
                    )
                }
                is ServiceResult.Error -> {
                    SecureErrorHandler.serviceError(result.code, result.message)
                }
            }
        }.getOrElse { exception ->
            SecureErrorHandler.internalError(exception as Exception)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOOL: create_event
    // ═══════════════════════════════════════════════════════════════════
    server.addTool(
        name = "create_event",
        description = "Create a new calendar event",
        title = "Create Event",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("calendar_id", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Calendar ID to create event in"))
                })
                put("title", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event title"))
                })
                put("start_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Start time (ISO 8601, e.g., 2025-01-15T09:00:00Z)"))
                })
                put("end_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("End time (ISO 8601, e.g., 2025-01-15T10:00:00Z)"))
                })
                put("location", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event location (optional)"))
                })
                put("description", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event description (optional)"))
                })
                put("is_all_day", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("True for all-day events (optional)"))
                })
                put("start_date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Start date for all-day events (YYYY-MM-DD)"))
                })
                put("end_date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("End date for all-day events (YYYY-MM-DD, inclusive)"))
                })
                put("timezone", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("IANA timezone for non-UTC timed events (e.g., America/New_York)"))
                })
                put("rrule", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Recurrence rule (e.g., FREQ=WEEKLY;BYDAY=MO)"))
                })
            },
            required = listOf("calendar_id", "title")
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("uid", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("summary", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
            },
            required = listOf("success", "uid", "summary", "message")
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true
        )
    ) { request ->
        SecureErrorHandler.safeExecute("create_event") {
            // Check rate limit (write operation)
            val rateResult = rateLimiter.acquireWriteWithStatus()
            if (!rateResult.allowed) {
                return@safeExecute SecureErrorHandler.rateLimitError(rateResult.retryAfterMs)
            }

            val args = request.arguments ?: buildJsonObject { }
            val calendarId = args["calendar_id"]?.jsonPrimitive?.content
            val title = args["title"]?.jsonPrimitive?.content
            val startTime = args["start_time"]?.jsonPrimitive?.content
            val endTime = args["end_time"]?.jsonPrimitive?.content
            val location = args["location"]?.jsonPrimitive?.content
            val description = args["description"]?.jsonPrimitive?.content
            val isAllDay = args["is_all_day"]?.jsonPrimitive?.booleanOrNull ?: false
            val startDate = args["start_date"]?.jsonPrimitive?.content
            val endDate = args["end_date"]?.jsonPrimitive?.content
            val timezone = args["timezone"]?.jsonPrimitive?.content
            val rrule = args["rrule"]?.jsonPrimitive?.content

            // Validate required inputs
            val errors = mutableListOf<String>()
            errors.addAll(InputValidator.collectErrors(
                InputValidator.validateCalendarId(calendarId),
                InputValidator.validateTitle(title)
            ))

            // Validate times based on all-day flag
            if (isAllDay) {
                errors.addAll(InputValidator.collectErrors(
                    InputValidator.validateDate(startDate, "start_date"),
                    InputValidator.validateDate(endDate, "end_date")
                ))
            } else {
                errors.addAll(InputValidator.collectErrors(
                    InputValidator.validateDateTime(startTime, "start_time"),
                    InputValidator.validateDateTime(endTime, "end_time")
                ))
                // Validate time range
                if (startTime != null && endTime != null) {
                    val timeRangeResult = InputValidator.validateTimeRange(startTime, endTime)
                    if (timeRangeResult is ValidationResult.Invalid) {
                        errors.add(timeRangeResult.message)
                    }
                }
            }

            // Validate optional inputs
            errors.addAll(InputValidator.collectErrors(
                InputValidator.validateOptionalText(location, "location", maxLength = 500),
                InputValidator.validateOptionalText(description, "description", maxLength = 5000)
            ))

            if (errors.isNotEmpty()) {
                return@safeExecute SecureErrorHandler.validationError(errors)
            }

            // Check service
            if (calendarService == null) {
                return@safeExecute SecureErrorHandler.authenticationError(
                    "iCloud credentials not configured"
                )
            }

            // Sanitize text for ICS
            val safeTitle = InputValidator.sanitizeForIcs(title!!)
            val safeLocation = location?.let { InputValidator.sanitizeForIcs(it) }
            val safeDescription = description?.let { InputValidator.sanitizeForIcs(it) }

            val result = calendarService.createEvent(
                calendarId = calendarId!!,
                summary = safeTitle,
                startTime = if (!isAllDay) startTime else null,
                endTime = if (!isAllDay) endTime else null,
                startDate = if (isAllDay) startDate else null,
                endDate = if (isAllDay) endDate else null,
                isAllDay = isAllDay,
                description = safeDescription,
                location = safeLocation,
                timezone = timezone,
                rrule = rrule
            )

            when (result) {
                is ServiceResult.Success -> {
                    logger.info("Event created", mapOf(
                        "tool" to "create_event",
                        "uid" to result.data.uid,
                        "calendar" to calendarId
                    ))
                    val eventJson = buildJsonObject {
                        put("success", true)
                        put("uid", result.data.uid)
                        put("summary", result.data.summary)
                        put("message", "Event created successfully")
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = eventJson.toString()))
                    )
                }
                is ServiceResult.Error -> {
                    logger.warning("Event creation failed", mapOf(
                        "tool" to "create_event",
                        "calendar" to calendarId,
                        "code" to result.code
                    ))
                    SecureErrorHandler.serviceError(result.code, result.message)
                }
            }
        }.getOrElse { exception ->
            SecureErrorHandler.internalError(exception as Exception)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOOL: update_event
    // ═══════════════════════════════════════════════════════════════════
    server.addTool(
        name = "update_event",
        description = "Update an existing calendar event. First use get_events to find the event UID.",
        title = "Update Event",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event UID to update"))
                })
                put("title", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New event title (optional)"))
                })
                put("start_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New start time (optional)"))
                })
                put("end_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New end time (optional)"))
                })
                put("location", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New location (optional)"))
                })
                put("description", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New description (optional)"))
                })
                put("is_all_day", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Change to all-day event (optional)"))
                })
                put("start_date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New start date for all-day events (YYYY-MM-DD)"))
                })
                put("end_date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New end date for all-day events (YYYY-MM-DD, inclusive)"))
                })
                put("timezone", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("IANA timezone for non-UTC timed events (e.g., America/New_York)"))
                })
                put("rrule", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Recurrence rule (e.g., FREQ=WEEKLY;BYDAY=MO)"))
                })
            },
            required = listOf("event_id")
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("uid", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
            },
            required = listOf("success", "uid", "message")
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true
        )
    ) { request ->
        SecureErrorHandler.safeExecute("update_event") {
            // Check rate limit (write operation)
            val rateResult = rateLimiter.acquireWriteWithStatus()
            if (!rateResult.allowed) {
                return@safeExecute SecureErrorHandler.rateLimitError(rateResult.retryAfterMs)
            }

            val args = request.arguments ?: buildJsonObject { }
            val eventId = args["event_id"]?.jsonPrimitive?.content
            val title = args["title"]?.jsonPrimitive?.content
            val startTime = args["start_time"]?.jsonPrimitive?.content
            val endTime = args["end_time"]?.jsonPrimitive?.content
            val location = args["location"]?.jsonPrimitive?.content
            val description = args["description"]?.jsonPrimitive?.content
            val isAllDay = args["is_all_day"]?.jsonPrimitive?.booleanOrNull
            val startDate = args["start_date"]?.jsonPrimitive?.content
            val endDate = args["end_date"]?.jsonPrimitive?.content
            val timezone = args["timezone"]?.jsonPrimitive?.content
            val rrule = args["rrule"]?.jsonPrimitive?.content

            // Validate event ID (required)
            val errors = mutableListOf<String>()
            val eventIdResult = InputValidator.validateCalendarId(eventId, "event_id")
            if (eventIdResult is ValidationResult.Invalid) {
                errors.add(eventIdResult.message)
            }

            // Validate optional fields if provided
            if (title != null) {
                val titleResult = InputValidator.validateTitle(title)
                if (titleResult is ValidationResult.Invalid) {
                    errors.add(titleResult.message)
                }
            }

            if (startTime != null) {
                val startResult = InputValidator.validateDateTime(startTime, "start_time")
                if (startResult is ValidationResult.Invalid) {
                    errors.add(startResult.message)
                }
            }

            if (endTime != null) {
                val endResult = InputValidator.validateDateTime(endTime, "end_time")
                if (endResult is ValidationResult.Invalid) {
                    errors.add(endResult.message)
                }
            }

            // Validate time range if both provided
            if (startTime != null && endTime != null) {
                val timeRangeResult = InputValidator.validateTimeRange(startTime, endTime)
                if (timeRangeResult is ValidationResult.Invalid) {
                    errors.add(timeRangeResult.message)
                }
            }

            errors.addAll(InputValidator.collectErrors(
                InputValidator.validateOptionalText(location, "location", maxLength = 500),
                InputValidator.validateOptionalText(description, "description", maxLength = 5000)
            ))

            if (errors.isNotEmpty()) {
                return@safeExecute SecureErrorHandler.validationError(errors)
            }

            // Check service
            if (calendarService == null) {
                return@safeExecute SecureErrorHandler.authenticationError(
                    "iCloud credentials not configured"
                )
            }

            // Sanitize text inputs
            val safeTitle = title?.let { InputValidator.sanitizeForIcs(it) }
            val safeLocation = location?.let { InputValidator.sanitizeForIcs(it) }
            val safeDescription = description?.let { InputValidator.sanitizeForIcs(it) }

            val result = calendarService.updateEvent(
                eventId = eventId!!,
                summary = safeTitle,
                startTime = startTime,
                endTime = endTime,
                startDate = startDate,
                endDate = endDate,
                isAllDay = isAllDay,
                description = safeDescription,
                location = safeLocation,
                timezone = timezone,
                rrule = rrule
            )

            when (result) {
                is ServiceResult.Success -> {
                    logger.info("Event updated", mapOf(
                        "tool" to "update_event",
                        "uid" to result.data.uid,
                        "eventId" to eventId
                    ))
                    val eventJson = buildJsonObject {
                        put("success", true)
                        put("uid", result.data.uid)
                        put("message", "Event updated successfully")
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = eventJson.toString()))
                    )
                }
                is ServiceResult.Error -> {
                    logger.warning("Event update failed", mapOf(
                        "tool" to "update_event",
                        "eventId" to eventId,
                        "code" to result.code
                    ))
                    SecureErrorHandler.serviceError(result.code, result.message)
                }
            }
        }.getOrElse { exception ->
            SecureErrorHandler.internalError(exception as Exception)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOOL: delete_event
    // ═══════════════════════════════════════════════════════════════════
    server.addTool(
        name = "delete_event",
        description = "Delete a calendar event. First use get_events to find the event UID.",
        title = "Delete Event",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event UID to delete"))
                })
            },
            required = listOf("event_id")
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
            },
            required = listOf("success", "message")
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = true,
            openWorldHint = true
        )
    ) { request ->
        SecureErrorHandler.safeExecute("delete_event") {
            // Check rate limit (write operation)
            val rateResult = rateLimiter.acquireWriteWithStatus()
            if (!rateResult.allowed) {
                return@safeExecute SecureErrorHandler.rateLimitError(rateResult.retryAfterMs)
            }

            val args = request.arguments ?: buildJsonObject { }
            val eventId = args["event_id"]?.jsonPrimitive?.content

            // Validate event ID
            val eventIdResult = InputValidator.validateCalendarId(eventId, "event_id")
            if (eventIdResult is ValidationResult.Invalid) {
                return@safeExecute SecureErrorHandler.validationError(listOf(eventIdResult.message))
            }

            // Check service
            if (calendarService == null) {
                return@safeExecute SecureErrorHandler.authenticationError(
                    "iCloud credentials not configured"
                )
            }

            when (val result = calendarService.deleteEvent(eventId!!)) {
                is ServiceResult.Success -> {
                    logger.info("Event deleted", mapOf(
                        "tool" to "delete_event",
                        "eventId" to eventId
                    ))
                    val responseJson = buildJsonObject {
                        put("success", true)
                        put("message", "Event deleted successfully")
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = responseJson.toString()))
                    )
                }
                is ServiceResult.Error -> {
                    logger.warning("Event deletion failed", mapOf(
                        "tool" to "delete_event",
                        "eventId" to eventId,
                        "code" to result.code
                    ))
                    SecureErrorHandler.serviceError(result.code, result.message)
                }
            }
        }.getOrElse { exception ->
            SecureErrorHandler.internalError(exception as Exception)
        }
    }
}

/**
 * Register resources for browsing calendars.
 *
 * Resources provide a read-only view of calendar data:
 * - calendar://calendars - List all calendars
 */
private fun registerResources(server: Server, calendarService: CalendarService?) {
    server.addResources(listOf(
        RegisteredResource(
            resource = Resource(
                uri = "calendar://calendars",
                name = "iCloud Calendars",
                description = "List of all calendars in the connected iCloud account",
                mimeType = "application/json",
                title = "iCloud Calendars"
            ),
            readHandler = { _ ->
                // Check rate limit (read operation)
                val rateResult = rateLimiter.acquireReadWithStatus()
                if (!rateResult.allowed) {
                    throw McpException(-32000, "Rate limit exceeded. Retry after ${rateResult.retryAfterMs}ms")
                }

                if (calendarService == null) {
                    throw McpException(-32001, "iCloud credentials not configured")
                }

                when (val result = calendarService.listCalendars()) {
                    is ServiceResult.Success -> {
                        val calendarsJson = buildJsonObject {
                            putJsonArray("calendars") {
                                result.data.forEach { cal ->
                                    addJsonObject {
                                        put("id", cal.id)
                                        put("name", cal.name)
                                        put("uri", "calendar://calendars/${cal.id}")
                                        cal.color?.let { put("color", it) }
                                        put("readOnly", cal.readOnly)
                                    }
                                }
                            }
                        }
                        ReadResourceResult(
                            contents = listOf(
                                TextResourceContents(
                                    uri = "calendar://calendars",
                                    mimeType = "application/json",
                                    text = calendarsJson.toString()
                                )
                            )
                        )
                    }
                    is ServiceResult.Error -> {
                        throw McpException(-32002, "Calendar service error: ${result.message}")
                    }
                }
            }
        )
    ))
}
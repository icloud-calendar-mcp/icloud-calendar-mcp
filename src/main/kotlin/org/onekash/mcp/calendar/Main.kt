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
import org.onekash.mcp.calendar.ics.AlarmSpec
import org.onekash.mcp.calendar.security.CredentialManager
import org.onekash.mcp.calendar.ratelimit.RateLimiter
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import org.onekash.mcp.calendar.logging.McpLogger
import org.onekash.mcp.calendar.validation.EventScope
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
    // kotlin-logging's startup banner goes to System.out, which stdio reserves for JSON-RPC.
    // Must run before the first MCP SDK class loads. See StdioPurityTest.
    System.setProperty("kotlin-logging.logStartupMessage", "false")

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
            version = "3.1.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = true),
                prompts = ServerCapabilities.Prompts(listChanged = false),
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

    // Register user-initiated prompt templates
    registerPrompts(server)

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
/**
 * Decode the `alarms` JSON-RPC argument into the validator-friendly map shape.
 * Returns null when the field is absent or not an array — preserves
 * "leave existing alarms untouched" semantics on update_event.
 * Returns an empty list when the field is `[]` — caller asked to clear.
 */
private fun decodeAlarmsForValidation(args: JsonObject): List<Map<String, Any?>>? {
    val arr = args["alarms"]?.jsonArray ?: return null
    return arr.mapNotNull { it as? JsonObject }.map { obj ->
        mapOf<String, Any?>(
            "trigger" to obj["trigger"]?.jsonPrimitive?.contentOrNull,
            "action" to obj["action"]?.jsonPrimitive?.contentOrNull,
            "description" to obj["description"]?.jsonPrimitive?.contentOrNull,
            "summary" to obj["summary"]?.jsonPrimitive?.contentOrNull,
            "repeat_count" to obj["repeat_count"]?.jsonPrimitive?.intOrNull,
            "repeat_duration" to obj["repeat_duration"]?.jsonPrimitive?.contentOrNull
        )
    }
}

/** Convert the validated alarm shape into AlarmSpec instances for the service layer. */
private fun toAlarmSpecs(decoded: List<Map<String, Any?>>?): List<AlarmSpec>? {
    if (decoded == null) return null
    return decoded.map { entry ->
        AlarmSpec(
            trigger = entry["trigger"] as String,
            action = entry["action"] as? String,
            description = entry["description"] as? String,
            summary = entry["summary"] as? String,
            repeatCount = (entry["repeat_count"] as? Number)?.toInt(),
            repeatDuration = entry["repeat_duration"] as? String
        )
    }
}

/**
 * The single item-shape declaration for the `alarms` array, reused across the
 * create_event/update_event input schemas and their output schemas.
 */
private fun alarmItemSchema(): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildJsonObject {
        put("trigger", buildJsonObject { put("type", JsonPrimitive("string")) })
        put("action", buildJsonObject { put("type", JsonPrimitive("string")) })
        put("description", buildJsonObject { put("type", JsonPrimitive("string")) })
        put("summary", buildJsonObject { put("type", JsonPrimitive("string")) })
        put("repeat_count", buildJsonObject { put("type", JsonPrimitive("integer")) })
        put("repeat_duration", buildJsonObject { put("type", JsonPrimitive("string")) })
    })
    put("required", buildJsonArray { add(JsonPrimitive("trigger")) })
}

/**
 * Encode parsed alarms into the MCP response JSON shape so external clients can
 * verify what landed. Keys mirror the input schema (snake_case).
 */
private fun encodeAlarmsForResponse(alarms: List<org.onekash.mcp.calendar.ics.ParsedAlarm>): JsonArray =
    buildJsonArray {
        for (a in alarms) {
            add(buildJsonObject {
                put("trigger", JsonPrimitive(a.trigger))
                put("action", JsonPrimitive(a.action))
                a.description?.let { put("description", JsonPrimitive(it)) }
                a.summary?.let { put("summary", JsonPrimitive(it)) }
                a.repeatCount?.let { put("repeat_count", JsonPrimitive(it)) }
                a.repeatDuration?.let { put("repeat_duration", JsonPrimitive(it)) }
            })
        }
    }

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

internal fun registerTools(server: Server, calendarService: CalendarService?, logger: McpLogger) {
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
                        content = listOf(TextContent(text = calendarsJson.toString())),
                        structuredContent = calendarsJson
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
        description = "Get events from a calendar within a date range. Reflects iCloud's server state at query time. The range is capped at 366 days, and end_date must not precede start_date; a range past the cap or an inverted one is rejected up front. The response is also capped: a query that would return more than 1000 events, or a single recurring series that expands to too many occurrences in the range, is rejected rather than returned. Query a week or a month at a time to stay under the caps. Note on read-after-write: iCloud has no immediate-visibility guarantee, so an event created moments ago may not appear in the very next get_events (CDN indexing lag) — this is NOT a deletion. If a create_event/update_event returned success, that write landed; do not treat a briefly-missing just-created event as failed or deleted, and do not recreate it (that duplicates the event). Use the returned uid/handle to reference it directly.",
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
                    put("description", JsonPrimitive("End date (YYYY-MM-DD), inclusive. Must be on or after start_date, and within 366 days of it."))
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
                InputValidator.validateDate(endDate, "end_date"),
                // Cross-field span cap (US1): reject inverted or > 366-day ranges
                // before any CalDAV fetch. No-op when either date is malformed;
                // the validateDate calls above own those errors.
                InputValidator.validateDateSpan(startDate, endDate)
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
                                    // Self-contained reference for update/delete — works
                                    // with a cold cache / fresh process (see EventHandle).
                                    event.handle?.let { put("handle", it) }
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
                                    // RECURRENCE-ID of this instance (RFC 5545 §3.8.4.4),
                                    // present only for one occurrence of a series. Marks the
                                    // event as a series instance and identifies which one; the
                                    // handle already encodes it for scoped update/delete.
                                    event.recurrenceId?.let { put("recurrenceId", it) }
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
                        content = listOf(TextContent(text = eventsJson.toString())),
                        structuredContent = eventsJson
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
        description = "Create a new calendar event. A success response is authoritative: the event was persisted on iCloud and the returned uid/handle can be used immediately for update_event/delete_event. Do NOT verify the write by re-listing with get_events and retrying on absence — iCloud's CDN may not surface a just-created event in the next listing for a short window, and a retry would create a duplicate.",
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
                put("summary", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Alias for 'title' (the field get_events and the create response echo back). Used only when 'title' is omitted."))
                })
                put("start_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Start time (ISO 8601). Naive local (2025-01-15T09:00:00): interpreted as UTC unless 'timezone' is set. UTC (2025-01-15T09:00:00Z) or offset (2025-01-15T18:00:00+09:00): an absolute instant that wins over 'timezone'."))
                })
                put("end_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("End time (ISO 8601). Naive local (2025-01-15T10:00:00): interpreted as UTC unless 'timezone' is set. UTC (2025-01-15T10:00:00Z) or offset (2025-01-15T19:00:00+09:00): an absolute instant that wins over 'timezone'."))
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
                put("end_timezone", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional IANA timezone for DTEND when distinct from start (e.g., flights JFK->LAX). Falls back to timezone when omitted."))
                })
                put("rrule", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Recurrence rule (e.g., FREQ=WEEKLY;BYDAY=MO). For biweekly schedules whose BYDAY includes Sunday, set WKST=<two-letter-day> explicitly to avoid ical4j defaulting to MO and dropping occurrences."))
                })
                put("rdates", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Optional additional occurrence dates (RFC 5545 RDATE). ISO 8601 instants for timed events; YYYY-MM-DD for all-day. One value per array element."))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
                put("exdates", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Optional excluded occurrence dates (RFC 5545 EXDATE). Same format as rdates."))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
                put("alarms", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Optional VALARM components (RFC 5545 §3.6.6). Each entry is an object with: trigger (required: '-PT15M' duration form or '20260115T093000Z' absolute), action (DISPLAY|AUDIO|EMAIL, default DISPLAY), description, summary (EMAIL only), repeat_count, repeat_duration."))
                    put("items", alarmItemSchema())
                })
            },
            required = listOf("calendar_id", "title")
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("uid", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("handle", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Opaque, self-contained reference to pass to update_event/delete_event. Works even from a fresh process (no cache dependency)."))
                })
                put("summary", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("alarms", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("VALARM components attached to the created event (RFC 5545 §3.6.6); present only when the event has alarms."))
                    put("items", alarmItemSchema())
                })
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
            // Accept `summary` as an alias for `title` on input: get_events and the
            // create response echo the field back as `summary`, so a caller that
            // round-trips a result would otherwise hit "title is required".
            val title = args["title"]?.jsonPrimitive?.content
                ?: args["summary"]?.jsonPrimitive?.content
            val startTime = args["start_time"]?.jsonPrimitive?.content
            val endTime = args["end_time"]?.jsonPrimitive?.content
            val location = args["location"]?.jsonPrimitive?.content
            val description = args["description"]?.jsonPrimitive?.content
            val isAllDay = args["is_all_day"]?.jsonPrimitive?.booleanOrNull ?: false
            val startDate = args["start_date"]?.jsonPrimitive?.content
            val endDate = args["end_date"]?.jsonPrimitive?.content
            val timezone = args["timezone"]?.jsonPrimitive?.content
            val endTimezone = args["end_timezone"]?.jsonPrimitive?.content
            val rrule = args["rrule"]?.jsonPrimitive?.content
            val rdates = args["rdates"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val exdates = args["exdates"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val alarmsDecoded = decodeAlarmsForValidation(args)

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
                // Validate time range (resolve naive endpoints in the same zone the
                // write path uses, so a mixed naive/zoned range is ordered honestly).
                if (startTime != null && endTime != null) {
                    val timeRangeResult = InputValidator.validateTimeRange(startTime, endTime, timezone, endTimezone)
                    if (timeRangeResult is ValidationResult.Invalid) {
                        errors.add(timeRangeResult.message)
                    }
                }
            }

            // Validate optional inputs
            errors.addAll(InputValidator.collectErrors(
                InputValidator.validateOptionalText(location, "location", maxLength = 500),
                InputValidator.validateOptionalText(description, "description", maxLength = 5000),
                InputValidator.validateTimezone(timezone, "timezone"),
                InputValidator.validateTimezone(endTimezone, "end_timezone"),
                InputValidator.validateRecurrenceDateList(rdates, "rdates"),
                InputValidator.validateRecurrenceDateList(exdates, "exdates"),
                InputValidator.validateAlarmList(alarmsDecoded, "alarms")
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
                rrule = rrule,
                endTimezone = endTimezone,
                rdates = rdates,
                exdates = exdates,
                alarms = toAlarmSpecs(alarmsDecoded)
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
                        // Self-contained reference for later update/delete (see EventHandle).
                        result.data.handle?.let { put("handle", it) }
                        put("summary", result.data.summary)
                        put("message", "Event created successfully")
                        if (result.data.alarms.isNotEmpty()) {
                            put("alarms", encodeAlarmsForResponse(result.data.alarms))
                        }
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = eventJson.toString())),
                        structuredContent = eventJson
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
        description = "Update an existing calendar event. First use get_events (or create_event) to obtain the event's handle. " +
            "For a recurring series, get_events returns one handle per occurrence (each carries a recurrenceId); use scope to say " +
            "whether an edit changes just that occurrence or the whole series. If the handle is one occurrence of a series you must " +
            "set scope, otherwise the edit is rejected rather than silently changing every occurrence.",
        title = "Update Event",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event reference to update. Prefer the opaque `handle` from get_events/create_event (works even from a fresh process); a bare event UID is also accepted for recently-fetched events."))
                })
                put("title", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New event title (optional)"))
                })
                put("start_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New start time (optional, ISO 8601). Naive local (2025-01-15T09:00:00): interpreted as UTC unless 'timezone' is set. UTC (…Z) or offset (…+09:00): an absolute instant that wins over 'timezone'."))
                })
                put("end_time", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("New end time (optional, ISO 8601). Naive local (2025-01-15T10:00:00): interpreted as UTC unless 'timezone' is set. UTC (…Z) or offset (…+09:00): an absolute instant that wins over 'timezone'."))
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
                put("end_timezone", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional IANA timezone for DTEND when distinct from start (e.g., flights JFK->LAX). Falls back to timezone when omitted."))
                })
                put("rrule", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Recurrence rule (e.g., FREQ=WEEKLY;BYDAY=MO). For biweekly schedules whose BYDAY includes Sunday, set WKST=<two-letter-day> explicitly to avoid ical4j defaulting to MO and dropping occurrences."))
                })
                put("rdates", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Replace additional occurrence dates (RFC 5545 RDATE). ISO 8601 instants for timed; YYYY-MM-DD for all-day. Pass null/omit to leave existing untouched; pass empty array to clear."))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
                put("exdates", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Replace excluded occurrence dates (RFC 5545 EXDATE). Same semantics as rdates."))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
                put("alarms", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Replace VALARM components (RFC 5545 §3.6.6). Pass null/omit to preserve existing alarms; pass empty array to clear all; pass a list of alarm objects to replace. Each entry has: trigger (required), action (DISPLAY|AUDIO|EMAIL, default DISPLAY), description, summary (EMAIL only), repeat_count, repeat_duration."))
                    put("items", alarmItemSchema())
                })
                put("scope", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { EventScope.TOKENS.forEach { add(JsonPrimitive(it)) } })
                    put("description", JsonPrimitive("For a recurring series: which occurrences the edit affects. " +
                        "`this_occurrence` edits only the instance the handle points at (leaving siblings unchanged); " +
                        "`this_and_future` edits that instance and every later one (earlier ones unchanged); " +
                        "`all_events` edits the whole series. Required when the handle is one occurrence of a series; " +
                        "omit for standalone events. rrule/rdates/exdates cannot be combined with an occurrence scope."))
                })
            },
            required = listOf("event_id")
        ),
        outputSchema = ToolSchema(
            properties = buildJsonObject {
                put("success", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                put("uid", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("handle", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Refreshed opaque reference (carries the new etag) for a subsequent update_event/delete_event. For a this_occurrence edit this is an occurrence handle for the edited instance."))
                })
                put("message", buildJsonObject { put("type", JsonPrimitive("string")) })
                put("alarms", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("VALARM components attached to the updated event (RFC 5545 §3.6.6); present only when the event has alarms."))
                    put("items", alarmItemSchema())
                })
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
            val endTimezone = args["end_timezone"]?.jsonPrimitive?.content
            val rrule = args["rrule"]?.jsonPrimitive?.content
            val rdates = args["rdates"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val exdates = args["exdates"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val scope = args["scope"]?.jsonPrimitive?.content
            val alarmsDecoded = decodeAlarmsForValidation(args)

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

            // Validate time range if both provided (resolve naive endpoints in the
            // same zone the write path uses, so a mixed naive/zoned range is honest).
            if (startTime != null && endTime != null) {
                val timeRangeResult = InputValidator.validateTimeRange(startTime, endTime, timezone, endTimezone)
                if (timeRangeResult is ValidationResult.Invalid) {
                    errors.add(timeRangeResult.message)
                }
            }

            errors.addAll(InputValidator.collectErrors(
                InputValidator.validateOptionalText(location, "location", maxLength = 500),
                InputValidator.validateOptionalText(description, "description", maxLength = 5000),
                InputValidator.validateTimezone(timezone, "timezone"),
                InputValidator.validateTimezone(endTimezone, "end_timezone"),
                InputValidator.validateRecurrenceDateList(rdates, "rdates"),
                InputValidator.validateRecurrenceDateList(exdates, "exdates"),
                InputValidator.validateAlarmList(alarmsDecoded, "alarms"),
                InputValidator.validateScope(scope, "scope"),
                // Defense at the MCP layer, mirroring the service-layer check: an
                // occurrence scope must not carry series-level fields.
                InputValidator.validateOccurrenceScopeFields(scope, rrule, rdates, exdates, "scope")
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
                rrule = rrule,
                endTimezone = endTimezone,
                rdates = rdates,
                exdates = exdates,
                alarms = toAlarmSpecs(alarmsDecoded),
                scope = EventScope.fromToken(scope)
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
                        // Refreshed handle carrying the new etag for a follow-up edit.
                        result.data.handle?.let { put("handle", it) }
                        put("message", "Event updated successfully")
                        if (result.data.alarms.isNotEmpty()) {
                            put("alarms", encodeAlarmsForResponse(result.data.alarms))
                        }
                    }
                    CallToolResult(
                        content = listOf(TextContent(text = eventJson.toString())),
                        structuredContent = eventJson
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
        description = "Delete a calendar event. First use get_events (or create_event) to obtain the event's handle. " +
            "For a recurring series, use scope to say whether the delete removes just one occurrence or the whole series. " +
            "If the handle is one occurrence of a series you must set scope, otherwise the delete is rejected rather than " +
            "silently cancelling every occurrence.",
        title = "Delete Event",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("event_id", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Event reference to delete. Prefer the opaque `handle` from get_events/create_event (works even from a fresh process); a bare event UID is also accepted for recently-fetched events."))
                })
                put("scope", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { EventScope.TOKENS.forEach { add(JsonPrimitive(it)) } })
                    put("description", JsonPrimitive("For a recurring series: which occurrences the delete removes. " +
                        "`this_occurrence` cancels only the instance the handle points at (an EXDATE; siblings remain); " +
                        "`this_and_future` cancels that instance and every later one (earlier ones remain); " +
                        "`all_events` deletes the whole series. Required when the handle is one occurrence of a series; " +
                        "omit for standalone events."))
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
            val scope = args["scope"]?.jsonPrimitive?.content

            // Validate event ID + scope
            val deleteErrors = InputValidator.collectErrors(
                InputValidator.validateCalendarId(eventId, "event_id"),
                InputValidator.validateScope(scope, "scope")
            )
            if (deleteErrors.isNotEmpty()) {
                return@safeExecute SecureErrorHandler.validationError(deleteErrors)
            }

            // Check service
            if (calendarService == null) {
                return@safeExecute SecureErrorHandler.authenticationError(
                    "iCloud credentials not configured"
                )
            }

            when (val result = calendarService.deleteEvent(eventId!!, EventScope.fromToken(scope))) {
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
                        content = listOf(TextContent(text = responseJson.toString())),
                        structuredContent = responseJson
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
 * Register user-initiated prompt templates (RFC-agnostic MCP prompts).
 *
 * Prompts are menu templates a client surfaces to its user — they are NOT
 * consulted by the model during tool use. Each one expands, given its
 * arguments, to a single user-role message that steers the client through the
 * correct tool workflow. They are purely additive: the five tools and the
 * calendar resource are untouched.
 *
 * Kept deliberately thin — a prompt's value is a good starting instruction, not
 * business logic; the tools remain the source of truth for what actually
 * happens against iCloud.
 */
internal fun registerPrompts(server: Server) {
    // schedule_meeting: title + attendees + duration → a filled create_event call.
    server.addPrompt(
        name = "schedule_meeting",
        description = "Draft a calendar event from a title, attendees, and duration, then create it with create_event.",
        arguments = listOf(
            PromptArgument(name = "title", description = "Meeting title", required = true),
            PromptArgument(name = "attendees", description = "Comma-separated attendee emails", required = false),
            PromptArgument(name = "duration", description = "Duration, e.g. 30m, 1h (default 30m)", required = false)
        )
    ) { request ->
        val args = request.arguments ?: emptyMap()
        val title = args["title"]?.takeIf { it.isNotBlank() } ?: "Untitled meeting"
        val attendees = args["attendees"]?.takeIf { it.isNotBlank() } ?: "(none specified)"
        val duration = args["duration"]?.takeIf { it.isNotBlank() } ?: "30m"
        promptResult(
            description = "Schedule a meeting via create_event",
            text = """
                Schedule a calendar meeting using the create_event tool.

                Details:
                - Title: $title
                - Attendees: $attendees
                - Duration: $duration

                Steps:
                1. If you don't already know the target calendar, call list_calendars and pick the user's default writable calendar.
                2. Choose a concrete start_time from the conversation (ask if the user hasn't given one). Compute end_time from the $duration duration.
                3. Call create_event with calendar_id, title="$title", start_time, end_time, and a sensible timezone (IANA, e.g. America/New_York) and a default reminder if the user wants one.
                4. Mention the attendees ($attendees) in the description; note that this server does not send invitations.
                5. Treat the create_event success response as authoritative — use the returned uid/handle and do NOT re-list to verify (that risks duplicates).
            """.trimIndent()
        )
    }

    // reschedule: walk the get_events → update_event handle round-trip.
    server.addPrompt(
        name = "reschedule",
        description = "Move an existing event to a new time by looking it up with get_events and editing it with update_event.",
        arguments = listOf(
            PromptArgument(name = "event", description = "Title or description of the event to move", required = true),
            PromptArgument(name = "new_time", description = "New start time (ISO 8601, e.g. 2026-02-01T15:00:00Z)", required = false)
        )
    ) { request ->
        val args = request.arguments ?: emptyMap()
        val event = args["event"]?.takeIf { it.isNotBlank() } ?: "(describe the event)"
        val newTime = args["new_time"]?.takeIf { it.isNotBlank() } ?: "(ask the user for the new time)"
        promptResult(
            description = "Reschedule an event via the get_events → update_event handle round-trip",
            text = """
                Reschedule an existing calendar event.

                Target event: $event
                New start time: $newTime

                Steps:
                1. Call get_events over a date range that should contain "$event" and identify the matching event.
                2. Take the opaque `handle` from that event in the get_events result — do NOT reconstruct an id yourself; the handle is what update_event needs and it survives a cold process.
                3. Call update_event with event_id set to that handle and start_time=$newTime (adjust end_time to preserve the original duration).
                4. update_event returns a refreshed handle carrying the new etag — keep it for any follow-up edit.
                5. If update_event reports a conflict (stale etag), re-run get_events to get the current handle and retry once.
            """.trimIndent()
        )
    }

    // find_conflicts: get_events for a date + overlap reasoning.
    server.addPrompt(
        name = "find_conflicts",
        description = "List a day's events with get_events and reason about overlapping/conflicting time slots.",
        arguments = listOf(
            PromptArgument(name = "date", description = "Day to check (YYYY-MM-DD)", required = true)
        )
    ) { request ->
        val args = request.arguments ?: emptyMap()
        val date = args["date"]?.takeIf { it.isNotBlank() } ?: "(ask the user which day)"
        promptResult(
            description = "Find scheduling conflicts on a given day",
            text = """
                Find scheduling conflicts on $date.

                Steps:
                1. Call get_events with start_date=$date and end_date=$date (or the day after, if the client treats end_date as exclusive) for the user's calendars.
                2. Sort the returned events by start time and compare adjacent events.
                3. Report any pairs whose time ranges overlap as conflicts, plus any back-to-back events with no gap that the user may want to space out.
                4. For all-day events, note overlaps with timed events separately (they rarely constitute a hard conflict).
                5. Present the conflicts clearly; do not modify anything unless the user asks.
            """.trimIndent()
        )
    }
}

/** Build a single user-role GetPromptResult from a plain-text template. */
private fun promptResult(description: String, text: String): GetPromptResult =
    GetPromptResult(
        description = description,
        messages = listOf(PromptMessage(role = Role.User, content = TextContent(text = text)))
    )

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
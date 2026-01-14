package org.onekash.mcp.calendar.caldav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * CalDAV client implementation using OkHttp.
 *
 * Handles iCloud CalDAV protocol:
 * - 3-step discovery (principal -> home-set -> calendars)
 * - REPORT for event queries
 * - PUT/DELETE for event mutations
 *
 * Thread-safe: Can be shared across coroutines.
 */
class OkHttpCalDavClient(
    private val baseUrl: String,
    private val credentials: CalDavCredentials,
    private val httpClient: OkHttpClient = createDefaultClient()
) : CalDavClient {

    private val xmlParser = ICloudXmlParser()

    // Cached discovery results
    private var calendarHomeSet: String? = null
    private var calendarsCache: List<CalDavCalendar>? = null

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICS_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()

        private fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        // PROPFIND XML bodies
        private val PROPFIND_PRINCIPAL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:current-user-principal/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        private val PROPFIND_HOME_SET = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:prop>
                    <C:calendar-home-set/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        private val PROPFIND_CALENDARS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/" xmlns:ICAL="http://apple.com/ns/ical/">
                <D:prop>
                    <D:displayname/>
                    <D:resourcetype/>
                    <CS:getctag/>
                    <ICAL:calendar-color/>
                    <D:current-user-privilege-set/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        private fun calendarQueryReport(startDate: String, endDate: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:prop>
                    <D:getetag/>
                    <C:calendar-data/>
                </D:prop>
                <C:filter>
                    <C:comp-filter name="VCALENDAR">
                        <C:comp-filter name="VEVENT">
                            <C:time-range start="${startDate}T000000Z" end="${endDate}T235959Z"/>
                        </C:comp-filter>
                    </C:comp-filter>
                </C:filter>
            </C:calendar-query>
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════════════════════
    // CalDavClient IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════

    override fun listCalendars(): CalDavResult<List<CalDavCalendar>> {
        // Use cache if available
        calendarsCache?.let { return CalDavResult.Success(it) }

        // Step 1: Get current user principal
        val principalResult = discoverPrincipal()
        if (principalResult is CalDavResult.Error) return principalResult

        val principal = (principalResult as CalDavResult.Success).data
            ?: return CalDavResult.Error(500, "Could not discover principal")

        // Step 2: Get calendar home set
        val homeSetResult = discoverHomeSet(principal)
        if (homeSetResult is CalDavResult.Error) return homeSetResult

        val homeSet = (homeSetResult as CalDavResult.Success).data
            ?: return CalDavResult.Error(500, "Could not discover calendar-home-set")

        calendarHomeSet = homeSet

        // Step 3: List calendars
        val calendarsResult = fetchCalendars(homeSet)
        if (calendarsResult is CalDavResult.Success) {
            calendarsCache = calendarsResult.data
        }

        return calendarsResult
    }

    override fun getEvents(calendarId: String, startDate: String, endDate: String): CalDavResult<List<CalDavEvent>> {
        // Ensure we have calendar info
        val calendarsResult = listCalendars()
        if (calendarsResult is CalDavResult.Error) return calendarsResult

        val calendars = (calendarsResult as CalDavResult.Success).data
        val calendar = calendars.find { it.id == calendarId }
            ?: return CalDavResult.Error(404, "Calendar not found: $calendarId")

        val url = calendar.url
        val reportBody = calendarQueryReport(startDate.replace("-", ""), endDate.replace("-", ""))

        val request = Request.Builder()
            .url(url)
            .method("REPORT", reportBody.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "1")
            .build()

        return executeRequest(request) { responseBody ->
            val events = xmlParser.parseEvents(responseBody, baseUrl)
            CalDavResult.Success(events)
        }
    }

    override fun createEvent(calendarId: String, icalData: String): CalDavResult<CalDavEvent> {
        // Ensure we have calendar info
        val calendarsResult = listCalendars()
        if (calendarsResult is CalDavResult.Error) return calendarsResult

        val calendars = (calendarsResult as CalDavResult.Success).data
        val calendar = calendars.find { it.id == calendarId }
            ?: return CalDavResult.Error(404, "Calendar not found: $calendarId")

        // Extract UID from ICS data
        val uid = xmlParser.extractUid(icalData)
            ?: return CalDavResult.Error(400, "ICS data missing UID")

        // Generate href for new event
        val eventFileName = "${UUID.randomUUID()}.ics"
        val eventUrl = "${calendar.url.trimEnd('/')}/$eventFileName"

        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody(ICS_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("If-None-Match", "*") // Fail if exists
            .build()

        return executeRequestWithHeaders(request) { _, headers ->
            // Get etag from response header
            val etag = headers["ETag"]

            CalDavResult.Success(CalDavEvent(
                uid = uid,
                href = eventUrl.substringAfter(baseUrl),
                url = eventUrl,
                etag = etag,
                icalData = icalData
            ))
        }
    }

    override fun updateEvent(href: String, icalData: String, etag: String?): CalDavResult<CalDavEvent> {
        val url = if (href.startsWith("http")) href else "$baseUrl$href"

        val uid = xmlParser.extractUid(icalData)
            ?: return CalDavResult.Error(400, "ICS data missing UID")

        val requestBuilder = Request.Builder()
            .url(url)
            .put(icalData.toRequestBody(ICS_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())

        if (etag != null) {
            requestBuilder.addHeader("If-Match", etag)
        }

        val request = requestBuilder.build()

        return executeRequestWithHeaders(request) { _, headers ->
            val newEtag = headers["ETag"]
            CalDavResult.Success(CalDavEvent(
                uid = uid,
                href = href,
                url = url,
                etag = newEtag,
                icalData = icalData
            ))
        }
    }

    override fun deleteEvent(href: String, etag: String?): CalDavResult<Unit> {
        val url = if (href.startsWith("http")) href else "$baseUrl$href"

        val requestBuilder = Request.Builder()
            .url(url)
            .delete()
            .addHeader("Authorization", basicAuth())

        if (etag != null) {
            requestBuilder.addHeader("If-Match", etag)
        }

        val request = requestBuilder.build()

        return executeRequest(request, expectBody = false, allow404 = true) { _ ->
            CalDavResult.Success(Unit)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DISCOVERY METHODS
    // ═══════════════════════════════════════════════════════════════════

    private fun discoverPrincipal(): CalDavResult<String?> {
        val request = Request.Builder()
            .url(baseUrl)
            .method("PROPFIND", PROPFIND_PRINCIPAL.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "0")
            .build()

        return executeRequest(request) { responseBody ->
            val principal = xmlParser.parseCurrentUserPrincipal(responseBody)
            CalDavResult.Success(principal)
        }
    }

    private fun discoverHomeSet(principalHref: String): CalDavResult<String?> {
        val url = if (principalHref.startsWith("http")) principalHref else "$baseUrl$principalHref"

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_HOME_SET.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "0")
            .build()

        return executeRequest(request) { responseBody ->
            val homeSet = xmlParser.parseCalendarHomeSet(responseBody)
            CalDavResult.Success(homeSet)
        }
    }

    private fun fetchCalendars(homeSetHref: String): CalDavResult<List<CalDavCalendar>> {
        val url = if (homeSetHref.startsWith("http")) homeSetHref else "$baseUrl$homeSetHref"

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_CALENDARS.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "1")
            .build()

        return executeRequest(request) { responseBody ->
            val calendars = xmlParser.parseCalendars(responseBody, baseUrl)
            CalDavResult.Success(calendars)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun basicAuth(): String = Credentials.basic(credentials.username, credentials.password)

    private fun <T> executeRequest(
        request: Request,
        expectBody: Boolean = true,
        allow404: Boolean = false,
        onSuccess: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                when {
                    code in 200..299 || code == 207 -> {
                        onSuccess(body)
                    }
                    code == 404 && allow404 -> {
                        @Suppress("UNCHECKED_CAST")
                        CalDavResult.Success(Unit) as CalDavResult<T>
                    }
                    code == 401 || code == 403 -> {
                        CalDavResult.Error(code, "Authentication failed", isRetryable = false)
                    }
                    code == 404 -> {
                        CalDavResult.Error(code, "Resource not found", isRetryable = false)
                    }
                    code == 412 -> {
                        CalDavResult.Error(code, "Precondition failed (resource modified)", isRetryable = false)
                    }
                    code in 500..599 -> {
                        CalDavResult.Error(code, "Server error", isRetryable = true)
                    }
                    else -> {
                        CalDavResult.Error(code, "Unexpected response: $code", isRetryable = false)
                    }
                }
            }
        } catch (e: Exception) {
            CalDavResult.Error(0, "Network error: ${e.message}", isRetryable = true)
        }
    }

    /**
     * Helper for response handling that needs etag from headers.
     */
    private fun <T> executeRequestWithHeaders(
        request: Request,
        onSuccess: (String, Map<String, String>) -> CalDavResult<T>
    ): CalDavResult<T> {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                val headers = response.headers.toMap()

                when {
                    code in 200..299 || code == 207 -> {
                        onSuccess(body, headers)
                    }
                    code == 401 || code == 403 -> {
                        CalDavResult.Error(code, "Authentication failed", isRetryable = false)
                    }
                    code == 404 -> {
                        CalDavResult.Error(code, "Resource not found", isRetryable = false)
                    }
                    code == 412 -> {
                        CalDavResult.Error(code, "Precondition failed", isRetryable = false)
                    }
                    code in 500..599 -> {
                        CalDavResult.Error(code, "Server error", isRetryable = true)
                    }
                    else -> {
                        CalDavResult.Error(code, "Unexpected response: $code", isRetryable = false)
                    }
                }
            }
        } catch (e: Exception) {
            CalDavResult.Error(0, "Network error: ${e.message}", isRetryable = true)
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until size) {
            map[name(i)] = value(i)
        }
        return map
    }
}

package org.onekash.mcp.calendar.caldav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.random.Random

/**
 * CalDAV client implementation using OkHttp.
 *
 * Handles iCloud CalDAV protocol:
 * - 3-step discovery (principal -> home-set -> calendars)
 * - REPORT for event queries
 * - PUT/DELETE for event mutations
 *
 * Thread-safe: Can be shared across coroutines.
 * calendarHomeSet and calendarsCache are @Volatile — double-fetch
 * under contention is acceptable (idempotent).
 */
class OkHttpCalDavClient(
    private val baseUrl: String,
    private val credentials: CalDavCredentials,
    private val httpClient: OkHttpClient = createDefaultClient()
) : CalDavClient, java.io.Closeable {

    private val xmlParser = ICloudXmlParser()
    private val logger = org.slf4j.LoggerFactory.getLogger(OkHttpCalDavClient::class.java)

    // Cached discovery results
    // @Volatile: double-fetch under contention is acceptable (idempotent)
    @Volatile
    private var calendarHomeSet: String? = null
    @Volatile
    private var calendarsCache: List<CalDavCalendar>? = null
    @Volatile
    private var cacheTimestamp: Long = 0L

    // Circuit breaker: consecutive request-level failures
    private val consecutiveFailures = AtomicInteger(0)

    // Well-known discovery cache
    // @Volatile: double-fetch under contention is acceptable (idempotent)
    @Volatile
    private var wellKnownAttempted: Boolean = false
    @Volatile
    private var discoveredBaseUrl: String? = null

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val ICS_MEDIA_TYPE = "text/calendar; charset=utf-8".toMediaType()

        /** Maximum response body size: 2 MB */
        internal const val MAX_BODY_SIZE = 2_000_000L

        /** Cache TTL: 5 minutes */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        private val CONNECT_TIMEOUT_SECONDS = 15L
        private val READ_TIMEOUT_SECONDS = 30L
        private val WRITE_TIMEOUT_SECONDS = 30L

        /** Retry configuration — fast for MCP server responsiveness */
        internal const val MAX_RETRIES = 1
        internal const val BASE_DELAY_MS = 100L
        internal const val MAX_JITTER_MS = 50L
        /** Cap Retry-After to 2s max — MCP tools must respond quickly */
        internal const val MAX_RETRY_AFTER_MS = 2000L

        /** Circuit breaker: consecutive failures before failing fast */
        internal const val CIRCUIT_BREAKER_THRESHOLD = 5

        /** Maximum redirect hops for well-known discovery */
        internal const val MAX_WELL_KNOWN_HOPS = 5

        /** Date format regex for getEvents validation */
        private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

        /** Max chars to log for response bodies */
        private const val MAX_BODY_LOG_CHARS = 1000

        private fun createDefaultClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)

            // Logging interceptor controlled by env var
            if (System.getenv("CALDAV_LOG_HTTP")?.equals("true", ignoreCase = true) == true) {
                builder.addInterceptor { chain ->
                    val request = chain.request()
                    System.err.println("--> ${request.method} ${request.url}")

                    val response = chain.proceed(request)

                    val peekBody = try {
                        response.peekBody((MAX_BODY_LOG_CHARS + 1).toLong()).string()
                    } catch (e: Exception) {
                        org.slf4j.LoggerFactory.getLogger(OkHttpCalDavClient::class.java)
                            .debug("failed to peek response body for HTTP logging: {}", e.toString())
                        ""
                    }

                    val bodyLog = if (peekBody.length > MAX_BODY_LOG_CHARS) {
                        peekBody.take(MAX_BODY_LOG_CHARS) + "... [truncated]"
                    } else peekBody

                    System.err.println("<-- ${response.code} ${request.url} (${bodyLog.length} chars)")
                    if (bodyLog.isNotBlank()) System.err.println(bodyLog)

                    response
                }
            }

            return builder.build()
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
                    <C:supported-calendar-component-set/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        private val PROPFIND_ETAG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:getetag/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        private fun etagOnlyReport(startDate: String, endDate: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:prop>
                    <D:getetag/>
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
        // Use cache if available and within TTL
        val cached = calendarsCache
        if (cached != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
            return CalDavResult.Success(cached)
        }

        // Try well-known discovery for CalDAV endpoint (RFC 6764)
        val effectiveBaseUrl = discoverWellKnown() ?: baseUrl

        // Step 1: Get current user principal
        val principalResult = discoverPrincipal(effectiveBaseUrl)
        if (principalResult is CalDavResult.Error) return principalResult

        val principal = (principalResult as CalDavResult.Success).data
            ?: return CalDavResult.Error.serverError(500, "Could not discover principal")

        // Step 2: Get calendar home sets (RFC 4791 §6.2.1 - may have multiple)
        val homeSetsResult = discoverHomeSets(principal)
        if (homeSetsResult is CalDavResult.Error) return homeSetsResult

        val homeSets = (homeSetsResult as CalDavResult.Success).data
        if (homeSets.isEmpty()) {
            return CalDavResult.Error.serverError(500, "Could not discover calendar-home-set")
        }

        calendarHomeSet = homeSets.first()

        // Step 3: List calendars from all home sets, deduplicate by href
        val allCalendars = mutableListOf<CalDavCalendar>()
        val seenHrefs = mutableSetOf<String>()

        for (homeSet in homeSets) {
            val calendarsResult = fetchCalendars(homeSet)
            if (calendarsResult is CalDavResult.Success) {
                for (cal in calendarsResult.data) {
                    if (seenHrefs.add(cal.href)) {
                        allCalendars.add(cal)
                    }
                }
            }
        }

        calendarsCache = allCalendars
        cacheTimestamp = System.currentTimeMillis()
        return CalDavResult.Success(allCalendars)
    }

    override fun getEvents(calendarId: String, startDate: String, endDate: String): CalDavResult<List<CalDavEvent>> {
        // Validate date formats (defense in depth against XML injection)
        if (!DATE_PATTERN.matches(startDate)) {
            return CalDavResult.Error.badRequestError("Invalid start date format: expected YYYY-MM-DD")
        }
        if (!DATE_PATTERN.matches(endDate)) {
            return CalDavResult.Error.badRequestError("Invalid end date format: expected YYYY-MM-DD")
        }

        // Ensure we have calendar info
        val calendarsResult = listCalendars()
        if (calendarsResult is CalDavResult.Error) return calendarsResult

        val calendars = (calendarsResult as CalDavResult.Success).data
        val calendar = calendars.find { it.id == calendarId }
            ?: return CalDavResult.Error.notFoundError("Calendar not found: $calendarId")

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
            ?: return CalDavResult.Error.notFoundError("Calendar not found: $calendarId")

        // Extract UID from ICS data
        val uid = xmlParser.extractUid(icalData)
            ?: return CalDavResult.Error.badRequestError("ICS data missing UID")

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
            // Get etag from response header, fallback to PROPFIND if missing
            // Normalize to strip W/ prefix, quotes, XML entities (RFC 7232)
            val etag = EtagUtils.normalizeEtag(headers["ETag"]) ?: fetchEtag(eventUrl)

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
            ?: return CalDavResult.Error.badRequestError("ICS data missing UID")

        // When the caller has no etag (e.g. the original REPORT response omitted
        // <getetag> per server quirk — RFC 4791 §5.3.4 SHOULD), probe via PROPFIND
        // so the PUT can carry an If-Match instead of silently bypassing optimistic
        // concurrency. PROPFIND failure falls through to no-If-Match (best-effort).
        val effectiveEtag = etag ?: fetchEtag(url)

        val requestBuilder = Request.Builder()
            .url(url)
            .put(icalData.toRequestBody(ICS_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())

        if (effectiveEtag != null) {
            requestBuilder.addHeader("If-Match", effectiveEtag)
        }

        val request = requestBuilder.build()

        return executeRequestWithHeaders(request) { _, headers ->
            // Get etag from response header, fallback to PROPFIND if missing
            val newEtag = EtagUtils.normalizeEtag(headers["ETag"]) ?: fetchEtag(url)
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

        // Same PROPFIND-recovery logic as updateEvent — see comment there.
        val effectiveEtag = etag ?: fetchEtag(url)

        val requestBuilder = Request.Builder()
            .url(url)
            .delete()
            .addHeader("Authorization", basicAuth())

        if (effectiveEtag != null) {
            requestBuilder.addHeader("If-Match", effectiveEtag)
        }

        val request = requestBuilder.build()

        return executeRequest(request, expectBody = false, allow404 = true) { _ ->
            CalDavResult.Success(Unit)
        }
    }

    override fun checkConnection(): CalDavResult<Boolean> {
        return executeWithRetry {
            try {
                val request = Request.Builder()
                    .url(baseUrl)
                    .method("OPTIONS", null)
                    .addHeader("Authorization", basicAuth())
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            if (response.code == 401) CalDavResult.Error.authError() else CalDavResult.Error.forbiddenError()
                        response.code !in 200..299 ->
                            CalDavResult.Error.serverError(response.code)
                        else -> {
                            val davHeader = response.header("DAV") ?: ""
                            if (davHeader.contains("calendar-access")) {
                                CalDavResult.Success(true)
                            } else {
                                CalDavResult.Error.badRequestError(
                                    "Server does not support CalDAV (missing 'calendar-access' in DAV header)")
                            }
                        }
                    }
                }
            } catch (e: SSLException) {
                CalDavResult.Error.sslError("SSL error: ${e.message}")
            } catch (e: SocketTimeoutException) {
                CalDavResult.Error.timeoutError("Connection timed out")
            } catch (e: Exception) {
                CalDavResult.Error.networkError("Unable to connect: ${e.message}")
            }
        }
    }

    override fun fetchEtags(calendarId: String, startDate: String, endDate: String): CalDavResult<Map<String, String?>> {
        if (!DATE_PATTERN.matches(startDate) || !DATE_PATTERN.matches(endDate)) {
            return CalDavResult.Error.badRequestError("Invalid date format")
        }

        val calendarsResult = listCalendars()
        if (calendarsResult is CalDavResult.Error) return calendarsResult

        val calendars = (calendarsResult as CalDavResult.Success).data
        val calendar = calendars.find { it.id == calendarId }
            ?: return CalDavResult.Error.notFoundError("Calendar not found: $calendarId")

        val url = calendar.url
        val reportBody = etagOnlyReport(startDate.replace("-", ""), endDate.replace("-", ""))

        val request = Request.Builder()
            .url(url)
            .method("REPORT", reportBody.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "1")
            .build()

        return executeRequest(request) { responseBody ->
            val etags = xmlParser.parseEtags(responseBody)
            CalDavResult.Success(etags)
        }
    }

    override fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ═══════════════════════════════════════════════════════════════════
    // DISCOVERY METHODS
    // ═══════════════════════════════════════════════════════════════════

    private fun discoverPrincipal(discoveryUrl: String = baseUrl): CalDavResult<String?> {
        val request = Request.Builder()
            .url(discoveryUrl)
            .method("PROPFIND", PROPFIND_PRINCIPAL.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "0")
            .build()

        return executeRequest(request) { responseBody ->
            val principal = xmlParser.parseCurrentUserPrincipal(responseBody)
            CalDavResult.Success(principal)
        }
    }

    private fun discoverHomeSets(principalHref: String): CalDavResult<List<String>> {
        val url = if (principalHref.startsWith("http")) principalHref else "$baseUrl$principalHref"

        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_HOME_SET.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "0")
            .build()

        return executeRequest(request) { responseBody ->
            val homeSets = xmlParser.parseCalendarHomeSets(responseBody)
            CalDavResult.Success(homeSets)
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

    /**
     * RFC 6764 well-known URL discovery.
     * Sends GET to /.well-known/caldav and follows redirects manually
     * to discover the CalDAV endpoint URL.
     *
     * Returns the discovered URL, or null if well-known is not supported.
     * Result is cached (only attempted once per client lifetime).
     */
    private fun discoverWellKnown(): String? {
        if (wellKnownAttempted) return discoveredBaseUrl
        wellKnownAttempted = true // Double-fetch OK (idempotent)

        try {
            val url = "${baseUrl.trimEnd('/')}/.well-known/caldav"
            val noRedirectClient = httpClient.newBuilder()
                .followRedirects(false)
                .build()

            var currentUrl = url
            val visited = mutableSetOf<String>()

            for (hop in 0 until MAX_WELL_KNOWN_HOPS) {
                if (!visited.add(currentUrl)) {
                    return null // Loop detected
                }

                val request = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .addHeader("Authorization", basicAuth())
                    .build()

                noRedirectClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        301, 302, 307, 308 -> {
                            val location = response.header("Location") ?: return null
                            val resolved = response.request.url.resolve(location)?.toString()
                                ?: return null

                            // Preserve HTTPS: reject downgrade to HTTP
                            currentUrl = if (baseUrl.startsWith("https://") && resolved.startsWith("http://")) {
                                resolved.replaceFirst("http://", "https://")
                            } else {
                                resolved
                            }
                        }
                        in 200..299, 207 -> {
                            discoveredBaseUrl = currentUrl
                            return currentUrl
                        }
                        else -> return null // 404 or other error
                    }
                }
            }

            return null // Max hops exceeded
        } catch (e: Exception) {
            logger.debug("well-known discovery failed for {}/.well-known/caldav: {}", baseUrl.trimEnd('/'), e.toString())
            return null // Well-known discovery is best-effort
        }
    }

    /**
     * Skip well-known discovery (for testing).
     */
    internal fun skipWellKnownDiscovery() {
        wellKnownAttempted = true
    }

    /**
     * Fetch ETag for an event URL via PROPFIND (RFC 4791 §5.3.4 fallback).
     * Returns null if PROPFIND fails or ETag not present.
     */
    private fun fetchEtag(eventUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url(eventUrl)
                .method("PROPFIND", PROPFIND_ETAG.toRequestBody(XML_MEDIA_TYPE))
                .addHeader("Authorization", basicAuth())
                .addHeader("Depth", "0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code in 200..299 || response.code == 207) {
                    val body = readBodyWithLimit(response) ?: return null
                    xmlParser.parseEtag(body)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("fetchEtag PROPFIND failed for {}: {}", eventUrl, e.toString())
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun basicAuth(): String = Credentials.basic(credentials.username, credentials.password)

    /**
     * Read response body with size limit to prevent OOM.
     * Checks Content-Length header first for early rejection of oversized responses,
     * then falls back to streaming check for chunked/unknown-length responses.
     * Returns null if body exceeds MAX_BODY_SIZE.
     */
    private fun readBodyWithLimit(response: Response): String? {
        val body = response.body ?: return ""
        // Early reject if Content-Length header indicates oversized response
        val declaredLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
        if (declaredLength > MAX_BODY_SIZE) {
            return null
        }
        // Streaming check for chunked responses (contentLength == -1)
        val source = body.source()
        source.request(MAX_BODY_SIZE + 1)
        if (source.buffer.size > MAX_BODY_SIZE) {
            return null
        }
        return body.string()
    }

    private fun <T> executeRequest(
        request: Request,
        expectBody: Boolean = true,
        allow404: Boolean = false,
        onSuccess: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return executeWithRetry {
            executeSingleRequest(request, expectBody, allow404, onSuccess)
        }
    }

    private fun <T> executeSingleRequest(
        request: Request,
        expectBody: Boolean = true,
        allow404: Boolean = false,
        onSuccess: (String) -> CalDavResult<T>
    ): CalDavResult<T> {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = readBodyWithLimit(response)
                    ?: return CalDavResult.Error.payloadTooLargeError("Response too large (exceeds ${MAX_BODY_SIZE / 1_000_000}MB limit)")

                when {
                    code in 200..299 || code == 207 -> {
                        onSuccess(body)
                    }
                    code == 404 && allow404 -> {
                        @Suppress("UNCHECKED_CAST")
                        CalDavResult.Success(Unit) as CalDavResult<T>
                    }
                    code == 401 || code == 403 -> {
                        if (code == 401) CalDavResult.Error.authError() else CalDavResult.Error.forbiddenError()
                    }
                    code == 404 -> {
                        CalDavResult.Error.notFoundError()
                    }
                    code == 412 -> {
                        CalDavResult.Error.conflictError()
                    }
                    code == 429 -> {
                        val retryAfter = parseRetryAfter(response)
                        CalDavResult.Error.rateLimitError().also {
                            retryAfterHint.set(retryAfter)
                        }
                    }
                    code in 500..599 -> {
                        CalDavResult.Error.serverError(code)
                    }
                    else -> {
                        CalDavResult.Error(code, "Unexpected response: $code", isRetryable = false)
                    }
                }
            }
        } catch (e: SSLException) {
            CalDavResult.Error.sslError("SSL error: ${e.message}")
        } catch (e: SocketTimeoutException) {
            CalDavResult.Error.timeoutError("Request timed out: ${e.message}")
        } catch (e: Exception) {
            CalDavResult.Error.networkError("Network error: ${e.message}")
        }
    }

    /**
     * Helper for response handling that needs etag from headers.
     */
    private fun <T> executeRequestWithHeaders(
        request: Request,
        onSuccess: (String, Map<String, String>) -> CalDavResult<T>
    ): CalDavResult<T> {
        return executeWithRetry {
            executeSingleRequestWithHeaders(request, onSuccess)
        }
    }

    private fun <T> executeSingleRequestWithHeaders(
        request: Request,
        onSuccess: (String, Map<String, String>) -> CalDavResult<T>
    ): CalDavResult<T> {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = readBodyWithLimit(response)
                    ?: return CalDavResult.Error.payloadTooLargeError("Response too large (exceeds ${MAX_BODY_SIZE / 1_000_000}MB limit)")
                val headers = response.headers.toMap()

                when {
                    code in 200..299 || code == 207 -> {
                        onSuccess(body, headers)
                    }
                    code == 401 || code == 403 -> {
                        if (code == 401) CalDavResult.Error.authError() else CalDavResult.Error.forbiddenError()
                    }
                    code == 404 -> {
                        CalDavResult.Error.notFoundError()
                    }
                    code == 412 -> {
                        CalDavResult.Error.conflictError()
                    }
                    code == 429 -> {
                        val retryAfter = parseRetryAfter(response)
                        CalDavResult.Error.rateLimitError().also {
                            retryAfterHint.set(retryAfter)
                        }
                    }
                    code in 500..599 -> {
                        CalDavResult.Error.serverError(code)
                    }
                    else -> {
                        CalDavResult.Error(code, "Unexpected response: $code", isRetryable = false)
                    }
                }
            }
        } catch (e: SSLException) {
            CalDavResult.Error.sslError("SSL error: ${e.message}")
        } catch (e: SocketTimeoutException) {
            CalDavResult.Error.timeoutError("Request timed out: ${e.message}")
        } catch (e: Exception) {
            CalDavResult.Error.networkError("Network error: ${e.message}")
        }
    }

    // Thread-local Retry-After hint for retry loop
    private val retryAfterHint = ThreadLocal<Long>()

    /**
     * Execute with retry: exponential backoff + jitter.
     * Max 2 retries. Only retries when result.isRetryable.
     * Respects Retry-After header (capped at 10s).
     * Circuit breaker: fails fast after CIRCUIT_BREAKER_THRESHOLD consecutive failures.
     */
    private fun <T> executeWithRetry(block: () -> CalDavResult<T>): CalDavResult<T> {
        // Circuit breaker check
        if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
            return CalDavResult.Error(503, "Circuit breaker open: too many consecutive failures", isRetryable = false)
        }

        var lastResult: CalDavResult<T>? = null

        for (attempt in 0..MAX_RETRIES) {
            retryAfterHint.remove()
            val result = block()

            if (result is CalDavResult.Success) {
                consecutiveFailures.set(0) // Reset on success
                return result
            }

            lastResult = result
            val error = result as CalDavResult.Error

            // Don't retry non-retryable errors
            if (!error.isRetryable) {
                consecutiveFailures.incrementAndGet()
                return result
            }

            // Don't retry after last attempt
            if (attempt == MAX_RETRIES) break

            // Calculate delay
            val retryAfterMs = (retryAfterHint.get() ?: 0L)
            val backoffMs = BASE_DELAY_MS * (1L shl attempt) // exponential: 500, 1000
            val jitterMs = Random.nextLong(0, MAX_JITTER_MS)
            val delayMs = maxOf(backoffMs + jitterMs, retryAfterMs)

            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        consecutiveFailures.incrementAndGet()
        return lastResult ?: CalDavResult.Error.networkError("Request failed after retries")
    }

    /**
     * Parse Retry-After header: supports seconds (integer) and HTTP-date (RFC 7231).
     * Returns delay in milliseconds, capped at MAX_RETRY_AFTER_MS for MCP responsiveness.
     */
    internal fun parseRetryAfter(response: Response): Long {
        val header = response.header("Retry-After") ?: return 0L

        // Try as integer (seconds)
        header.toLongOrNull()?.let { seconds ->
            return minOf(seconds * 1000, MAX_RETRY_AFTER_MS)
        }

        // Try as HTTP-date (RFC 7231)
        return try {
            val date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            val delayMs = date.toInstant().toEpochMilli() - System.currentTimeMillis()
            minOf(maxOf(delayMs, 0), MAX_RETRY_AFTER_MS)
        } catch (_: Exception) {
            0L
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

package org.onekash.mcp.calendar.caldav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
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
    httpClient: OkHttpClient = createDefaultClient(),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: (Long) -> Unit = Thread::sleep
) : CalDavClient, java.io.Closeable {

    private val backoff = CalDavBackoff(clock, sleep)
    private val httpClient = httpClient.newBuilder().apply {
        interceptors().add(0, backoff)
        networkInterceptors().add(0, backoff.networkInterceptor)
        retryOnConnectionFailure(false)
    }.build()
    private val mutationClient = this.httpClient.newBuilder().followRedirects(false).build()

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
        /** Longest backoff window to wait out within one call. */
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
        try {
            backoff.awaitAdmission()
        } catch (e: IOException) {
            return requestFailure(e)
        }
        // Use cache if available and within TTL
        val cached = calendarsCache
        if (cached != null && (clock.millis() - cacheTimestamp) < CACHE_TTL_MS) {
            return CalDavResult.Success(cached)
        }

        // Try well-known discovery for CalDAV endpoint (RFC 6764)
        val discovery = discoverWellKnown()
        if (discovery is CalDavResult.Error) return discovery
        val effectiveBaseUrl = (discovery as CalDavResult.Success).data ?: baseUrl

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
            if (calendarsResult is CalDavResult.Error &&
                (calendarsResult.isRetryable || calendarsResult.isAuthError || calendarsResult.code == 0)) {
                return calendarsResult
            }
            if (calendarsResult is CalDavResult.Success) {
                for (cal in calendarsResult.data) {
                    if (seenHrefs.add(cal.href)) {
                        allCalendars.add(cal)
                    }
                }
            }
        }

        calendarsCache = allCalendars
        cacheTimestamp = clock.millis()
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

    override fun getEvent(href: String): CalDavResult<CalDavEvent> {
        val normalizedHref = ICloudUrlNormalizer.normalize(href) ?: href
        val url = if (normalizedHref.startsWith("http")) normalizedHref else "$baseUrl$normalizedHref"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", basicAuth())
            .build()

        return executeRequestWithHeaders(request) { body, headers ->
            val uid = xmlParser.extractUid(body)
                ?: return@executeRequestWithHeaders CalDavResult.Error.notFoundError(
                    "Event body missing UID at $normalizedHref")
            // Prefer the response ETag header; fall back to a PROPFIND probe.
            val etag = EtagUtils.normalizeEtag(headers["ETag"]) ?: fetchEtag(url)
            CalDavResult.Success(CalDavEvent(
                uid = uid,
                href = normalizedHref,
                url = url,
                etag = etag,
                icalData = body
            ))
        }
    }

    override fun createEvent(calendarId: String, icalData: String): CalDavResult<CalDavEvent> {
        // Ensure we have calendar info
        val calendarsResult = listCalendars()
        if (calendarsResult is CalDavResult.Error) return notApplied(calendarsResult)

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
                    responseError(response, authentication = true)?.let { return@executeWithRetry it }
                    val davHeader = response.header("DAV") ?: ""
                    if (davHeader.contains("calendar-access")) {
                        CalDavResult.Success(true)
                    } else {
                        CalDavResult.Error.badRequestError(
                            "Server does not support CalDAV (missing 'calendar-access' in DAV header)")
                    }
                }
            } catch (e: Exception) {
                requestFailure(e)
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

        return executeRequest(request, authentication = true) { responseBody ->
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

        return executeRequest(request, authentication = true) { responseBody ->
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

        return executeRequest(request, authentication = true) { responseBody ->
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
     * Cache successful discovery and permanent absence, leaving transient failures retryable.
     */
    private fun discoverWellKnown(): CalDavResult<String?> = executeWithRetry { discoverWellKnownOnce() }

    private fun discoverWellKnownOnce(): CalDavResult<String?> {
        if (wellKnownAttempted) return CalDavResult.Success(discoveredBaseUrl)

        try {
            val url = "${baseUrl.trimEnd('/')}/.well-known/caldav"
            val noRedirectClient = httpClient.newBuilder()
                .followRedirects(false)
                .build()

            var currentUrl = url
            val visited = mutableSetOf<String>()

            for (hop in 0 until MAX_WELL_KNOWN_HOPS) {
                if (!visited.add(currentUrl)) {
                    wellKnownAttempted = true
                    return CalDavResult.Success(null)
                }

                val request = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .addHeader("Authorization", basicAuth())
                    .build()

                noRedirectClient.newCall(request).execute().use { response ->
                    val error = responseError(response, authentication = true)
                    if (error != null && (error.isRetryable || error.isAuthError)) return error
                    when (response.code) {
                        301, 302, 307, 308 -> {
                            val location = response.header("Location")
                                ?: return CalDavResult.Success(null).also { wellKnownAttempted = true }
                            val resolved = response.request.url.resolve(location)?.toString()
                                ?: return CalDavResult.Success(null).also { wellKnownAttempted = true }

                            // Preserve HTTPS: reject downgrade to HTTP
                            currentUrl = if (baseUrl.startsWith("https://") && resolved.startsWith("http://")) {
                                resolved.replaceFirst("http://", "https://")
                            } else {
                                resolved
                            }
                        }
                        in 200..299, 207 -> {
                            discoveredBaseUrl = currentUrl
                            wellKnownAttempted = true
                            return CalDavResult.Success(currentUrl)
                        }
                        else -> return CalDavResult.Success(null).also { wellKnownAttempted = true }
                    }
                }
            }

            wellKnownAttempted = true
            return CalDavResult.Success(null)
        } catch (e: Exception) {
            logger.debug("well-known discovery failed for {}/.well-known/caldav: {}", baseUrl.trimEnd('/'), e.toString())
            return requestFailure(e)
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
        val request = Request.Builder()
            .url(eventUrl)
            .method("PROPFIND", PROPFIND_ETAG.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Authorization", basicAuth())
            .addHeader("Depth", "0")
            .build()
        return executeRequest(request) { body -> CalDavResult.Success(xmlParser.parseEtag(body)) }.getOrNull()
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

    private fun Request.isMutation() = method == "PUT" || method == "DELETE"

    private fun <T> executeRequest(
        request: Request,
        expectBody: Boolean = true,
        allow404: Boolean = false,
        authentication: Boolean = false,
        onSuccess: (String) -> CalDavResult<T>
    ): CalDavResult<T> = executeWithRetry(readOnly = !request.isMutation()) {
        executeSingleRequest(request, expectBody, allow404, authentication, onSuccess)
    }

    private fun <T> executeSingleRequest(
        request: Request,
        expectBody: Boolean,
        allow404: Boolean,
        authentication: Boolean,
        onSuccess: (String) -> CalDavResult<T>
    ): CalDavResult<T> = executeSingleRequestWithHeaders(request, expectBody, allow404, authentication) { body, _ ->
        onSuccess(body)
    }

    private fun <T> executeRequestWithHeaders(
        request: Request,
        onSuccess: (String, Map<String, String>) -> CalDavResult<T>
    ): CalDavResult<T> = executeWithRetry(readOnly = !request.isMutation()) {
        executeSingleRequestWithHeaders(request, onSuccess = onSuccess)
    }

    private fun <T> executeSingleRequestWithHeaders(
        request: Request,
        expectBody: Boolean = true,
        allow404: Boolean = false,
        authentication: Boolean = false,
        onSuccess: (String, Map<String, String>) -> CalDavResult<T>
    ): CalDavResult<T> {
        return try {
            val call = if (request.isMutation()) {
                // Disable automatic replays for PUT and DELETE.
                val body = request.body ?: ByteArray(0).toRequestBody()
                val oneShot = object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                    override fun isOneShot() = true
                }
                mutationClient.newCall(request.newBuilder().method(request.method, oneShot).build())
            } else httpClient.newCall(request)
            call.execute().use { response ->
                if (response.code == 404 && allow404) {
                    @Suppress("UNCHECKED_CAST")
                    return CalDavResult.Success(Unit) as CalDavResult<T>
                }
                responseError(response, authentication, request.isMutation())?.let { return it }
                val body = if (expectBody) readBodyWithLimit(response)
                    ?: return CalDavResult.Error.payloadTooLargeError(
                        "Response too large (exceeds ${MAX_BODY_SIZE / 1_000_000}MB limit)")
                    else ""
                onSuccess(body, response.headers.toMap())
            }
        } catch (e: Exception) {
            requestFailure(e, request.isMutation())
        }
    }

    private fun responseError(
        response: Response,
        authentication: Boolean = false,
        mutation: Boolean = false
    ): CalDavResult.Error? {
        val code = response.code
        if (code in 200..299) return null
        val window = response.request.tag(CalDavBackoff.Window::class.java)
        val error = when {
            (code == 429 || code == 503) && window != null -> window.error()
            code == 401 || (code == 403 && authentication) -> CalDavResult.Error.authError()
            code == 403 -> CalDavResult.Error.forbiddenError()
            code == 404 -> CalDavResult.Error.notFoundError()
            code == 412 -> CalDavResult.Error.conflictError()
            code == 429 -> CalDavResult.Error.rateLimitError()
            code in 500..599 -> CalDavResult.Error.serverError(code)
            else -> CalDavResult.Error(code, "Unexpected response: $code")
        }
        return when {
            mutation && code in 500..599 && backoff.retryAfter(response) == null -> unknownOutcome(error)
            mutation && error.retryAt != null -> notApplied(error)
            else -> error
        }
    }

    private fun requestFailure(exception: Exception, mutation: Boolean = false): CalDavResult.Error {
        if (exception is CalDavBackoff.Blocked) {
            val error = exception.window.error()
            return if (mutation) notApplied(error) else error
        }
        val cause = if (exception is CalDavBackoff.TransportFailure) exception.failure else exception
        val error = when (cause) {
            is SSLException -> CalDavResult.Error.sslError("SSL error: ${cause.message}")
            is SocketTimeoutException -> CalDavResult.Error.timeoutError("Request timed out: ${cause.message}")
            else -> CalDavResult.Error.networkError("Network error: ${cause.message}")
        }
        return if (mutation && cause is IOException) unknownOutcome(error) else error
    }

    private fun notApplied(error: CalDavResult.Error) = error.copy(
        message = "Mutation not applied. ${error.message}"
    )

    private fun unknownOutcome(error: CalDavResult.Error) = error.copy(
        message = "Mutation may have been applied. Read back the event before retrying. ${error.message}",
        isRetryable = false
    )

    private fun <T> executeWithRetry(
        readOnly: Boolean = true,
        block: () -> CalDavResult<T>
    ): CalDavResult<T> {
        for (attempt in 0..if (readOnly) MAX_RETRIES else 0) {
            val result = block()
            if (result !is CalDavResult.Error || !result.isRetryable || !readOnly) return result
            val window = backoff.window()
            if (window != null && Duration.between(clock.instant(), window.retryAt).toMillis() > MAX_RETRY_AFTER_MS) {
                return window.error()
            }
            if (attempt == MAX_RETRIES) return result
            if (window == null) {
                try {
                    sleep(BASE_DELAY_MS * (1L shl attempt) + Random.nextLong(0, MAX_JITTER_MS))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return result
                }
            }
        }
        error("Retry loop must return a result")
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until size) {
            map[name(i)] = value(i)
        }
        return map
    }
}

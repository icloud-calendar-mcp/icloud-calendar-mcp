package org.onekash.mcp.calendar.integration

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.onekash.icaldav.testutil.PropertyRuns
import org.onekash.mcp.calendar.caldav.CalDavCalendar
import org.onekash.mcp.calendar.caldav.CalDavEvent
import org.onekash.mcp.calendar.caldav.ICloudUrlNormalizer
import org.onekash.mcp.calendar.error.SecureErrorHandler
import org.onekash.mcp.calendar.ics.IcsParser
import org.onekash.mcp.calendar.logging.McpLogger
import org.onekash.mcp.calendar.registerTools
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.EventHandle
import org.onekash.mcp.calendar.testsupport.MockCalDavClient
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Properties over the MCP surface: whatever an LLM sends, a tool answers with a result or a
 * structured client error, never an INTERNAL_ERROR from an unhandled exception; handles and error
 * messages keep their security guarantees for any input.
 */
class ToolInputPropertiesTest {

    // ---------- Tool calls through registerTools with a real CalendarService ----------

    private fun server(mock: MockCalDavClient): Server {
        val server = Server(
            serverInfo = Implementation(name = "property-server", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
        )
        registerTools(server, CalendarService(mock), McpLogger(server))
        return server
    }

    private fun call(server: Server, name: String, args: JsonObject): CallToolResult = runBlocking {
        val tool = server.tools[name] ?: error("tool $name not registered")
        tool.handler(mockk<ClientConnection>(relaxed = true), CallToolRequest(CallToolRequestParams(name = name, arguments = args)))
    }

    private fun textOf(result: CallToolResult) = (result.content.first() as TextContent).text

    /** The error code of a failed result, or null for a success. The text must be valid JSON. */
    private fun errorCode(result: CallToolResult): String? {
        val json = Json.parseToJsonElement(textOf(result)).jsonObject
        if (result.isError != true) return null
        return json["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    }

    private val dateTimes = listOf(
        "2026-03-02T09:00:00", "2026-03-02T09:00:00Z", "2026-03-02T23:30:00+05:30", "2026-03-02T09:00",
        "2026-03-03T17:45:00Z", "2026-13-40T99:00:00", "", "not a date", "2026-03-02", "9999-12-31T23:59:59Z"
    )
    private val dates = listOf("2026-03-02", "2026-03-05", "2026-02-30", "26-03-02", "", "2026-03-02T09:00:00")
    private val zones = listOf(null, "America/New_York", "Asia/Seoul", "UTC", "Europe/Berlin", "Not/A_Zone", "")
    private val rrules = listOf(
        null, "FREQ=DAILY;COUNT=5", "FREQ=DAILY;BYHOUR=9,17;COUNT=6", "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6",
        "FREQ=HOURLY;INTERVAL=6;COUNT=8", "FREQ=DAILY;UNTIL=20260320T000000Z", "FREQ=YEARLY;BYDAY=54MO", "garbage", ""
    )
    private val recurrenceValues = dateTimes + dates + listOf("2026-03-04T09:00:00-08:00", "x".repeat(40))

    private val createArgs = arbitrary {
        val allDay = Arb.int(0..3).bind() == 0
        val exdates = (0 until Arb.int(0..3).bind()).map { Arb.element(recurrenceValues).bind() }
        val rdates = (0 until Arb.int(0..2).bind()).map { Arb.element(recurrenceValues).bind() }
        val calendarId = Arb.element("cal-1", "cal-1", "missing-cal", "", "../etc").bind()
        val title = Arb.element("Meeting", "", "A\r\nX-INJECT:1", "x".repeat(300), "Zoom; call, \\N now").bind()
        val startDate = Arb.element(dates).bind()
        val endDate = Arb.element(dates).bind()
        val startTime = Arb.element(dateTimes).bind()
        val endTime = Arb.element(dateTimes).bind()
        val zone = Arb.element(zones).bind()
        val rrule = Arb.element(rrules).bind()
        buildJsonObject {
            put("calendar_id", calendarId)
            put("title", title)
            if (allDay) {
                put("is_all_day", JsonPrimitive(true))
                put("start_date", startDate)
                put("end_date", endDate)
            } else {
                put("start_time", startTime)
                put("end_time", endTime)
            }
            zone?.let { put("timezone", it) }
            rrule?.let { put("rrule", it) }
            if (exdates.isNotEmpty()) putJsonArray("exdates") { exdates.forEach { add(JsonPrimitive(it)) } }
            if (rdates.isNotEmpty()) putJsonArray("rdates") { rdates.forEach { add(JsonPrimitive(it)) } }
        }
    }

    @Test
    fun `create_event answers any arguments with a result or a client error, never INTERNAL_ERROR`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(400), createArgs) { args ->
            val mock = MockCalDavClient().apply {
                calendars = listOf(CalDavCalendar("cal-1", "/cal/", "https://test.com/cal/", "Cal", null, null, false))
            }
            val result = call(server(mock), "create_event", args)
            val code = errorCode(result)
            assertNotEquals("INTERNAL_ERROR", code, "$args -> ${textOf(result)}")
            if (code == null) {
                val ics = mock.lastCreatedIcs ?: error("success without a write: $args")
                assertTrue(IcsParser().parse(ics).isNotEmpty(), "the written ICS parses back: $args\n$ics")
            }
        }
    }

    private val seriesIcs = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN", "BEGIN:VEVENT", "UID:series@test",
        "DTSTAMP:20260101T000000Z", "DTSTART;TZID=America/New_York:20260301T090000",
        "DTEND;TZID=America/New_York:20260301T093000", "RRULE:FREQ=DAILY;UNTIL=20260320T130000Z",
        "SUMMARY:Series", "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Room\":geo:1,2", "END:VEVENT",
        "BEGIN:VEVENT", "UID:series@test", "DTSTAMP:20260101T000000Z", "RECURRENCE-ID;TZID=America/New_York:20260303T090000",
        "DTSTART;TZID=America/New_York:20260303T100000", "DTEND;TZID=America/New_York:20260303T103000",
        "SUMMARY:Moved", "END:VEVENT", "END:VCALENDAR"
    ).joinToString("\r\n")

    private val updateArgs = arbitrary {
        val title = if (Arb.int(0..1).bind() == 0) Arb.element("Renamed", "", "A\r\nB").bind() else null
        val times = if (Arb.int(0..2).bind() == 0) Arb.element(dateTimes).bind() to Arb.element(dateTimes).bind() else null
        val zone = Arb.element(zones).bind()
        val rrule = if (Arb.int(0..2).bind() == 0) Arb.element(rrules).bind() else null
        val exdates = (0 until Arb.int(0..3).bind()).map { Arb.element(recurrenceValues).bind() }
        val scope = Arb.element(null, "all_events", "this_and_future", "this_occurrence", "nonsense").bind()
        buildJsonObject {
            put("event_id", EventHandle.encode("/cal/series.ics", "\"e1\""))
            title?.let { put("title", it) }
            times?.let { (start, end) -> put("start_time", start); put("end_time", end) }
            zone?.let { put("timezone", it) }
            rrule?.let { put("rrule", it) }
            if (exdates.isNotEmpty()) putJsonArray("exdates") { exdates.forEach { add(JsonPrimitive(it)) } }
            scope?.let { put("scope", it) }
        }
    }

    @Test
    fun `update_event answers any arguments on a series with a result or a client error`(): Unit = runBlocking {
        checkAll(PropertyRuns.config(300), updateArgs) { args ->
            val mock = MockCalDavClient().apply {
                calendars = listOf(CalDavCalendar("cal", "/cal/", "https://test.com/cal/", "Cal", null, null, false))
                registeredEvents["series@test"] =
                    CalDavEvent("series@test", "/cal/series.ics", "https://test.com/cal/series.ics", "\"e1\"", seriesIcs)
            }
            val result = call(server(mock), "update_event", args)
            assertNotEquals("INTERNAL_ERROR", errorCode(result), "$args -> ${textOf(result)}")
        }
    }

    // ---------- EventHandle ----------

    @Test
    fun `a handle round-trips its href, etag and recurrence id`(): Unit = runBlocking {
        val cases = arbitrary {
            val href = Arb.element("/123/calendars/home/", "/cal/", "https://p42-caldav.icloud.com/123/calendars/x/").bind() +
                Arb.element("a", "event", "x-1", "Ünï", "evt%20id").bind() + ".ics"
            val etag = Arb.element(null, "\"abc\"", "W/\"1-2\"", "plain").bind()
            val recid = Arb.element(null, "20260303T090000Z", "20260303T090000", "20260303").bind()
            Triple(href, etag, recid)
        }
        checkAll(PropertyRuns.config(300), cases) { (href, etag, recid) ->
            val decoded = EventHandle.decode(EventHandle.encode(href, etag, recid)) ?: error("decode failed for $href")
            assertEquals(ICloudUrlNormalizer.normalize(href), decoded.href)
            assertEquals(etag, decoded.etag)
            assertEquals(recid, decoded.recurrenceId)
        }
    }

    @Test
    fun `decoding any token never throws and never yields a non-iCloud absolute href`(): Unit = runBlocking {
        val tokens = arbitrary {
            when (Arb.int(0..2).bind()) {
                0 -> Arb.string(0..80).bind()
                1 -> Arb.element("evt1_", "evt2_").bind() + Arb.string(0..80).bind()
                else -> {
                    val href = Arb.element("https://evil.com/x", "https:/evil.com/x", "http://p1-caldav.icloud.com.evil.com/",
                        "javascript:alert(1)", "//evil.com/x", "/ok/path.ics", "HTTPS://CALDAV.ICLOUD.COM/1/x.ics",
                        "file:///etc/passwd", "https://caldav.icloud.com@evil.com/").bind()
                    val payload = "$href\n${Arb.element("", "e").bind()}" + Arb.element("", "\n20260303T090000Z").bind()
                    Arb.element("evt1_", "evt2_").bind() +
                        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
                }
            }
        }
        checkAll(PropertyRuns.config(500), tokens) { token ->
            val decoded = EventHandle.decode(token) ?: return@checkAll
            val href = decoded.href
            val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(href)
            if (scheme != null) {
                val host = runCatching { URI(href).host }.getOrNull()?.lowercase()
                assertTrue(host != null && (host == "icloud.com" || host.endsWith(".icloud.com")),
                    "an absolute handle href must be an iCloud host: $href (token $token)")
            }
        }
    }

    // ---------- SecureErrorHandler ----------

    @Test
    fun `an error result is valid JSON and leaks no credential, personal path or full email`(): Unit = runBlocking {
        val secret = "S3cretValue9"
        val leaks = listOf(
            "password=$secret", "Password: $secret", "token=$secret", "api_key=$secret", "secret=$secret",
            "/Users/real$secret/file", "/home/real$secret/file", "jane.doe.$secret@example.org"
        )
        val filler = listOf("a", " ", "\"", "\\", "\n", "\r", "\t", "\u0001", "\u001f", "é", "{", "}", ":", ",")
        val messages = arbitrary {
            val m = StringBuilder()
            repeat(Arb.int(0..30).bind()) { m.append(Arb.element(filler).bind()) }
            m.append(Arb.element(leaks).bind())
            repeat(Arb.int(0..10).bind()) { m.append(Arb.element(filler).bind()) }
            m.toString()
        }
        checkAll(PropertyRuns.config(300), messages) { message ->
            val result = SecureErrorHandler.createErrorResult(SecureErrorHandler.ErrorCode.CALDAV_ERROR, message)
            val text = textOf(result)
            val parsed = runCatching { Json.parseToJsonElement(text) }
            assertTrue(parsed.isSuccess, "error result is not valid JSON for message ${message.map { it.code }}: $text")
            // RFC 8259 7: control characters U+0000 to U+001F must be escaped inside a string.
            assertTrue(text.none { it < ' ' }, "raw control character in ${text.map { it.code }}")
            assertFalse(text.contains(secret), "secret leaked from $message: $text")
        }
    }

    // ---------- ICloudUrlNormalizer ----------

    @Test
    fun `normalizing an href is idempotent`(): Unit = runBlocking {
        val hrefs = arbitrary {
            Arb.element("/", "https://", "http://", "https://p12-caldav.icloud.com:443", "//").bind() +
                Arb.string(0..60).bind()
        }
        checkAll(PropertyRuns.config(500), hrefs) { href ->
            val once = runCatching { ICloudUrlNormalizer.normalize(href) }
            assertTrue(once.isSuccess, "normalize threw for $href: ${once.exceptionOrNull()}")
            assertEquals(once.getOrNull(), ICloudUrlNormalizer.normalize(once.getOrNull()), "not idempotent for $href")
        }
    }
}

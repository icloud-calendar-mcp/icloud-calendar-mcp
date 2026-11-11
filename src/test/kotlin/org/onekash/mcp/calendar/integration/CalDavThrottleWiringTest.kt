package org.onekash.mcp.calendar.integration

import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import org.onekash.mcp.calendar.error.SecureErrorHandler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class CalDavThrottleWiringTest {
    @Test
    fun `error deadlines use UTC and round remaining seconds up with a floor of one`() {
        val now = Instant.parse("2026-11-10T12:00:00Z")
        for ((code, deadline, expected) in listOf(
            Triple(429, now.plusMillis(1595001), 1596L),
            Triple(503, now.plusSeconds(30), 30L),
            Triple(429, now.minusSeconds(1), 1L)
        )) {
            val result = SecureErrorHandler.serviceError(code, "Calendar temporarily unavailable", deadline,
                Clock.fixed(now, ZoneOffset.UTC))
            val error = Json.parseToJsonElement((result.content.first() as TextContent).text)
                .jsonObject["error"]!!.jsonObject
            assertEquals(if (code == 429) "RATE_LIMITED" else "CALDAV_ERROR", error["code"]!!.jsonPrimitive.content)
            assertEquals(deadline.toString(), error["retryAt"]!!.jsonPrimitive.content)
            assertEquals(expected, error["retryAfterSeconds"]!!.jsonPrimitive.long)
        }
    }
}

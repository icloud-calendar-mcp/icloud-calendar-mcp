package org.onekash.mcp.calendar

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.onekash.mcp.calendar.ics.ParsedAlarm
import org.onekash.mcp.calendar.service.EventInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the get_events response building blocks:
 * - [encodeEventForResponse]: completeness of the emitted JSON shape
 */
class GetEventsResponseTest {

    private fun event(uid: String) = EventInfo(
        uid = uid,
        href = "/cal/$uid.ics",
        etag = "\"etag-$uid\"",
        summary = "Event $uid",
        description = null,
        location = null,
        isAllDay = false,
        startTime = "2026-01-15T09:00:00Z",
        endTime = "2026-01-15T10:00:00Z",
        startDate = null,
        endDate = null,
        rrule = null
    )

    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encode emits timezone recurrence fields and alarms when present`() {
        val info = event("event-1").copy(
            timezone = "America/New_York",
            endTimezone = "America/Los_Angeles",
            rrule = "FREQ=WEEKLY;COUNT=6",
            recurrenceId = "2026-01-12T09:00:00Z",
            rdates = listOf("2026-01-20T09:00:00Z"),
            exdates = listOf("2026-01-19T09:00:00Z"),
            alarms = listOf(ParsedAlarm(trigger = "-PT15M", action = "DISPLAY"))
        )

        val json = encodeEventForResponse(info)

        assertEquals("America/New_York", json["timezone"]!!.jsonPrimitive.content)
        assertEquals("America/Los_Angeles", json["endTimezone"]!!.jsonPrimitive.content)
        assertEquals("FREQ=WEEKLY;COUNT=6", json["rrule"]!!.jsonPrimitive.content)
        assertEquals("2026-01-12T09:00:00Z", json["recurrenceId"]!!.jsonPrimitive.content)
        assertEquals("2026-01-20T09:00:00Z", json["rdates"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("2026-01-19T09:00:00Z", json["exdates"]!!.jsonArray[0].jsonPrimitive.content)
        val alarm = json["alarms"]!!.jsonArray[0].jsonObject
        assertEquals("-PT15M", alarm["trigger"]!!.jsonPrimitive.content)
        assertEquals("DISPLAY", alarm["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encode omits optional fields when absent`() {
        val json = encodeEventForResponse(event("event-1"))

        assertEquals("event-1", json["uid"]!!.jsonPrimitive.content)
        assertEquals("2026-01-15T09:00:00Z", json["startTime"]!!.jsonPrimitive.content)
        listOf("timezone", "endTimezone", "rrule", "recurrenceId", "rdates", "exdates", "alarms").forEach {
            assertNull(json[it], "field '$it' should be omitted when absent")
        }
    }

    @Test
    fun `encode emits date fields for all-day events`() {
        val info = event("event-1").copy(
            isAllDay = true,
            startTime = null,
            endTime = null,
            startDate = "2026-01-15",
            endDate = "2026-01-16"
        )

        val json = encodeEventForResponse(info)

        assertEquals("2026-01-15", json["startDate"]!!.jsonPrimitive.content)
        assertEquals("2026-01-16", json["endDate"]!!.jsonPrimitive.content)
        assertNull(json["startTime"])
        assertNull(json["endTime"])
    }
}

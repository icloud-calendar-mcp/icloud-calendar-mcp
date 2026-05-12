package org.onekash.mcp.calendar.validation

import org.onekash.mcp.calendar.validation.InputValidator.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [InputValidator.validateAlarmList] (issue #1 follow-up).
 *
 * The validator runs at the MCP boundary on already-decoded JSON (a
 * `List<Map<String, Any?>>`), one level above the typed [AlarmSpec].
 * These tests pin the rejection criteria the plan called out (chunk 36
 * items 14–16) plus a happy-path mixed-action sanity check.
 */
class InputValidatorAlarmTest {

    @Test
    fun `validateAlarmList accepts a mixed-action list within bounds`() {
        val alarms = listOf(
            mapOf<String, Any?>("trigger" to "-PT15M"),                                     // DISPLAY default
            mapOf<String, Any?>("trigger" to "-P1D", "action" to "AUDIO"),
            mapOf<String, Any?>(
                "trigger" to "20260115T093000Z",
                "action" to "EMAIL",
                "summary" to "Reminder subject",
                "description" to "Body"
            ),
            mapOf<String, Any?>(
                "trigger" to "-PT5M",
                "repeat_count" to 3,
                "repeat_duration" to "PT5M"
            )
        )
        val result = InputValidator.validateAlarmList(alarms, "alarms")
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun `validateAlarmList rejects more than 8 alarms`() {
        val alarms = (1..9).map { mapOf<String, Any?>("trigger" to "-PT${it}M") }
        val result = InputValidator.validateAlarmList(alarms, "alarms")
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(result.message.contains("max 8"), "Expected cap message, got: ${result.message}")
    }

    @Test
    fun `validateAlarmList rejects an unknown action`() {
        val alarms = listOf(
            mapOf<String, Any?>("trigger" to "-PT15M", "action" to "PROCEDURE")
        )
        val result = InputValidator.validateAlarmList(alarms, "alarms")
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(
            result.message.contains("DISPLAY/AUDIO/EMAIL"),
            "Expected action whitelist error, got: ${result.message}"
        )
    }

    @Test
    fun `validateAlarmList rejects a malformed trigger`() {
        // Not a duration ("PT15M" missing leading P? — actually that IS valid).
        // Use a clearly bogus value: "yesterday".
        val alarms = listOf(
            mapOf<String, Any?>("trigger" to "yesterday")
        )
        val result = InputValidator.validateAlarmList(alarms, "alarms")
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(
            result.message.contains("RFC 5545 duration") || result.message.contains("absolute UTC instant"),
            "Expected trigger-format error, got: ${result.message}"
        )
    }

    @Test
    fun `validateAlarmList rejects missing trigger field`() {
        val alarms = listOf(
            mapOf<String, Any?>("action" to "DISPLAY")
        )
        val result = InputValidator.validateAlarmList(alarms, "alarms")
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(
            result.message.contains("trigger is required"),
            "Expected required-trigger error, got: ${result.message}"
        )
    }
}

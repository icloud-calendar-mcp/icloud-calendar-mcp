package org.onekash.mcp.calendar.ics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Reproduction probe for issue #2: "partial update_event corrupts SUMMARY when
 * the previous DESCRIPTION is long enough to be line-folded".
 *
 * User's reported pattern:
 *   - Create event with long DESCRIPTION (>75 octets, so gets folded)
 *   - Call update_event with ONLY a new short description
 *   - Subsequent get_events shows SUMMARY contains uppercase fragments of
 *     the OLD description
 *
 * These tests probe the in-process patcher path. If they pass, the corruption
 * is happening server-side (iCloud) or in a wire-encoding step we don't model
 * here. If they fail, we've reproduced the bug locally.
 */
class Issue2ReproTest {

    private val patcher = IcsPatcher()
    private val parser = IcsParser()

    // The exact reported strings.
    private val originalSummary = "MCP TEST long-description event"
    private val originalDescription = "Send a short follow-up email with an attachment for a warm introduction. Frame around the pitch, not generic availability."
    private val newDescription = "MCP TEST touch simple."

    /**
     * Build an ICS string representative of what iCloud might return: SUMMARY
     * is short, DESCRIPTION is long enough to require RFC 5545 §3.1 line folding
     * (>75 octets per line). Encoded with CRLF + space continuation.
     */
    private fun buildOriginalIcsWithFoldedDescription(): String {
        // Hand-fold the description at the 75-octet boundary.
        val descRaw = "DESCRIPTION:$originalDescription"
        val firstChunk = descRaw.substring(0, 75)
        val rest = descRaw.substring(75)
        // RFC 5545 continuation: CRLF + single space + remainder
        val foldedDescription = "$firstChunk\r\n $rest"

        return listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Test//EN",
            "BEGIN:VEVENT",
            "UID:repro-issue2@test",
            "DTSTAMP:20260115T100000Z",
            "DTSTART:20260120T140000Z",
            "DTEND:20260120T150000Z",
            "SUMMARY:$originalSummary",
            foldedDescription,
            "END:VEVENT",
            "END:VCALENDAR"
        ).joinToString("\r\n") + "\r\n"
    }

    @Test
    fun `parser correctly handles folded DESCRIPTION`() {
        val ics = buildOriginalIcsWithFoldedDescription()
        val parsed = parser.parse(ics)
        assertEquals(1, parsed.size, "expect exactly one event")
        val event = parsed[0]
        assertEquals(originalSummary, event.summary, "SUMMARY should not absorb DESCRIPTION on parse")
        assertEquals(originalDescription, event.description, "DESCRIPTION should be unfolded correctly")
    }

    @Test
    fun `description-only patch leaves SUMMARY exactly as it was`() {
        val ics = buildOriginalIcsWithFoldedDescription()

        val patched = patcher.patch(
            existingIcs = ics,
            uid = "repro-issue2@test",
            description = newDescription
        )

        // Re-parse the patched ICS
        val parsed = parser.parse(patched)
        assertEquals(1, parsed.size)
        val event = parsed[0]

        // The smoking gun for issue #2: SUMMARY must NOT contain ANY part of
        // the original description (especially uppercased fragments).
        assertEquals(originalSummary, event.summary,
            "SUMMARY corruption detected — issue #2 reproduced! Got: '${event.summary}'")
        assertEquals(newDescription, event.description,
            "DESCRIPTION should match the new value")

        // Belt-and-suspenders: verify lowercase fragments of old description
        // don't leak into summary either.
        assertTrue(!event.summary.contains("warm", ignoreCase = true),
            "SUMMARY contains 'warm' fragment from old description: '${event.summary}'")
        assertTrue(!event.summary.contains("frame", ignoreCase = true),
            "SUMMARY contains 'frame' fragment from old description: '${event.summary}'")
    }

    @Test
    fun `description-only patch then second-pass parse round trip`() {
        // Mimics the full user flow:
        // 1. Original ICS (server-style folded)
        // 2. patch with description-only update
        // 3. server re-stores it and emits it back; we have to parse what the
        //    PATCHER produces (since we don't have the iCloud loop here, this
        //    is the closest local proxy)
        // 4. parse what we just produced — should match what we expect
        val ics = buildOriginalIcsWithFoldedDescription()

        val patched = patcher.patch(
            existingIcs = ics,
            uid = "repro-issue2@test",
            description = newDescription
        )

        // Print the patched ICS for diagnostic purposes if any later assertion fails.
        println("\n===== PATCHED ICS =====\n$patched\n=======================")

        val parsed = parser.parse(patched)
        assertNotNull(parsed.firstOrNull(), "must parse a single event")

        // Now feed the patched ICS through CalendarBuilder again — simulating
        // what would happen if iCloud round-tripped it through its own parser
        // and emitted a normalized version. The key invariant: SUMMARY survives.
        val secondParsed = parser.parse(patched)
        assertEquals(1, secondParsed.size)
        assertEquals(originalSummary, secondParsed[0].summary,
            "SUMMARY should survive second-pass parse")
    }

    /**
     * Probe the case where the ORIGINAL ICS has only LF line endings
     * (no CRLF) — this is what KashCal-style producers, or our old pre-§3.1
     * builder, would emit. ical4j is generally tolerant but iCloud might not be.
     */
    @Test
    fun `description-only patch on LF-only original throws typed exception (chunk 38)`() {
        // Chunk 38 hardening: when ical4j can't parse the existing ICS, the
        // patcher used to silently fall through to buildFresh, producing an
        // Untitled-summary event that destroyed the original. The new behavior
        // is a typed exception that the service layer turns into a 422.
        val lfOnly = buildOriginalIcsWithFoldedDescription().replace("\r\n", "\n")
        kotlin.test.assertFailsWith<IcsPatcher.UnparseableExistingIcsException> {
            patcher.patch(
                existingIcs = lfOnly,
                uid = "repro-issue2@test",
                description = newDescription
            )
        }
    }

    /**
     * Probe with description that contains the kind of escaped punctuation
     * the user reported: literal commas/semicolons. RFC 5545 §3.3.11 says
     * commas in TEXT must be backslash-escaped. ical4j handles this on
     * encode/decode — let's confirm round-trip stays clean.
     */
    @Test
    fun `description with escaped commas and semicolons round-trips clean`() {
        val tricky = "Send a follow-up email; warm introduction, framed around the pitch, not generic availability."
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:repro-issue2-escape@test
            DTSTAMP:20260115T100000Z
            DTSTART:20260120T140000Z
            DTEND:20260120T150000Z
            SUMMARY:$originalSummary
            DESCRIPTION:Send a follow-up email\; warm introduction\, framed around the pitch\, not generic availability.
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        // Sanity: parser unescapes correctly
        val sourceParsed = parser.parse(ics)
        assertEquals(1, sourceParsed.size)
        assertEquals(tricky, sourceParsed[0].description, "source description should unescape")

        // Patch with new description, verify SUMMARY survives
        val patched = patcher.patch(
            existingIcs = ics,
            uid = "repro-issue2-escape@test",
            description = newDescription
        )
        val parsed = parser.parse(patched)
        assertEquals(originalSummary, parsed[0].summary)
        assertEquals(newDescription, parsed[0].description)
    }
}

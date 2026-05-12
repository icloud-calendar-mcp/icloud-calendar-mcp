package org.onekash.mcp.calendar.rfc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.onekash.mcp.calendar.ics.AlarmSpec
import org.onekash.mcp.calendar.ics.IcsBuilder
import org.onekash.mcp.calendar.ics.IcsParser
import java.time.Instant

/**
 * RFC 5545 (iCalendar) compliance audit covering the producer + consumer paths
 * exercised by this MCP server. Each test cites the RFC clause it pins so any
 * failure points at a real compliance gap, not an implementation detail.
 *
 * Slim port of KashCal's Rfc5545ComplianceAuditTest — clauses included are
 * those touched by chunks 27-31 and 36 (VALARM authoring). Out of scope for
 * this audit (covered by the project's other test suites or deliberately
 * deferred):
 *
 * - §3.3.10 RRULE corpus — RRULE strings pass through verbatim; client owns
 *   construction. KashCal has a parity harness for this; the MCP server does not
 *   expand recurrences server-side.
 * - §3.8.4 relationships (RELATED-TO, ATTENDEE/ORGANIZER beyond preservation) —
 *   IcsPatcherTest covers ATTENDEE/ORGANIZER preservation; deeper semantics not
 *   modeled here.
 * - §3.8.5.2 PERIOD value type for RDATE — handled best-effort by IcsParser
 *   (start instant only); iCloud rarely emits this form.
 * - §3.6.2/.3 VTODO / VJOURNAL — this server only exposes VEVENT.
 * - RFC 9074 alarm extensions (DEFAULT-ALARM, ACKNOWLEDGED, PROXIMITY,
 *   RELATED-TO) — deferred until requested.
 */
class Rfc5545ComplianceAuditTest {

    private val builder = IcsBuilder()
    private val parser = IcsParser()

    // ───────────────────────────────────────────────────────────────────────
    // §3.1 — Content lines MUST end with CRLF
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 1 - produced ICS uses CRLF line endings`() {
        val ics = builder.build(
            uid = "audit-3-1@example.com",
            summary = "CRLF check",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z"
        )

        // Every line break inside the produced ICS must be CRLF.
        // Bare LF means the platform's lineSeparator() leaked through (Linux/Android
        // would emit LF, violating §3.1's ABNF: contentline = name *(";" param ) ":"
        // value CRLF).
        val crlfCount = "\r\n".toRegex().findAll(ics).count()
        val lfCount = ics.count { it == '\n' }

        assertEquals(
            crlfCount, lfCount,
            "Every \\n in the produced ICS must be preceded by \\r (RFC 5545 §3.1). " +
                "Found $lfCount LF and $crlfCount CRLF — they must match.\n\nICS:\n$ics"
        )

        // Sanity: there should be at least a handful of lines.
        assertTrue(crlfCount >= 6, "Expected multiple lines in produced ICS, got $crlfCount CRLF")

        // Producer must not emit double CRLF inside event blocks (would imply
        // a multi-line value passed unsplit through a CRLF helper).
        val betweenBeginAndEnd = ics.substringAfter("BEGIN:VEVENT").substringBefore("END:VEVENT")
        assertFalse(
            betweenBeginAndEnd.contains("\r\n\r\n"),
            "Double CRLF detected inside VEVENT block — likely a multi-line property emitted as one chunk"
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // §3.6.1 — DTEND for "DATE" is non-inclusive (smoke pin: already passes)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 6 1 - all-day DTEND is exclusive on the wire`() {
        val ics = builder.build(
            uid = "audit-3-6-1@example.com",
            summary = "All-day exclusive end",
            isAllDay = true,
            startDate = "2026-01-15",
            endDate = "2026-01-15" // single-day event
        )

        // RFC 5545 §3.6.1: DTEND for DATE is the day AFTER the inclusive end.
        // 1-day event from 2026-01-15 should serialize DTEND;VALUE=DATE:20260116.
        assertTrue(
            ics.contains("DTSTART;VALUE=DATE:20260115"),
            "Expected DTSTART;VALUE=DATE:20260115 in:\n$ics"
        )
        assertTrue(
            ics.contains("DTEND;VALUE=DATE:20260116"),
            "Expected DTEND;VALUE=DATE:20260116 (exclusive end per §3.6.1) in:\n$ics"
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // §3.3.11 — TEXT escapes (smoke pin: ical4j decodes once; we don't double-decode)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 3 11 - TEXT escapes round-trip without double-decoding`() {
        // Construct an ICS by hand (so we know exactly what's escaped) and parse it.
        // The DESCRIPTION value contains: a literal backslash, a semicolon, a comma,
        // and a newline — all in their RFC 5545 §3.3.11 TEXT-escaped form.
        // After one decode, we expect a string like: "back\\slash; comma, line"+LF+"break"
        // After two decodes (the bug this audit guards against), the literal
        // backslash collapses to nothing.
        val handBuilt = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//AuditTest//EN",
            "BEGIN:VEVENT",
            "UID:audit-3-3-11@example.com",
            "DTSTAMP:20260115T100000Z",
            "DTSTART:20260115T100000Z",
            "DTEND:20260115T110000Z",
            "SUMMARY:Escape test",
            "DESCRIPTION:back\\\\slash\\; comma\\, line\\nbreak",
            "END:VEVENT",
            "END:VCALENDAR"
        ).joinToString("\r\n") + "\r\n"

        val parsed = parser.parse(handBuilt)
        assertEquals(1, parsed.size, "Expected exactly 1 parsed event")
        val description = parsed[0].description

        // After one (correct) decode: \\ -> \, \; -> ;, \, -> ,, \n -> LF.
        assertEquals(
            "back\\slash; comma, line\nbreak",
            description,
            "DESCRIPTION should be decoded exactly once (RFC 5545 §3.3.11). " +
                "If it equals 'backslash; comma, line\\nbreak' the producer/consumer is " +
                "double-decoding — RFC 5545 §3.3.11 requires exactly one decode."
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // §3.8.5 — RECURRENCE: when RRULE present, prefer DURATION over DTEND
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 8 5 - recurring event uses DURATION not DTEND`() {
        val ics = builder.build(
            uid = "audit-3-8-5@example.com",
            summary = "Weekly meeting",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            rrule = "FREQ=WEEKLY;BYDAY=TH"
        )

        // RFC 5545 §3.8.5 + Etar/Fossify/AOSP convention: when RRULE is present,
        // emit DTSTART + DURATION so each occurrence carries its length without
        // recomputing from DTEND.
        assertTrue(
            ics.contains("RRULE:FREQ=WEEKLY;BYDAY=TH"),
            "Sanity: RRULE should be present in:\n$ics"
        )
        assertTrue(
            ics.contains("DURATION:PT1H"),
            "Expected DURATION:PT1H (1-hour event) when RRULE is present (§3.8.5). ICS:\n$ics"
        )
        assertFalse(
            ics.lineSequence().any { it.startsWith("DTEND") },
            "DTEND must be absent when RRULE is present — DURATION supersedes it (§3.8.5). ICS:\n$ics"
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // §3.8.7.1 — CREATED: producer should emit it when known
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 8 7 1 - builder emits CREATED when createdAt provided`() {
        // CalendarService passes Instant.now() on createEvent — the audit pins that
        // the producer DOES emit CREATED when given a value. Patcher (§3.8.7.1) tests
        // verify the never-changes-after-first-set rule in IcsPatcherTest.
        val ics = builder.build(
            uid = "audit-3-8-7-1@example.com",
            summary = "With createdAt",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            createdAt = Instant.parse("2026-01-10T08:30:00Z")
        )

        assertTrue(
            ics.contains("CREATED:20260110T083000Z"),
            "Expected CREATED:20260110T083000Z in produced ICS (§3.8.7.1). ICS:\n$ics"
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // §3.8.7.3 — LAST-MODIFIED: producer should emit it when known
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 8 7 3 - builder emits LAST-MODIFIED when lastModified provided`() {
        val ics = builder.build(
            uid = "audit-3-8-7-3@example.com",
            summary = "With lastModified",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            lastModified = Instant.parse("2026-01-12T14:45:00Z")
        )

        assertTrue(
            ics.contains("LAST-MODIFIED:20260112T144500Z"),
            "Expected LAST-MODIFIED:20260112T144500Z in produced ICS (§3.8.7.3). ICS:\n$ics"
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // §3.6.6 — VALARM authoring (added by chunk 36)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RFC 5545 section 3 6 6 - builder emits VALARM with ACTION and TRIGGER`() {
        val ics = builder.build(
            uid = "audit-3-6-6@example.com",
            summary = "With alarm",
            startTime = "2026-01-15T10:00:00Z",
            endTime = "2026-01-15T11:00:00Z",
            alarms = listOf(AlarmSpec(trigger = "-PT15M"))
        )
        assertTrue(ics.contains("BEGIN:VALARM"), "VALARM block present (§3.6.6):\n$ics")
        assertTrue(ics.contains("ACTION:DISPLAY"), "Default ACTION DISPLAY (§3.6.6):\n$ics")
        assertTrue(ics.contains("TRIGGER:-PT15M"), "Duration TRIGGER (§3.8.6.3):\n$ics")
        assertTrue(ics.contains("END:VALARM"), "VALARM closed (§3.6.6):\n$ics")
    }
}

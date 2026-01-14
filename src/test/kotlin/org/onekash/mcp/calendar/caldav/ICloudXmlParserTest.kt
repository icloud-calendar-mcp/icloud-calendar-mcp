package org.onekash.mcp.calendar.caldav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Tests for iCloud CalDAV XML response parsing.
 *
 * iCloud uses specific XML namespaces and formats that differ
 * from standard CalDAV. These tests verify correct parsing of:
 * - PROPFIND responses (calendar discovery)
 * - Multi-status responses (207)
 * - Calendar-data with CDATA wrapping
 * - Various namespace prefixes
 */
class ICloudXmlParserTest {

    private val parser = ICloudXmlParser()

    // ═══════════════════════════════════════════════════════════════════
    // CALENDAR DISCOVERY (PROPFIND)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseCalendars extracts calendar from standard response`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/1234567/calendars/home/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Personal</D:displayname>
                            <D:resourcetype>
                                <D:collection/>
                                <C:calendar/>
                            </D:resourcetype>
                            <CS:getctag>abc123</CS:getctag>
                            <ICAL:calendar-color xmlns:ICAL="http://apple.com/ns/ical/">#FF5733FF</ICAL:calendar-color>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        val cal = calendars[0]
        assertEquals("home", cal.id)
        assertEquals("/1234567/calendars/home/", cal.href)
        assertEquals("https://caldav.icloud.com/1234567/calendars/home/", cal.url)
        assertEquals("Personal", cal.displayName)
        assertEquals("#FF5733", cal.color)  // Alpha stripped
        assertEquals("abc123", cal.ctag)
        assertFalse(cal.isReadOnly)
    }

    @Test
    fun `parseCalendars handles multiple calendars`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/123/calendars/home/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Home</D:displayname>
                            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                            <CS:getctag>ctag1</CS:getctag>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
                <D:response>
                    <D:href>/123/calendars/work/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Work</D:displayname>
                            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                            <CS:getctag>ctag2</CS:getctag>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")

        assertEquals(2, calendars.size)
        assertEquals("Home", calendars[0].displayName)
        assertEquals("Work", calendars[1].displayName)
    }

    @Test
    fun `parseCalendars skips non-calendar collections`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:CS="http://calendarserver.org/ns/" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/123/calendars/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Calendars</D:displayname>
                            <D:resourcetype><D:collection/></D:resourcetype>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
                <D:response>
                    <D:href>/123/calendars/home/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Home</D:displayname>
                            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertEquals("Home", calendars[0].displayName)
    }

    @Test
    fun `parseCalendars handles missing color`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/123/calendars/home/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Home</D:displayname>
                            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertNull(calendars[0].color)
        assertNull(calendars[0].ctag)
    }

    @Test
    fun `parseCalendars handles read-only calendars`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/shared/calendars/team/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Team Shared</D:displayname>
                            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                            <D:current-user-privilege-set>
                                <D:privilege><D:read/></D:privilege>
                            </D:current-user-privilege-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertTrue(calendars[0].isReadOnly)
    }

    @Test
    fun `parseCalendars handles writable calendars`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/123/calendars/home/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:displayname>Home</D:displayname>
                            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>
                            <D:current-user-privilege-set>
                                <D:privilege><D:read/></D:privilege>
                                <D:privilege><D:write/></D:privilege>
                            </D:current-user-privilege-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")

        assertEquals(1, calendars.size)
        assertFalse(calendars[0].isReadOnly)
    }

    @Test
    fun `parseCalendars returns empty list for empty response`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
            </D:multistatus>
        """.trimIndent()

        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")
        assertTrue(calendars.isEmpty())
    }

    @Test
    fun `parseCalendars returns empty list for invalid XML`() {
        val xml = "not xml at all"
        val calendars = parser.parseCalendars(xml, "https://caldav.icloud.com")
        assertTrue(calendars.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // CALENDAR PRINCIPAL DISCOVERY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseCurrentUserPrincipal extracts principal href`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:current-user-principal>
                                <D:href>/1234567/principal/</D:href>
                            </D:current-user-principal>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val principal = parser.parseCurrentUserPrincipal(xml)
        assertEquals("/1234567/principal/", principal)
    }

    @Test
    fun `parseCurrentUserPrincipal returns null for missing principal`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/</D:href>
                    <D:propstat>
                        <D:prop></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val principal = parser.parseCurrentUserPrincipal(xml)
        assertNull(principal)
    }

    @Test
    fun `parseCalendarHomeSet extracts home set href`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/1234567/principal/</D:href>
                    <D:propstat>
                        <D:prop>
                            <C:calendar-home-set>
                                <D:href>/1234567/calendars/</D:href>
                            </C:calendar-home-set>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val homeSet = parser.parseCalendarHomeSet(xml)
        assertEquals("/1234567/calendars/", homeSet)
    }

    // ═══════════════════════════════════════════════════════════════════
    // EVENT DATA PARSING (REPORT)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseEvents extracts events from calendar-multiget response`() {
        val icsData = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:event-123@icloud.com\nDTSTART:20250115T090000Z\nDTEND:20250115T100000Z\nSUMMARY:Meeting\nEND:VEVENT\nEND:VCALENDAR"

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/calendars/home/event-123.ics</D:href>
<D:propstat>
<D:prop>
<D:getetag>"etag-abc"</D:getetag>
<C:calendar-data>$icsData</C:calendar-data>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""

        val events = parser.parseEvents(xml, "https://caldav.icloud.com")

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("event-123@icloud.com", event.uid)
        assertEquals("/123/calendars/home/event-123.ics", event.href)
        assertEquals("https://caldav.icloud.com/123/calendars/home/event-123.ics", event.url)
        assertEquals("\"etag-abc\"", event.etag)
        assertTrue(event.icalData.contains("SUMMARY:Meeting"))
    }

    @Test
    fun `parseEvents handles CDATA wrapped calendar-data`() {
        val icsData = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:cdata-test@icloud.com\nSUMMARY:CDATA Test\nEND:VEVENT\nEND:VCALENDAR"

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/123/calendars/home/cdata-test.ics</D:href>
<D:propstat>
<D:prop>
<D:getetag>"etag-xyz"</D:getetag>
<C:calendar-data><![CDATA[$icsData]]></C:calendar-data>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""

        val events = parser.parseEvents(xml, "https://caldav.icloud.com")

        assertEquals(1, events.size)
        assertEquals("cdata-test@icloud.com", events[0].uid)
        assertTrue(events[0].icalData.contains("SUMMARY:CDATA Test"))
    }

    @Test
    fun `parseEvents handles multiple events`() {
        val ics1 = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:uid1@test\nEND:VEVENT\nEND:VCALENDAR"
        val ics2 = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:uid2@test\nEND:VEVENT\nEND:VCALENDAR"

        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
<D:response>
<D:href>/cal/event1.ics</D:href>
<D:propstat>
<D:prop>
<D:getetag>"e1"</D:getetag>
<C:calendar-data>$ics1</C:calendar-data>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
<D:response>
<D:href>/cal/event2.ics</D:href>
<D:propstat>
<D:prop>
<D:getetag>"e2"</D:getetag>
<C:calendar-data>$ics2</C:calendar-data>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""

        val events = parser.parseEvents(xml, "https://caldav.icloud.com")

        assertEquals(2, events.size)
        assertEquals("uid1@test", events[0].uid)
        assertEquals("uid2@test", events[1].uid)
    }

    @Test
    fun `parseEvents skips responses without calendar-data`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/cal/event1.ics</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:getetag>"e1"</D:getetag>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val events = parser.parseEvents(xml, "https://caldav.icloud.com")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `parseEvents skips 404 responses`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                <D:response>
                    <D:href>/cal/deleted.ics</D:href>
                    <D:propstat>
                        <D:prop/>
                        <D:status>HTTP/1.1 404 Not Found</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val events = parser.parseEvents(xml, "https://caldav.icloud.com")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `parseEvents returns empty list for invalid XML`() {
        val events = parser.parseEvents("not xml", "https://caldav.icloud.com")
        assertTrue(events.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // UID EXTRACTION FROM ICS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `extractUid from standard ICS`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:unique-id-123@example.com
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertEquals("unique-id-123@example.com", parser.extractUid(ics))
    }

    @Test
    fun `extractUid handles folded UID lines`() {
        // RFC 5545 allows line folding with CRLF + space
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:very-long-unique-identifier-that-spans-multiple-lines-
             and-continues-here@example.com
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val uid = parser.extractUid(ics)
        assertNotNull(uid)
        assertTrue(uid.startsWith("very-long"))
    }

    @Test
    fun `extractUid returns null for missing UID`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:No UID Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        assertNull(parser.extractUid(ics))
    }

    @Test
    fun `extractUid returns null for empty ICS`() {
        assertNull(parser.extractUid(""))
        assertNull(parser.extractUid("   "))
    }

    // ═══════════════════════════════════════════════════════════════════
    // SYNC TOKEN PARSING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseSyncToken extracts sync token from sync-collection response`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:sync-token>https://caldav.icloud.com/sync/token-abc123</D:sync-token>
                <D:response>
                    <D:href>/cal/event.ics</D:href>
                    <D:propstat>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals("https://caldav.icloud.com/sync/token-abc123", parser.parseSyncToken(xml))
    }

    @Test
    fun `parseSyncToken returns null when not present`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/cal/</D:href>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        assertNull(parser.parseSyncToken(xml))
    }

    // ═══════════════════════════════════════════════════════════════════
    // DELETED EVENTS (SYNC-COLLECTION)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseDeletedEventHrefs extracts hrefs with 404 status`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/cal/event1.ics</D:href>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:response>
                <D:response>
                    <D:href>/cal/deleted-event.ics</D:href>
                    <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        val deleted = parser.parseDeletedEventHrefs(xml)

        assertEquals(1, deleted.size)
        assertEquals("/cal/deleted-event.ics", deleted[0])
    }

    @Test
    fun `parseDeletedEventHrefs returns empty for no deletions`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/cal/event1.ics</D:href>
                    <D:status>HTTP/1.1 200 OK</D:status>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        assertTrue(parser.parseDeletedEventHrefs(xml).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // ETAG PARSING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseEtag extracts etag from PUT response`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/cal/new-event.ics</D:href>
                    <D:propstat>
                        <D:prop>
                            <D:getetag>"new-etag-123"</D:getetag>
                        </D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status>
                    </D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals("\"new-etag-123\"", parser.parseEtag(xml))
    }

    @Test
    fun `parseEtag returns null when not present`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/cal/event.ics</D:href>
                </D:response>
            </D:multistatus>
        """.trimIndent()

        assertNull(parser.parseEtag(xml))
    }
}

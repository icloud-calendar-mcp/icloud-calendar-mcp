package org.onekash.mcp.calendar.service

import io.mockk.every
import io.mockk.mockk
import org.onekash.mcp.calendar.caldav.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Tests for EventCache TTL and size limits.
 */
class EventCacheTest {

    private lateinit var mockClient: CalDavClient
    private lateinit var service: CalendarService

    @BeforeEach
    fun setup() {
        mockClient = mockk()
    }

    @Test
    fun `cache expires entries after TTL`() {
        // Create service with very short TTL (10ms)
        service = CalendarService(mockClient, cacheTtlMs = 10, maxCacheSize = 100)

        val calendarId = "test-calendar"
        val event = CalDavEvent(
            uid = "test-uid-123",
            href = "/calendar/test.ics",
            url = "https://caldav.icloud.com/calendar/test.ics",
            etag = "etag-1",
            icalData = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:test-uid-123
                SUMMARY:Test Event
                DTSTART:20250115T090000Z
                DTEND:20250115T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(listOf(event))

        // Fetch events to populate cache
        service.getEvents(calendarId, "2025-01-01", "2025-01-31")

        // Event should be in cache
        val result1 = service.getEventById("test-uid-123")
        assertIs<ServiceResult.Success<EventInfo>>(result1)

        // Wait for TTL to expire
        Thread.sleep(50)

        // Event should be expired and not found
        val result2 = service.getEventById("test-uid-123")
        assertIs<ServiceResult.Error>(result2)
        assertEquals(404, result2.code)
    }

    @Test
    fun `cache clears expired entries when at capacity`() {
        // Create service with short TTL and max 3 entries
        service = CalendarService(mockClient, cacheTtlMs = 10, maxCacheSize = 3)

        val events = (1..3).map { i ->
            CalDavEvent(
                uid = "uid-$i",
                href = "/calendar/event-$i.ics",
                url = "https://caldav.icloud.com/calendar/event-$i.ics",
                etag = "etag-$i",
                icalData = """
                    BEGIN:VCALENDAR
                    BEGIN:VEVENT
                    UID:uid-$i
                    SUMMARY:Event $i
                    DTSTART:20250115T090000Z
                    DTEND:20250115T100000Z
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            )
        }

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(events)

        // Fetch events to populate cache
        service.getEvents("calendar", "2025-01-01", "2025-01-31")
        assertEquals(3, service.cacheSize(), "Cache should have 3 entries")

        // Wait for TTL to expire
        Thread.sleep(50)

        // Now add more events - should clear expired entries
        val moreEvents = (4..5).map { i ->
            CalDavEvent(
                uid = "uid-$i",
                href = "/calendar/event-$i.ics",
                url = "https://caldav.icloud.com/calendar/event-$i.ics",
                etag = "etag-$i",
                icalData = """
                    BEGIN:VCALENDAR
                    BEGIN:VEVENT
                    UID:uid-$i
                    SUMMARY:Event $i
                    DTSTART:20250115T090000Z
                    DTEND:20250115T100000Z
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            )
        }

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(moreEvents)
        service.getEvents("calendar", "2025-01-01", "2025-01-31")

        // Cache should have new entries (expired ones were cleared)
        assertTrue(service.cacheSize() <= 3, "Cache should not exceed max after clearing expired: ${service.cacheSize()}")
    }

    @Test
    fun `clearCache removes all entries`() {
        service = CalendarService(mockClient, cacheTtlMs = 60_000, maxCacheSize = 100)

        val event = CalDavEvent(
            uid = "clear-test-uid",
            href = "/calendar/test.ics",
            url = "https://caldav.icloud.com/calendar/test.ics",
            etag = "etag-1",
            icalData = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:clear-test-uid
                SUMMARY:Test Event
                DTSTART:20250115T090000Z
                DTEND:20250115T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(listOf(event))

        service.getEvents("calendar", "2025-01-01", "2025-01-31")
        assertTrue(service.cacheSize() > 0, "Cache should have entries")

        service.clearCache()
        assertEquals(0, service.cacheSize(), "Cache should be empty after clear")
    }

    @Test
    fun `cache is thread-safe for concurrent access`() {
        service = CalendarService(mockClient, cacheTtlMs = 60_000, maxCacheSize = 1000)

        val events = (1..100).map { i ->
            CalDavEvent(
                uid = "concurrent-uid-$i",
                href = "/calendar/event-$i.ics",
                url = "https://caldav.icloud.com/calendar/event-$i.ics",
                etag = "etag-$i",
                icalData = """
                    BEGIN:VCALENDAR
                    BEGIN:VEVENT
                    UID:concurrent-uid-$i
                    SUMMARY:Event $i
                    DTSTART:20250115T090000Z
                    DTEND:20250115T100000Z
                    END:VEVENT
                    END:VCALENDAR
                """.trimIndent()
            )
        }

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(events)

        // Concurrent access from multiple threads
        val threads = (1..10).map {
            Thread {
                service.getEvents("calendar", "2025-01-01", "2025-01-31")
                repeat(10) { i ->
                    service.getEventById("concurrent-uid-${i + 1}")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should complete without ConcurrentModificationException
        assertTrue(service.cacheSize() > 0, "Cache should have entries after concurrent access")
    }

    @Test
    fun `cached events are retrievable within TTL`() {
        service = CalendarService(mockClient, cacheTtlMs = 60_000, maxCacheSize = 100)

        val event = CalDavEvent(
            uid = "retrieve-test-uid",
            href = "/calendar/test.ics",
            url = "https://caldav.icloud.com/calendar/test.ics",
            etag = "etag-1",
            icalData = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:retrieve-test-uid
                SUMMARY:Retrievable Event
                DTSTART:20250115T090000Z
                DTEND:20250115T100000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent()
        )

        every { mockClient.getEvents(any(), any(), any()) } returns CalDavResult.Success(listOf(event))

        // Fetch to populate cache
        service.getEvents("calendar", "2025-01-01", "2025-01-31")

        // Should be retrievable by ID
        val result = service.getEventById("retrieve-test-uid")
        assertIs<ServiceResult.Success<EventInfo>>(result)
        assertEquals("Retrievable Event", result.data.summary)
    }
}

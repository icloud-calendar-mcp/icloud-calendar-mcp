package org.onekash.mcp.calendar.live

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.onekash.mcp.calendar.caldav.CalDavResult
import org.onekash.mcp.calendar.caldav.OkHttpCalDavClient
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Base class for the live iCloud integration suite.
 *
 * Contract for every subclass:
 *  - Tagged `@Tag("integration")` — excluded from the default `./gradlew test`
 *    run; opt in with `-Pintegration` (see build.gradle.kts).
 *  - Self-skips the whole class when no iCloud credentials resolve
 *    ([LiveTestSupport.available]).
 *  - Every event a test creates MUST be registered with [track] (or created via
 *    [createTracked]) so it is deleted in [cleanupAll] even if the test throws
 *    mid-way. Belt-and-suspenders over per-test `finally` blocks: a JVM-level
 *    failure still can't orphan events on the shared real account.
 *
 * @TestInstance(PER_CLASS) lets @BeforeAll/@AfterAll be instance methods so the
 * cleanup tracker is a normal field.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LiveCalendarTestBase {

    protected lateinit var service: CalendarService
        private set

    protected lateinit var calendarId: String
        private set

    /** UIDs created during this class's run, cleaned up newest-first in @AfterAll. */
    private val createdUids = ConcurrentLinkedDeque<String>()

    @BeforeAll
    fun setUpClass() {
        assumeTrue(LiveTestSupport.available, "iCloud credentials not set — skipping live suite")
        println("\n=== Live suite: ${LiveTestSupport.describe} ===")
        service = CalendarService(LiveTestSupport.newClient())
        calendarId = firstWritableCalendarId()
    }

    @BeforeEach
    fun guardCredentials() {
        // Redundant with @BeforeAll's assume, but makes an individually-run test
        // (--tests "…#method") skip cleanly rather than NPE on `service`.
        assumeTrue(LiveTestSupport.available, "iCloud credentials not set")
    }

    /** Register a UID for automatic post-suite cleanup. Returns the UID for chaining. */
    protected fun track(uid: String): String {
        createdUids.add(uid)
        return uid
    }

    /**
     * Create an event through the service and auto-track its UID. Fails the test
     * (not just returns Error) if creation didn't succeed, since a broken create
     * makes the rest of a scenario meaningless.
     */
    protected fun createTracked(
        summary: String,
        startTime: String? = null,
        endTime: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        isAllDay: Boolean = false,
        description: String? = null,
        location: String? = null,
        timezone: String? = null,
        rrule: String? = null,
        endTimezone: String? = null,
        rdates: List<String>? = null,
        exdates: List<String>? = null,
        alarms: List<org.onekash.mcp.calendar.ics.AlarmSpec>? = null
    ): org.onekash.mcp.calendar.service.EventInfo {
        val result = service.createEvent(
            calendarId = calendarId,
            summary = prefixed(summary),
            startTime = startTime,
            endTime = endTime,
            startDate = startDate,
            endDate = endDate,
            isAllDay = isAllDay,
            description = description,
            location = location,
            timezone = timezone,
            rrule = rrule,
            endTimezone = endTimezone,
            rdates = rdates,
            exdates = exdates,
            alarms = alarms
        )
        check(result is ServiceResult.Success) { "createEvent failed: $result" }
        val info = (result as ServiceResult.Success).data
        track(info.uid)
        return info
    }

    /** Stamp the standard test prefix so orphans stay findable by the janitor. */
    protected fun prefixed(summary: String): String =
        if (summary.startsWith(LiveTestSupport.TEST_PREFIX)) summary
        else "${LiveTestSupport.TEST_PREFIX} $summary"

    /**
     * Fetch the raw ICS for a created event straight from iCloud (bypasses the
     * service cache) so a test can assert wire-level properties. Uses a fresh
     * client + a tight ±1 day window around [anchorDate] (YYYY-MM-DD).
     */
    protected fun fetchRawIcs(uid: String, anchorDate: String): String? {
        val anchor = java.time.LocalDate.parse(anchorDate)
        val client: OkHttpCalDavClient = LiveTestSupport.newClient()
        val res = client.getEvents(
            calendarId,
            anchor.minusDays(1).toString(),
            anchor.plusDays(1).toString()
        )
        if (res !is CalDavResult.Success) return null
        return res.data.firstOrNull { it.uid == uid }?.icalData
    }

    @AfterAll
    fun cleanupAll() {
        if (!LiveTestSupport.available) return
        var deleted = 0
        var failed = 0
        // Newest-first: overrides/exceptions before masters where it matters.
        for (uid in createdUids) {
            when (val del = service.deleteEvent(uid)) {
                is ServiceResult.Success -> deleted++
                is ServiceResult.Error -> {
                    // 404 = already gone (a test deleted it itself); anything else is noise.
                    if (del.code != 404) {
                        failed++
                        println("  ! cleanup delete failed for $uid: ${del.code} ${del.message}")
                    }
                }
            }
        }
        println("=== Cleanup: ${createdUids.size} tracked, $deleted deleted, $failed failed ===")
    }

    private fun firstWritableCalendarId(): String {
        val result = service.listCalendars()
        check(result is ServiceResult.Success) { "listCalendars failed: $result" }
        val writable = (result as ServiceResult.Success).data.firstOrNull {
            !it.readOnly && it.id.isNotBlank() && !it.name.equals("Reminders", ignoreCase = true)
        }
        checkNotNull(writable) { "no writable VEVENT calendar found on this account" }
        return writable.id
    }
}

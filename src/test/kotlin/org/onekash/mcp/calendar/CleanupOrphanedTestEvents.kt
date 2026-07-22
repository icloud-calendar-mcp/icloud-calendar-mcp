package org.onekash.mcp.calendar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.onekash.mcp.calendar.live.LiveTestSupport
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import java.time.LocalDate

/**
 * One-shot janitor that deletes any MCP-LIVE- / MCP TEST-prefixed events left
 * behind by a failed live run. Tagged @Tag("integration") so it only runs under
 * `-Pintegration` — run manually with:
 *
 *   ./gradlew :test -Pintegration --tests "*CleanupOrphanedTestEvents*"
 *
 * Credentials resolve through [LiveTestSupport] (env or local.properties, same
 * as the rest of the live suite); skipped silently when none are available.
 */
@Tag("integration")
class CleanupOrphanedTestEvents {

    @Test
    fun `delete orphaned MCP-LIVE events from all writable calendars`() {
        assumeTrue(LiveTestSupport.available, "Credentials not set")
        println("=== Janitor: ${LiveTestSupport.describe} ===")

        val service = CalendarService(LiveTestSupport.newClient())

        val calendarsResult = service.listCalendars()
        if (calendarsResult !is ServiceResult.Success) {
            println("listCalendars failed: $calendarsResult")
            return
        }

        // Window: 90 days back through 90 days forward — covers every test.
        val today = LocalDate.now()
        val from = today.minusDays(90).toString()
        val to = today.plusDays(90).toString()

        var found = 0
        var deleted = 0
        for (cal in calendarsResult.data) {
            if (cal.readOnly || cal.id.isBlank() || cal.name.equals("Reminders", ignoreCase = true)) continue

            val events = service.getEvents(cal.id, from, to)
            if (events !is ServiceResult.Success) continue

            for (e in events.data) {
                if (e.summary.startsWith("MCP-LIVE:") || e.summary.startsWith("MCP TEST")) {
                    found++
                    println("Deleting orphan: [${cal.name}] ${e.summary} (uid=${e.uid})")
                    val del = service.deleteEvent(e.uid)
                    if (del is ServiceResult.Success) deleted++
                    else println("  ! delete failed: $del")
                }
            }
        }

        println("\n=== Orphan cleanup ===")
        println("Found:   $found")
        println("Deleted: $deleted")
    }
}

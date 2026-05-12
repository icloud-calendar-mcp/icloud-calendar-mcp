package org.onekash.mcp.calendar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.onekash.mcp.calendar.caldav.CalDavCredentials
import org.onekash.mcp.calendar.caldav.OkHttpCalDavClient
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult
import java.time.LocalDate

/**
 * One-shot janitor that deletes any MCP-LIVE-prefixed test events left behind
 * by failed live runs. Not part of the normal suite — run manually with:
 *
 *   ICLOUD_USERNAME=… ICLOUD_PASSWORD=… ./gradlew test --tests "*CleanupOrphanedTestEvents*"
 *
 * Skipped silently when credentials aren't set, just like LiveCalDavTest.
 */
class CleanupOrphanedTestEvents {

    private val username = System.getenv("ICLOUD_USERNAME")
    private val password = System.getenv("ICLOUD_PASSWORD")

    @Test
    fun `delete orphaned MCP-LIVE events from all writable calendars`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val client = OkHttpCalDavClient(
            baseUrl = "https://caldav.icloud.com",
            credentials = CalDavCredentials(username!!, password!!)
        )
        val service = CalendarService(client)

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

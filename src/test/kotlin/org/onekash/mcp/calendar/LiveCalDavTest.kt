package org.onekash.mcp.calendar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.onekash.mcp.calendar.caldav.CalDavCredentials
import org.onekash.mcp.calendar.caldav.OkHttpCalDavClient
import org.onekash.mcp.calendar.service.CalendarService
import org.onekash.mcp.calendar.service.ServiceResult

/**
 * Live integration test with real iCloud CalDAV.
 *
 * Run with:
 * ICLOUD_USERNAME=xxx ICLOUD_PASSWORD=xxx ./gradlew test --tests "LiveCalDavTest"
 *
 * Skipped if credentials not set.
 */
class LiveCalDavTest {

    private val username = System.getenv("ICLOUD_USERNAME")
    private val password = System.getenv("ICLOUD_PASSWORD")

    @Test
    fun `list calendars from iCloud`() {
        assumeTrue(username != null && password != null, "Credentials not set")

        val client = OkHttpCalDavClient(
            baseUrl = "https://caldav.icloud.com",
            credentials = CalDavCredentials(username!!, password!!)
        )
        val service = CalendarService(client)

        val result = service.listCalendars()

        when (result) {
            is ServiceResult.Success -> {
                println("\n=== Calendars Found ===")
                result.data.forEach { cal ->
                    println("- ${cal.name} (${cal.id})")
                    println("  Color: ${cal.color ?: "none"}")
                    println("  ReadOnly: ${cal.readOnly}")
                }
                println("Total: ${result.data.size} calendars")
            }
            is ServiceResult.Error -> {
                println("\n=== Error ===")
                println("Code: ${result.code}")
                println("Message: ${result.message}")
            }
        }
    }
}

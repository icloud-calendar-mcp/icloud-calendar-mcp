package org.onekash.icaldav.testutil

import java.util.TimeZone

/** Builds and reads iCalendar text in tests, for icaldav-core and the root module alike. */
object IcsText {

    /** A VCALENDAR around [components], CRLF line endings, with a test PRODID. */
    fun calendar(vararg components: String): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN") +
            components.toList() + listOf("END:VCALENDAR", "")).joinToString("\r\n")

    /** [ics] with folded lines joined (RFC 5545 3.1), CRLF or LF. */
    fun unfold(ics: String): String = ics.replace(Regex("\r?\n[ \t]"), "")

    /** The unfolded content lines of [ics]. */
    fun unfoldedLines(ics: String): List<String> = unfold(ics).split("\r\n", "\n")

    /**
     * The top-level lines of each component named [type] (VEVENT by default), in document order.
     * Lines of nested components, such as a VALARM inside a VEVENT, are skipped.
     */
    fun componentBlocks(ics: String, type: String = "VEVENT"): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (line in unfoldedLines(ics)) {
            when {
                current == null && line == "BEGIN:$type" -> { current = mutableListOf(); depth = 0 }
                current == null -> Unit
                line == "END:$type" && depth == 0 -> { out += current; current = null }
                line.startsWith("BEGIN:") -> depth++
                line.startsWith("END:") -> depth--
                depth == 0 && line.isNotEmpty() -> current.add(line)
            }
        }
        return out
    }
}

/** Runs [block] with the JVM default time zone set to [zoneId], then restores the previous one. */
fun <T> withDefaultZone(zoneId: String, block: () -> T): T {
    val saved = TimeZone.getDefault()
    return try {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        block()
    } finally {
        TimeZone.setDefault(saved)
    }
}

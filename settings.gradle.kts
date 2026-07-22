rootProject.name = "icloud-calendar-mcp"

// icaldav-core: the bundled iCalendar library (ical4j 4.2.2). Kept as a
// subproject so ical4j stays confined here and the MCP's own source never
// depends on ical4j directly.
include(":icaldav-core")
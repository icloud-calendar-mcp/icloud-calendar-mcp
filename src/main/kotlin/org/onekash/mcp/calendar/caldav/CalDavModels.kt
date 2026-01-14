package org.onekash.mcp.calendar.caldav

/**
 * Result type for CalDAV operations.
 *
 * Provides type-safe error handling without exceptions for expected failures
 * (auth errors, not found, network issues).
 */
sealed class CalDavResult<out T> {
    data class Success<T>(val data: T) : CalDavResult<T>()

    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean = false
    ) : CalDavResult<Nothing>() {

        /** True if this is an authentication or authorization error (401, 403) */
        val isAuthError: Boolean get() = code == 401 || code == 403

        /** True if this is a not found error (404) */
        val isNotFound: Boolean get() = code == 404

        /** True if this is a server error (5xx) */
        val isServerError: Boolean get() = code in 500..599
    }
}

/** True if this result is a Success */
val <T> CalDavResult<T>.isSuccess: Boolean
    get() = this is CalDavResult.Success

/** True if this result is an Error */
val <T> CalDavResult<T>.isError: Boolean
    get() = this is CalDavResult.Error

/** Returns the data if Success, null otherwise */
fun <T> CalDavResult<T>.getOrNull(): T? = when (this) {
    is CalDavResult.Success -> data
    is CalDavResult.Error -> null
}

/** Returns the data if Success, default value otherwise */
fun <T> CalDavResult<T>.getOrDefault(default: T): T = when (this) {
    is CalDavResult.Success -> data
    is CalDavResult.Error -> default
}

/** Transform the data if Success, preserve Error otherwise */
inline fun <T, R> CalDavResult<T>.map(transform: (T) -> R): CalDavResult<R> = when (this) {
    is CalDavResult.Success -> CalDavResult.Success(transform(data))
    is CalDavResult.Error -> this
}

/** Handle both Success and Error cases */
inline fun <T, R> CalDavResult<T>.fold(
    onSuccess: (T) -> R,
    onError: (code: Int, message: String) -> R
): R = when (this) {
    is CalDavResult.Success -> onSuccess(data)
    is CalDavResult.Error -> onError(code, message)
}

/**
 * Represents a CalDAV calendar collection.
 *
 * @property id Calendar identifier (extracted from href, last path segment)
 * @property href Full path to calendar (e.g., /1234567/calendars/home/)
 * @property url Full URL to calendar
 * @property displayName Human-readable calendar name
 * @property color Hex color code (normalized to #RRGGBB) or null
 * @property ctag Change tag for detecting modifications
 * @property isReadOnly True if calendar is read-only (shared calendars)
 */
data class CalDavCalendar(
    val id: String,
    val href: String,
    val url: String,
    val displayName: String,
    val color: String?,
    val ctag: String?,
    val isReadOnly: Boolean = false
)

/**
 * Represents a CalDAV event resource.
 *
 * @property uid Event UID from ICS VEVENT
 * @property href Full path to .ics resource
 * @property url Full URL to .ics resource
 * @property etag ETag for conditional updates (If-Match header)
 * @property icalData Raw ICS content
 */
data class CalDavEvent(
    val uid: String,
    val href: String,
    val url: String,
    val etag: String?,
    val icalData: String
) {
    /** True if this event has a valid etag for conditional updates */
    val hasEtag: Boolean get() = !etag.isNullOrBlank()
}

/**
 * Extract calendar ID from href path.
 *
 * Takes the last non-empty path segment.
 * Examples:
 * - /1234567/calendars/home/ -> home
 * - /calendars/work -> work
 */
fun extractCalendarIdFromHref(href: String): String {
    return href.trim('/')
        .split('/')
        .lastOrNull { it.isNotEmpty() }
        ?: href
}

/**
 * Normalize color value to #RRGGBB format.
 *
 * Handles:
 * - Already correct format (#RRGGBB)
 * - Missing hash (RRGGBB -> #RRGGBB)
 * - iCloud's 8-char format (#RRGGBBAA -> #RRGGBB)
 * - Invalid formats (returns null)
 */
fun normalizeColor(color: String?): String? {
    if (color.isNullOrBlank()) return null

    val trimmed = color.trim()

    // Check if it's a valid hex color
    val hexPattern = Regex("^#?([0-9A-Fa-f]{6,8})$")
    val match = hexPattern.matchEntire(trimmed) ?: return null

    val hex = match.groupValues[1]

    // Take first 6 chars (ignore alpha if present)
    val rgb = hex.take(6)

    return "#$rgb"
}

package org.onekash.mcp.calendar.caldav

/**
 * Normalizes iCloud CalDAV URLs/hrefs to a canonical, partition-independent form.
 *
 * iCloud serves the same resource from regional partitions such as
 * `p180-caldav.icloud.com:443`. A reference (cache key, or an opaque event handle)
 * minted while talking to one partition must still match the same event resolved
 * through another partition, so we strip the `p{N}-` regional prefix and any
 * explicit port down to the canonical host `caldav.icloud.com`.
 *
 * Contract:
 *  - strips `p{N}-` regional prefix AND an explicit port (`:443`, `:8443`, …)
 *  - case-insensitive
 *  - idempotent (normalizing an already-canonical URL is a no-op)
 *  - preserves path / query / fragment
 *  - leaves non-iCloud URLs (and plain paths without a host) untouched
 *  - passes null/empty through unchanged
 */
object ICloudUrlNormalizer {

    private const val CANONICAL_HOST = "caldav.icloud.com"

    // Matches p180-caldav.icloud.com, p1-caldav.icloud.com, with optional :port,
    // case-insensitive. Does NOT match the already-canonical caldav.icloud.com, nor
    // look-alikes like p180-caldav.notcloud.com.
    private val REGIONAL_PATTERN = Regex(
        """p\d+-caldav\.icloud\.com(:\d+)?""",
        RegexOption.IGNORE_CASE
    )

    /** Return [url] with any regional iCloud host collapsed to the canonical host. */
    fun normalize(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        return url.replace(REGIONAL_PATTERN, CANONICAL_HOST)
    }
}

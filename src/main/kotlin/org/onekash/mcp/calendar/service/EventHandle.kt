package org.onekash.mcp.calendar.service

import org.onekash.mcp.calendar.caldav.ICloudUrlNormalizer
import java.util.Base64

/**
 * A self-contained, opaque reference to a CalDAV event.
 *
 * The MCP `get_events` tool historically returned only a UID, and update/delete
 * recovered the event's href+etag purely from an in-memory TTL cache. That breaks
 * across process restarts, different workers, and after the 5-minute TTL expires:
 * a fresh [CalendarService] has never cached the event, so it 404s even though the
 * event plainly exists on iCloud.
 *
 * A handle fixes that by carrying the durable coordinates the server needs —
 * the (regional-normalized) href and the last-known etag — encoded into a single
 * opaque, URL-safe token. Any process can decode it and act on the event with NO
 * cache dependency. The cache remains a pure optimization.
 *
 * Encoding (exact, so external clients / tests can rely on it):
 *  - Take the href, run it through [ICloudUrlNormalizer.normalize] so a handle
 *    minted while talking to partition `p180-caldav.icloud.com` matches the same
 *    event resolved via any other partition.
 *  - Join `normalizedHref + '\n' + (etag ?: "")` (newline 0x0A separator; neither
 *    an href nor an etag contains a raw newline).
 *  - UTF-8 encode, then Base64 **url-safe, no padding** (RFC 4648 §5). The
 *    resulting alphabet is `[A-Za-z0-9_-]`, which contains no characters the
 *    input validator rejects.
 *  - Prefix the literal marker [PREFIX] (`evt1_`) so a handle is distinguishable
 *    from a legacy bare UID and is version-tagged for future evolution.
 */
data class EventHandle(
    /** Regional-normalized href of the event resource. */
    val href: String,
    /** Last-known etag, or null when the minting fetch had none. */
    val etag: String?
) {
    /** Encode this handle to its opaque `evt1_…` token form. */
    fun encode(): String {
        val payload = "${ICloudUrlNormalizer.normalize(href)}\n${etag ?: ""}"
        val b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "$PREFIX$b64"
    }

    companion object {
        /** Marker + version tag prefixed to every encoded handle. */
        const val PREFIX = "evt1_"

        /** Encode an (href, etag) pair to an opaque handle token. */
        fun encode(href: String, etag: String?): String = EventHandle(href, etag).encode()

        /** True if [token] looks like an encoded handle (vs. a legacy bare UID). */
        fun looksLikeHandle(token: String?): Boolean =
            token != null && token.startsWith(PREFIX)

        /**
         * Decode an `evt1_…` token back to an [EventHandle], or null if [token] is
         * not a well-formed handle (missing prefix, undecodable base64, or missing
         * the href/etag separator). Callers treat a null as "not a handle" and fall
         * back to legacy UID-based cache resolution.
         */
        fun decode(token: String?): EventHandle? {
            if (!looksLikeHandle(token)) return null
            val b64 = token!!.substring(PREFIX.length)
            val decoded = try {
                String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val sep = decoded.indexOf('\n')
            if (sep < 0) return null
            val href = decoded.substring(0, sep)
            if (href.isBlank()) return null
            // SSRF guard: the href is fed to an authenticated GET/PUT/DELETE, so a
            // handle must never steer credentials at an arbitrary host. A relative
            // path is safe (it gets prefixed with the trusted baseUrl); an absolute
            // URL is only accepted when its HOST is iCloud. Anything else decodes to
            // null and falls back to the (validated) UID path.
            //
            // "Absolute" is detected by the presence of a URI SCHEME, not a literal
            // "://": OkHttpCalDavClient.getEvent treats any href that startsWith
            // "http" as absolute and hands it straight to OkHttp, so a single-slash
            // authority like `https:/evil.com/x` (no "://") would slip past a
            // contains("://") check here yet still resolve to host `evil.com` at the
            // client. Gating on the scheme makes this guard strictly stricter than
            // the client's own absolute-vs-relative split, closing that mismatch.
            if (hasScheme(href) && !hasICloudHost(href)) return null
            val etag = decoded.substring(sep + 1).ifEmpty { null }
            return EventHandle(href = href, etag = etag)
        }

        // A URI scheme is `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":"` at the
        // very start (RFC 3986 §3.1). Any href carrying one is treated as absolute
        // and must clear [hasICloudHost]; anything else is a server-relative path.
        private val SCHEME_PREFIX = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

        /** True if [href] begins with a URI scheme (vs. a server-relative path). */
        private fun hasScheme(href: String): Boolean = SCHEME_PREFIX.containsMatchIn(href)

        /**
         * True only when [href] is an http(s) URL whose HOST is iCloud — `icloud.com`
         * or a subdomain of it. Deliberately host-based, NOT a substring match: naive
         * `contains("icloud.com")` accepts `icloud.com.attacker.com`,
         * `evil.com/?icloud.com`, and userinfo tricks like
         * `https://caldav.icloud.com@attacker.com`, all of which would leak
         * credentials. The scheme is also pinned to http/https so a dangerous scheme
         * that happens to carry an iCloud host (`gopher://icloud.com`, `file://…`)
         * never resolves. A URL we cannot parse a host from is rejected.
         */
        private fun hasICloudHost(href: String): Boolean {
            val uri = try {
                java.net.URI(href)
            } catch (_: Exception) {
                return false
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return host == "icloud.com" || host.endsWith(".icloud.com")
        }
    }
}

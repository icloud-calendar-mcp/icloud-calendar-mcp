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
 *
 * Occurrence references (`evt2_`): a handle may additionally carry a
 * [recurrenceId] — the RFC 5545 RECURRENCE-ID (§3.8.4.4) of ONE instance of a
 * recurring series, in its iCalendar wire form (`20260818T140000Z` for a timed
 * occurrence, `20260818` for an all-day one). When present, the token uses the
 * [PREFIX_V2] (`evt2_`) marker and a 3-field payload `href\netag\nrecurrenceId`;
 * such a handle is an *occurrence* reference (see [isOccurrenceRef]). When absent
 * the token is byte-identical to the original `evt1_` *master* reference, so
 * existing callers and persisted handles are unaffected. The RECURRENCE-ID is in
 * the master's stored value form, so the reference resolves to the same instant
 * regardless of the host time zone that decodes it.
 */
data class EventHandle(
    /** Regional-normalized href of the event resource. */
    val href: String,
    /** Last-known etag, or null when the minting fetch had none. */
    val etag: String?,
    /**
     * RECURRENCE-ID of a single occurrence (iCal wire form), or null for a
     * master/whole-event reference. Non-null marks this an occurrence reference.
     */
    val recurrenceId: String? = null
) {
    /** True when this handle targets one occurrence of a series (carries a RECURRENCE-ID). */
    fun isOccurrenceRef(): Boolean = recurrenceId != null

    /** Encode this handle to its opaque token form (`evt1_…` master, `evt2_…` occurrence). */
    fun encode(): String {
        val normHref = ICloudUrlNormalizer.normalize(href)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return if (recurrenceId == null) {
            val payload = "$normHref\n${etag ?: ""}"
            "$PREFIX${encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))}"
        } else {
            val payload = "$normHref\n${etag ?: ""}\n$recurrenceId"
            "$PREFIX_V2${encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))}"
        }
    }

    companion object {
        /** Marker + version tag prefixed to a master/whole-event handle. */
        const val PREFIX = "evt1_"

        /** Marker + version tag prefixed to an occurrence handle (carries a RECURRENCE-ID). */
        const val PREFIX_V2 = "evt2_"

        /** Encode an (href, etag) pair to a master handle token. */
        fun encode(href: String, etag: String?): String = EventHandle(href, etag).encode()

        /**
         * Encode an (href, etag, recurrenceId) triple. A null [recurrenceId] yields
         * a master handle byte-identical to [encode] (href, etag); a non-null one
         * yields an `evt2_` occurrence handle.
         */
        fun encode(href: String, etag: String?, recurrenceId: String?): String =
            EventHandle(href, etag, recurrenceId).encode()

        /** True if [token] looks like an encoded handle (vs. a legacy bare UID). */
        fun looksLikeHandle(token: String?): Boolean =
            token != null && (token.startsWith(PREFIX) || token.startsWith(PREFIX_V2))

        /**
         * Decode an `evt1_…`/`evt2_…` token back to an [EventHandle], or null if
         * [token] is not a well-formed handle (missing prefix, undecodable base64,
         * missing the href/etag separator, or — for `evt2_` — a missing/blank
         * RECURRENCE-ID segment). Callers treat a null as "not a handle" and fall
         * back to legacy UID-based cache resolution.
         */
        fun decode(token: String?): EventHandle? {
            if (!looksLikeHandle(token)) return null
            val isV2 = token!!.startsWith(PREFIX_V2)
            val b64 = token.substring((if (isV2) PREFIX_V2 else PREFIX).length)
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
            val rest = decoded.substring(sep + 1)
            if (!isV2) {
                return EventHandle(href = href, etag = rest.ifEmpty { null })
            }
            // evt2_: the remainder is `etag\nrecurrenceId`; neither an etag nor a
            // RECURRENCE-ID contains a raw newline, so a single split is exact.
            val sep2 = rest.indexOf('\n')
            if (sep2 < 0) return null
            val etag = rest.substring(0, sep2).ifEmpty { null }
            val recurrenceId = rest.substring(sep2 + 1)
            if (recurrenceId.isBlank()) return null
            return EventHandle(href = href, etag = etag, recurrenceId = recurrenceId)
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

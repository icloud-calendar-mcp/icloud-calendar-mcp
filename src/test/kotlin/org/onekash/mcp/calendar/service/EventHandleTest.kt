package org.onekash.mcp.calendar.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventHandleTest {

    @Test
    fun `round-trips href and etag`() {
        val handle = EventHandle.encode("/cal/abc.ics", "\"etag-1\"")
        val decoded = EventHandle.decode(handle)
        assertEquals("/cal/abc.ics", decoded?.href)
        assertEquals("\"etag-1\"", decoded?.etag)
    }

    @Test
    fun `round-trips null etag`() {
        val handle = EventHandle.encode("/cal/abc.ics", null)
        val decoded = EventHandle.decode(handle)
        assertEquals("/cal/abc.ics", decoded?.href)
        assertNull(decoded?.etag, "empty etag decodes back to null")
    }

    @Test
    fun `encoded token carries the version prefix`() {
        val handle = EventHandle.encode("/cal/abc.ics", "\"e\"")
        assertTrue(handle.startsWith(EventHandle.PREFIX))
    }

    @Test
    fun `encoded token uses only url-safe base64 alphabet after prefix`() {
        val handle = EventHandle.encode("https://p180-caldav.icloud.com:443/x/y z.ics", "\"e/+=\"")
        val body = handle.removePrefix(EventHandle.PREFIX)
        assertTrue(body.all { it.isLetterOrDigit() || it == '-' || it == '_' },
            "handle body must be url-safe base64 (no +,/,= or spaces): $body")
    }

    @Test
    fun `normalizes regional href on encode`() {
        // A handle minted while talking to a partition host must decode to the
        // canonical host so it resolves regardless of which partition answers later.
        val handle = EventHandle.encode("https://p42-caldav.icloud.com/12345/calendars/home/e.ics", "\"e\"")
        val decoded = EventHandle.decode(handle)
        assertEquals("https://caldav.icloud.com/12345/calendars/home/e.ics", decoded?.href)
    }

    @Test
    fun `looksLikeHandle distinguishes handles from bare uids`() {
        assertTrue(EventHandle.looksLikeHandle(EventHandle.encode("/x.ics", null)))
        assertFalse(EventHandle.looksLikeHandle("plain-uid-1234"))
        assertFalse(EventHandle.looksLikeHandle(null))
    }

    @Test
    fun `decode returns null for a bare uid`() {
        assertNull(EventHandle.decode("plain-uid-1234"))
    }

    @Test
    fun `decode returns null for undecodable base64`() {
        // Valid prefix but the body is not valid base64.
        assertNull(EventHandle.decode(EventHandle.PREFIX + "!!!not base64!!!"))
    }

    @Test
    fun `decode returns null when the href-etag separator is missing`() {
        val noSep = EventHandle.PREFIX +
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("no-newline-here".toByteArray())
        assertNull(EventHandle.decode(noSep))
    }

    @Test
    fun `decode returns null for a blank href`() {
        val blankHref = EventHandle.PREFIX +
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("\n\"etag\"".toByteArray())
        assertNull(EventHandle.decode(blankHref))
    }

    // ── SSRF guard: a decoded href drives an authenticated GET/PUT/DELETE, so a
    //    handle must never steer credentials at a non-iCloud absolute URL. The
    //    guard is HOST-based, so these adversarial look-alikes must all be rejected.

    private fun forgeHandle(href: String, etag: String? = "\"e\""): String =
        EventHandle.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$href\n${etag ?: ""}".toByteArray(Charsets.UTF_8))

    @Test
    fun `decode rejects a plainly off-host absolute href`() {
        assertNull(EventHandle.decode(forgeHandle("https://evil.com/steal.ics")))
        assertNull(EventHandle.decode(forgeHandle("http://169.254.169.254/latest/meta-data")),
            "link-local metadata endpoint must not resolve")
    }

    @Test
    fun `decode rejects a suffix-spoofed host`() {
        // Naive contains("icloud.com") would accept this — the host is attacker.com.
        assertNull(EventHandle.decode(forgeHandle("https://icloud.com.attacker.com/steal.ics")))
    }

    @Test
    fun `decode rejects an embedded-substring host`() {
        assertNull(EventHandle.decode(forgeHandle("https://evil.com/path?x=icloud.com")))
        assertNull(EventHandle.decode(forgeHandle("https://evilicloud.com/x.ics")))
    }

    @Test
    fun `decode rejects a userinfo-spoofed authority`() {
        // The real host is attacker.com; caldav.icloud.com is only userinfo here.
        assertNull(EventHandle.decode(forgeHandle("https://caldav.icloud.com@attacker.com/x.ics")))
    }

    @Test
    fun `decode rejects a non-http scheme`() {
        assertNull(EventHandle.decode(forgeHandle("file:///etc/passwd")))
        assertNull(EventHandle.decode(forgeHandle("gopher://icloud.com/x")),
            "even an iCloud host under a dangerous scheme should not resolve as an absolute URL we honor")
    }

    @Test
    fun `decode rejects a scheme with a single-slash authority`() {
        // `https:/evil.com/x` and `https:evil.com/x` carry no "://", so a naive
        // contains("://") check treats them as relative and skips the host guard —
        // but OkHttpCalDavClient.getEvent sees startsWith("http") and resolves them
        // to host `evil.com`. The scheme-based guard must reject both.
        assertNull(EventHandle.decode(forgeHandle("https:/evil.com/steal.ics")),
            "single-slash scheme authority must not bypass the host guard")
        assertNull(EventHandle.decode(forgeHandle("https:evil.com/steal.ics")),
            "schemeless-slash authority must not bypass the host guard")
    }

    @Test
    fun `decode accepts a protocol-relative href (client keeps it on baseUrl host)`() {
        // `//evil.com/x` has no scheme, so it is treated as a server-relative path
        // and safe: the client prefixes baseUrl, yielding baseUrl + "//evil.com/x"
        // whose host stays caldav.icloud.com (verified against okhttp). Accepting it
        // does not leak credentials; rejecting it would be fine too, but the guard's
        // contract is "no scheme => relative => trusted-baseUrl prefix".
        val h = EventHandle.decode(forgeHandle("//evil.com/x.ics"))
        assertEquals("//evil.com/x.ics", h?.href)
    }

    @Test
    fun `decode accepts a server-relative path`() {
        // Relative paths are safe — they get prefixed with the trusted baseUrl.
        val h = EventHandle.decode(forgeHandle("/123/calendars/home/e.ics"))
        assertEquals("/123/calendars/home/e.ics", h?.href)
    }

    @Test
    fun `decode accepts an absolute canonical iCloud href`() {
        val h = EventHandle.decode(forgeHandle("https://caldav.icloud.com/123/calendars/home/e.ics"))
        assertEquals("https://caldav.icloud.com/123/calendars/home/e.ics", h?.href)
    }

    @Test
    fun `decode accepts a regional iCloud subdomain host`() {
        // A handle minted while talking to a partition is still iCloud — must resolve.
        val h = EventHandle.decode(forgeHandle("https://p180-caldav.icloud.com/123/e.ics"))
        assertEquals("https://p180-caldav.icloud.com/123/e.ics", h?.href)
    }

    // ── Encode/decode round-trip robustness ──────────────────────────────────

    @Test
    fun `round-trips an href containing url-unsafe and unicode characters`() {
        // base64url must survive spaces, +, /, =, and multibyte UTF-8 in the href.
        val weird = "/cal/a b+c/=d/é-events.ics"
        val decoded = EventHandle.decode(EventHandle.encode(weird, "\"e/+=\""))
        assertEquals(weird, decoded?.href)
        assertEquals("\"e/+=\"", decoded?.etag)
    }

    @Test
    fun `round-trips a weak-validator (W prefixed) etag`() {
        val decoded = EventHandle.decode(EventHandle.encode("/cal/x.ics", "W/\"abc\""))
        assertEquals("W/\"abc\"", decoded?.etag)
    }

    @Test
    fun `handle for the same event is stable across partitions`() {
        // The whole point of normalization: two partitions, one handle.
        val a = EventHandle.encode("https://p1-caldav.icloud.com/9/e.ics", "\"e\"")
        val b = EventHandle.encode("https://p77-caldav.icloud.com/9/e.ics", "\"e\"")
        assertEquals(a, b, "handles minted via different partitions must be identical")
    }

    @Test
    fun `decode returns null for the bare prefix with no body`() {
        assertNull(EventHandle.decode(EventHandle.PREFIX))
    }

    @Test
    fun `decode returns null for empty and blank input`() {
        assertNull(EventHandle.decode(""))
        assertNull(EventHandle.decode("   "))
    }

    // ── Occurrence references (evt2_ handles carrying a RECURRENCE-ID) ─────────

    @Test
    fun `round-trips an occurrence handle with a recurrence id`() {
        val handle = EventHandle.encode("/cal/series.ics", "\"etag-1\"", "20260818T140000Z")
        assertTrue(handle.startsWith(EventHandle.PREFIX_V2), "occurrence handle uses the evt2_ prefix")
        val decoded = EventHandle.decode(handle)
        assertEquals("/cal/series.ics", decoded?.href)
        assertEquals("\"etag-1\"", decoded?.etag)
        assertEquals("20260818T140000Z", decoded?.recurrenceId)
        assertTrue(decoded?.isOccurrenceRef() == true)
    }

    @Test
    fun `round-trips an occurrence handle with an all-day DATE recurrence id`() {
        val decoded = EventHandle.decode(EventHandle.encode("/cal/series.ics", "\"e\"", "20260818"))
        assertEquals("20260818", decoded?.recurrenceId)
        assertTrue(decoded?.isOccurrenceRef() == true)
    }

    @Test
    fun `round-trips an occurrence handle with a null etag`() {
        val decoded = EventHandle.decode(EventHandle.encode("/cal/series.ics", null, "20260818T140000Z"))
        assertEquals("/cal/series.ics", decoded?.href)
        assertNull(decoded?.etag)
        assertEquals("20260818T140000Z", decoded?.recurrenceId)
    }

    @Test
    fun `encode with a null recurrence id produces a master evt1_ handle`() {
        // The 3-arg encode with a null recurrenceId must be byte-identical to the
        // 2-arg (master) form, so a non-occurrence caller keeps today's handle.
        val v1 = EventHandle.encode("/cal/x.ics", "\"e\"")
        val v1ViaThreeArg = EventHandle.encode("/cal/x.ics", "\"e\"", null)
        assertEquals(v1, v1ViaThreeArg)
        assertTrue(v1ViaThreeArg.startsWith(EventHandle.PREFIX))
    }

    @Test
    fun `decoding a legacy evt1_ handle yields a null recurrence id (master reference)`() {
        val decoded = EventHandle.decode(EventHandle.encode("/cal/x.ics", "\"e\""))
        assertNull(decoded?.recurrenceId, "an evt1_ handle is a master reference, not an occurrence")
        assertFalse(decoded?.isOccurrenceRef() == true)
    }

    @Test
    fun `occurrence handle body uses only url-safe base64 alphabet`() {
        val handle = EventHandle.encode("https://p180-caldav.icloud.com/x/y z.ics", "\"e/+=\"", "20260818T140000Z")
        val body = handle.removePrefix(EventHandle.PREFIX_V2)
        assertTrue(body.all { it.isLetterOrDigit() || it == '-' || it == '_' },
            "occurrence handle body must be url-safe base64 (no +,/,= or spaces): $body")
    }

    @Test
    fun `looksLikeHandle recognizes occurrence handles`() {
        assertTrue(EventHandle.looksLikeHandle(EventHandle.encode("/x.ics", null, "20260818T140000Z")))
    }

    @Test
    fun `occurrence handle preserves the SSRF guard`() {
        // A forged evt2_ pointing off-host must be rejected exactly like an evt1_.
        val forged = EventHandle.PREFIX_V2 + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("https://evil.com/steal.ics\n\"e\"\n20260818T140000Z".toByteArray(Charsets.UTF_8))
        assertNull(EventHandle.decode(forged))
    }

    @Test
    fun `occurrence handle is host-normalized across partitions`() {
        val a = EventHandle.encode("https://p1-caldav.icloud.com/9/e.ics", "\"e\"", "20260818T140000Z")
        val b = EventHandle.encode("https://p77-caldav.icloud.com/9/e.ics", "\"e\"", "20260818T140000Z")
        assertEquals(a, b, "occurrence handles minted via different partitions must be identical")
    }

    @Test
    fun `decode returns null for an evt2_ handle missing the recurrence id segment`() {
        // Only two segments (href, etag) under the evt2_ prefix is malformed.
        val malformed = EventHandle.PREFIX_V2 + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("/cal/x.ics\n\"e\"".toByteArray(Charsets.UTF_8))
        assertNull(EventHandle.decode(malformed))
    }

    @Test
    fun `decode returns null for an evt2_ handle with a blank recurrence id`() {
        val blankRecid = EventHandle.PREFIX_V2 + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("/cal/x.ics\n\"e\"\n".toByteArray(Charsets.UTF_8))
        assertNull(EventHandle.decode(blankRecid))
    }
}

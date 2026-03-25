package org.onekash.mcp.calendar.caldav

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for EtagUtils.normalizeEtag() — RFC 7232 ETag normalization.
 *
 * Ported from KashCal's EtagUtilsTest.
 */
class EtagUtilsTest {

    @Test
    fun `null input returns null`() {
        assertNull(EtagUtils.normalizeEtag(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(EtagUtils.normalizeEtag(""))
        assertNull(EtagUtils.normalizeEtag("   "))
    }

    @Test
    fun `plain etag unchanged`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("abc123"))
    }

    @Test
    fun `quoted etag strips quotes`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("\"abc123\""))
    }

    @Test
    fun `weak etag strips W prefix and quotes`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("W/\"abc123\""))
    }

    @Test
    fun `XML entity quotes decoded and stripped`() {
        assertEquals("abc123", EtagUtils.normalizeEtag("&quot;abc123&quot;"))
    }

    @Test
    fun `W prefix with XML entity quotes`() {
        assertEquals("abc", EtagUtils.normalizeEtag("W/&quot;abc&quot;"))
    }

    @Test
    fun `whitespace trimmed`() {
        assertEquals("abc", EtagUtils.normalizeEtag("  \"abc\"  "))
    }

    @Test
    fun `single character`() {
        assertEquals("a", EtagUtils.normalizeEtag("\"a\""))
    }

    @Test
    fun `empty quotes returns null`() {
        assertNull(EtagUtils.normalizeEtag("\"\""))
    }

    @Test
    fun `iCloud-style long etag`() {
        val icloudEtag = "\"C=123@U=456-789\""
        assertEquals("C=123@U=456-789", EtagUtils.normalizeEtag(icloudEtag))
    }

    @Test
    fun `weak etag without quotes`() {
        assertEquals("abc", EtagUtils.normalizeEtag("W/abc"))
    }
}

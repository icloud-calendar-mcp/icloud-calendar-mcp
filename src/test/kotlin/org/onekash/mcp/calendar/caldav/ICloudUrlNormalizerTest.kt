package org.onekash.mcp.calendar.caldav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ICloudUrlNormalizerTest {

    @Test
    fun `strips regional partition prefix`() {
        assertEquals(
            "https://caldav.icloud.com/123/calendars/home/e.ics",
            ICloudUrlNormalizer.normalize("https://p180-caldav.icloud.com/123/calendars/home/e.ics")
        )
    }

    @Test
    fun `strips regional prefix and 443 port`() {
        assertEquals(
            "https://caldav.icloud.com/x.ics",
            ICloudUrlNormalizer.normalize("https://p42-caldav.icloud.com:443/x.ics")
        )
    }

    @Test
    fun `is case insensitive`() {
        assertEquals(
            "https://caldav.icloud.com/x.ics",
            ICloudUrlNormalizer.normalize("https://P42-CALDAV.ICLOUD.COM/x.ics")
        )
    }

    @Test
    fun `is idempotent`() {
        val once = ICloudUrlNormalizer.normalize("https://p1-caldav.icloud.com/x.ics")
        assertEquals(once, ICloudUrlNormalizer.normalize(once))
    }

    @Test
    fun `leaves already-canonical iCloud urls alone`() {
        val canonical = "https://caldav.icloud.com/123/calendars/home/e.ics"
        assertEquals(canonical, ICloudUrlNormalizer.normalize(canonical))
    }

    @Test
    fun `leaves non-iCloud urls alone`() {
        val other = "https://p180-caldav.example.com/x.ics"
        assertEquals(other, ICloudUrlNormalizer.normalize(other))
    }

    @Test
    fun `passes through null and empty`() {
        assertNull(ICloudUrlNormalizer.normalize(null))
        assertEquals("", ICloudUrlNormalizer.normalize(""))
    }
}

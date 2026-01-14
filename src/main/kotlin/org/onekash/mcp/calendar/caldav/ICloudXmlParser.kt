package org.onekash.mcp.calendar.caldav

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses iCloud CalDAV XML responses.
 *
 * Handles iCloud-specific XML quirks:
 * - Multiple namespace prefixes (D:, C:, CS:, ICAL:, ME:)
 * - CDATA wrapping of calendar-data
 * - 207 Multi-Status responses
 */
class ICloudXmlParser {

    private val docBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Security: Disable external entities (gracefully handle if features not supported)
        try {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) { }
        try {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (_: Exception) { }
        try {
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CALENDAR DISCOVERY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse calendars from PROPFIND response.
     */
    fun parseCalendars(xml: String, baseUrl: String): List<CalDavCalendar> {
        val doc = parseXml(xml) ?: return emptyList()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val calendars = mutableListOf<CalDavCalendar>()

        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val calendar = parseCalendarFromResponse(response, baseUrl)
            if (calendar != null) {
                calendars.add(calendar)
            }
        }

        return calendars
    }

    private fun parseCalendarFromResponse(response: Element, baseUrl: String): CalDavCalendar? {
        // Get href
        val href = getElementText(response, "href") ?: return null

        // Check if this is a calendar collection
        if (!isCalendarCollection(response)) return null

        // Check status (must be 200 OK)
        if (!hasSuccessStatus(response)) return null

        // Extract properties
        val displayName = getElementText(response, "displayname") ?: extractCalendarIdFromHref(href)
        val ctag = getElementText(response, "getctag")
        val rawColor = getElementText(response, "calendar-color")
        val color = normalizeColor(rawColor)
        val isReadOnly = !hasWritePrivilege(response)

        val id = extractCalendarIdFromHref(href)
        val url = buildUrl(baseUrl, href)

        return CalDavCalendar(
            id = id,
            href = href,
            url = url,
            displayName = displayName,
            color = color,
            ctag = ctag,
            isReadOnly = isReadOnly
        )
    }

    private fun isCalendarCollection(response: Element): Boolean {
        // Look for <C:calendar/> or <calendar/> in resourcetype
        val resourceTypes = response.getElementsByTagNameNS("*", "resourcetype")
        if (resourceTypes.length == 0) return false

        val resourceType = resourceTypes.item(0) as? Element ?: return false
        val children = resourceType.childNodes

        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val localName = child.localName ?: child.nodeName.substringAfter(":")
                if (localName == "calendar") return true
            }
        }
        return false
    }

    private fun hasWritePrivilege(response: Element): Boolean {
        val privilegeSets = response.getElementsByTagNameNS("*", "current-user-privilege-set")
        if (privilegeSets.length == 0) return true  // Assume writable if no privilege info

        val privilegeSet = privilegeSets.item(0) as? Element ?: return true
        val privileges = privilegeSet.getElementsByTagNameNS("*", "privilege")

        for (i in 0 until privileges.length) {
            val privilege = privileges.item(i) as? Element ?: continue
            val children = privilege.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val localName = child.localName ?: child.nodeName.substringAfter(":")
                    if (localName == "write") return true
                }
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRINCIPAL DISCOVERY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse current-user-principal href from PROPFIND response.
     */
    fun parseCurrentUserPrincipal(xml: String): String? {
        val doc = parseXml(xml) ?: return null
        val principals = doc.getElementsByTagNameNS("*", "current-user-principal")
        if (principals.length == 0) return null

        val principal = principals.item(0) as? Element ?: return null
        return getElementText(principal, "href")
    }

    /**
     * Parse calendar-home-set href from PROPFIND response.
     */
    fun parseCalendarHomeSet(xml: String): String? {
        val doc = parseXml(xml) ?: return null
        val homeSets = doc.getElementsByTagNameNS("*", "calendar-home-set")
        if (homeSets.length == 0) return null

        val homeSet = homeSets.item(0) as? Element ?: return null
        return getElementText(homeSet, "href")
    }

    // ═══════════════════════════════════════════════════════════════════
    // EVENT PARSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse events from calendar-multiget or calendar-query REPORT response.
     */
    fun parseEvents(xml: String, baseUrl: String): List<CalDavEvent> {
        val doc = parseXml(xml) ?: return emptyList()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val events = mutableListOf<CalDavEvent>()

        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val event = parseEventFromResponse(response, baseUrl)
            if (event != null) {
                events.add(event)
            }
        }

        return events
    }

    private fun parseEventFromResponse(response: Element, baseUrl: String): CalDavEvent? {
        // Check status
        if (!hasSuccessStatus(response)) return null

        // Get href
        val href = getElementText(response, "href") ?: return null

        // Get calendar-data (may be in CDATA)
        val calendarData = getCalendarData(response) ?: return null
        if (calendarData.isBlank()) return null

        // Extract UID from ICS
        val uid = extractUid(calendarData) ?: return null

        // Get etag
        val etag = getElementText(response, "getetag")

        val url = buildUrl(baseUrl, href)

        return CalDavEvent(
            uid = uid,
            href = href,
            url = url,
            etag = etag,
            icalData = calendarData
        )
    }

    private fun getCalendarData(response: Element): String? {
        val calDataElements = response.getElementsByTagNameNS("*", "calendar-data")
        if (calDataElements.length == 0) return null

        val calData = calDataElements.item(0) as? Element ?: return null
        return calData.textContent?.trim()
    }

    // ═══════════════════════════════════════════════════════════════════
    // UID EXTRACTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Extract UID from ICS content.
     *
     * Handles line folding (RFC 5545) where long lines continue
     * with CRLF + space/tab.
     */
    fun extractUid(ics: String): String? {
        if (ics.isBlank()) return null

        // Unfold lines first (RFC 5545: CRLF + SPACE/TAB is continuation)
        val unfolded = ics
            .replace("\r\n ", "")
            .replace("\r\n\t", "")
            .replace("\n ", "")
            .replace("\n\t", "")

        // Find UID line
        val uidLine = unfolded.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("UID:", ignoreCase = true) }

        return uidLine?.substringAfter(":")?.trim()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SYNC TOKEN
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse sync-token from sync-collection response.
     */
    fun parseSyncToken(xml: String): String? {
        val doc = parseXml(xml) ?: return null
        val syncTokens = doc.getElementsByTagNameNS("*", "sync-token")
        if (syncTokens.length == 0) return null

        return syncTokens.item(0)?.textContent?.trim()
    }

    // ═══════════════════════════════════════════════════════════════════
    // DELETED EVENTS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse hrefs of deleted events from sync-collection response.
     * Deleted events have 404 status.
     */
    fun parseDeletedEventHrefs(xml: String): List<String> {
        val doc = parseXml(xml) ?: return emptyList()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val deleted = mutableListOf<String>()

        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue

            // Check for 404 status
            val status = getDirectElementText(response, "status")
            if (status != null && status.contains("404")) {
                val href = getElementText(response, "href")
                if (href != null) {
                    deleted.add(href)
                }
            }
        }

        return deleted
    }

    // ═══════════════════════════════════════════════════════════════════
    // ETAG PARSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Parse etag from response (e.g., after PUT).
     */
    fun parseEtag(xml: String): String? {
        val doc = parseXml(xml) ?: return null
        val etags = doc.getElementsByTagNameNS("*", "getetag")
        if (etags.length == 0) return null

        return etags.item(0)?.textContent?.trim()
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun parseXml(xml: String): Document? {
        return try {
            val builder = docBuilderFactory.newDocumentBuilder()
            builder.parse(xml.byteInputStream())
        } catch (e: Exception) {
            null
        }
    }

    private fun getElementText(parent: Element, localName: String): String? {
        val elements = parent.getElementsByTagNameNS("*", localName)
        if (elements.length == 0) return null
        return elements.item(0)?.textContent?.trim()
    }

    /**
     * Get text from direct child element only (not nested).
     */
    private fun getDirectElementText(parent: Element, localName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val childLocalName = child.localName ?: child.nodeName.substringAfter(":")
                if (childLocalName == localName) {
                    return child.textContent?.trim()
                }
            }
        }
        return null
    }

    private fun hasSuccessStatus(response: Element): Boolean {
        // Look for status in propstat
        val propstats = response.getElementsByTagNameNS("*", "propstat")
        for (i in 0 until propstats.length) {
            val propstat = propstats.item(i) as? Element ?: continue
            val status = getElementText(propstat, "status")
            if (status != null && status.contains("200")) {
                return true
            }
        }

        // Also check direct status element
        val directStatus = getDirectElementText(response, "status")
        if (directStatus != null && directStatus.contains("200")) {
            return true
        }

        return false
    }

    private fun buildUrl(baseUrl: String, href: String): String {
        val base = baseUrl.trimEnd('/')
        val path = if (href.startsWith("/")) href else "/$href"
        return "$base$path"
    }
}

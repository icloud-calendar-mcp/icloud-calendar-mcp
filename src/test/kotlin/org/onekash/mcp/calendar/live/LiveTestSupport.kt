package org.onekash.mcp.calendar.live

import org.onekash.mcp.calendar.caldav.CalDavCredentials
import org.onekash.mcp.calendar.caldav.OkHttpCalDavClient
import java.io.File
import java.util.Properties

/**
 * Credential loading + iCloud endpoint for the live integration suite.
 *
 * Resolution order (first hit wins):
 *   1. Environment: ICLOUD_USERNAME + (ICLOUD_PASSWORD | ICLOUD_APP_PASSWORD)
 *   2. local.properties via a 2-path fallback:
 *        - ./local.properties   (this repo)
 *        - ../local.properties  (parent, when run from a worktree)
 *      Keys accepted: ICLOUD_USERNAME / ICLOUD_APP_PASSWORD (preferred), with
 *      ICLOUD_PASSWORD accepted as a legacy alias for the password.
 *
 * When nothing resolves, [credentials] is null and every live test self-skips
 * via [org.junit.jupiter.api.Assumptions.assumeTrue]. Passwords are never printed.
 */
object LiveTestSupport {

    const val ICLOUD_BASE_URL = "https://caldav.icloud.com"

    /** Prefix stamped on every event this suite creates, so orphans are findable. */
    const val TEST_PREFIX = "MCP-LIVE:"

    private val localPropertyPaths = listOf(
        "local.properties",
        "../local.properties"
    )

    /** Resolved once per JVM. Null when no credentials are available. */
    val credentials: CalDavCredentials? by lazy { resolveCredentials() }

    /** True when the live suite can actually talk to iCloud. */
    val available: Boolean get() = credentials != null

    /**
     * A short, masked description of where creds came from — safe to print.
     * e.g. "user=rocki***  (source: local.properties)". Never reveals the password.
     */
    val describe: String by lazy {
        val creds = credentials ?: return@lazy "no credentials"
        "user=${creds.username.take(5)}***  (source: $source)"
    }

    private var source: String = "none"

    private fun resolveCredentials(): CalDavCredentials? {
        // 1. Environment
        val envUser = System.getenv("ICLOUD_USERNAME")
        val envPass = System.getenv("ICLOUD_PASSWORD") ?: System.getenv("ICLOUD_APP_PASSWORD")
        if (!envUser.isNullOrBlank() && !envPass.isNullOrBlank()) {
            source = "env"
            return CalDavCredentials(envUser, envPass)
        }

        // 2. local.properties fallback chain
        for (path in localPropertyPaths) {
            val file = File(path)
            if (!file.exists()) continue
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val user = props.getProperty("ICLOUD_USERNAME")?.trim()
            val pass = (props.getProperty("ICLOUD_APP_PASSWORD")
                ?: props.getProperty("ICLOUD_PASSWORD"))?.trim()
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                source = file.path
                return CalDavCredentials(user, pass)
            }
        }
        return null
    }

    /** A fresh client bound to iCloud. Only call when [available] is true. */
    fun newClient(): OkHttpCalDavClient = OkHttpCalDavClient(
        baseUrl = ICLOUD_BASE_URL,
        credentials = credentials ?: error("newClient() called without credentials")
    )
}

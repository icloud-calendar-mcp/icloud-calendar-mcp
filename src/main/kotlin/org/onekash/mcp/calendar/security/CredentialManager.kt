package org.onekash.mcp.calendar.security

/**
 * Secure credential management for iCloud authentication.
 *
 * Security requirements (from MCP spec):
 * - Never log credentials in plain text
 * - Use environment variables for credential storage
 * - Mask credentials in error messages
 * - Don't pass through tokens from external sources
 */
object CredentialManager {

    private const val ENV_ICLOUD_USERNAME = "ICLOUD_USERNAME"
    private const val ENV_ICLOUD_PASSWORD = "ICLOUD_PASSWORD"

    /**
     * Credential data class with secure toString().
     */
    data class Credentials(
        val username: String,
        val password: String
    ) {
        /**
         * Secure toString that masks sensitive data.
         * Only shows first 3 chars of username, password completely hidden.
         */
        override fun toString(): String {
            val maskedUsername = if (username.length > 3) {
                "${username.take(3)}***"
            } else {
                "***"
            }
            return "Credentials(username=$maskedUsername, password=****)"
        }
    }

    /**
     * Load credentials from environment variables.
     *
     * @return Credentials if both username and password are set, null otherwise
     * @throws CredentialException if credentials are invalid
     */
    fun loadFromEnvironment(): Credentials? {
        val username = System.getenv(ENV_ICLOUD_USERNAME)
        val password = System.getenv(ENV_ICLOUD_PASSWORD)

        if (username.isNullOrBlank() && password.isNullOrBlank()) {
            return null // No credentials configured
        }

        if (username.isNullOrBlank()) {
            throw CredentialException("$ENV_ICLOUD_USERNAME is not set")
        }

        if (password.isNullOrBlank()) {
            throw CredentialException("$ENV_ICLOUD_PASSWORD is not set")
        }

        // Validate username format (should be email for iCloud)
        if (!username.contains("@")) {
            throw CredentialException("Invalid username format: expected email address")
        }

        // App-specific passwords are 19 chars with dashes: xxxx-xxxx-xxxx-xxxx
        // But some formats may vary, so just check minimum length
        if (password.length < 16) {
            throw CredentialException("Password too short: expected app-specific password")
        }

        return Credentials(username, password)
    }

    /**
     * Mask a credential value for safe logging.
     *
     * @param value The credential value to mask
     * @param showChars Number of characters to show at the start (default 3)
     * @return Masked string like "abc***"
     */
    fun mask(value: String?, showChars: Int = 3): String {
        if (value.isNullOrBlank()) return "***"
        return if (value.length > showChars) {
            "${value.take(showChars)}***"
        } else {
            "***"
        }
    }

    /**
     * Check if credentials are configured (without loading them).
     */
    fun isConfigured(): Boolean {
        val username = System.getenv(ENV_ICLOUD_USERNAME)
        val password = System.getenv(ENV_ICLOUD_PASSWORD)
        return !username.isNullOrBlank() && !password.isNullOrBlank()
    }
}

/**
 * Exception for credential-related errors.
 * Message is safe to expose (no sensitive data).
 */
class CredentialException(message: String) : Exception(message)
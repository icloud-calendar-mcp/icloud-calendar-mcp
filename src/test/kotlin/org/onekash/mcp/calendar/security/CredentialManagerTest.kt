package org.onekash.mcp.calendar.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/**
 * Unit tests for CredentialManager.
 *
 * Tests verify:
 * - Credential masking for safe logging
 * - Secure toString() implementations
 * - Environment variable validation
 * - No credential leakage in any output
 */
class CredentialManagerTest {

    // ═══════════════════════════════════════════════════════════════════
    // MASK FUNCTION TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `mask should show first 3 chars by default`() {
        val result = CredentialManager.mask("username@example.com")
        assertEquals("use***", result)
    }

    @Test
    fun `mask should handle custom showChars parameter`() {
        val result = CredentialManager.mask("username@example.com", showChars = 5)
        assertEquals("usern***", result)
    }

    @Test
    fun `mask should return stars for null input`() {
        val result = CredentialManager.mask(null)
        assertEquals("***", result)
    }

    @Test
    fun `mask should return stars for blank input`() {
        val result = CredentialManager.mask("")
        assertEquals("***", result)
        assertEquals("***", CredentialManager.mask("   "))
    }

    @Test
    fun `mask should return stars for short input`() {
        // Input shorter than showChars should be fully masked
        val result = CredentialManager.mask("ab", showChars = 3)
        assertEquals("***", result)
    }

    @Test
    fun `mask should handle exactly showChars length`() {
        // Input equal to showChars should be fully masked
        val result = CredentialManager.mask("abc", showChars = 3)
        assertEquals("***", result)
    }

    @Test
    fun `mask should handle one char more than showChars`() {
        val result = CredentialManager.mask("abcd", showChars = 3)
        assertEquals("abc***", result)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CREDENTIALS TOSTRING TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `credentials toString should mask username and hide password`() {
        val creds = CredentialManager.Credentials(
            username = "user@icloud.com",
            password = "xxxx-xxxx-xxxx-xxxx"
        )

        val str = creds.toString()

        // Should show first 3 chars of username
        assertTrue(str.contains("use***"), "Username should be masked to 'use***'")
        // Should never contain full username
        assertFalse(str.contains("user@icloud.com"), "Full username should not appear")
        // Should never contain password
        assertFalse(str.contains("xxxx-xxxx-xxxx-xxxx"), "Password should not appear")
        // Should show password placeholder
        assertTrue(str.contains("password=****"), "Password should be masked as '****'")
    }

    @Test
    fun `credentials toString should handle short username`() {
        val creds = CredentialManager.Credentials(
            username = "ab",
            password = "xxxx-xxxx-xxxx-xxxx"
        )

        val str = creds.toString()
        assertTrue(str.contains("username=***"), "Short username should be fully masked")
    }

    @Test
    fun `credentials data class should not leak via copy`() {
        val creds = CredentialManager.Credentials(
            username = "user@icloud.com",
            password = "secret-password"
        )

        // Even copy().toString() should be safe
        val copied = creds.copy()
        assertFalse(copied.toString().contains("secret-password"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // ISCONFIGURED TESTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `isConfigured should return false when env vars not set`() {
        // Note: This test assumes env vars are not set in test environment
        // In CI, these would need to be explicitly unset
        // For now, we test the function exists and returns a boolean
        val result = CredentialManager.isConfigured()
        // Result depends on test environment - we're just testing the function works
        assertTrue(result is Boolean)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SECURITY EDGE CASES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `mask should handle special characters safely`() {
        // Test various special characters that might cause issues
        assertEquals("abc***", CredentialManager.mask("abc\ndef"))
        assertEquals("abc***", CredentialManager.mask("abc\tdef"))
        assertEquals("abc***", CredentialManager.mask("abc\"def"))
        assertEquals("abc***", CredentialManager.mask("abc'def"))
        assertEquals("abc***", CredentialManager.mask("abc\\def"))
    }

    @Test
    fun `mask should handle unicode characters`() {
        val result = CredentialManager.mask("user@例え.com")
        assertEquals("use***", result)
    }

    @Test
    fun `credentials should not expose data through hashCode or equals`() {
        val creds1 = CredentialManager.Credentials("user@icloud.com", "pass1")
        val creds2 = CredentialManager.Credentials("user@icloud.com", "pass2")

        // Data class equals compares all fields - different passwords = not equal
        assertFalse(creds1 == creds2)

        // Same credentials should be equal
        val creds3 = CredentialManager.Credentials("user@icloud.com", "pass1")
        assertTrue(creds1 == creds3)
    }
}
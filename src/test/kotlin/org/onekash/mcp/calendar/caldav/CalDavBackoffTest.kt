package org.onekash.mcp.calendar.caldav

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CalDavBackoffTest {
    @Test
    fun `Apple long retry after sends just one request`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503)
                .addHeader("Retry-After", "1595"))
            val http = OkHttpClient.Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .build()
            OkHttpCalDavClient(
                server.url("/").toString().trimEnd('/'),
                CalDavCredentials("test@icloud.com", "test-password"),
                http
            ).use { client ->
                client.skipWellKnownDiscovery()
                val result = assertIs<CalDavResult.Error>(
                    client.checkConnection())
                assertEquals(429, result.code)
                assertEquals(1, server.requestCount)
            }
        }
    }
}

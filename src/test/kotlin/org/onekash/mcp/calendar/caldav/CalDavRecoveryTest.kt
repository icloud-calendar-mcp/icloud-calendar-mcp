package org.onekash.mcp.calendar.caldav

import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Timeout
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.test.*

internal class CalDavTestClock(start: Instant = Instant.parse("2026-11-10T12:00:00Z")) : Clock() {
    private var now = start
    val sleeps = mutableListOf<Long>()
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    @Synchronized override fun instant(): Instant = now
    @Synchronized fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    @Synchronized fun sleep(millis: Long) {
        sleeps.add(millis)
        now = now.plusMillis(millis)
    }
}

@Timeout(30)
class CalDavRecoveryTest {
    private val ics = "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:test@local\nEND:VEVENT\nEND:VCALENDAR"

    private fun client(server: MockWebServer, clock: CalDavTestClock,
                       http: OkHttpClient = OkHttpClient.Builder()
                           .readTimeout(100, TimeUnit.MILLISECONDS).build()) =
        OkHttpCalDavClient(server.url("/").toString().trimEnd('/'),
            CalDavCredentials("test@icloud.com", "test-password"), http, clock, clock::sleep)
            .also { it.skipWellKnownDiscovery() }

    private fun error(result: CalDavResult<*>, code: Int, retryAt: Instant? = null): CalDavResult.Error =
        assertIs<CalDavResult.Error>(result).also {
            assertEquals(code, it.code)
            assertEquals(retryAt, it.retryAt)
        }

    private fun connected() = MockResponse().addHeader("DAV", "calendar-access")

    private fun discover(server: MockWebServer, client: OkHttpCalDavClient) {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>
            <D:current-user-principal><D:href>/principal/</D:href></D:current-user-principal>
            </D:prop></D:propstat></D:response></D:multistatus>
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response><D:propstat><D:prop><C:calendar-home-set><D:href>/cal/</D:href>
            </C:calendar-home-set></D:prop></D:propstat></D:response></D:multistatus>
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(207).setBody("""
            <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
            <D:response><D:href>/cal/home/</D:href><D:propstat><D:prop><D:displayname>Home</D:displayname>
            <D:resourcetype><D:collection/><C:calendar/></D:resourcetype></D:prop>
            </D:propstat></D:response></D:multistatus>
        """.trimIndent()))
        val calendars = assertIs<CalDavResult.Success<List<CalDavCalendar>>>(client.listCalendars()).data
        assertEquals("home", calendars.single().id)
    }

    @Test
    fun `Apple long retry after blocks every operation and recovers on the same client`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                discover(server, client)
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "1595"))
                val deadline = clock.instant().plusSeconds(1595)
                error(client.checkConnection(), 429, deadline)
                val count = server.requestCount
                error(client.listCalendars(), 429, deadline)
                error(client.getEvents("home", "2026-11-10", "2026-11-11"), 429, deadline)
                error(client.getEvent("/cal/home/event.ics"), 429, deadline)
                error(client.createEvent("home", ics), 429, deadline)
                error(client.updateEvent("/cal/home/event.ics", ics, "etag"), 429, deadline)
                error(client.deleteEvent("/cal/home/event.ics", "etag"), 429, deadline)
                error(client.checkConnection(), 429, deadline)
                assertEquals(count, server.requestCount)
                assertTrue(clock.sleeps.isEmpty())
                clock.advance(1596)
                server.enqueue(connected())
                assertIs<CalDavResult.Success<Boolean>>(client.checkConnection())
                assertEquals(count + 1, server.requestCount)
            }
        }
    }

    @Test
    fun `429 and 503 share deadlines through PROPFIND REPORT GET PUT and OPTIONS`() {
        for (status in listOf(429, 503)) for (method in listOf("PROPFIND", "REPORT", "GET", "PUT", "OPTIONS")) {
            MockWebServer().use { server ->
                val clock = CalDavTestClock()
                client(server, clock).use { client ->
                    if (method == "REPORT") discover(server, client)
                    val before = server.requestCount
                    server.enqueue(MockResponse().setResponseCode(status).addHeader("Retry-After", "1595"))
                    val result = when (method) {
                        "PROPFIND" -> client.listCalendars()
                        "REPORT" -> client.getEvents("home", "2026-11-10", "2026-11-11")
                        "GET" -> client.getEvent("/event.ics")
                        "PUT" -> client.updateEvent("/event.ics", ics, "etag")
                        else -> client.checkConnection()
                    }
                    error(result, 429, clock.instant().plusSeconds(1595))
                    assertEquals(before + 1, server.requestCount, "$status $method")
                    assertTrue(clock.sleeps.isEmpty())
                }
            }
        }
    }

    @Test
    fun `Retry-After accepts dates clamps at one day and ignores invalid headers`() {
        val start = CalDavTestClock().instant()
        val headers = listOf(
            "1595" to 1595L,
            DateTimeFormatter.RFC_1123_DATE_TIME.format(start.plusSeconds(1595).atZone(ZoneOffset.UTC)) to 1595L,
            "99999999999999999999999999" to 86400L,
            DateTimeFormatter.RFC_1123_DATE_TIME.format(start.plusSeconds(100000).atZone(ZoneOffset.UTC)) to 86400L
        )
        for ((header, delay) in headers) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", header))
                error(client.checkConnection(), 429, start.plusSeconds(delay))
                assertEquals(1, server.requestCount)
            }
        }
        for (header in listOf("nonsense", "-1")) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                repeat(2) { server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", header)) }
                assertTrue(error(client.checkConnection(), 503).isRetryable)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `short windows wait only the remainder and reads retry once`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
                server.enqueue(connected())
                assertIs<CalDavResult.Success<Boolean>>(client.checkConnection())
                assertEquals(listOf(2000L), clock.sleeps)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `a shorter in-flight Retry-After cannot shorten the shared deadline`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            val arrived = CountDownLatch(2)
            val releaseShort = CountDownLatch(1)
            val order = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val first = order.getAndIncrement() == 0
                    arrived.countDown()
                    check(arrived.await(5, TimeUnit.SECONDS))
                    if (!first) check(releaseShort.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503).addHeader("Retry-After", if (first) "1595" else "10")
                }
            }
            val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
            client(server, clock, http).use { client ->
                val pool = Executors.newFixedThreadPool(2)
                val completed = ExecutorCompletionService<CalDavResult<Boolean>>(pool)
                try {
                    repeat(2) { completed.submit { client.checkConnection() } }
                    assertTrue(arrived.await(5, TimeUnit.SECONDS))
                    val deadline = clock.instant().plusSeconds(1595)
                    error(completed.poll(5, TimeUnit.SECONDS)!!.get(), 429, deadline)
                    clock.advance(100)
                    releaseShort.countDown()
                    error(completed.poll(5, TimeUnit.SECONDS)!!.get(), 429, deadline)
                    error(client.checkConnection(), 429, deadline)
                    assertEquals(2, server.requestCount)
                } finally {
                    releaseShort.countDown()
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `ten real refused connections recover when the same endpoint becomes available`() {
        // Refuse at the socket layer instead of freeing and rebinding a port, which races on shared hosts.
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            var refuse = true
            val attempts = AtomicInteger()
            val clock = CalDavTestClock()
            val sockets = object : SocketFactory() {
                private fun socket() = object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) {
                        if (refuse) throw ConnectException("Connection refused")
                        super.connect(endpoint, timeout)
                    }
                }
                override fun createSocket() = socket()
                override fun createSocket(host: String, port: Int) = throw UnsupportedOperationException()
                override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) =
                    throw UnsupportedOperationException()
                override fun createSocket(host: InetAddress, port: Int) = throw UnsupportedOperationException()
                override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) =
                    throw UnsupportedOperationException()
            }
            val http = OkHttpClient.Builder().socketFactory(sockets).eventListener(object : EventListener() {
                override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
                    attempts.incrementAndGet()
                }
            }).build()
            OkHttpCalDavClient(url, CalDavCredentials("test", "password"), http, clock, clock::sleep).use { client ->
                for (count in listOf(2, 4, 5, 6, 7, 8, 9, 10)) {
                    val result = assertIs<CalDavResult.Error>(client.checkConnection())
                    assertEquals(count, attempts.get())
                    if (count >= 5) {
                        val cooldown = minOf(30L shl (count - 5), 300L)
                        assertEquals(clock.instant().plusSeconds(cooldown), result.retryAt)
                        error(client.checkConnection(), 503, result.retryAt)
                        assertEquals(count, attempts.get())
                        clock.advance(cooldown)
                    }
                }
                refuse = false
                server.enqueue(connected())
                assertIs<CalDavResult.Success<Boolean>>(client.checkConnection())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `ten server failures recover after every cooldown and success resets the count`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                repeat(10) { server.enqueue(MockResponse().setResponseCode(500)) }
                for (count in listOf(2, 4, 5, 6, 7, 8, 9, 10)) {
                    val result = assertIs<CalDavResult.Error>(client.checkConnection())
                    assertEquals(count, server.requestCount)
                    if (count >= 5) {
                        val cooldown = minOf(30L shl (count - 5), 300L)
                        assertEquals(clock.instant().plusSeconds(cooldown), result.retryAt)
                        error(client.checkConnection(), 503, result.retryAt)
                        assertEquals(count, server.requestCount)
                        clock.advance(cooldown)
                    }
                }
                server.enqueue(connected())
                assertIs<CalDavResult.Success<Boolean>>(client.checkConnection())
                repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }
                error(client.checkConnection(), 500)
                assertEquals(13, server.requestCount)
            }
        }
    }

    @Test
    fun `ten refused connections recover and SSL neither counts nor resets`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            val attempts = AtomicInteger()
            var ssl = false
            var success = false
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                attempts.incrementAndGet()
                when {
                    ssl -> throw SSLException("test TLS refusal")
                    !success -> throw ConnectException("Connection refused")
                    else -> chain.proceed(chain.request())
                }
            }.build()
            client(server, clock, http).use { client ->
                repeat(2) { error(client.checkConnection(), 0) }
                assertEquals(4, attempts.get())
                ssl = true
                assertFalse(error(client.checkConnection(), 0).isRetryable)
                ssl = false
                for (count in 5..10) {
                    val result = assertIs<CalDavResult.Error>(client.checkConnection())
                    assertEquals(count + 1, attempts.get())
                    val cooldown = minOf(30L shl (count - 5), 300L)
                    assertEquals(clock.instant().plusSeconds(cooldown), result.retryAt)
                    clock.advance(cooldown)
                }
                success = true
                server.enqueue(connected())
                assertIs<CalDavResult.Success<Boolean>>(client.checkConnection())
                success = false
                error(client.checkConnection(), 0)
                assertEquals(14, attempts.get())
            }
        }
    }

    @Test
    fun `mutations send once and distinguish unknown outcome from not applied`() {
        for (method in listOf("PUT", "DELETE")) for (response in listOf("500", "timeout", "throttled", "zero")) {
            MockWebServer().use { server ->
                val clock = CalDavTestClock()
                client(server, clock).use { client ->
                    server.enqueue(when (response) {
                        "timeout" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        "throttled" -> MockResponse().setResponseCode(503).addHeader("Retry-After", "1595")
                        "zero" -> MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                        else -> MockResponse().setResponseCode(500)
                    })
                    val result = assertIs<CalDavResult.Error>(if (method == "PUT")
                        client.updateEvent("/event.ics", ics, "etag") else client.deleteEvent("/event.ics", "etag"))
                    assertEquals(1, server.requestCount, "$method $response")
                    if (response == "throttled" || response == "zero") {
                        assertEquals(429, result.code)
                        assertEquals(clock.instant().plusSeconds(if (response == "zero") 0 else 1595), result.retryAt)
                        assertTrue(result.message.contains("not applied"))
                    } else {
                        assertFalse(result.isRetryable)
                        assertTrue(result.message.contains("may have been applied"))
                        assertTrue(result.message.contains("read back", ignoreCase = true))
                    }
                }
            }
        }
    }

    @Test
    fun `mutation TLS failures report an unknown outcome without retrying`() {
        for (method in listOf("PUT", "DELETE")) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            val attempts = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor {
                attempts.incrementAndGet()
                throw SSLException("TLS transport failed")
            }.build()
            client(server, clock, http).use { client ->
                val result = assertIs<CalDavResult.Error>(if (method == "PUT")
                    client.updateEvent("/event.ics", ics, "etag") else client.deleteEvent("/event.ics", "etag"))
                assertEquals(1, attempts.get())
                assertFalse(result.isRetryable)
                assertTrue(result.message.contains("may have been applied"))
                assertTrue(result.message.contains("Read back"))
            }
        }
    }

    @Test
    fun `authentication failures do not open breaker and event 403 stays forbidden`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                repeat(5) {
                    server.enqueue(MockResponse().setResponseCode(401))
                    error(client.listCalendars(), 401)
                }
                server.enqueue(MockResponse().setResponseCode(403))
                error(client.listCalendars(), 401)
                server.enqueue(MockResponse().setResponseCode(403))
                error(client.checkConnection(), 401)
                server.enqueue(MockResponse().setResponseCode(403))
                error(client.getEvent("/event.ics"), 403)
                assertEquals(8, server.requestCount)
            }
        }
    }

    @Test
    fun `calendar list throttle is returned uncached`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                server.enqueue(MockResponse().setResponseCode(207).setBody("""
                    <D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>
                    <D:current-user-principal><D:href>/p/</D:href></D:current-user-principal>
                    </D:prop></D:propstat></D:response></D:multistatus>
                """.trimIndent()))
                server.enqueue(MockResponse().setResponseCode(207).setBody("""
                    <D:multistatus xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                    <D:response><D:propstat><D:prop><C:calendar-home-set><D:href>/cal/</D:href>
                    </C:calendar-home-set></D:prop></D:propstat></D:response></D:multistatus>
                """.trimIndent()))
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "1595"))
                error(client.listCalendars(), 429, clock.instant().plusSeconds(1595))
                clock.advance(1595)
                discover(server, client)
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test
    fun `well-known transient failure is not cached`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            OkHttpCalDavClient(server.url("/").toString().trimEnd('/'),
                CalDavCredentials("test", "password"), clock = clock, sleep = clock::sleep).use { client ->
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "1595"))
                error(client.listCalendars(), 429, clock.instant().plusSeconds(1595))
                assertEquals(1, server.requestCount)
                clock.advance(1595)
                server.enqueue(MockResponse().setResponseCode(404))
                discover(server, client)
                assertEquals("/.well-known/caldav", server.takeRequest().path)
                assertEquals("/.well-known/caldav", server.takeRequest().path)
            }
        }
    }

    @Test
    fun `well-known GET waits out short windows and ETag PROPFIND retries once`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            OkHttpCalDavClient(server.url("/").toString().trimEnd('/'), CalDavCredentials("test", "password"),
                clock = clock, sleep = clock::sleep).use { client ->
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "1"))
                server.enqueue(MockResponse().setResponseCode(404))
                discover(server, client)
                assertEquals(listOf(1000L), clock.sleeps)
                assertEquals(5, server.requestCount)
            }
        }
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                server.enqueue(MockResponse().setResponseCode(500))
                server.enqueue(MockResponse().setResponseCode(207).setBody("""
                    <D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>
                    <D:getetag>recovered</D:getetag></D:prop></D:propstat></D:response></D:multistatus>
                """.trimIndent()))
                server.enqueue(MockResponse().setResponseCode(204).addHeader("ETag", "updated"))
                assertIs<CalDavResult.Success<CalDavEvent>>(client.updateEvent("/event.ics", ics, null))
                assertEquals(listOf("PROPFIND", "PROPFIND", "PUT"), (1..3).map { server.takeRequest().method })
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test
    fun `body timeouts count before successful headers reset the breaker`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                repeat(5) { server.enqueue(MockResponse().setBody(ics).setBodyDelay(1, TimeUnit.SECONDS)) }
                for (count in listOf(2, 4, 5)) {
                    val result = assertIs<CalDavResult.Error>(client.getEvent("/event.ics"))
                    assertEquals(count, server.requestCount)
                    if (count == 5) {
                        assertEquals(503, result.code)
                        assertEquals(clock.instant().plusSeconds(30), result.retryAt)
                    }
                }
                clock.advance(30)
                server.enqueue(MockResponse().setBody(ics).addHeader("ETag", "etag"))
                assertIs<CalDavResult.Success<CalDavEvent>>(client.getEvent("/event.ics"))
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test
    fun `a call timeout counts its failed request once`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val http = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            client(server, clock, http).use { client ->
                repeat(2) { assertNull(assertIs<CalDavResult.Error>(client.checkConnection()).retryAt) }
                assertEquals(4, server.requestCount)
                error(client.checkConnection(), 503, clock.instant().plusSeconds(30))
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test
    fun `zero Retry-After does not allow hidden HTTP retries`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                repeat(2) { server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0")) }
                error(client.checkConnection(), 429, clock.instant())
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `eight in-flight callers share the deadline and later callers send nothing`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            val arrived = CountDownLatch(8)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    check(arrived.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503).addHeader("Retry-After", "1595")
                }
            }
            val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
            client(server, clock, http).use { client ->
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val calls = (1..8).map { pool.submit<CalDavResult<Boolean>> { client.checkConnection() } }
                    val deadline = clock.instant().plusSeconds(1595)
                    calls.forEach { error(it.get(10, TimeUnit.SECONDS), 429, deadline) }
                    repeat(8) { error(client.checkConnection(), 429, deadline) }
                    assertEquals(8, server.requestCount)
                    assertTrue(clock.sleeps.isEmpty())
                } finally { pool.shutdownNow() }
            }
        }
    }

    // ── Review fixes ────────────────────────────────────────────────────

    private fun wellKnownClient(server: MockWebServer, clock: CalDavTestClock) =
        OkHttpCalDavClient(server.url("/").toString().trimEnd('/'), CalDavCredentials("test", "password"),
            clock = clock, sleep = clock::sleep)

    @Test
    fun `a failing well-known probe falls back to the base URL`() {
        for (failure in listOf(500, 403)) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            wellKnownClient(server, clock).use { client ->
                // 500 is retried once, 403 is not; both fall back to PROPFIND on the base URL.
                repeat(if (failure == 500) 2 else 1) { server.enqueue(MockResponse().setResponseCode(failure)) }
                discover(server, client)
                assertEquals(if (failure == 500) 5 else 4, server.requestCount, "well-known $failure")
            }
        }
    }

    @Test
    fun `refused credentials on well-known still report AUTH_ERROR from the principal PROPFIND`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            wellKnownClient(server, clock).use { client ->
                server.enqueue(MockResponse().setResponseCode(401))
                server.enqueue(MockResponse().setResponseCode(401))
                error(client.listCalendars(), 401)
                assertEquals("/.well-known/caldav", server.takeRequest().path)
                assertEquals("PROPFIND", server.takeRequest().method)
            }
        }
    }

    @Test
    fun `a write on a dropped pooled connection is replayed instead of reported as uncertain`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                // Pool a connection, then have the server drop it on the next request without answering
                // (a stale connection after sleep or a network change). OkHttp replays the conditional DELETE.
                server.enqueue(connected())
                assertIs<CalDavResult.Success<Boolean>>(client.checkConnection())
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                server.enqueue(MockResponse().setResponseCode(204))
                assertIs<CalDavResult.Success<Unit>>(client.deleteEvent("/event.ics", "etag"))
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test
    fun `a write refused before sending is labeled not applied`() {
        for (method in listOf("PUT", "DELETE")) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            val http = OkHttpClient.Builder().addInterceptor { throw ConnectException("Connection refused") }.build()
            client(server, clock, http).use { client ->
                val result = assertIs<CalDavResult.Error>(if (method == "PUT")
                    client.updateEvent("/event.ics", ics, "etag") else client.deleteEvent("/event.ics", "etag"))
                assertTrue(result.message.startsWith("Mutation not applied"), result.message)
                assertTrue(result.isRetryable)
                assertFalse(result.outcomeUnknown)
            }
        }
    }

    private fun storedEvent(uid: String) = MockResponse().addHeader("ETag", "\"stored\"")
        .setBody("BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:$uid\nEND:VEVENT\nEND:VCALENDAR")

    @Test
    fun `a create whose earlier attempt landed resolves the 412 to success`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                discover(server, client)
                server.enqueue(MockResponse().setResponseCode(412))
                server.enqueue(storedEvent("test@local"))
                val event = assertIs<CalDavResult.Success<CalDavEvent>>(client.createEvent("home", ics)).data
                assertEquals("stored", event.etag)
                repeat(3) { server.takeRequest() }
                val put = server.takeRequest()
                val get = server.takeRequest()
                assertEquals(listOf("PUT", "GET"), listOf(put.method, get.method))
                assertEquals(put.path, get.path)
            }
        }
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                discover(server, client)
                server.enqueue(MockResponse().setResponseCode(412))
                server.enqueue(storedEvent("someone-else"))
                error(client.createEvent("home", ics), 412)
            }
        }
    }

    @Test
    fun `an uncertain create is resent to the same href and never told to re-list`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                discover(server, client)
                server.enqueue(MockResponse().setResponseCode(502))
                server.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"new\""))
                assertIs<CalDavResult.Success<CalDavEvent>>(client.createEvent("home", ics))
                repeat(3) { server.takeRequest() }
                val first = server.takeRequest()
                val second = server.takeRequest()
                assertEquals(listOf("PUT", "PUT"), listOf(first.method, second.method))
                assertEquals(first.path, second.path)
                assertEquals("*", second.getHeader("If-None-Match"))
            }
        }
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                discover(server, client)
                repeat(2) { server.enqueue(MockResponse().setResponseCode(502)) }
                val result = error(client.createEvent("home", ics), 502)
                assertTrue(result.message.contains("Do not call create_event again"), result.message)
                assertFalse(result.message.contains("Read back"), result.message)
                assertFalse(result.isRetryable)
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test
    fun `a 408 is replayed once and counts as a transient failure`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                // A write has no app-level retry, so only OkHttp's 408 replay can make this succeed.
                server.enqueue(MockResponse().setResponseCode(408))
                server.enqueue(MockResponse().setResponseCode(204).addHeader("ETag", "\"new\""))
                assertIs<CalDavResult.Success<CalDavEvent>>(client.updateEvent("/event.ics", ics, "etag"))
                assertEquals(2, server.requestCount)
            }
        }
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                // Four 500s, then 408 pairs (OkHttp gives up on a second consecutive 408): the breaker opens.
                repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
                repeat(2) { server.enqueue(MockResponse().setResponseCode(408)) }
                repeat(2) { error(client.checkConnection(), 500) }
                error(client.checkConnection(), 503, clock.instant().plusSeconds(30))
            }
        }
    }

    @Test
    fun `definitive server refusals of a write are not applied and not retryable`() {
        for (code in listOf(501, 505, 507)) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                server.enqueue(MockResponse().setResponseCode(code))
                val result = error(client.updateEvent("/event.ics", ics, "etag"), code)
                assertTrue(result.message.startsWith("Mutation not applied"), "$code: ${result.message}")
                assertFalse(result.isRetryable)
            }
        }
    }

    @Test
    fun `a non-ASCII Retry-After is ignored instead of failing the call`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                repeat(2) {
                    server.enqueue(MockResponse().setResponseCode(503).setBody("busy")
                        .addHeaderLenient("Retry-After", "sam., 12 d\u00e9c. 2026 10:00:00 GMT"))
                }
                error(client.checkConnection(), 503)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `a lowercase etag header avoids the PROPFIND probe`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                server.enqueue(MockResponse().addHeader("etag", "\"lower\"").setBody(ics))
                assertEquals("lower", assertIs<CalDavResult.Success<CalDavEvent>>(
                    client.getEvent("/event.ics")).data.etag)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `a cached calendar list does not wait out a short window`() {
        MockWebServer().use { server ->
            val clock = CalDavTestClock()
            client(server, clock).use { client ->
                discover(server, client)
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "2"))
                error(client.updateEvent("/cal/home/event.ics", ics, "etag"), 429, clock.instant().plusSeconds(2))
                val count = server.requestCount
                assertIs<CalDavResult.Success<List<CalDavCalendar>>>(client.listCalendars())
                assertTrue(clock.sleeps.isEmpty())
                assertEquals(count, server.requestCount)
            }
        }
    }

    @Test
    fun `a redirected write is reported as an error, never as a GET success`() {
        for (code in listOf(301, 302, 303)) MockWebServer().use { server ->
            val clock = CalDavTestClock()
            // A followed redirect would reach /elsewhere and read its 200 as a successful write.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/elsewhere")
                    MockResponse().setResponseCode(200)
                    else MockResponse().setResponseCode(code).addHeader("Location", "/elsewhere")
            }
            client(server, clock).use { client ->
                assertIs<CalDavResult.Error>(client.updateEvent("/event.ics", ics, "etag"), "PUT $code")
                assertIs<CalDavResult.Error>(client.deleteEvent("/event.ics", "etag"), "DELETE $code")
                assertEquals(listOf("PUT", "DELETE"), listOf(server.takeRequest().method, server.takeRequest().method))
                assertEquals(2, server.requestCount)
            }
        }
    }
}

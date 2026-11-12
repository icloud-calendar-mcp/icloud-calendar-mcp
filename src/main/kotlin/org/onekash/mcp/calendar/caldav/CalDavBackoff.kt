package org.onekash.mcp.calendar.caldav

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** HTTP statuses that signal a temporary server condition. */
internal fun isTransientStatus(code: Int) = code == 408 || code == 429 || code == 500 || code in 502..504

/** Shared request admission and transient-failure recovery for one client. */
internal class CalDavBackoff(
    private val clock: Clock,
    private val sleep: (Long) -> Unit
) : Interceptor {
    data class Window(val retryAt: Instant, val rateLimited: Boolean) {
        fun error() = CalDavResult.Error(
            if (rateLimited) 429 else 503,
            if (rateLimited) "iCloud requested a pause until $retryAt"
            else "Calendar service cooling down until $retryAt",
            isRetryable = true,
            retryAt = retryAt
        )
    }

    class Blocked(val window: Window) : IOException(
        window.error().message)

    class TransportFailure(val failure: IOException) : IOException(failure)

    private var appleUntil: Instant? = null
    private var cooldownUntil: Instant? = null
    private var consecutiveFailures = 0

    private class ResponseState(var retryAfter: String? = null)

    // Route 503 retries and Retry-After parsing through the shared gate.
    val networkInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 503) {
            chain.request().tag(ResponseState::class.java)?.retryAfter = response.header("Retry-After")
            response.newBuilder().removeHeader("Retry-After").build()
        } else response
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        awaitAdmission()
        val state = ResponseState()
        val response = try {
            val received = chain.proceed(chain.request().newBuilder().tag(ResponseState::class.java, state).build())
            try {
                // Buffer successful responses up to the body limit before resetting the breaker.
                if (received.isSuccessful && received.body.contentLength() <= OkHttpCalDavClient.MAX_BODY_SIZE) {
                    received.body.source().request(OkHttpCalDavClient.MAX_BODY_SIZE + 1)
                }
            } catch (e: IOException) {
                received.close()
                throw e
            }
            // addUnsafeNonAscii: a malformed header must not throw past the IOException handling.
            state.retryAfter?.let {
                received.newBuilder()
                    .headers(received.headers.newBuilder().addUnsafeNonAscii("Retry-After", it).build())
                    .build()
            } ?: received
        } catch (e: IOException) {
            failed(e)
            throw TransportFailure(e)
        }
        val window = observed(response)
        return if (window == null) response else response.newBuilder()
            .request(response.request.newBuilder().tag(Window::class.java, window).build())
            .build()
    }

    /** The active window when it is too long to wait out within one call, else null. */
    fun blockingWindow(): Window? = window()?.takeIf {
        Duration.between(clock.instant(), it.retryAt) > Duration.ofMillis(OkHttpCalDavClient.MAX_RETRY_AFTER_MS)
    }

    fun awaitAdmission() {
        while (true) {
            val window = window() ?: return
            val remaining = Duration.between(clock.instant(), window.retryAt)
            if (remaining > Duration.ofMillis(OkHttpCalDavClient.MAX_RETRY_AFTER_MS)) {
                throw Blocked(window)
            }
            if (remaining.isNegative || remaining.isZero) continue
            try {
                sleep((remaining.toNanos() + 999_999) / 1_000_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Backoff wait interrupted")
            }
        }
    }

    @Synchronized
    fun window(): Window? {
        val now = clock.instant()
        val apple = appleUntil?.takeIf { it > now }
        val cooldown = cooldownUntil?.takeIf { it > now }
        return when {
            apple != null && (cooldown == null || apple >= cooldown) ->
                Window(apple, true)
            cooldown != null -> Window(cooldown, false)
            else -> null
        }
    }

    @Synchronized
    private fun observed(response: Response): Window? {
        val retryAt = retryAfter(response)
        when {
            retryAt != null -> {
                if (appleUntil == null || retryAt > appleUntil) {
                    appleUntil = retryAt
                }
            }
            isTransientStatus(response.code) -> transientFailure()
            else -> {
                consecutiveFailures = 0
                cooldownUntil = null
            }
        }
        return window() ?: retryAt?.let { Window(appleUntil!!, true) }
    }

    @Synchronized
    private fun failed(error: IOException) {
        if (error !is SSLException) transientFailure()
    }

    private fun transientFailure() {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(30)
        if (consecutiveFailures <
            OkHttpCalDavClient.CIRCUIT_BREAKER_THRESHOLD) return
        val exponent = (consecutiveFailures -
            OkHttpCalDavClient.CIRCUIT_BREAKER_THRESHOLD).coerceAtMost(4)
        val seconds = (30L shl exponent).coerceAtMost(300)
        val deadline = clock.instant().plusSeconds(seconds)
        if (cooldownUntil == null || deadline > cooldownUntil) {
            cooldownUntil = deadline
        }
    }

    /** A valid server delay, bounded to one day, including an expired date. */
    fun retryAfter(response: Response): Instant? {
        if (response.code != 429 && response.code != 503) return null
        val header = response.header("Retry-After")?.trim() ?: return null
        val now = clock.instant()
        val deadline = if (header.isNotEmpty() && header.all { it in '0'..'9' }) {
            val seconds = header.toLongOrNull() ?: 86_400L
            now.plusSeconds(seconds.coerceAtMost(86_400))
        } else {
            try {
                ZonedDateTime.parse(header,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (_: java.time.DateTimeException) {
                return null
            }
        }
        return deadline.coerceIn(now, now.plusSeconds(86_400))
    }
}

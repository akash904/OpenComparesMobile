package com.opencompares.android.chatgpt

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class StreamAndServerTest {

    @Test
    fun sseParserCollectsTextAndCompletion() {
        val lines = listOf(
            "event: response.created", "data: {\"type\":\"response.created\"}", "",
            ": keep-alive", "",
            "event: response.output_text.delta", "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Open \"}", "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Compares\"}", "",
            "data: {\"type\":\"response.completed\",\"response\":{}}", ""
        )
        val parser = SseParser()
        val events = lines.mapNotNull { parser.feed(it) }
        assertEquals(
            listOf(StreamEvent.TextDelta("Open "), StreamEvent.TextDelta("Compares"), StreamEvent.Completed),
            events
        )
    }

    @Test
    fun sseParserReportsPlanUsageFailures() {
        val parser = SseParser()
        parser.feed("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"limit\"}}}")
        val event = parser.feed("") as StreamEvent.Failed
        assertEquals("subscription_sharing_usage_limit_exceeded", event.code)
        assertNull(SseParser().feed(""))
    }

    @Test
    fun apiErrorsMapToClearMessages() {
        val limit = SiwcApiException.fromBody(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"x"}}""")
        assertTrue(limit.userMessage.startsWith("Usage limit reached"))
        val oauth = SiwcApiException.fromBody(400, """{"error":"refresh_token_reused","error_description":"reused"}""")
        assertEquals("refresh_token_reused", oauth.code)
        assertTrue(oauth.requiresSignIn)
        val notEligible = SiwcApiException.fromBody(403, """{"error":{"code":"subscription_sharing_user_not_eligible"}}""")
        assertTrue(notEligible.userMessage.contains("Go, Plus, Pro or Business"))
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        // Timeouts make a broken server fail the test instead of hanging the build.
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        return code to body
    }

    @Test
    fun loopbackServerIgnoresOtherPathsAndReturnsTheCallbackQuery() = runBlocking {
        val server = LoopbackCallbackServer.start(preferredPort = 0)
        val port = server.port
        // Start listening right away, as the app does before it opens the browser.
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            server.awaitCallback { LoopbackCallbackServer.callbackPage(it, "com.example://auth-complete") }
        }
        val (faviconCode, _) = get("http://127.0.0.1:$port/favicon.ico")
        assertEquals(404, faviconCode)
        val (code, body) = get("http://127.0.0.1:$port/auth/callback?code=abc&state=s%201&client_id=oaiapp_1")
        assertEquals(200, code)
        assertTrue(body.contains("com.example://auth-complete"))
        val params = withTimeout(5_000) { result.await() }
        assertEquals("abc", params["code"])
        assertEquals("s 1", params["state"])
        assertEquals("oaiapp_1", params["client_id"])
    }

    @Test
    fun loopbackServerFallsBackWhenThePreferredPortIsTaken() {
        val first = LoopbackCallbackServer.start(preferredPort = 0)
        val second = LoopbackCallbackServer.start(preferredPort = first.port)
        assertTrue(second.port != first.port)
        first.close()
        second.close()
    }
}

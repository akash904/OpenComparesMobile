package com.opencompares.android.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A token endpoint response. `refreshToken` may be absent if offline access wasn't granted. */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val scopes: Set<String>?
) {
    companion object {
        fun parse(json: JSONObject) = TokenResponse(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").ifEmpty { null },
            idToken = json.optString("id_token").ifEmpty { null },
            expiresInSeconds = json.optLong("expires_in", 3600),
            scopes = json.optString("scope").ifEmpty { null }?.split(' ')?.filter { it.isNotEmpty() }?.toSet()
        )
    }
}

data class ModelInfo(val slug: String, val displayName: String)

/**
 * Talks to OpenAI's auth server and API for the ChatGPT plan-usage flow.
 * Never logs or puts tokens in URLs.
 */
class SiwcClient {

    suspend fun exchangeCode(pending: PendingAuthorization, clientId: String, code: String): TokenResponse =
        TokenResponse.parse(
            postForm(
                SiwcConfig.TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to pending.codeVerifier,
                    "redirect_uri" to pending.redirectUri,
                    "resource" to SiwcConfig.RESOURCE
                )
            )
        )

    /** Omits scope so the existing grant is kept, as the docs require. */
    suspend fun refresh(clientId: String, refreshToken: String): TokenResponse =
        TokenResponse.parse(
            postForm(
                SiwcConfig.TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refreshToken,
                    "resource" to SiwcConfig.RESOURCE
                )
            )
        )

    /** Ends the renewable session. An empty 200 means success, even for an already-invalid token. */
    suspend fun revoke(clientId: String, refreshToken: String) {
        postForm(
            SiwcConfig.REVOKE_URL,
            mapOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId),
            expectJson = false
        )
    }

    suspend fun fetchJwks(): JSONObject = withContext(Dispatchers.IO) {
        val conn = open(SiwcConfig.JWKS_URL, "GET")
        readJsonOrThrow(conn)
    }

    /** Models available through the user's plan; only entries meant for listing are kept. */
    suspend fun listModels(accessToken: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val conn = open("${SiwcConfig.API_BASE}/models", "GET").apply {
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        val json = readJsonOrThrow(conn)
        val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val visibility = m.optString("visibility")
            if (visibility.isNotEmpty() && visibility != "list") return@mapNotNull null
            val slug = m.optString("slug").ifEmpty { m.optString("id") }.ifEmpty { return@mapNotNull null }
            ModelInfo(slug, m.optString("display_name").ifEmpty { slug })
        }
    }

    /**
     * Streams one Responses API call and returns the full text. The plan-usage flow
     * requires `store: false` and `stream: true`, and only `response.completed` counts as success.
     */
    suspend fun streamText(
        accessToken: String,
        model: String,
        input: JSONArray,
        instructions: String? = null,
        onDelta: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("store", false)
            .put("stream", true)
        if (instructions != null) body.put("instructions", instructions)

        val conn = open("${SiwcConfig.API_BASE}/responses", "POST").apply {
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            readTimeout = 120_000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) throw errorFrom(conn)

        val text = StringBuilder()
        val parser = SseParser()
        var completed = false
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            while (!completed) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                when (val event = parser.feed(line)) {
                    is StreamEvent.TextDelta -> {
                        text.append(event.text)
                        withContext(Dispatchers.Main) { onDelta(text.toString()) }
                    }
                    StreamEvent.Completed -> completed = true
                    is StreamEvent.Failed -> throw SiwcApiException(200, event.code, event.message)
                    null -> Unit
                }
            }
            if (!completed && parser.flush() == StreamEvent.Completed) completed = true
        }
        conn.disconnect()
        if (!completed) throw SiwcApiException(200, null, "The response ended before it completed.")
        text.toString()
    }

    private suspend fun postForm(url: String, form: Map<String, String>, expectJson: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = open(url, "POST").apply {
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }
            conn.outputStream.use { it.write(formEncode(form).toByteArray(Charsets.UTF_8)) }
            if (expectJson) readJsonOrThrow(conn) else {
                if (conn.responseCode !in 200..299) throw errorFrom(conn)
                conn.disconnect()
                JSONObject()
            }
        }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }

    private fun readJsonOrThrow(conn: HttpURLConnection): JSONObject {
        try {
            if (conn.responseCode !in 200..299) throw errorFrom(conn)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorFrom(conn: HttpURLConnection): SiwcApiException {
        val body = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
        return SiwcApiException.fromBody(conn.responseCode, body)
    }
}

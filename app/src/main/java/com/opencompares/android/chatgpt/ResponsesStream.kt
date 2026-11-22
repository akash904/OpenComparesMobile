package com.opencompares.android.chatgpt

import org.json.JSONObject

/** One meaningful event from a streamed /v1/responses call. */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data object Completed : StreamEvent
    data class Failed(val code: String?, val message: String?) : StreamEvent
}

/**
 * Turns server-sent-event lines into [StreamEvent]s. Feed every line as it arrives;
 * an event is emitted when its terminating blank line is seen.
 */
class SseParser {
    private val data = StringBuilder()

    fun feed(line: String): StreamEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // comment / keep-alive
        if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").removePrefix(" "))
        }
        // "event:" lines repeat the JSON "type" field, so they can be ignored.
        return null
    }

    /** Call at end of stream to emit a final event that had no trailing blank line. */
    fun flush(): StreamEvent? {
        if (data.isEmpty()) return null
        val payload = data.toString()
        data.setLength(0)
        if (payload == "[DONE]") return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return when (json.optString("type")) {
            "response.output_text.delta" -> StreamEvent.TextDelta(json.optString("delta"))
            "response.completed" -> StreamEvent.Completed
            "response.failed" -> {
                val error = json.optJSONObject("response")?.optJSONObject("error")
                StreamEvent.Failed(error?.optString("code")?.ifEmpty { null }, error?.optString("message")?.ifEmpty { null })
            }
            "error" -> StreamEvent.Failed(
                json.optString("code").ifEmpty { null },
                json.optString("message").ifEmpty { null }
            )
            else -> null
        }
    }
}

/** An error from OpenAI's API or auth server, with a message fit to show users. */
class SiwcApiException(
    val httpStatus: Int,
    val code: String?,
    serverMessage: String?
) : Exception(serverMessage ?: "HTTP $httpStatus") {
    val userMessage: String = userMessageFor(httpStatus, code, serverMessage)

    /** True when the saved tokens can't be used any more and the user must sign in again. */
    val requiresSignIn: Boolean
        get() = httpStatus == 401 || code in SIGN_IN_AGAIN_CODES

    companion object {
        private val SIGN_IN_AGAIN_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused", "subscription_sharing_invalid_user"
        )

        /** Parses `{"error": {...}}` (API) or `{"error": "...", "error_description": "..."}` (OAuth). */
        fun fromBody(httpStatus: Int, body: String?): SiwcApiException {
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            val nested = json?.optJSONObject("error")
            return if (nested != null) {
                SiwcApiException(httpStatus, nested.optString("code").ifEmpty { null }, nested.optString("message").ifEmpty { null })
            } else {
                SiwcApiException(
                    httpStatus,
                    json?.optString("error")?.ifEmpty { null },
                    json?.optString("error_description")?.ifEmpty { null }
                )
            }
        }

        fun userMessageFor(httpStatus: Int, code: String?, serverMessage: String?): String = when (code) {
            "subscription_sharing_usage_limit_exceeded" ->
                "Usage limit reached. Review your plan or this app's limit in ChatGPT settings."
            "subscription_sharing_user_not_eligible" ->
                "Your ChatGPT plan can't be used in other apps. A Go, Plus, Pro or Business plan is needed."
            "subscription_sharing_usage_unavailable" ->
                "ChatGPT plan usage is temporarily unavailable. Try again shortly."
            "subscription_sharing_unsupported_capability" ->
                "This request uses a feature that isn't available with ChatGPT plan usage."
            "subscription_sharing_route_not_supported" ->
                "This request isn't supported with ChatGPT plan usage."
            "subscription_sharing_invalid_user", "invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused" ->
                "Your ChatGPT session ended. Please sign in again."
            else -> when (httpStatus) {
                401 -> "Your ChatGPT session ended. Please sign in again."
                429 -> "Too many requests. Try again in a moment."
                in 500..599 -> "OpenAI is having trouble right now. Try again shortly."
                else -> serverMessage ?: "Request failed (HTTP $httpStatus)."
            }
        }
    }
}

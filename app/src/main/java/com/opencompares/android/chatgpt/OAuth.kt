package com.opencompares.android.chatgpt

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Sign in with ChatGPT, plan-usage flow for open-source apps on the user's own device.
 * Protocol: https://developers.openai.com/siwc/token-sharing-open-source/sign-in
 */
object SiwcConfig {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val REVOKE_URL = "https://auth.openai.com/api/accounts/oauth/revoke"
    const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
    const val API_BASE = "https://api.openai.com/v1"

    /** The API the tokens are for; sent on authorize, code exchange and refresh. */
    const val RESOURCE = API_BASE

    /** Used only for a host's first sign-in. Never stored as the issued client ID. */
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"

    /** Shown by OpenAI during registration. Keep it the same across installs. */
    const val AGENT_NAME = "Open Compares"

    const val PLAN_USAGE_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_USAGE_SCOPE"

    /** Only the port may vary between sign-ins; scheme, host and path must stay exactly this. */
    const val CALLBACK_HOST = "127.0.0.1"
    const val CALLBACK_PATH = "/auth/callback"
    const val PREFERRED_PORT = 1455

    fun redirectUri(port: Int) = "http://$CALLBACK_HOST:$port$CALLBACK_PATH"

    /** A stable, opaque per-install host ID in the documented urn:uuid form. */
    fun newHostId(): String = "urn:uuid:${UUID.randomUUID()}"
}

/**
 * Unpadded base64url (RFC 4648 section 5). Hand-written because java.util.Base64 needs
 * API 26 and android.util.Base64 doesn't run in local unit tests.
 */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }

    /** Accepts padded or unpadded input, and standard '+' '/' as well as '-' '_'. */
    fun decode(text: String): ByteArray {
        val clean = text.trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid base64url character" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

object Pkce {
    private val random = SecureRandom()

    /** 64 random bytes give an 86-character verifier, inside RFC 7636's 43..128 range. */
    fun newVerifier(): String = randomUrlSafe(64)

    /** base64url(SHA-256(verifier)) without padding, as the docs require for S256. */
    fun challenge(verifier: String): String =
        Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun randomUrlSafe(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64Url.encode(buf)
    }
}

/** Everything one sign-in attempt must remember between the browser and the code exchange. */
data class PendingAuthorization(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val redirectUri: String,
    /** The issued client ID for a returning host, or null for first-time registration. */
    val knownClientId: String?,
    val hostId: String
) {
    val isRegistration: Boolean get() = knownClientId == null

    companion object {
        fun create(port: Int, knownClientId: String?, hostId: String) = PendingAuthorization(
            state = Pkce.randomUrlSafe(),
            nonce = Pkce.randomUrlSafe(),
            codeVerifier = Pkce.newVerifier(),
            redirectUri = SiwcConfig.redirectUri(port),
            knownClientId = knownClientId,
            hostId = hostId
        )
    }
}

object AuthorizeUrl {
    /**
     * Builds the authorize URL. First-time registration uses the dynamic client and the
     * agent name; returning hosts use their issued client ID and may hint the last account.
     */
    fun build(pending: PendingAuthorization, idTokenHint: String? = null): String {
        val params = linkedMapOf<String, String>()
        params["client_id"] = pending.knownClientId ?: SiwcConfig.DYNAMIC_CLIENT_ID
        if (pending.isRegistration) params["agent_name_hint"] = SiwcConfig.AGENT_NAME
        params["ext_agent_host_id"] = pending.hostId
        params["response_type"] = "code"
        params["redirect_uri"] = pending.redirectUri
        params["scope"] = SiwcConfig.SCOPES
        params["resource"] = SiwcConfig.RESOURCE
        params["state"] = pending.state
        params["nonce"] = pending.nonce
        params["code_challenge_method"] = "S256"
        params["code_challenge"] = Pkce.challenge(pending.codeVerifier)
        if (!pending.isRegistration && idTokenHint != null) params["id_token_hint"] = idTokenHint
        return SiwcConfig.AUTHORIZE_URL + "?" + formEncode(params)
    }
}

fun formEncode(params: Map<String, String>): String =
    params.entries.joinToString("&") { (k, v) ->
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }

fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split("&").filter { it.isNotEmpty() }.associate { pair ->
        val i = pair.indexOf('=')
        val key = if (i < 0) pair else pair.substring(0, i)
        val value = if (i < 0) "" else pair.substring(i + 1)
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
}

/** What the browser brought back to the loopback callback, checked against the pending attempt. */
sealed interface CallbackResult {
    data class Code(val code: String, val clientId: String, val grantedScopes: Set<String>?) : CallbackResult
    data class Denied(val error: String, val description: String?) : CallbackResult
    data class Invalid(val reason: String) : CallbackResult
}

object CallbackValidator {
    /** Applies the documented checks: state first, then OAuth errors, then the client ID rules. */
    fun validate(params: Map<String, String>, pending: PendingAuthorization): CallbackResult {
        val state = params["state"] ?: return CallbackResult.Invalid("The sign-in response had no state.")
        if (state != pending.state) return CallbackResult.Invalid("The sign-in response didn't match this attempt.")

        params["error"]?.let { return CallbackResult.Denied(it, params["error_description"]) }

        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: return CallbackResult.Invalid("The sign-in response had no authorization code.")
        val returnedClientId = params["client_id"]?.takeIf { it.isNotEmpty() }
        val scopes = params["scope"]?.split(' ', '+')?.filter { it.isNotEmpty() }?.toSet()

        val clientId = if (pending.isRegistration) {
            // A new registration must return its issued ID; the dynamic ID is never a valid result.
            if (returnedClientId == null || returnedClientId == SiwcConfig.DYNAMIC_CLIENT_ID) {
                return CallbackResult.Invalid("OpenAI didn't finish registering this app. Please try again.")
            }
            returnedClientId
        } else {
            val known = pending.knownClientId!!
            if (returnedClientId != null && returnedClientId != known) {
                return CallbackResult.Invalid("The sign-in response was for a different app registration.")
            }
            known
        }
        return CallbackResult.Code(code, clientId, scopes)
    }

    fun isCallbackPath(target: String): Boolean =
        runCatching { URI(target).path == SiwcConfig.CALLBACK_PATH }.getOrDefault(false)
}

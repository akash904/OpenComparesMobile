package com.opencompares.android.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

class IdTokenException(message: String) : Exception(message)

/** The verified identity from an ID token. `sub` is the account identity. */
data class VerifiedIdentity(
    val subject: String,
    val email: String?,
    val name: String?,
    val expiresAtSeconds: Long
)

/**
 * Validates an OpenAI ID token as the sign-in docs require: signature against the
 * published JWKS, issuer, audience equal to the issued client ID, expiry, and the nonce
 * saved for this attempt. Only RS256 is accepted, matching the discovery document.
 */
object IdTokenVerifier {

    fun verify(
        idToken: String,
        jwks: JSONObject,
        expectedClientId: String,
        expectedNonce: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        leewaySeconds: Long = 120
    ): VerifiedIdentity {
        val parts = idToken.split('.')
        if (parts.size != 3) throw IdTokenException("ID token is not a JWT")
        val header = decodeJson(parts[0], "header")
        val claims = decodeJson(parts[1], "claims")

        if (header.optString("alg") != "RS256") throw IdTokenException("Unexpected signing algorithm")
        val key = findKey(jwks, header.optString("kid"))
        val signature = try {
            Base64Url.decode(parts[2])
        } catch (_: IllegalArgumentException) {
            throw IdTokenException("ID token signature is malformed")
        }
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        if (!verifier.verify(signature)) throw IdTokenException("ID token signature is invalid")

        if (claims.optString("iss") != SiwcConfig.ISSUER) throw IdTokenException("ID token has the wrong issuer")
        if (expectedClientId !in audiences(claims)) throw IdTokenException("ID token was issued to a different app")
        val exp = claims.optLong("exp", 0)
        if (exp == 0L || exp + leewaySeconds < nowSeconds) throw IdTokenException("ID token has expired")
        if (claims.optString("nonce") != expectedNonce) throw IdTokenException("ID token nonce doesn't match this sign-in")

        val subject = claims.optString("sub").takeIf { it.isNotEmpty() }
            ?: throw IdTokenException("ID token has no subject")
        return VerifiedIdentity(
            subject = subject,
            email = claims.optString("email").takeIf { it.isNotEmpty() },
            name = claims.optString("name").takeIf { it.isNotEmpty() },
            expiresAtSeconds = exp
        )
    }

    private fun decodeJson(segment: String, what: String): JSONObject = try {
        JSONObject(String(Base64Url.decode(segment), Charsets.UTF_8))
    } catch (e: Exception) {
        throw IdTokenException("ID token $what is malformed")
    }

    private fun audiences(claims: JSONObject): Set<String> = when (val aud = claims.opt("aud")) {
        is String -> setOf(aud)
        is JSONArray -> (0 until aud.length()).map { aud.optString(it) }.toSet()
        else -> emptySet()
    }

    private fun findKey(jwks: JSONObject, kid: String): java.security.PublicKey {
        val keys = jwks.optJSONArray("keys") ?: throw IdTokenException("Signing keys are unavailable")
        for (i in 0 until keys.length()) {
            val jwk = keys.optJSONObject(i) ?: continue
            if (jwk.optString("kty") != "RSA") continue
            if (kid.isNotEmpty() && jwk.optString("kid") != kid) continue
            val n = BigInteger(1, Base64Url.decode(jwk.getString("n")))
            val e = BigInteger(1, Base64Url.decode(jwk.getString("e")))
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        }
        throw IdTokenException("No signing key matches this ID token")
    }
}

package com.opencompares.android.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class IdTokenVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKeyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_800_000_000L

    private fun jwk(kp: KeyPair, kid: String): JSONObject {
        val pub = kp.public as RSAPublicKey
        fun unsigned(bytes: ByteArray) = if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        return JSONObject().put("kty", "RSA").put("kid", kid).put("alg", "RS256")
            .put("n", Base64Url.encode(unsigned(pub.modulus.toByteArray())))
            .put("e", Base64Url.encode(unsigned(pub.publicExponent.toByteArray())))
    }

    private val jwks = JSONObject().put("keys", JSONArray().put(jwk(keyPair, "k1")))

    private fun token(
        claims: JSONObject,
        kid: String = "k1",
        alg: String = "RS256",
        signer: KeyPair = keyPair
    ): String {
        val header = Base64Url.encode(JSONObject().put("alg", alg).put("kid", kid).toString().toByteArray())
        val body = Base64Url.encode(claims.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(signer.private)
            update("$header.$body".toByteArray())
        }.sign()
        return "$header.$body.${Base64Url.encode(sig)}"
    }

    private fun claims() = JSONObject()
        .put("iss", "https://auth.openai.com")
        .put("aud", JSONArray().put("oaiapp_1"))
        .put("sub", "user-123")
        .put("email", "a@example.com")
        .put("exp", now + 3600)
        .put("nonce", "n-1")

    private fun verify(t: String) = IdTokenVerifier.verify(t, jwks, "oaiapp_1", "n-1", nowSeconds = now)

    @Test
    fun aValidTokenYieldsTheAccountIdentity() {
        val id = verify(token(claims()))
        assertEquals("user-123", id.subject)
        assertEquals("a@example.com", id.email)
    }

    @Test
    fun aStringAudienceIsAccepted() {
        assertEquals("user-123", verify(token(claims().put("aud", "oaiapp_1"))).subject)
    }

    @Test
    fun aTokenSignedByAnotherKeyIsRejected() {
        assertThrows(IdTokenException::class.java) { verify(token(claims(), signer = otherKeyPair)) }
    }

    @Test
    fun tamperedClaimsAreRejected() {
        val parts = token(claims()).split('.')
        val forged = Base64Url.encode(claims().put("sub", "attacker").toString().toByteArray())
        assertThrows(IdTokenException::class.java) { verify("${parts[0]}.$forged.${parts[2]}") }
    }

    @Test
    fun wrongIssuerAudienceNonceOrExpiryAreRejected() {
        assertThrows(IdTokenException::class.java) { verify(token(claims().put("iss", "https://evil.example"))) }
        assertThrows(IdTokenException::class.java) { verify(token(claims().put("aud", "oaiapp_other"))) }
        assertThrows(IdTokenException::class.java) { verify(token(claims().put("nonce", "replayed"))) }
        assertThrows(IdTokenException::class.java) { verify(token(claims().put("exp", now - 3600))) }
    }

    @Test
    fun onlyRs256WithAKnownKeyIsAccepted() {
        assertThrows(IdTokenException::class.java) { verify(token(claims(), alg = "none")) }
        assertThrows(IdTokenException::class.java) { verify(token(claims(), kid = "unknown")) }
        assertThrows(IdTokenException::class.java) { verify("not-a-jwt") }
    }
}

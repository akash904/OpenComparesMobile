package com.opencompares.android.chatgpt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OAuthTest {

    @Test
    fun pkceChallengeMatchesTheRfc7636TestVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun verifiersAreLongEnoughAndUrlSafe() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun base64UrlRoundTripsEveryLength() {
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            assertArrayEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
        }
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    private val host = "urn:uuid:3f1c9b9e-0000-4000-8000-000000000001"

    @Test
    fun firstSignInUsesTheDynamicClientAndTheAgentName() {
        val pending = PendingAuthorization.create(1455, knownClientId = null, hostId = host)
        val params = parseQuery(URI(AuthorizeUrl.build(pending)).rawQuery)
        assertEquals("dynamic_agent_client", params["client_id"])
        assertEquals("Open Compares", params["agent_name_hint"])
        assertEquals(host, params["ext_agent_host_id"])
        assertEquals("http://127.0.0.1:1455/auth/callback", params["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", params["scope"])
        assertEquals("https://api.openai.com/v1", params["resource"])
        assertEquals("code", params["response_type"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(Pkce.challenge(pending.codeVerifier), params["code_challenge"])
        assertEquals(pending.state, params["state"])
        assertEquals(pending.nonce, params["nonce"])
        assertNull(params["id_token_hint"])
    }

    @Test
    fun returningSignInUsesTheIssuedClientWithoutTheAgentName() {
        val pending = PendingAuthorization.create(54321, knownClientId = "oaiapp_abc", hostId = host)
        val params = parseQuery(URI(AuthorizeUrl.build(pending, idTokenHint = "old.id.token")).rawQuery)
        assertEquals("oaiapp_abc", params["client_id"])
        assertNull(params["agent_name_hint"])
        assertEquals("old.id.token", params["id_token_hint"])
        assertEquals("http://127.0.0.1:54321/auth/callback", params["redirect_uri"])
    }

    @Test
    fun registrationCallbackYieldsTheIssuedClientId() {
        val pending = PendingAuthorization.create(1455, null, host)
        val result = CallbackValidator.validate(
            mapOf("code" to "c1", "state" to pending.state, "client_id" to "oaiapp_new",
                "scope" to "chatgpt.tokens.use.direct email offline_access openid profile resource.invoke"),
            pending
        ) as CallbackResult.Code
        assertEquals("oaiapp_new", result.clientId)
        assertTrue("chatgpt.tokens.use.direct" in result.grantedScopes!!)
    }

    @Test
    fun aWrongStateIsRejectedBeforeAnythingElse() {
        val pending = PendingAuthorization.create(1455, null, host)
        val result = CallbackValidator.validate(mapOf("code" to "c", "state" to "forged", "client_id" to "oaiapp_x"), pending)
        assertTrue(result is CallbackResult.Invalid)
    }

    @Test
    fun deniedSignInIsReportedAndNoCodeIsUsed() {
        val pending = PendingAuthorization.create(1455, null, host)
        val result = CallbackValidator.validate(mapOf("error" to "access_denied", "state" to pending.state), pending)
        assertEquals("access_denied", (result as CallbackResult.Denied).error)
    }

    @Test
    fun registrationWithoutAnIssuedIdIsIncomplete() {
        val pending = PendingAuthorization.create(1455, null, host)
        assertTrue(CallbackValidator.validate(mapOf("code" to "c", "state" to pending.state), pending) is CallbackResult.Invalid)
        assertTrue(
            CallbackValidator.validate(
                mapOf("code" to "c", "state" to pending.state, "client_id" to "dynamic_agent_client"), pending
            ) is CallbackResult.Invalid
        )
    }

    @Test
    fun reauthorizationKeepsTheKnownIdAndRejectsADifferentOne() {
        val pending = PendingAuthorization.create(1455, "oaiapp_known", host)
        val ok = CallbackValidator.validate(mapOf("code" to "c", "state" to pending.state), pending) as CallbackResult.Code
        assertEquals("oaiapp_known", ok.clientId)
        val other = CallbackValidator.validate(
            mapOf("code" to "c", "state" to pending.state, "client_id" to "oaiapp_other"), pending
        )
        assertTrue(other is CallbackResult.Invalid)
    }

    @Test
    fun queryParsingDecodesValues() {
        val q = parseQuery("a=1&scope=openid+email&redirect=http%3A%2F%2F127.0.0.1%3A1455&empty=")
        assertEquals("openid email", q["scope"])
        assertEquals("http://127.0.0.1:1455", q["redirect"])
        assertEquals("", q["empty"])
        assertFalse(q.containsKey("missing"))
    }

    @Test
    fun hostIdsUseTheDocumentedUuidForm() {
        assertTrue(Regex("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(SiwcConfig.newHostId()))
    }
}

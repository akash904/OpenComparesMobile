package com.opencompares.android.chatgpt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** What the app currently knows about the user's ChatGPT connection. */
sealed interface AccountState {
    data object Loading : AccountState
    data class SignedOut(val message: String? = null) : AccountState
    data class SigningIn(val step: String) : AccountState
    data class SignedIn(
        val email: String?,
        val name: String?,
        val hasPlanUsage: Boolean,
        val showWelcome: Boolean
    ) : AccountState
}

/**
 * The user's ChatGPT sign-in for this install: runs the browser flow, keeps tokens fresh,
 * and hands out access tokens to features that call the model.
 *
 * Every request uses this user's own plan, from this device only, for this app's features.
 */
class ChatGptAccount private constructor(private val appContext: Context) {

    private val store = CredentialStore(appContext)
    private val client = SiwcClient()
    private val refreshLock = Mutex()

    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    suspend fun load() {
        val creds = withContext(Dispatchers.IO) { store.load() }
        _state.value = creds?.toState() ?: AccountState.SignedOut()
    }

    /**
     * Signs in through the browser. [openBrowser] must show [url] in a browser tab; the
     * call then waits for OpenAI's redirect to the app's loopback listener.
     */
    suspend fun signIn(openBrowser: (url: String) -> Unit) {
        val existing = withContext(Dispatchers.IO) { store.load() }
        // The host ID must be chosen and saved before the first sign-in and then reused.
        val base = existing ?: StoredCredentials(hostId = SiwcConfig.newHostId())
        if (existing == null) withContext(Dispatchers.IO) { store.save(base) }

        val server = withContext(Dispatchers.IO) { LoopbackCallbackServer.start() }
        val pending = PendingAuthorization.create(server.port, base.clientId, base.hostId)
        SignInKeepAliveService.start(appContext)
        try {
            _state.value = AccountState.SigningIn("Finish signing in in your browser.")
            openBrowser(AuthorizeUrl.build(pending, idTokenHint = base.idToken))

            val returnUri = AuthReturnActivity.returnUri(appContext)
            val params = withTimeout(SIGN_IN_TIMEOUT_MS) {
                server.awaitCallback { LoopbackCallbackServer.callbackPage(it, returnUri) }
            }
            _state.value = AccountState.SigningIn("Finishing sign-in…")

            val result = CallbackValidator.validate(params, pending)
            val callback = when (result) {
                is CallbackResult.Code -> result
                is CallbackResult.Denied -> {
                    _state.value = AccountState.SignedOut("Sign-in was cancelled.")
                    return
                }
                is CallbackResult.Invalid -> throw IllegalStateException(result.reason)
            }

            // Registration is complete once OpenAI issues a client ID; keep it even if later steps fail.
            var creds = base
            if (pending.isRegistration) {
                creds = creds.copy(clientId = callback.clientId)
                withContext(Dispatchers.IO) { store.save(creds) }
            }

            val tokens = client.exchangeCode(pending, callback.clientId, callback.code)
            val idToken = tokens.idToken ?: throw IllegalStateException("OpenAI didn't return an ID token.")
            val identity = IdTokenVerifier.verify(idToken, client.fetchJwks(), callback.clientId, pending.nonce)

            // A registration belongs to one account; never mix another account's tokens into it.
            if (creds.subject != null && creds.subject != identity.subject) {
                tokens.refreshToken?.let { runCatching { client.revoke(callback.clientId, it) } }
                withContext(Dispatchers.IO) { store.save(StoredCredentials(hostId = creds.hostId)) }
                _state.value = AccountState.SignedOut(
                    "That was a different ChatGPT account. Tap Continue with ChatGPT again to connect it."
                )
                return
            }

            val refreshToken = tokens.refreshToken
                ?: throw IllegalStateException("OpenAI didn't grant offline access, so the session can't be kept.")
            val scopes = tokens.scopes ?: callback.grantedScopes ?: emptySet()
            creds = creds.copy(
                clientId = callback.clientId,
                subject = identity.subject,
                email = identity.email,
                name = identity.name,
                idToken = idToken,
                accessToken = tokens.accessToken,
                refreshToken = refreshToken,
                accessExpiresAtMillis = System.currentTimeMillis() + tokens.expiresInSeconds * 1000,
                scopes = scopes
            )
            withContext(Dispatchers.IO) { store.save(creds) }
            _state.value = creds.toState()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _state.value = AccountState.SignedOut("Sign-in timed out. Please try again.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = AccountState.SignedOut()
            throw e
        } catch (e: SiwcApiException) {
            _state.value = AccountState.SignedOut(e.userMessage)
        } catch (e: IdTokenException) {
            _state.value = AccountState.SignedOut("Couldn't verify your ChatGPT sign-in: ${e.message}")
        } catch (e: Exception) {
            _state.value = AccountState.SignedOut(e.message ?: "Sign-in failed. Please try again.")
        } finally {
            server.close()
            SignInKeepAliveService.stop(appContext)
        }
    }

    /** A valid access token, refreshed when it is within a minute of expiring. */
    suspend fun accessToken(): String = refreshLock.withLock {
        val creds = withContext(Dispatchers.IO) { store.load() }
        if (creds == null || !creds.isSignedIn) throw SiwcApiException(401, null, "Not signed in")
        if (creds.accessExpiresAtMillis - System.currentTimeMillis() > 60_000) return creds.accessToken!!

        try {
            val tokens = client.refresh(creds.clientId!!, creds.refreshToken!!)
            // Refresh tokens rotate: always keep the newest one.
            val updated = creds.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: creds.refreshToken,
                idToken = tokens.idToken ?: creds.idToken,
                accessExpiresAtMillis = System.currentTimeMillis() + tokens.expiresInSeconds * 1000,
                scopes = tokens.scopes ?: creds.scopes
            )
            withContext(Dispatchers.IO) { store.save(updated) }
            updated.accessToken!!
        } catch (e: SiwcApiException) {
            if (e.requiresSignIn) {
                withContext(Dispatchers.IO) { store.save(creds.signedOut()) }
                _state.value = AccountState.SignedOut(e.userMessage)
            }
            throw e
        }
    }

    /** Lists the plan's models and streams one short reply, proving the whole chain works. */
    suspend fun testConnection(onDelta: (String) -> Unit): String {
        val token = accessToken()
        val model = chooseModel(token, refresh = true)
        val text = client.streamText(token, model, userInput("Reply with exactly: Open Compares is connected."), onDelta = onDelta)
        return "$model: $text"
    }

    /** Sends one prompt to the plan's model and returns the whole reply. */
    suspend fun ask(instructions: String, prompt: String, onDelta: (String) -> Unit = {}): String {
        val token = accessToken()
        val model = chooseModel(token, refresh = false)
        return client.streamText(token, model, userInput(prompt), instructions, onDelta)
    }

    /** The saved model if the plan still offers it, else the plan's first listed model. */
    private suspend fun chooseModel(token: String, refresh: Boolean): String {
        val creds = withContext(Dispatchers.IO) { store.load() }
        val saved = creds?.preferredModel
        if (!refresh && saved != null) return saved
        val models = client.listModels(token)
        val model = saved?.takeIf { p -> models.any { it.slug == p } }
            ?: models.firstOrNull()?.slug
            ?: throw IllegalStateException("No models are available on this ChatGPT plan.")
        if (creds != null && creds.preferredModel != model) {
            withContext(Dispatchers.IO) { store.save(creds.copy(preferredModel = model)) }
        }
        return model
    }

    private fun userInput(text: String): JSONArray =
        JSONArray().put(JSONObject().put("role", "user").put("content", text))

    suspend fun dismissWelcome() {
        val updated = withContext(Dispatchers.IO) {
            val creds = store.load() ?: return@withContext null
            creds.copy(welcomeShown = true).also { store.save(it) }
        } ?: return
        _state.value = updated.toState()
    }

    /** Revokes the session at OpenAI, then clears tokens. The registration itself is kept for reuse. */
    suspend fun signOut() {
        val creds = withContext(Dispatchers.IO) { store.load() } ?: return
        val clientId = creds.clientId
        val refreshToken = creds.refreshToken
        if (clientId != null && refreshToken != null) {
            runCatching { client.revoke(clientId, refreshToken) }
        }
        withContext(Dispatchers.IO) { store.save(creds.signedOut()) }
        _state.value = AccountState.SignedOut()
    }

    private fun StoredCredentials.toState(): AccountState =
        if (isSignedIn) {
            AccountState.SignedIn(email, name, hasPlanUsage, showWelcome = hasPlanUsage && !welcomeShown)
        } else {
            AccountState.SignedOut()
        }

    companion object {
        private const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L

        @Volatile private var instance: ChatGptAccount? = null

        fun get(context: Context): ChatGptAccount =
            instance ?: synchronized(this) {
                instance ?: ChatGptAccount(context.applicationContext).also { instance = it }
            }
    }
}

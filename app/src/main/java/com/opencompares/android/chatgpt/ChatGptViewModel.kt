package com.opencompares.android.chatgpt

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Result of the "Test connection" check. */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data class Running(val partialText: String) : ConnectionTest
    data class Passed(val reply: String) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

class ChatGptViewModel(application: Application) : AndroidViewModel(application) {

    private val account = ChatGptAccount.get(application)
    val state: StateFlow<AccountState> = account.state

    var connectionTest by mutableStateOf<ConnectionTest>(ConnectionTest.Idle)
        private set

    private var signInJob: Job? = null

    init {
        viewModelScope.launch { account.load() }
    }

    fun signIn(openBrowser: (String) -> Unit) {
        if (signInJob?.isActive == true) return
        connectionTest = ConnectionTest.Idle
        signInJob = viewModelScope.launch { account.signIn(openBrowser) }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
    }

    fun testConnection() {
        if (connectionTest is ConnectionTest.Running) return
        connectionTest = ConnectionTest.Running("")
        viewModelScope.launch {
            connectionTest = try {
                ConnectionTest.Passed(account.testConnection { partial -> connectionTest = ConnectionTest.Running(partial) })
            } catch (e: SiwcApiException) {
                ConnectionTest.Failed(e.userMessage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionTest.Failed(e.message ?: "The test failed.")
            }
        }
    }

    fun dismissWelcome() {
        viewModelScope.launch { account.dismissWelcome() }
    }

    fun signOut() {
        connectionTest = ConnectionTest.Idle
        viewModelScope.launch { account.signOut() }
    }
}

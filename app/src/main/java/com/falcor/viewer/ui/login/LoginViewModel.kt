package com.falcor.viewer.ui.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.R
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.repo.FrigateConnectException
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val isLoading: Boolean = false,
    val restoring: Boolean = false,
    val errorRes: Int? = null,
    val loggedIn: Boolean = false
)

class LoginViewModel(
    private val repository: FrigateRepository,
    private val store: SecureCredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        store.load()?.let { creds ->
            _state.update {
                it.copy(
                    baseUrl = creds.baseUrl,
                    username = creds.username.orEmpty(),
                    password = creds.password.orEmpty(),
                    token = creds.token.orEmpty(),
                    restoring = true
                )
            }
            connect()
        }
    }

    fun onBaseUrl(v: String) = _state.update { it.copy(baseUrl = v, errorRes = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v) }
    fun onPassword(v: String) = _state.update { it.copy(password = v) }
    fun onToken(v: String) = _state.update { it.copy(token = v) }

    fun connect() {
        val s = _state.value
        val url = s.baseUrl.trim().trimEnd('/')
        when {
            url.isEmpty() -> {
                _state.update {
                    it.copy(errorRes = R.string.login_error_empty_url, restoring = false)
                }
                return
            }
            !url.startsWith("http://") &&
                !url.startsWith("https://") &&
                !url.contains("://") -> {
                // Allow host:port — repository will add scheme.
            }
            url.contains("://") &&
                !url.startsWith("http://") &&
                !url.startsWith("https://") -> {
                _state.update {
                    it.copy(errorRes = R.string.login_error_invalid_url, restoring = false)
                }
                return
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorRes = null) }
            val creds = SecureCredentialStore.Credentials(
                baseUrl = url,
                username = s.username.ifBlank { null },
                password = s.password.ifBlank { null },
                token = s.token.ifBlank { null }
            )
            val result = repository.login(creds)
            _state.update {
                if (result.isSuccess) {
                    it.copy(isLoading = false, restoring = false, loggedIn = true)
                } else {
                    val err = result.exceptionOrNull()
                    Log.e(TAG, "Connect failed: ${err?.message}", err)
                    it.copy(
                        isLoading = false,
                        restoring = false,
                        errorRes = mapError(err)
                    )
                }
            }
        }
    }

    private fun mapError(err: Throwable?): Int {
        val kind = (err as? FrigateConnectException)?.kind
            ?: (err?.cause as? FrigateConnectException)?.kind
        return when (kind) {
            FrigateConnectException.Kind.SSL -> R.string.login_error_ssl
            FrigateConnectException.Kind.AUTH -> R.string.login_error_auth
            FrigateConnectException.Kind.NETWORK -> R.string.login_error_network
            FrigateConnectException.Kind.HTTP -> R.string.login_error_http
            FrigateConnectException.Kind.UNKNOWN, null -> R.string.login_error_failed
        }
    }

    companion object {
        private const val TAG = "LoginViewModel"

        fun factory(repo: FrigateRepository, store: SecureCredentialStore) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LoginViewModel(repo, store) as T
            }
    }
}

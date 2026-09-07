package com.falcor.viewer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val cameras: List<CameraUiModel> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val toggling: String? = null,
    val showLogoutConfirm: Boolean = false
)

sealed class HomeUserMessage {
    data class ToggleFailed(val camera: String) : HomeUserMessage()
}

class HomeViewModel(
    private val repository: FrigateRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<HomeUserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<HomeUserMessage> = _messages.asSharedFlow()

    /** Keep Frigate WS alive on the home grid so enable/disable works without opening PTZ. */
    private var wsAcquired = false

    init {
        ensureHomeWs()
        refresh()
    }

    private fun ensureHomeWs() {
        if (!wsAcquired) {
            repository.connectPtzWs()
            wsAcquired = true
        } else {
            repository.ensurePtzWs()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            ensureHomeWs()
            // Always re-scan /api/config (+ talk/live stream map) on home load.
            repository.refreshCapabilities(forceConfig = true)
            val result = repository.getCameras()
            _state.update {
                if (result.isSuccess) {
                    it.copy(cameras = result.getOrDefault(emptyList()), loading = false, error = false)
                } else {
                    it.copy(loading = false, error = true)
                }
            }
        }
    }

    fun toggleCamera(camera: CameraUiModel) {
        viewModelScope.launch {
            val targetEnabled = !camera.enabled
            // Optimistic UI so the Switch feels responsive.
            _state.update { st ->
                st.copy(
                    toggling = camera.name,
                    cameras = st.cameras.map {
                        if (it.name == camera.name) it.copy(enabled = targetEnabled) else it
                    }
                )
            }
            ensureHomeWs()
            val result = repository.setCameraEnabled(camera.name, targetEnabled)
            if (result.isSuccess) {
                // Authoritative refresh from Frigate config/API.
                val cameras = repository.getCameras().getOrNull()
                _state.update { st ->
                    st.copy(
                        toggling = null,
                        cameras = cameras ?: st.cameras
                    )
                }
            } else {
                // Revert optimistic flip + snackbar.
                _state.update { st ->
                    st.copy(
                        toggling = null,
                        cameras = st.cameras.map {
                            if (it.name == camera.name) it.copy(enabled = camera.enabled) else it
                        }
                    )
                }
                _messages.emit(HomeUserMessage.ToggleFailed(camera.name))
            }
        }
    }

    fun showLogout(show: Boolean) = _state.update { it.copy(showLogoutConfirm = show) }

    override fun onCleared() {
        if (wsAcquired) {
            repository.disconnectPtzWs()
            wsAcquired = false
        }
        super.onCleared()
    }

    companion object {
        fun factory(repo: FrigateRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo) as T
        }
    }
}

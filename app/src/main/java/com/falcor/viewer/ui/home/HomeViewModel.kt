package com.falcor.viewer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.prefs.AppPreferences
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val cameras: List<CameraUiModel> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    /** Camera name currently mid enable/disable — Switch stays visible but disabled. */
    val toggling: String? = null,
    val showLogoutConfirm: Boolean = false,
    /** Name of camera being long-press dragged for reorder. */
    val draggingName: String? = null
)

sealed class HomeUserMessage {
    data class ToggleFailed(val camera: String) : HomeUserMessage()
}

class HomeViewModel(
    private val repository: FrigateRepository,
    private val preferences: AppPreferences
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
            // Always re-scan /api/config (+ talk/live/listen map) on home load.
            repository.refreshCapabilities(forceConfig = true)
            val result = repository.getCameras()
            val order = preferences.cameraOrder.first()
            _state.update {
                if (result.isSuccess) {
                    val cams = repository.applyCameraOrder(result.getOrDefault(emptyList()), order)
                    it.copy(cameras = cams, loading = false, error = false)
                } else {
                    it.copy(loading = false, error = true)
                }
            }
        }
    }

    fun toggleCamera(camera: CameraUiModel) {
        // Ignore re-entrant toggles while in-flight for this (or any) camera.
        if (_state.value.toggling != null) return
        viewModelScope.launch {
            val targetEnabled = !camera.enabled
            // Optimistic UI — update only this camera's enabled flag; keep order & previews.
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
                // Soft merge by name — never replace the whole grid (avoids scroll/preview flash).
                val fresh = repository.getCameras().getOrNull()
                _state.update { st ->
                    val merged = if (fresh != null) {
                        repository.mergeCamerasPreservingOrder(st.cameras, fresh)
                    } else {
                        st.cameras
                    }
                    st.copy(toggling = null, cameras = merged)
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

    fun onDragStart(name: String) {
        _state.update { it.copy(draggingName = name) }
    }

    fun onDragEnd() {
        _state.update { it.copy(draggingName = null) }
    }

    /** Move [from] index to [to] and persist the new name order. */
    fun moveCamera(from: Int, to: Int) {
        if (from == to) return
        val list = _state.value.cameras.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        _state.update { it.copy(cameras = list) }
        viewModelScope.launch {
            preferences.saveCameraOrder(list.map { it.name })
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
        fun factory(repo: FrigateRepository, prefs: AppPreferences) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repo, prefs) as T
        }
    }
}

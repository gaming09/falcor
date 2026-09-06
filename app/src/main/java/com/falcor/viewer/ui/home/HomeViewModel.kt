package com.falcor.viewer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class HomeViewModel(
    private val repository: FrigateRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            repository.ensureConfig()
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
            _state.update { it.copy(toggling = camera.name) }
            val result = repository.setCameraEnabled(camera.name, !camera.enabled)
            if (result.isSuccess) {
                _state.update { st ->
                    st.copy(
                        toggling = null,
                        cameras = st.cameras.map {
                            if (it.name == camera.name) it.copy(enabled = !camera.enabled) else it
                        }
                    )
                }
            } else {
                _state.update { it.copy(toggling = null) }
                refresh()
            }
        }
    }

    fun showLogout(show: Boolean) = _state.update { it.copy(showLogoutConfirm = show) }

    companion object {
        fun factory(repo: FrigateRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo) as T
        }
    }
}

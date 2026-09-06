package com.falcor.viewer.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.FrigateEvent
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlertsUiState(
    val events: List<FrigateEvent> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val cameraFilter: String = "",
    val labelFilter: String = "",
    val onlyClips: Boolean = false,
    val onlySnapshots: Boolean = false,
    val selectedEvent: FrigateEvent? = null,
    val detailLoading: Boolean = false
)

class AlertsViewModel(
    private val repository: FrigateRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AlertsUiState())
    val state: StateFlow<AlertsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onCameraFilter(v: String) = _state.update { it.copy(cameraFilter = v) }
    fun onLabelFilter(v: String) = _state.update { it.copy(labelFilter = v) }
    fun onOnlyClips(v: Boolean) = _state.update { it.copy(onlyClips = v) }
    fun onOnlySnapshots(v: Boolean) = _state.update { it.copy(onlySnapshots = v) }

    fun refresh() {
        viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(loading = true, error = false) }
            val result = repository.getEvents(
                camera = s.cameraFilter.ifBlank { null },
                label = s.labelFilter.ifBlank { null },
                hasClip = if (s.onlyClips) true else null,
                hasSnapshot = if (s.onlySnapshots) true else null
            )
            _state.update {
                if (result.isSuccess) {
                    it.copy(events = result.getOrDefault(emptyList()), loading = false)
                } else {
                    it.copy(loading = false, error = true)
                }
            }
        }
    }

    fun loadEvent(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(detailLoading = true) }
            val cached = _state.value.events.find { it.id == id }
            val result = repository.getEvent(id)
            _state.update {
                it.copy(
                    detailLoading = false,
                    selectedEvent = result.getOrNull() ?: cached
                )
            }
        }
    }

    fun snapshotUrl(id: String) = repository.eventSnapshotUrl(id)
    fun thumbnailUrl(id: String) = repository.eventThumbnailUrl(id)
    fun clipUrl(id: String) = repository.eventClipUrl(id)
    fun authHeaders() = repository.authHeaders()
    fun httpClient() = repository.authenticatedHttpClient()

    companion object {
        fun factory(repo: FrigateRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AlertsViewModel(repo) as T
        }
    }
}

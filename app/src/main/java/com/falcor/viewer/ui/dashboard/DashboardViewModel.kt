package com.falcor.viewer.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.prefs.AppPreferences
import com.falcor.viewer.data.prefs.Dashboard
import com.falcor.viewer.data.prefs.DashboardCameraTile
import com.falcor.viewer.data.prefs.TileSpan
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DashboardUiState(
    val dashboards: List<Dashboard> = emptyList(),
    val selectedId: String? = null,
    val cameras: List<CameraUiModel> = emptyList(),
    val editMode: Boolean = false,
    val loading: Boolean = true,
    val showCreateDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val draftName: String = ""
) {
    val selected: Dashboard?
        get() = dashboards.find { it.id == selectedId } ?: dashboards.firstOrNull()
}

class DashboardViewModel(
    private val repository: FrigateRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.dashboards, repository.sessionReady) { boards, _ -> boards }
                .collect { boards ->
                    val cams = repository.getCameras().getOrDefault(emptyList())
                    _state.update {
                        val sel = it.selectedId?.takeIf { id -> boards.any { b -> b.id == id } }
                            ?: boards.firstOrNull()?.id
                        it.copy(
                            dashboards = boards,
                            selectedId = sel,
                            cameras = cams,
                            loading = false
                        )
                    }
                }
        }
        refreshCameras()
    }

    fun refreshCameras() {
        viewModelScope.launch {
            repository.ensureConfig()
            val cams = repository.getCameras().getOrDefault(emptyList())
            _state.update { it.copy(cameras = cams, loading = false) }
        }
    }

    fun selectDashboard(id: String) {
        _state.update { it.copy(selectedId = id) }
    }

    fun setEditMode(edit: Boolean) {
        _state.update { it.copy(editMode = edit) }
    }

    fun showCreate(show: Boolean) {
        _state.update { it.copy(showCreateDialog = show, draftName = if (show) "Dashboard" else "") }
    }

    fun showRename(show: Boolean) {
        val name = _state.value.selected?.name.orEmpty()
        _state.update { it.copy(showRenameDialog = show, draftName = if (show) name else "") }
    }

    fun onDraftName(name: String) {
        _state.update { it.copy(draftName = name) }
    }

    fun createDashboard() {
        viewModelScope.launch {
            val name = _state.value.draftName.ifBlank { "Dashboard" }
            val board = Dashboard(id = UUID.randomUUID().toString(), name = name)
            val next = _state.value.dashboards + board
            preferences.saveDashboards(next)
            _state.update { it.copy(showCreateDialog = false, selectedId = board.id) }
        }
    }

    fun renameDashboard() {
        viewModelScope.launch {
            val id = _state.value.selectedId ?: return@launch
            val name = _state.value.draftName.ifBlank { return@launch }
            val next = _state.value.dashboards.map {
                if (it.id == id) it.copy(name = name) else it
            }
            preferences.saveDashboards(next)
            _state.update { it.copy(showRenameDialog = false) }
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val id = _state.value.selectedId ?: return@launch
            val next = _state.value.dashboards.filterNot { it.id == id }
            preferences.saveDashboards(next)
        }
    }

    fun pinCamera(cameraName: String) {
        viewModelScope.launch {
            val board = _state.value.selected ?: return@launch
            if (board.tiles.any { it.cameraName == cameraName }) return@launch
            val tile = DashboardCameraTile(
                cameraName = cameraName,
                span = TileSpan.SMALL,
                order = board.tiles.size
            )
            val updated = board.copy(tiles = board.tiles + tile)
            saveBoard(updated)
        }
    }

    fun unpinCamera(cameraName: String) {
        viewModelScope.launch {
            val board = _state.value.selected ?: return@launch
            val updated = board.copy(tiles = board.tiles.filterNot { it.cameraName == cameraName })
            saveBoard(updated)
        }
    }

    fun cycleTileSpan(cameraName: String) {
        viewModelScope.launch {
            val board = _state.value.selected ?: return@launch
            val updated = board.copy(
                tiles = board.tiles.map { tile ->
                    if (tile.cameraName != cameraName) tile
                    else tile.copy(
                        span = when (tile.span) {
                            TileSpan.SMALL -> TileSpan.MEDIUM
                            TileSpan.MEDIUM -> TileSpan.LARGE
                            TileSpan.LARGE -> TileSpan.SMALL
                        }
                    )
                }
            )
            saveBoard(updated)
        }
    }

    private suspend fun saveBoard(board: Dashboard) {
        val next = _state.value.dashboards.map { if (it.id == board.id) board else it }
        preferences.saveDashboards(next)
    }

    fun mjpegUrl(camera: String) = repository.mjpegLiveUrl(camera)
    fun snapshotUrl(camera: String) = repository.thumbnailUrl(camera)
    fun httpClient() = repository.authenticatedHttpClient()

    companion object {
        fun factory(repo: FrigateRepository, prefs: AppPreferences) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(repo, prefs) as T
            }
    }
}

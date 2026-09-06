package com.falcor.viewer.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.model.RecordingSegment
import com.falcor.viewer.data.repo.FrigateRepository
import com.falcor.viewer.data.ws.FrigateWsClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class StreamQuality { MAIN, SUB }

data class CameraUiState(
    val cameraName: String,
    val camera: CameraUiModel? = null,
    val loading: Boolean = true,
    val mediaUrl: String? = null,
    val candidateUrls: List<String> = emptyList(),
    val candidateIndex: Int = 0,
    /** When true, LibVLC candidates are exhausted — show OkHttp MJPEG/snapshot preview. */
    val useOkHttpPreview: Boolean = false,
    val quality: StreamQuality = StreamQuality.SUB,
    val isLive: Boolean = true,
    val ptzSupported: Boolean = false,
    val ptzSupportsZoom: Boolean = true,
    val ptzSupportsFocus: Boolean = false,
    val ptzPresets: List<String> = emptyList(),
    val ptzSheetOpen: Boolean = false,
    val talkSupported: Boolean = false,
    val talking: Boolean = false,
    val recordings: List<RecordingSegment> = emptyList(),
    val historyLoading: Boolean = false,
    val historyProgress: Float = 1f,
    val historyWindowStart: Double = 0.0,
    val historyWindowEnd: Double = 0.0,
    val scrubTimestamp: Double? = null,
    val error: Boolean = false
)

sealed class CameraUserMessage {
    data object PtzWsFailed : CameraUserMessage()
}

class CameraViewModel(
    private val repository: FrigateRepository,
    private val cameraName: String
) : ViewModel() {

    private val _state = MutableStateFlow(CameraUiState(cameraName = cameraName))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<CameraUserMessage>(extraBufferCapacity = 1)
    val messages: SharedFlow<CameraUserMessage> = _messages.asSharedFlow()

    private var ptzWsAcquired = false

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            repository.ensureConfig()
            val cameras = repository.getCameras()
            val cam = cameras.getOrNull()?.find { it.name == cameraName }
            val ptzInfo = repository.getPtzInfo(cameraName).getOrNull()
            val configHint = cam?.supportsPtz == true
            val ptzSupported = ptzInfo?.isSupported == true || configHint
            if (cam == null && cameras.isFailure) {
                _state.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val model = cam ?: CameraUiModel(
                name = cameraName,
                enabled = true,
                supportsPtz = ptzSupported,
                supportsAudio = false,
                streamNames = listOf(cameraName),
                thumbnailUrl = repository.thumbnailUrl(cameraName)
            )
            _state.update {
                it.copy(
                    camera = model,
                    loading = false,
                    ptzSupported = ptzSupported,
                    ptzSupportsZoom = ptzInfo?.supportsZoom != false,
                    ptzSupportsFocus = ptzInfo?.supportsFocus == true,
                    ptzPresets = ptzInfo?.presets.orEmpty(),
                    talkSupported = model.supportsAudio
                )
            }
            if (ptzSupported) {
                if (!ptzWsAcquired) {
                    repository.connectPtzWs()
                    ptzWsAcquired = true
                } else {
                    repository.ensurePtzWs()
                }
            }
            applyLiveStream()
            loadHistory()
        }
    }

    fun openPtzSheet() {
        _state.update { it.copy(ptzSheetOpen = true) }
        repository.ensurePtzWs()
    }

    fun closePtzSheet() {
        _state.update { it.copy(ptzSheetOpen = false) }
    }

    fun setQuality(quality: StreamQuality) {
        _state.update { it.copy(quality = quality) }
        if (_state.value.isLive) applyLiveStream()
    }

    private fun applyLiveStream() {
        val cam = _state.value.camera ?: return
        val preferSub = _state.value.quality == StreamQuality.SUB
        val urls = repository.liveStreamUrls(cameraName, preferSub, cam.streamNames)
        _state.update {
            it.copy(
                isLive = true,
                candidateUrls = urls,
                candidateIndex = 0,
                mediaUrl = urls.firstOrNull(),
                useOkHttpPreview = false,
                scrubTimestamp = null,
                historyProgress = 1f
            )
        }
    }

    fun onStreamError() {
        val s = _state.value
        if (s.useOkHttpPreview) return
        val next = s.candidateIndex + 1
        if (next < s.candidateUrls.size) {
            _state.update { it.copy(candidateIndex = next, mediaUrl = s.candidateUrls[next]) }
        } else if (s.isLive) {
            // All LibVLC candidates failed — reliable OkHttp live preview (JWT + trusted TLS).
            _state.update { it.copy(useOkHttpPreview = true, mediaUrl = null) }
        }
    }

    fun mjpegLiveUrl(): String = repository.mjpegLiveUrl(cameraName)

    fun snapshotLiveUrl(): String = repository.thumbnailUrl(cameraName)

    fun httpClient() = repository.authenticatedHttpClient()

    fun jumpToLive() = applyLiveStream()

    fun loadHistory() {
        viewModelScope.launch {
            val end = System.currentTimeMillis() / 1000.0
            val start = end - TimeUnit.HOURS.toSeconds(12)
            _state.update {
                it.copy(
                    historyLoading = true,
                    historyWindowStart = start,
                    historyWindowEnd = end
                )
            }
            val result = repository.getRecordings(cameraName, start, end)
            _state.update {
                it.copy(
                    historyLoading = false,
                    recordings = result.getOrDefault(emptyList())
                )
            }
        }
    }

    fun onHistoryScrub(progress: Float) {
        val s = _state.value
        val start = s.historyWindowStart
        val end = s.historyWindowEnd
        if (end <= start) return
        if (progress >= 0.995f) {
            jumpToLive()
            return
        }
        val ts = start + (end - start) * progress.coerceIn(0f, 1f)
        val clipStart = ts
        val clipEnd = (ts + 60.0).coerceAtMost(end)
        val vod = repository.vodPlaylistUrl(cameraName, clipStart, clipEnd)
        val clip = repository.recordingPlaybackUrl(cameraName, clipStart, clipEnd)
        _state.update {
            it.copy(
                isLive = false,
                useOkHttpPreview = false,
                historyProgress = progress,
                scrubTimestamp = ts,
                candidateUrls = listOf(vod, clip),
                candidateIndex = 0,
                mediaUrl = vod
            )
        }
    }

    fun ptz(command: String) {
        viewModelScope.launch {
            val result = repository.ptz(cameraName, command)
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("WebSocket", ignoreCase = true) ||
                    repository.ptzWsState()?.value == FrigateWsClient.ConnectionState.FAILED
                ) {
                    _messages.emit(CameraUserMessage.PtzWsFailed)
                }
            }
        }
    }

    fun ptzPreset(presetName: String) {
        ptz("preset_$presetName")
    }

    fun setTalking(talking: Boolean) {
        _state.update { it.copy(talking = talking) }
        if (talking) {
            val cam = _state.value.camera ?: return
            val talkName = repository.talkStreamName(cam.streamNames, cameraName) ?: cameraName
            val talkUrl = repository.rtspUrlForStream(talkName)
            if (talkUrl != null) {
                _state.update {
                    it.copy(
                        mediaUrl = talkUrl,
                        isLive = true,
                        useOkHttpPreview = false,
                        candidateUrls = listOf(talkUrl),
                        candidateIndex = 0
                    )
                }
            } else {
                // No RTSP in config — keep HTTPS live candidates / OkHttp preview
                applyLiveStream()
                _state.update { it.copy(talking = true) }
            }
        } else if (_state.value.isLive) {
            applyLiveStream()
        }
    }

    fun authHeaders(): Map<String, String> = repository.authHeaders()

    override fun onCleared() {
        if (ptzWsAcquired) {
            repository.disconnectPtzWs()
            ptzWsAcquired = false
        }
        super.onCleared()
    }

    companion object {
        fun factory(repo: FrigateRepository, cameraName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CameraViewModel(repo, cameraName) as T
            }
    }
}

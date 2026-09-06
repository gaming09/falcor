package com.falcor.viewer.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraCapabilities
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.model.DetectionBox
import com.falcor.viewer.data.model.RecordingSegment
import com.falcor.viewer.data.model.toUi
import com.falcor.viewer.data.prefs.AppPreferences
import com.falcor.viewer.data.repo.FrigateRepository
import com.falcor.viewer.data.ws.FrigateWsClient
import kotlinx.coroutines.Job
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
    val capabilities: CameraCapabilities? = null,
    val loading: Boolean = true,
    val mediaUrl: String? = null,
    val candidateUrls: List<String> = emptyList(),
    val candidateIndex: Int = 0,
    /** LibVLC exhausted — OkHttp MJPEG/snapshot (last resort). */
    val useOkHttpPreview: Boolean = false,
    /** Prefer Frigate WebView live (MSE/WebRTC). */
    val useWebViewLive: Boolean = true,
    val livePageUrls: List<String> = emptyList(),
    val authenticatedClipUrl: String? = null,
    val quality: StreamQuality = StreamQuality.SUB,
    val isLive: Boolean = true,
    val ptzSupported: Boolean = false,
    val ptzSupportsZoom: Boolean = true,
    val ptzSupportsFocus: Boolean = false,
    val ptzPresets: List<String> = emptyList(),
    val ptzSheetOpen: Boolean = false,
    val talkSupported: Boolean = false,
    val talking: Boolean = false,
    val talkWebRtcOpen: Boolean = false,
    val talkWebRtcUrl: String? = null,
    val talkWebRtcCandidates: List<String> = emptyList(),
    val talkWebRtcIndex: Int = 0,
    val recordings: List<RecordingSegment> = emptyList(),
    val historyLoading: Boolean = false,
    val historyProgress: Float = 1f,
    val historyWindowStart: Double = 0.0,
    val historyWindowEnd: Double = 0.0,
    val scrubTimestamp: Double? = null,
    val fullscreen: Boolean = false,
    val showDetections: Boolean = false,
    val detectionBoxes: List<DetectionBox> = emptyList(),
    val error: Boolean = false
)

sealed class CameraUserMessage {
    data object PtzWsFailed : CameraUserMessage()
    data object PtzCommandFailed : CameraUserMessage()
    data object TalkWebRtcFailed : CameraUserMessage()
    data object HistoryNotFound : CameraUserMessage()
    data object HistoryDownloadFailed : CameraUserMessage()
    data class ClipSaved(val name: String) : CameraUserMessage()
    data class ClipSaveFailed(val message: String) : CameraUserMessage()
    data object CastFailed : CameraUserMessage()
    data object CastStarted : CameraUserMessage()
}

class CameraViewModel(
    private val repository: FrigateRepository,
    private val preferences: AppPreferences,
    private val cameraName: String
) : ViewModel() {

    private val _state = MutableStateFlow(CameraUiState(cameraName = cameraName))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<CameraUserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<CameraUserMessage> = _messages.asSharedFlow()

    private var ptzWsAcquired = false
    private var scrubJob: Job? = null
    private var detectionsJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.showDetections.collect { show ->
                _state.update { it.copy(showDetections = show) }
                if (show) ensureDetectionsSubscription()
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            repository.ensureConfig()
            val capsResult = repository.getCameraCapabilities(cameraName)
            val caps = capsResult.getOrNull()
            val cameras = repository.getCameras()
            val cam = cameras.getOrNull()?.find { it.name == cameraName }
                ?: caps?.toUi()

            val ptzSupported = caps?.showPtz == true || cam?.supportsPtz == true
            if (cam == null && cameras.isFailure && caps == null) {
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
                    capabilities = caps,
                    loading = false,
                    ptzSupported = ptzSupported,
                    ptzSupportsZoom = caps?.supportsZoom != false,
                    ptzSupportsFocus = caps?.supportsFocus == true,
                    ptzPresets = caps?.ptzPresets.orEmpty(),
                    talkSupported = caps?.showTalk == true || model.supportsAudio
                )
            }
            connectWsIfNeeded(ptzSupported || _state.value.showDetections)
            applyLiveStream()
            loadHistory()
        }
    }

    private fun connectWsIfNeeded(needed: Boolean) {
        if (!needed) return
        if (!ptzWsAcquired) {
            repository.connectPtzWs()
            ptzWsAcquired = true
        } else {
            repository.ensurePtzWs()
        }
        ensureDetectionsSubscription()
    }

    private fun ensureDetectionsSubscription() {
        if (detectionsJob?.isActive == true) return
        repository.ensurePtzWs()
        if (!ptzWsAcquired) {
            repository.connectPtzWs()
            ptzWsAcquired = true
        }
        detectionsJob = viewModelScope.launch {
            val flow = repository.detectionsFlow() ?: return@launch
            flow.collect { event ->
                if (event.camera.equals(cameraName, ignoreCase = true)) {
                    _state.update { it.copy(detectionBoxes = event.boxes) }
                }
            }
        }
    }

    fun setShowDetections(show: Boolean) {
        viewModelScope.launch {
            preferences.setShowDetections(show)
            if (show) {
                connectWsIfNeeded(true)
            } else {
                _state.update { it.copy(detectionBoxes = emptyList()) }
            }
        }
    }

    fun setFullscreen(open: Boolean) {
        _state.update { it.copy(fullscreen = open) }
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
        val pages = repository.livePlayerPageUrls(cameraName, preferSub, cam.streamNames)
        _state.update {
            it.copy(
                isLive = true,
                talking = false,
                talkWebRtcOpen = false,
                authenticatedClipUrl = null,
                candidateUrls = urls,
                candidateIndex = 0,
                mediaUrl = urls.firstOrNull(),
                useOkHttpPreview = false,
                useWebViewLive = true,
                livePageUrls = pages,
                scrubTimestamp = null,
                historyProgress = 1f,
                detectionBoxes = if (it.showDetections) it.detectionBoxes else emptyList()
            )
        }
    }

    fun onWebViewLiveFailed() {
        val s = _state.value
        if (!s.isLive || s.authenticatedClipUrl != null) return
        // Try LibVLC candidates next
        _state.update {
            it.copy(
                useWebViewLive = false,
                useOkHttpPreview = false,
                mediaUrl = it.candidateUrls.firstOrNull(),
                candidateIndex = 0
            )
        }
    }

    fun onStreamError() {
        val s = _state.value
        if (s.useOkHttpPreview || s.authenticatedClipUrl != null || s.useWebViewLive) return
        val next = s.candidateIndex + 1
        if (next < s.candidateUrls.size) {
            _state.update { it.copy(candidateIndex = next, mediaUrl = s.candidateUrls[next]) }
        } else if (s.isLive) {
            _state.update { it.copy(useOkHttpPreview = true, mediaUrl = null) }
        }
    }

    fun mjpegLiveUrl(): String = repository.mjpegLiveUrl(cameraName)

    fun snapshotLiveUrl(): String = repository.thumbnailUrl(cameraName)

    fun castStreamUrl(): String {
        val preferSub = _state.value.quality == StreamQuality.SUB
        return repository.castableStreamUrl(cameraName, preferSub)
    }

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
        val clip = repository.recordingPlaybackUrl(cameraName, clipStart, clipEnd)
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLive = false,
                    useOkHttpPreview = false,
                    useWebViewLive = false,
                    talking = false,
                    talkWebRtcOpen = false,
                    historyProgress = progress,
                    scrubTimestamp = ts,
                    mediaUrl = null,
                    candidateUrls = emptyList(),
                    candidateIndex = 0,
                    authenticatedClipUrl = clip,
                    fullscreen = false
                )
            }
        }
    }

    fun onHistoryPlayError(kind: String) {
        viewModelScope.launch {
            when {
                kind.equals("not_found", true) -> _messages.emit(CameraUserMessage.HistoryNotFound)
                else -> _messages.emit(CameraUserMessage.HistoryDownloadFailed)
            }
        }
    }

    fun onDownloadResult(result: String) {
        viewModelScope.launch {
            if (result.startsWith("error:")) {
                _messages.emit(CameraUserMessage.ClipSaveFailed(result.removePrefix("error:")))
            } else {
                _messages.emit(CameraUserMessage.ClipSaved(result))
            }
        }
    }

    fun notifyCast(ok: Boolean) {
        viewModelScope.launch {
            _messages.emit(if (ok) CameraUserMessage.CastStarted else CameraUserMessage.CastFailed)
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
                } else {
                    _messages.emit(CameraUserMessage.PtzCommandFailed)
                }
            }
        }
    }

    fun ptzPreset(presetName: String) {
        ptz("preset_$presetName")
    }

    fun setTalking(talking: Boolean) {
        if (!talking) {
            _state.update {
                it.copy(talking = false, talkWebRtcOpen = false, talkWebRtcUrl = null)
            }
            if (_state.value.isLive) applyLiveStream()
            return
        }
        val cam = _state.value.camera
        val caps = _state.value.capabilities
        val talkName = caps?.talkStreamName
            ?: repository.talkStreamName(cam?.streamNames.orEmpty(), cameraName)
            ?: cameraName
        val candidates = repository.webrtcTalkPageUrls(cameraName, talkName)
        _state.update {
            it.copy(
                talking = true,
                talkWebRtcOpen = true,
                talkWebRtcCandidates = candidates,
                talkWebRtcIndex = 0,
                talkWebRtcUrl = candidates.firstOrNull()
            )
        }
    }

    fun onTalkWebRtcFailed() {
        val s = _state.value
        val next = s.talkWebRtcIndex + 1
        if (next < s.talkWebRtcCandidates.size) {
            _state.update {
                it.copy(
                    talkWebRtcIndex = next,
                    talkWebRtcUrl = s.talkWebRtcCandidates[next]
                )
            }
        } else {
            viewModelScope.launch { _messages.emit(CameraUserMessage.TalkWebRtcFailed) }
            _state.update {
                it.copy(talkWebRtcOpen = false, talking = false, talkWebRtcUrl = null)
            }
        }
    }

    fun closeTalkWebRtc() {
        _state.update {
            it.copy(talking = false, talkWebRtcOpen = false, talkWebRtcUrl = null)
        }
    }

    fun authHeaders(): Map<String, String> = repository.authHeaders()

    fun jwtTokenRaw(): String? = repository.jwtTokenRaw()

    override fun onCleared() {
        scrubJob?.cancel()
        detectionsJob?.cancel()
        if (ptzWsAcquired) {
            repository.disconnectPtzWs()
            ptzWsAcquired = false
        }
        super.onCleared()
    }

    companion object {
        fun factory(repo: FrigateRepository, prefs: AppPreferences, cameraName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CameraViewModel(repo, prefs, cameraName) as T
            }
    }
}

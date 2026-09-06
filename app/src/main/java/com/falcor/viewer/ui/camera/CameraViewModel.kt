package com.falcor.viewer.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.data.model.RecordingSegment
import com.falcor.viewer.data.repo.FrigateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val quality: StreamQuality = StreamQuality.SUB,
    val isLive: Boolean = true,
    val ptzSupported: Boolean = false,
    val talkSupported: Boolean = false,
    val talking: Boolean = false,
    val recordings: List<RecordingSegment> = emptyList(),
    val historyLoading: Boolean = false,
    val historyProgress: Float = 1f, // 1f = live end
    val historyWindowStart: Double = 0.0,
    val historyWindowEnd: Double = 0.0,
    val scrubTimestamp: Double? = null,
    val error: Boolean = false
)

class CameraViewModel(
    private val repository: FrigateRepository,
    private val cameraName: String
) : ViewModel() {

    private val _state = MutableStateFlow(CameraUiState(cameraName = cameraName))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            val cameras = repository.getCameras()
            val cam = cameras.getOrNull()?.find { it.name == cameraName }
            val ptz = repository.getPtzInfo(cameraName).getOrNull()?.isSupported == true ||
                cam?.supportsPtz == true
            if (cam == null && cameras.isFailure) {
                _state.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val model = cam ?: CameraUiModel(
                name = cameraName,
                enabled = true,
                supportsPtz = ptz,
                supportsAudio = false,
                streamNames = listOf(cameraName),
                thumbnailUrl = repository.thumbnailUrl(cameraName)
            )
            _state.update {
                it.copy(
                    camera = model,
                    loading = false,
                    ptzSupported = ptz,
                    talkSupported = model.supportsAudio
                )
            }
            applyLiveStream()
            loadHistory()
        }
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
                scrubTimestamp = null,
                historyProgress = 1f
            )
        }
    }

    fun onStreamError() {
        val s = _state.value
        val next = s.candidateIndex + 1
        if (next < s.candidateUrls.size) {
            _state.update { it.copy(candidateIndex = next, mediaUrl = s.candidateUrls[next]) }
        }
    }

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

    /**
     * Scrub history slider: progress 0..1 maps across the loaded recording window.
     * Seeks VLC by swapping media to a Frigate clip/VOD URL around that timestamp.
     */
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
            repository.ptz(cameraName, command)
        }
    }

    fun setTalking(talking: Boolean) {
        _state.update { it.copy(talking = talking) }
        // Two-way audio over RTSP backchannel / go2rtc typically needs WebRTC.
        // Falcor enables mic capture UI; LibVLC talk-back is best-effort via unmute + backchannel stream.
        if (talking) {
            val cam = _state.value.camera ?: return
            val talkName = repository.talkStreamName(cam.streamNames, cameraName) ?: cameraName
            val host = runCatching { java.net.URI(repository.baseUrl).host }.getOrNull() ?: return
            val talkUrl = "rtsp://$host:8554/$talkName"
            _state.update { it.copy(mediaUrl = talkUrl, isLive = true) }
        } else if (_state.value.isLive) {
            applyLiveStream()
        }
    }

    fun authHeaders(): Map<String, String> = repository.authHeaders()

    companion object {
        fun factory(repo: FrigateRepository, cameraName: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CameraViewModel(repo, cameraName) as T
            }
    }
}

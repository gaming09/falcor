package com.falcor.viewer.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.falcor.viewer.R
import com.falcor.viewer.player.AuthenticatedClipPlayer
import com.falcor.viewer.player.OkHttpLivePreview
import com.falcor.viewer.player.TalkWebRtcDialog
import com.falcor.viewer.player.VlcPlayer
import com.falcor.viewer.ui.components.ErrorRetry
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val ptzWsError = stringResource(R.string.camera_ptz_ws_error)
    val ptzCmdError = stringResource(R.string.camera_ptz_command_error)
    val talkWebRtcError = stringResource(R.string.camera_talk_webrtc_failed)
    val historyNotFound = stringResource(R.string.media_not_found)
    val historyFailed = stringResource(R.string.media_download_failed)
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setTalking(true)
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            when (msg) {
                CameraUserMessage.PtzWsFailed -> snackbarHostState.showSnackbar(ptzWsError)
                CameraUserMessage.PtzCommandFailed -> snackbarHostState.showSnackbar(ptzCmdError)
                CameraUserMessage.TalkWebRtcFailed -> snackbarHostState.showSnackbar(talkWebRtcError)
                CameraUserMessage.HistoryNotFound -> snackbarHostState.showSnackbar(historyNotFound)
                CameraUserMessage.HistoryDownloadFailed -> snackbarHostState.showSnackbar(historyFailed)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.cameraName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.camera_back)
                        )
                    }
                },
                actions = {
                    if (state.ptzSupported) {
                        IconButton(onClick = viewModel::openPtzSheet) {
                            Icon(
                                Icons.Default.ControlCamera,
                                contentDescription = stringResource(R.string.camera_ptz_open)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error -> {
                ErrorRetry(
                    message = stringResource(R.string.error_generic),
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        !state.authenticatedClipUrl.isNullOrBlank() -> {
                            AuthenticatedClipPlayer(
                                remoteUrl = state.authenticatedClipUrl,
                                okHttpClient = viewModel.httpClient(),
                                mute = false,
                                onError = viewModel::onHistoryPlayError,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        state.useOkHttpPreview && state.isLive -> {
                            OkHttpLivePreview(
                                mjpegUrl = viewModel.mjpegLiveUrl(),
                                snapshotUrl = viewModel.snapshotLiveUrl(),
                                okHttpClient = viewModel.httpClient(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            VlcPlayer(
                                mediaUrl = state.mediaUrl,
                                headers = viewModel.authHeaders(),
                                mute = true,
                                onError = { viewModel.onStreamError() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.isLive,
                            onClick = viewModel::jumpToLive,
                            label = { Text(stringResource(R.string.camera_live)) }
                        )
                        FilterChip(
                            selected = state.quality == StreamQuality.MAIN,
                            onClick = { viewModel.setQuality(StreamQuality.MAIN) },
                            label = { Text(stringResource(R.string.camera_stream_main)) }
                        )
                        FilterChip(
                            selected = state.quality == StreamQuality.SUB,
                            onClick = { viewModel.setQuality(StreamQuality.SUB) },
                            label = { Text(stringResource(R.string.camera_stream_sub)) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.talkSupported) {
                            AssistChip(
                                onClick = {
                                    if (state.talking) {
                                        viewModel.setTalking(false)
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) viewModel.setTalking(true)
                                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            if (state.talking) R.string.camera_talk_on else R.string.camera_talk
                                        )
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (state.talking) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null
                                    )
                                }
                            )
                        } else {
                            Text(
                                stringResource(R.string.camera_talk_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.ptzSupported) {
                            AssistChip(
                                onClick = viewModel::openPtzSheet,
                                label = { Text(stringResource(R.string.camera_ptz)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ControlCamera,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.camera_history),
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.historyLoading) {
                        Text(
                            stringResource(R.string.camera_history_loading),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (state.recordings.isEmpty()) {
                        Text(
                            stringResource(R.string.camera_history_empty),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = state.historyProgress,
                        onValueChange = viewModel::onHistoryScrub,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                    val label = state.scrubTimestamp?.let {
                        DateFormat.getDateTimeInstance().format(Date((it * 1000).toLong()))
                    } ?: stringResource(R.string.camera_live)
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AssistChip(
                        onClick = viewModel::jumpToLive,
                        label = { Text(stringResource(R.string.camera_history_live)) },
                        modifier = Modifier.padding(16.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (state.ptzSheetOpen && state.ptzSupported) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closePtzSheet,
            sheetState = sheetState
        ) {
            PtzControlSheet(
                supportsZoom = state.ptzSupportsZoom,
                supportsFocus = state.ptzSupportsFocus,
                presets = state.ptzPresets,
                onCommand = viewModel::ptz,
                onPreset = viewModel::ptzPreset,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    if (state.talkWebRtcOpen && !state.talkWebRtcUrl.isNullOrBlank()) {
        TalkWebRtcDialog(
            pageUrl = state.talkWebRtcUrl!!,
            bearerToken = viewModel.jwtTokenRaw(),
            onDismiss = viewModel::closeTalkWebRtc,
            onLoadFailed = viewModel::onTalkWebRtcFailed
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PtzControlSheet(
    supportsZoom: Boolean,
    supportsFocus: Boolean,
    presets: List<String>,
    onCommand: (String) -> Unit,
    onPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.camera_ptz_sheet_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            stringResource(R.string.camera_ptz_hold_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // D-pad: Up / Left-Stop-Right / Down
        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.camera_ptz_up),
            moveCommand = "MOVE_UP",
            onCommand = onCommand
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            HoldPtzButton(
                icon = Icons.Default.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.camera_ptz_left),
                moveCommand = "MOVE_LEFT",
                onCommand = onCommand
            )
            // Explicit Stop (also sent on release)
            PtzTapButton(
                icon = Icons.Default.Stop,
                contentDescription = stringResource(R.string.camera_ptz_stop),
                onClick = { onCommand("STOP") }
            )
            HoldPtzButton(
                icon = Icons.Default.KeyboardArrowRight,
                contentDescription = stringResource(R.string.camera_ptz_right),
                moveCommand = "MOVE_RIGHT",
                onCommand = onCommand
            )
        }
        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.camera_ptz_down),
            moveCommand = "MOVE_DOWN",
            onCommand = onCommand
        )

        if (supportsZoom) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.camera_ptz_zoom),
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                HoldPtzButton(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.camera_ptz_zoom_in),
                    moveCommand = "ZOOM_IN",
                    onCommand = onCommand,
                    size = 56.dp
                )
                HoldPtzButton(
                    icon = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.camera_ptz_zoom_out),
                    moveCommand = "ZOOM_OUT",
                    onCommand = onCommand,
                    size = 56.dp
                )
            }
        }

        if (supportsFocus) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.camera_ptz_focus),
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                HoldPtzButton(
                    icon = Icons.Default.CenterFocusStrong,
                    contentDescription = stringResource(R.string.camera_ptz_focus_in),
                    moveCommand = "FOCUS_IN",
                    onCommand = onCommand,
                    size = 56.dp
                )
                HoldPtzButton(
                    icon = Icons.Default.CenterFocusWeak,
                    contentDescription = stringResource(R.string.camera_ptz_focus_out),
                    moveCommand = "FOCUS_OUT",
                    onCommand = onCommand,
                    size = 56.dp
                )
            }
        }

        if (presets.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.camera_ptz_presets),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                presets.forEach { name ->
                    AssistChip(
                        onClick = { onPreset(name) },
                        label = { Text(name) }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Press-and-hold PTZ control: onPress starts MOVE_/ZOOM_/FOCUS_, onRelease sends STOP.
 * Matches Frigate web UI behavior.
 */
@Composable
private fun HoldPtzButton(
    icon: ImageVector,
    contentDescription: String,
    moveCommand: String,
    onCommand: (String) -> Unit,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(moveCommand) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onCommand(moveCommand)
                    waitForUpOrCancellation()
                    onCommand("STOP")
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
private fun PtzTapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.45f)
        )
    }
}

package com.falcor.viewer.ui.camera

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.falcor.viewer.R
import com.falcor.viewer.cast.CastHelper
import com.falcor.viewer.player.AuthenticatedClipPlayer
import com.falcor.viewer.player.FrigateLiveWebView
import com.falcor.viewer.player.OkHttpLivePreview
import com.falcor.viewer.player.TalkWebRtcDialog
import com.falcor.viewer.player.VlcPlayer
import com.falcor.viewer.ui.components.ErrorRetry
import com.falcor.viewer.ui.player.DetectionOverlay
import com.falcor.viewer.ui.player.ZoomableBox
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
    val activity = context as? Activity
    val snackbarHostState = remember { SnackbarHostState() }
    val ptzWsError = stringResource(R.string.camera_ptz_ws_error)
    val ptzCmdError = stringResource(R.string.camera_ptz_command_error)
    val talkWebRtcError = stringResource(R.string.camera_talk_webrtc_failed)
    val historyNotFound = stringResource(R.string.media_not_found)
    val historyFailed = stringResource(R.string.media_download_failed)
    val castFailed = stringResource(R.string.cast_failed)
    val castStarted = stringResource(R.string.cast_started)
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setTalking(true)
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            when (msg) {
                CameraUserMessage.PtzWsFailed -> snackbarHostState.showSnackbar(ptzWsError)
                CameraUserMessage.PtzCommandFailed -> snackbarHostState.showSnackbar(ptzCmdError)
                CameraUserMessage.TalkWebRtcFailed -> snackbarHostState.showSnackbar(talkWebRtcError)
                CameraUserMessage.HistoryNotFound -> snackbarHostState.showSnackbar(historyNotFound)
                CameraUserMessage.HistoryDownloadFailed -> snackbarHostState.showSnackbar(historyFailed)
                is CameraUserMessage.ClipSaved ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.media_saved, msg.name)
                    )
                is CameraUserMessage.ClipSaveFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.media_save_failed, msg.message)
                    )
                CameraUserMessage.CastFailed -> snackbarHostState.showSnackbar(castFailed)
                CameraUserMessage.CastStarted -> snackbarHostState.showSnackbar(castStarted)
            }
        }
    }

    // Landscape: allow rotation; scale-to-fit handled in player ContentScale.Fit / object-fit contain
    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose {
            if (prev != null) activity.requestedOrientation = prev
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
                    IconButton(onClick = { viewModel.setShowDetections(!state.showDetections) }) {
                        Icon(
                            if (state.showDetections) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(R.string.camera_toggle_detections)
                        )
                    }
                    IconButton(onClick = {
                        val ok = CastHelper.castStream(
                            context,
                            viewModel.castStreamUrl(),
                            state.cameraName,
                            if (viewModel.castStreamUrl().contains("m3u8", true))
                                "application/x-mpegURL" else "video/x-motion-jpeg"
                        )
                        viewModel.notifyCast(ok)
                    }) {
                        Icon(Icons.Default.Cast, contentDescription = stringResource(R.string.cast_camera))
                    }
                    if (state.ptzSupported) {
                        IconButton(onClick = viewModel::openPtzSheet) {
                            Icon(
                                Icons.Default.ControlCamera,
                                contentDescription = stringResource(R.string.camera_ptz_open)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.setFullscreen(true) }) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.camera_fullscreen)
                        )
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
                    LiveOrClipSurface(
                        state = state,
                        viewModel = viewModel,
                        isLandscape = isLandscape,
                        modifier = Modifier.fillMaxWidth()
                    )

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
                                    Icon(Icons.Default.ControlCamera, contentDescription = null)
                                }
                            )
                        }
                        AssistChip(
                            onClick = { viewModel.setShowDetections(!state.showDetections) },
                            label = {
                                Text(
                                    stringResource(
                                        if (state.showDetections) R.string.camera_detections_on
                                        else R.string.camera_detections_off
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (state.showDetections) Icons.Default.Visibility
                                    else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        )
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

    if (state.fullscreen) {
        FullscreenLiveDialog(state = state, viewModel = viewModel)
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

@Composable
private fun LiveOrClipSurface(
    state: CameraUiState,
    viewModel: CameraViewModel,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    fill: Boolean = false
) {
    val aspectMod = if (fill) {
        modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxWidth()
            .then(if (isLandscape) Modifier.height(220.dp) else Modifier.aspectRatio(16f / 9f))
    }
    Box(modifier = aspectMod.background(Color.Black)) {
        ZoomableBox(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                when {
                    !state.authenticatedClipUrl.isNullOrBlank() -> {
                        AuthenticatedClipPlayer(
                            remoteUrl = state.authenticatedClipUrl,
                            okHttpClient = viewModel.httpClient(),
                            mute = false,
                            downloadFileName = "${state.cameraName}_${state.scrubTimestamp?.toLong() ?: 0}.mp4",
                            onError = viewModel::onHistoryPlayError,
                            onDownloadResult = viewModel::onDownloadResult,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    state.useWebViewLive && state.isLive && state.livePageUrls.isNotEmpty() -> {
                        FrigateLiveWebView(
                            pageUrls = state.livePageUrls,
                            bearerToken = viewModel.jwtTokenRaw(),
                            fillAspect = false,
                            showDetections = state.showDetections,
                            onAllFailed = viewModel::onWebViewLiveFailed,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    state.useOkHttpPreview && state.isLive -> {
                        OkHttpLivePreview(
                            mjpegUrl = viewModel.mjpegLiveUrl(),
                            snapshotUrl = viewModel.snapshotLiveUrl(),
                            okHttpClient = viewModel.httpClient(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        VlcPlayer(
                            mediaUrl = state.mediaUrl,
                            headers = viewModel.authHeaders(),
                            mute = true,
                            onError = { viewModel.onStreamError() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (state.showDetections && state.isLive && state.authenticatedClipUrl == null) {
                    DetectionOverlay(boxes = state.detectionBoxes)
                }
            }
        }
    }
}

@Composable
private fun FullscreenLiveDialog(
    state: CameraUiState,
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val activity = context as? Activity
    BackHandler { viewModel.setFullscreen(false) }

    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val prevOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) WindowCompat.setDecorFitsSystemWindows(window, true)
            if (prevOrientation != null) activity?.requestedOrientation = prevOrientation
        }
    }

    Dialog(
        onDismissRequest = { viewModel.setFullscreen(false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            LiveOrClipSurface(
                state = state,
                viewModel = viewModel,
                isLandscape = true,
                fill = true,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = { viewModel.setFullscreen(false) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = stringResource(R.string.camera_fullscreen_exit),
                    tint = Color.White
                )
            }
        }
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
            Text(stringResource(R.string.camera_ptz_zoom), style = MaterialTheme.typography.titleSmall)
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
            Text(stringResource(R.string.camera_ptz_focus), style = MaterialTheme.typography.titleSmall)
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
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                presets.forEach { name ->
                    AssistChip(onClick = { onPreset(name) }, label = { Text(name) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

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

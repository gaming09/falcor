@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.falcor.viewer.ui.camera

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.heightIn
import com.falcor.viewer.cast.CastOutcome
import com.falcor.viewer.player.WebViewAudioController
import com.falcor.viewer.player.OkHttpLivePreview
import com.falcor.viewer.player.VlcPlayer
import com.falcor.viewer.ui.components.ErrorRetry
import com.falcor.viewer.ui.player.DetectionOverlay
import com.falcor.viewer.ui.player.ZoomableBox

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
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
    val castNoDevice = stringResource(R.string.cast_no_device)
    val castAuthWarning = stringResource(R.string.cast_auth_url_warning)
    val audioController = remember { WebViewAudioController() }
    var pendingTalkAfterPermission by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingTalkAfterPermission) {
            viewModel.setTalking(true)
        }
        pendingTalkAfterPermission = false
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
                CameraUserMessage.CastNoDevice -> snackbarHostState.showSnackbar(castNoDevice)
                CameraUserMessage.CastAuthUrlWarning -> snackbarHostState.showSnackbar(castAuthWarning)
            }
        }
    }

    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose {
            if (prev != null) activity.requestedOrientation = prev
            viewModel.stopPtzHold()
            viewModel.setTalking(false)
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
                        val nextMuted = !state.audioMuted
                        // Same click path = user gesture → WebView unmute+play must work.
                        audioController.applyMute(nextMuted)
                        viewModel.setAudioMuted(nextMuted)
                    }) {
                        Icon(
                            if (state.audioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = stringResource(
                                if (state.audioMuted) R.string.camera_unmute
                                else R.string.camera_mute
                            )
                        )
                    }
                    IconButton(onClick = {
                        val url = viewModel.castStreamUrl()
                        val mime = if (url.contains("m3u8", true))
                            "application/x-mpegURL" else "video/x-motion-jpeg"
                        when (CastHelper.castStream(context, url, state.cameraName, mime)) {
                            CastOutcome.Started -> viewModel.notifyCastStarted()
                            CastOutcome.NoSession -> viewModel.notifyCastNoDevice()
                            CastOutcome.LoadFailed -> viewModel.notifyCastFailed()
                            CastOutcome.AuthUrlWarning -> viewModel.notifyCastAuthWarning()
                        }
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
                    Box(Modifier.fillMaxWidth()) {
                        LiveOrClipSurface(
                            state = state,
                            viewModel = viewModel,
                            isLandscape = isLandscape,
                            audioController = audioController,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.talking) {
                            Text(
                                stringResource(R.string.camera_talk_on),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xCCB71C1C))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
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
                            selected = state.isLive && !state.talking,
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
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.talkSupported) {
                            HoldTalkButton(
                                talking = state.talking,
                                onPress = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.setTalking(true)
                                    } else {
                                        pendingTalkAfterPermission = true
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onRelease = { viewModel.setTalking(false) }
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
                            .padding(horizontal = 16.dp, vertical = 0.dp)
                            .heightIn(max = 28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    )
                    val label = state.scrubTimestamp?.let {
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date((it * 1000).toLong()))
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (state.fullscreen) {
        FullscreenLiveDialog(state = state, viewModel = viewModel, audioController = audioController)
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
                invertPanTilt = state.ptzInvertPanTilt,
                onInvertChange = viewModel::setPtzInvertPanTilt,
                onHoldStart = viewModel::startPtzHold,
                onHoldStop = viewModel::stopPtzHold,
                onTapStop = { viewModel.ptz("STOP") },
                onPreset = viewModel::ptzPreset,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun HoldTalkButton(
    talking: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val bg = if (talking) Color(0xFFC62828) else MaterialTheme.colorScheme.primaryContainer
    val fg = if (talking) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .semantics { contentDescription = "Hold to talk; release to stop" }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        onPress()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        onRelease()
                        true
                    }
                    else -> true
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            if (talking) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp)
        )
        Text(
            stringResource(
                if (talking) R.string.camera_talk_on else R.string.camera_talk
            ),
            color = fg,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun LiveOrClipSurface(
    state: CameraUiState,
    viewModel: CameraViewModel,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    audioController: WebViewAudioController? = null
) {
    val aspectMod = if (fill) {
        modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxWidth()
            .then(if (isLandscape) Modifier.height(220.dp) else Modifier.aspectRatio(16f / 9f))
    }
    val webUrls = state.activeWebViewUrls.ifEmpty { state.livePageUrls }
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
                    (state.talking || (state.useWebViewLive && state.isLive)) && webUrls.isNotEmpty() -> {
                        FrigateLiveWebView(
                            pageUrls = webUrls,
                            bearerToken = viewModel.jwtTokenRaw(),
                            fillAspect = false,
                            showDetections = state.showDetections && !state.talking,
                            allowMicrophone = state.talking,
                            muted = state.audioMuted && !state.talking,
                            audioController = audioController,
                            onAllFailed = {
                                if (state.talking) viewModel.onTalkWebRtcFailed()
                                else viewModel.onWebViewLiveFailed()
                            },
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
                            mute = state.audioMuted,
                            onError = { viewModel.onStreamError() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (state.showDetections && state.isLive && state.authenticatedClipUrl == null && !state.talking) {
                    DetectionOverlay(boxes = state.detectionBoxes)
                }
            }
        }
    }
}

@Composable
private fun FullscreenLiveDialog(
    state: CameraUiState,
    viewModel: CameraViewModel,
    audioController: WebViewAudioController
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
                audioController = audioController,
                modifier = Modifier.fillMaxSize()
            )
            if (state.talking) {
                Text(
                    stringResource(R.string.camera_talk_on),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCCB71C1C))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IconButton(
                onClick = {
                    val nextMuted = !state.audioMuted
                    audioController.applyMute(nextMuted)
                    viewModel.setAudioMuted(nextMuted)
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(
                    if (state.audioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = stringResource(
                        if (state.audioMuted) R.string.camera_unmute else R.string.camera_mute
                    ),
                    tint = Color.White
                )
            }
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
    invertPanTilt: Boolean,
    onInvertChange: (Boolean) -> Unit,
    onHoldStart: (String) -> Unit,
    onHoldStop: () -> Unit,
    onTapStop: () -> Unit,
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
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.camera_ptz_up),
            moveCommand = "MOVE_UP",
            onHoldStart = onHoldStart,
            onHoldStop = onHoldStop
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 10.dp)
        ) {
            HoldPtzButton(
                icon = Icons.Default.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.camera_ptz_left),
                moveCommand = "MOVE_LEFT",
                onHoldStart = onHoldStart,
                onHoldStop = onHoldStop
            )
            PtzTapButton(
                icon = Icons.Default.Stop,
                contentDescription = stringResource(R.string.camera_ptz_stop),
                onClick = {
                    onHoldStop()
                    onTapStop()
                }
            )
            HoldPtzButton(
                icon = Icons.Default.KeyboardArrowRight,
                contentDescription = stringResource(R.string.camera_ptz_right),
                moveCommand = "MOVE_RIGHT",
                onHoldStart = onHoldStart,
                onHoldStop = onHoldStop
            )
        }
        HoldPtzButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.camera_ptz_down),
            moveCommand = "MOVE_DOWN",
            onHoldStart = onHoldStart,
            onHoldStop = onHoldStop
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
                    onHoldStart = onHoldStart,
                    onHoldStop = onHoldStop,
                    size = 64.dp
                )
                HoldPtzButton(
                    icon = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.camera_ptz_zoom_out),
                    moveCommand = "ZOOM_OUT",
                    onHoldStart = onHoldStart,
                    onHoldStop = onHoldStop,
                    size = 64.dp
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
                    onHoldStart = onHoldStart,
                    onHoldStop = onHoldStop,
                    size = 64.dp
                )
                HoldPtzButton(
                    icon = Icons.Default.CenterFocusWeak,
                    contentDescription = stringResource(R.string.camera_ptz_focus_out),
                    moveCommand = "FOCUS_OUT",
                    onHoldStart = onHoldStart,
                    onHoldStop = onHoldStop,
                    size = 64.dp
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    stringResource(R.string.camera_ptz_invert),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.camera_ptz_invert_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = invertPanTilt, onCheckedChange = onInvertChange)
        }

        if (presets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
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

/**
 * Press-and-hold PTZ control. Uses [pointerInteropFilter] so ModalBottomSheet cannot
 * steal DOWN/LEFT gestures; consumes the full press and drives ViewModel hold/repeat.
 */
@Composable
private fun HoldPtzButton(
    icon: ImageVector,
    contentDescription: String,
    moveCommand: String,
    onHoldStart: (String) -> Unit,
    onHoldStop: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 72.dp
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (pressed) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val tint = if (pressed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .semantics { this.contentDescription = contentDescription }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = true
                        onHoldStart(moveCommand)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        onHoldStop()
                        true
                    }
                    // Consume move so the bottom sheet cannot hijack vertical/horizontal drags.
                    MotionEvent.ACTION_MOVE -> true
                    else -> true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
private fun PtzTapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 72.dp
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

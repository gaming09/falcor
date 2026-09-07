package com.falcor.viewer.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.falcor.viewer.BuildConfig
import com.falcor.viewer.R
import com.falcor.viewer.data.model.CameraUiModel
import com.falcor.viewer.player.OkHttpLivePreview
import com.falcor.viewer.ui.components.ErrorRetry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenCamera: (String) -> Unit,
    onOpenAlerts: () -> Unit,
    onLogout: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val app = LocalContext.current.applicationContext as com.falcor.viewer.FalcorApp
    val httpClient = remember { app.repository.authenticatedHttpClient() }
    val baseUrl = remember { app.repository.baseUrl.trimEnd('/') }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            when (msg) {
                is HomeUserMessage.ToggleFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.home_toggle_failed, msg.camera)
                    )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.home_title))
                        Text(
                            stringResource(
                                R.string.app_version_label,
                                BuildConfig.VERSION_NAME
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.cameras.isNotEmpty()) {
                            Text(
                                stringResource(R.string.home_grid_cameras, state.cameras.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAlerts) {
                        Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.home_alerts))
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.home_refresh))
                    }
                    IconButton(onClick = { viewModel.showLogout(true) }) {
                        Icon(Icons.Default.Logout, contentDescription = stringResource(R.string.home_logout))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when {
            state.loading && state.cameras.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error && state.cameras.isEmpty() -> {
                ErrorRetry(
                    message = stringResource(R.string.home_error_load),
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
            }
            state.cameras.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.home_empty))
                }
            }
            else -> {
                var dragFromIndex by remember { mutableIntStateOf(-1) }
                var dragAccumDy by remember { mutableFloatStateOf(0f) }
                val camerasState = rememberUpdatedState(state.cameras)
                val draggingName = state.draggingName

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    itemsIndexed(
                        items = state.cameras,
                        key = { _, cam -> cam.name }
                    ) { index, cam ->
                        val isDragging = draggingName == cam.name
                        val elev by animateFloatAsState(
                            targetValue = if (isDragging) 8f else 2f,
                            label = "cardElev"
                        )
                        CameraCard(
                            camera = cam,
                            mjpegUrl = "$baseUrl/api/${cam.name}",
                            snapshotUrl = cam.thumbnailUrl,
                            okHttpClient = httpClient,
                            toggling = state.toggling == cam.name,
                            anyToggling = state.toggling != null,
                            isDragging = isDragging,
                            elevation = elev,
                            onOpen = { onOpenCamera(cam.name) },
                            onToggle = { viewModel.toggleCamera(cam) },
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = if (isDragging) 1.03f else 1f
                                    scaleY = if (isDragging) 1.03f else 1f
                                    alpha = if (isDragging) 0.92f else 1f
                                }
                                .pointerInput(cam.name, index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragFromIndex = index
                                            dragAccumDy = 0f
                                            viewModel.onDragStart(cam.name)
                                        },
                                        onDragCancel = {
                                            dragFromIndex = -1
                                            dragAccumDy = 0f
                                            viewModel.onDragEnd()
                                        },
                                        onDragEnd = {
                                            dragFromIndex = -1
                                            dragAccumDy = 0f
                                            viewModel.onDragEnd()
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            // Prefer vertical for Adaptive grids; fall back to horizontal.
                                            val primary = if (kotlin.math.abs(dragAmount.y) >=
                                                kotlin.math.abs(dragAmount.x)
                                            ) dragAmount.y else dragAmount.x
                                            dragAccumDy += primary
                                            // ~1 card height ≈ 140px density-independent approx in px
                                            val threshold = 140f
                                            val from = dragFromIndex
                                            if (from < 0) return@detectDragGesturesAfterLongPress
                                            val cams = camerasState.value
                                            when {
                                                dragAccumDy > threshold && from < cams.lastIndex -> {
                                                    val to = from + 1
                                                    viewModel.moveCamera(from, to)
                                                    dragFromIndex = to
                                                    dragAccumDy = 0f
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                dragAccumDy < -threshold && from > 0 -> {
                                                    val to = from - 1
                                                    viewModel.moveCamera(from, to)
                                                    dragFromIndex = to
                                                    dragAccumDy = 0f
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }

    if (state.showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.showLogout(false) },
            title = { Text(stringResource(R.string.home_logout_confirm_title)) },
            text = { Text(stringResource(R.string.home_logout_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.showLogout(false)
                    onLogout()
                }) { Text(stringResource(R.string.home_logout_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showLogout(false) }) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }
}

@Composable
private fun CameraCard(
    camera: CameraUiModel,
    mjpegUrl: String,
    snapshotUrl: String,
    okHttpClient: okhttp3.OkHttpClient,
    toggling: Boolean,
    anyToggling: Boolean,
    isDragging: Boolean,
    elevation: Float,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clickable(enabled = !isDragging, onClick = onOpen)
            ) {
                if (camera.enabled) {
                    OkHttpLivePreview(
                        mjpegUrl = mjpegUrl,
                        snapshotUrl = snapshotUrl,
                        okHttpClient = okHttpClient,
                        snapshotOnly = true,
                        snapshotIntervalMs = 500L,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.home_camera_disabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).clickable(enabled = !isDragging, onClick = onOpen)) {
                    Text(
                        camera.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(
                            if (isDragging) R.string.home_reorder_hint
                            else if (camera.enabled) R.string.home_live_tile
                            else R.string.home_camera_disabled
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Keep Switch mounted to avoid layout jump; disable while any toggle in-flight.
                Box(contentAlignment = Alignment.Center) {
                    Switch(
                        checked = camera.enabled,
                        onCheckedChange = { onToggle() },
                        enabled = !anyToggling && !isDragging
                    )
                    if (toggling) {
                        CircularProgressIndicator(
                            Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

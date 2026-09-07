package com.falcor.viewer.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                // LazyVerticalGrid only composes visible items — use snapshot poll
                // (~500ms) so many cameras don't open simultaneous MJPEG streams.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    items(state.cameras, key = { it.name }) { cam ->
                        CameraCard(
                            camera = cam,
                            mjpegUrl = "$baseUrl/api/${cam.name}",
                            snapshotUrl = cam.thumbnailUrl,
                            okHttpClient = httpClient,
                            toggling = state.toggling == cam.name,
                            onOpen = { onOpenCamera(cam.name) },
                            onToggle = { viewModel.toggleCamera(cam) }
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
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clickable(onClick = onOpen)
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
                Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                    Text(
                        camera.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(
                            if (camera.enabled) R.string.home_live_tile else R.string.home_camera_disabled
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (toggling) {
                    CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = camera.enabled,
                        onCheckedChange = { onToggle() }
                    )
                }
            }
        }
    }
}

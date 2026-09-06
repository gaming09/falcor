package com.falcor.viewer.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.falcor.viewer.R
import com.falcor.viewer.player.VlcPlayer
import com.falcor.viewer.ui.components.ErrorRetry
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setTalking(true)
    }

    Scaffold(
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
                    VlcPlayer(
                        mediaUrl = state.mediaUrl,
                        headers = viewModel.authHeaders(),
                        mute = !state.talking,
                        onError = { viewModel.onStreamError() },
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

                    // Talk-back
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                    }

                    // PTZ pad
                    if (state.ptzSupported) {
                        Text(
                            stringResource(R.string.camera_ptz),
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        PtzPad(
                            onCommand = viewModel::ptz,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }

                    // History scrubber
                    Text(
                        stringResource(R.string.camera_history),
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
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
}

@Composable
private fun PtzPad(
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PtzButton(Icons.Default.KeyboardArrowUp, stringResource(R.string.camera_ptz_up)) {
            onCommand("MOVE_UP")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PtzButton(Icons.Default.KeyboardArrowLeft, stringResource(R.string.camera_ptz_left)) {
                onCommand("MOVE_LEFT")
            }
            PtzButton(Icons.Default.Stop, stringResource(R.string.camera_ptz_stop)) {
                onCommand("STOP")
            }
            PtzButton(Icons.Default.KeyboardArrowRight, stringResource(R.string.camera_ptz_right)) {
                onCommand("MOVE_RIGHT")
            }
        }
        PtzButton(Icons.Default.KeyboardArrowDown, stringResource(R.string.camera_ptz_down)) {
            onCommand("MOVE_DOWN")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PtzButton(Icons.Default.Add, stringResource(R.string.camera_ptz_zoom_in)) {
                onCommand("ZOOM_IN")
            }
            PtzButton(Icons.Default.Remove, stringResource(R.string.camera_ptz_zoom_out)) {
                onCommand("ZOOM_OUT")
            }
        }
    }
}

@Composable
private fun PtzButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

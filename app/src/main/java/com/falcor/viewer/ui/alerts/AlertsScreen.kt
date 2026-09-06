package com.falcor.viewer.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.falcor.viewer.R
import com.falcor.viewer.data.model.FrigateEvent
import com.falcor.viewer.player.VlcPlayer
import com.falcor.viewer.ui.components.ErrorRetry
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenCamera: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val headers = viewModel.authHeaders()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts_title)) },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterBar(state = state, viewModel = viewModel)
            when {
                state.loading && state.events.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error && state.events.isEmpty() -> {
                    ErrorRetry(
                        message = stringResource(R.string.alerts_error),
                        onRetry = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                state.events.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.alerts_empty))
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.events, key = { it.id }) { event ->
                            EventRow(
                                event = event,
                                thumbnailUrl = viewModel.thumbnailUrl(event.id),
                                headers = headers,
                                onClick = { onOpenEvent(event.id) },
                                onCamera = { event.camera?.let(onOpenCamera) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(state: AlertsUiState, viewModel: AlertsViewModel) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.cameraFilter,
                onValueChange = viewModel::onCameraFilter,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.alerts_filter_camera)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.alerts_filter_all)) }
            )
            OutlinedTextField(
                value = state.labelFilter,
                onValueChange = viewModel::onLabelFilter,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.alerts_filter_label)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.alerts_filter_all)) }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.onlyClips, onCheckedChange = viewModel::onOnlyClips)
            Text(stringResource(R.string.alerts_has_clip), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Checkbox(checked = state.onlySnapshots, onCheckedChange = viewModel::onOnlySnapshots)
            Text(stringResource(R.string.alerts_has_snapshot), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = false,
                onClick = viewModel::refresh,
                label = { Text(stringResource(R.string.alerts_filter_apply)) }
            )
        }
    }
}

@Composable
private fun EventRow(
    event: FrigateEvent,
    thumbnailUrl: String,
    headers: Map<String, String>,
    onClick: () -> Unit,
    onCamera: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                    .build(),
                contentDescription = stringResource(R.string.cd_snapshot),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.label ?: stringResource(R.string.alerts_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    event.camera.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatEventTime(event.startTime),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.alerts_score, event.displayScore),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onCamera) {
                Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.alerts_open_camera))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    eventId: String,
    viewModel: AlertsViewModel,
    onBack: () -> Unit,
    onOpenCamera: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(eventId) { viewModel.loadEvent(eventId) }
    val event = state.selectedEvent
    var showClip by remember { mutableStateOf(false) }
    val headers = viewModel.authHeaders()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(event?.label ?: stringResource(R.string.alerts_detail_title))
                },
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
        if (state.detailLoading && event == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (event == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.alerts_no_media))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (showClip && event.hasClip == true) {
                VlcPlayer(
                    mediaUrl = viewModel.clipUrl(event.id),
                    headers = headers,
                    mute = false,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (event.hasSnapshot == true) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(viewModel.snapshotUrl(event.id))
                        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                        .build(),
                    contentDescription = stringResource(R.string.cd_snapshot),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Text(stringResource(R.string.alerts_no_media))
            }
            Spacer(Modifier.height(16.dp))
            Text(event.camera.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(formatEventTime(event.startTime), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.alerts_score, event.displayScore),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.hasSnapshot == true) {
                    Button(onClick = { showClip = false }) {
                        Text(stringResource(R.string.alerts_view_snapshot))
                    }
                }
                if (event.hasClip == true) {
                    Button(onClick = { showClip = true }) {
                        Text(stringResource(R.string.alerts_play_clip))
                    }
                }
                event.camera?.let { cam ->
                    Button(onClick = { onOpenCamera(cam) }) {
                        Text(stringResource(R.string.alerts_open_camera))
                    }
                }
            }
        }
    }
}

@Composable
private fun formatEventTime(start: Double?): String {
    if (start == null) return stringResource(R.string.alerts_time_unknown)
    return DateFormat.getDateTimeInstance().format(Date((start * 1000).toLong()))
}

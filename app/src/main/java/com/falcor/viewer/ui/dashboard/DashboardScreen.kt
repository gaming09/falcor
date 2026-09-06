package com.falcor.viewer.ui.dashboard

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.falcor.viewer.R
import com.falcor.viewer.data.prefs.TileSpan
import com.falcor.viewer.player.OkHttpLivePreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenCamera: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var pinMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.dashboards_title))
                        state.selected?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showCreate(true) }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dashboards_create))
                    }
                    IconButton(onClick = { viewModel.showRename(true) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.dashboards_rename))
                    }
                    IconButton(onClick = viewModel::deleteSelected) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dashboards_delete))
                    }
                    IconButton(onClick = { viewModel.setEditMode(!state.editMode) }) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.dashboards_edit)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.dashboards.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.dashboards.forEach { board ->
                        FilterChip(
                            selected = board.id == state.selected?.id,
                            onClick = { viewModel.selectDashboard(board.id) },
                            label = {
                                Text(board.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        )
                    }
                }
            }

            if (state.editMode) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.dashboards_pin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        AssistChip(
                            onClick = { pinMenu = true },
                            label = { Text(stringResource(R.string.dashboards_pin_camera)) }
                        )
                        DropdownMenu(expanded = pinMenu, onDismissRequest = { pinMenu = false }) {
                            state.cameras.forEach { cam ->
                                val pinned = state.selected?.tiles?.any { it.cameraName == cam.name } == true
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (pinned) "${cam.name} ✓" else cam.name
                                        )
                                    },
                                    onClick = {
                                        if (pinned) viewModel.unpinCamera(cam.name)
                                        else viewModel.pinCamera(cam.name)
                                        pinMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.dashboards.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.dashboards_empty))
                            TextButton(onClick = { viewModel.showCreate(true) }) {
                                Text(stringResource(R.string.dashboards_create))
                            }
                        }
                    }
                }
                state.selected?.tiles.isNullOrEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.dashboards_no_tiles))
                    }
                }
                else -> {
                    val tiles = state.selected!!.tiles.sortedBy { it.order }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = tiles,
                            key = { it.cameraName },
                            span = { tile ->
                                GridItemSpan(
                                    when (tile.span) {
                                        TileSpan.SMALL -> 1
                                        TileSpan.MEDIUM -> 2
                                        TileSpan.LARGE -> 2
                                    }
                                )
                            }
                        ) { tile ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCamera(tile.cameraName) }
                            ) {
                                Column {
                                    val aspect = when (tile.span) {
                                        TileSpan.LARGE -> 16f / 12f
                                        else -> 16f / 9f
                                    }
                                    OkHttpLivePreview(
                                        mjpegUrl = viewModel.mjpegUrl(tile.cameraName),
                                        snapshotUrl = viewModel.snapshotUrl(tile.cameraName),
                                        okHttpClient = viewModel.httpClient(),
                                        snapshotOnly = tile.span == TileSpan.SMALL,
                                        snapshotIntervalMs = if (tile.span == TileSpan.LARGE) 350L else 500L,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(aspect)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            tile.cameraName,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (state.editMode) {
                                            TextButton(onClick = { viewModel.cycleTileSpan(tile.cameraName) }) {
                                                Text(
                                                    when (tile.span) {
                                                        TileSpan.SMALL -> "1×1"
                                                        TileSpan.MEDIUM -> "2×1"
                                                        TileSpan.LARGE -> "2×2"
                                                    }
                                                )
                                            }
                                            TextButton(onClick = { viewModel.unpinCamera(tile.cameraName) }) {
                                                Text(stringResource(R.string.dashboards_unpin))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showCreateDialog) {
        NameDialog(
            title = stringResource(R.string.dashboards_create),
            value = state.draftName,
            onValueChange = viewModel::onDraftName,
            onConfirm = viewModel::createDashboard,
            onDismiss = { viewModel.showCreate(false) }
        )
    }
    if (state.showRenameDialog) {
        NameDialog(
            title = stringResource(R.string.dashboards_rename),
            value = state.draftName,
            onValueChange = viewModel::onDraftName,
            onConfirm = viewModel::renameDashboard,
            onDismiss = { viewModel.showRename(false) }
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        }
    )
}

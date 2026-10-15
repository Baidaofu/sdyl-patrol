package com.sdyl.patrol.feature.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sdyl.patrol.Graph
import com.sdyl.patrol.feature.video.model.VideoChannel
import com.sdyl.patrol.feature.video.model.VideoGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 视频监控（只读）。
 *
 * 与用户约定（m00654）：现场只有 MSE 与 FLV 两种流，且**只能查看**——
 * 不做推流、不做云台、不做对讲。播放完全复用服务端下发的 H5（unitPlayer），
 * 与原 App 同一路径，避免自己实现 MSE/FLV 协议栈。
 */
class VideoListViewModel : ViewModel() {

    private val repo = VideoRepository(Graph.api)

    private val _groups = MutableStateFlow<List<VideoGroup>>(emptyList())
    val groups: StateFlow<List<VideoGroup>> = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadedOnce = false

    init { refresh() }

    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            repo.loadGroups()
                .onSuccess { _groups.value = it; loadedOnce = true }
                .onFailure { _error.value = it.message ?: it.toString() }
            _loading.value = false
        }
    }

    /** 首次进入且没加载成功时自动重试一次，网络抖动时少一次手动刷新 */
    fun refreshIfEmpty() {
        if (!loadedOnce && !_loading.value && _groups.value.isEmpty()) refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    onOpenChannel: (VideoChannel) -> Unit,
    vm: VideoListViewModel = viewModel(),
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var keyword by remember { mutableStateOf("") }

    val filtered = remember(groups, keyword) {
        val k = keyword.trim()
        if (k.isEmpty()) groups
        else groups.mapNotNull { g ->
            val hit = g.channels.filter {
                it.name.contains(k, true) || it.channel.contains(k, true)
            }
            if (hit.isEmpty() && !g.name.contains(k, true)) null else g.copy(channels = hit)
        }
    }
    val totalOnline = groups.sumOf { g -> g.channels.count { it.online } }
    val totalChannel = groups.sumOf { it.channels.size }

    Scaffold(
        topBar = {
            // 与主页/设置统一：44dp 单行、背景画到状态栏后面，不留缝。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "视频监控",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.refresh() }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索通道名称 / 通道号") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { keyword = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                keyboardActions = KeyboardActions(),
            )

            if (totalChannel > 0) {
                Text(
                    "共 $totalChannel 路通道，在线 $totalOnline 路",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    loading && groups.isEmpty() -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                    )

                    groups.isEmpty() -> EmptyHint(
                        error = error,
                        onRetry = { vm.refresh() },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        filtered.forEach { group ->
                            item(key = "g-${group.id}") {
                                GroupHeader(group)
                            }
                            items(group.channels, key = { "c-${group.id}-${it.channel}" }) { ch ->
                                ChannelRow(ch) { onOpenChannel(ch) }
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: VideoGroup) {
    val online = group.channels.count { it.online }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${group.channels.size} 路 / 在线 $online",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChannelRow(ch: VideoChannel, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = ch.online, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    if (ch.online) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ch.online) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                contentDescription = null,
                tint = if (ch.online) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ch.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (ch.online) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "通道 ${ch.channel} · ${ch.cameraTypeLabel}" + if (ch.online) "" else " · 离线",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint(error: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.VideocamOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (error == null) "没有可用的视频通道" else "通道加载失败",
            style = MaterialTheme.typography.titleMedium,
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        androidx.compose.material3.FilledTonalButton(onClick = onRetry) { Text("重试") }
    }
}

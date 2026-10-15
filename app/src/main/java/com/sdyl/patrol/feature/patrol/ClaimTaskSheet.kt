package com.sdyl.patrol.feature.patrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.db.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「领取任务」。
 *
 * 用户 m05272 第 2 条 / m05518 第 2 条要求对着原版把这条流程做对。翻原版得到的事实：
 *
 * `XJOfflineHomeActivity.tvGetTask`（资源串 `@string/get_task` = 「领取任务」）
 * → `IntentRouter.go(GET_UPLOAD_TASK, {TASK_LIST_TYPE: GET})` → `XJGetUploadTasksActivity(isGet=true)`
 * → 勾选若干任务 → `XJUpdateTasksStateController.updateTasksState(false, 勾选的任务)`
 * → `XJUpdateTasksStatePresenter` 组 body
 * `{changeState: "PATROL_taskState/issued", idList: [任务 id…], mobileVersion, equipmentModel, macAddress}`
 * → `POST /msService/PATROL/patrolTask/potrolTask/uploadTaskState`。
 *
 * 所以**领取 = 把已下发给我的「未领取」任务置为「已领取」**（不是创建临时任务）。
 *
 * 注意：Web 列表上的「状态变更人」＝服务端任务实体字段 `statusChangeStaff`，**就是这一步写的**
 * （同时写 `acquirer`＝本次调用人）。移动端投影里的 `executeUser` 是「执行人」，由下一步
 * `/msService/PATROL/mobile/patrolTask/updatePatrolTask`（开始任务）写入，别把两者混为一谈。
 *
 * 版式：与主页一致 —— 按任务名称分块，块内是**时间段小块（一行 3 个）**，
 * 点小块切换选中（空心 = 未选），底部「确认领取（N）」。
 *
 * 两个稳定性措施（用户 m07422：滑动过快会抽动）：
 * 1. 选中集合放在 ViewModel 里，`ensureSelection` 只在候选**集合内容**变化时才重置，
 *    避免重组 / 列表实例变化把勾选状态洗掉；
 * 2. `skipPartiallyExpanded = true` + 列表固定高度上限，避免快速滑动时
 *    底部弹窗的拖拽锚点与列表滚动互相抢手势（表现为整块弹窗抽动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaimTaskSheet(
    candidates: List<TaskEntity>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val vm: ClaimTaskViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    val groups = remember(candidates) {
        groupByTaskName(candidates.filter { it.tableNo.orEmpty().isNotBlank() })
    }
    val allNos = remember(groups) { groups.flatMap { g -> g.slots.mapNotNull { it.tableNo } } }
    // 只在「候选项集合本身」变化时才重建默认选中，避免列表实例变了就洗掉勾选。
    val nosKey = remember(allNos) { allNos.joinToString(",") }
    LaunchedEffect(nosKey) { vm.ensureSelection(allNos) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = state.selected

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "领取任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (groups.isNotEmpty()) {
                    TextButton(onClick = { vm.toggleAll() }) {
                        Text(if (selected.size == allNos.size) "全不选" else "全选")
                    }
                }
            }
            // 用户 m15731：这段附加说明删掉，只留功能名称。
            Spacer(Modifier.height(12.dp))

            if (groups.isEmpty()) {
                Text(
                    "本日没有「未领取」的任务。换个日期，或先点右上角同步图标获取任务。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) { Text("知道了") }
                return@Column
            }

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.name }) { group ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Filled.Route,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            group.slots.chunked(3).forEach { line ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    line.forEach { task ->
                                        val no = task.tableNo.orEmpty()
                                        SlotChip(
                                            task = task,
                                            modifier = Modifier.weight(1f),
                                            selected = no in selected,
                                        ) { vm.toggle(no) }
                                    }
                                    repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.submit(selected.toList()) { msg -> onMessage(msg) } },
                enabled = !state.submitting && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (state.submitting) {
                        "正在领取…"
                    } else {
                        "确认领取（${selected.size}）"
                    },
                )
            }
        }
    }
}

data class ClaimTaskUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    /** 当前候选的全部单据号（保证顺序稳定，用于「全选/全不选」和默认选中） */
    val allNos: List<String> = emptyList(),
    /** 已勾选的时间段 */
    val selected: Set<String> = emptySet(),
)

/** 默认 ViewModel 工厂用反射实例化，类必须是 public（不能是 private/internal）。 */
class ClaimTaskViewModel : ViewModel() {
    private val repo = Graph.patrolRepository

    private val _state = MutableStateFlow(ClaimTaskUiState())
    val state: StateFlow<ClaimTaskUiState> = _state.asStateFlow()

    /** 候选集合内容变化时重置默认选中（用户 m15731：默认全不选）；内容没变就保留用户已勾的状态。 */
    fun ensureSelection(nos: List<String>) {
        val s = _state.value
        if (s.allNos == nos) return
        _state.value = s.copy(allNos = nos, selected = emptySet())
    }

    fun toggle(no: String) {
        val s = _state.value
        _state.value = s.copy(selected = if (no in s.selected) s.selected - no else s.selected + no)
    }

    fun toggleAll() {
        val s = _state.value
        _state.value = s.copy(
            selected = if (s.selected.size == s.allNos.size) emptySet() else s.allNos.toSet(),
        )
    }

    fun submit(tableNos: List<String>, onDone: (String) -> Unit) {
        if (_state.value.submitting) return
        _state.value = _state.value.copy(submitting = true, error = null)
        viewModelScope.launch {
            repo.claimTasks(tableNos)
                .onSuccess {
                    val n = tableNos.size
                    _state.value = ClaimTaskUiState()
                    onDone("已领取 $n 条任务")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        submitting = false,
                        error = e.message ?: "领取失败",
                    )
                }
        }
    }
}

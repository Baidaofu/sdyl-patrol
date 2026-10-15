package com.sdyl.patrol.feature.patrol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.db.TaskEntity
import com.sdyl.patrol.feature.patrol.model.PatrolTaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ *
 * 时段筛选已删除（用户 m15940：任务的时间间隔不止 2 小时——班长 4h、工程师 6h、
 * 其他部门的人 1h，所以 `00-02 / 02-04 …` 那排硬编码的「班次」本身就是错的）。
 * 现在主页只选**日期**，一次显示当天全部时间段：块内每个时间段自带自己的
 * `start~end`，1h/2h/4h/6h 的账号都能正常显示（只是格子数量不同）。
 * ------------------------------------------------------------------ */

data class TaskListUiState(
    val syncing: Boolean = false,
    /** 主页要显示的：选中那一天里、命中当前分类的任务 */
    val tasks: List<TaskEntity> = emptyList(),
    /** 「领取任务」弹窗的候选：选中那一天里还没领取的任务 */
    val claimCandidates: List<TaskEntity> = emptyList(),
    val error: String? = null,
    val filter: TaskFilter = TaskFilter.MINE,
    val dayStart: Long = 0L,
    val dayLabel: String = "",
    val isToday: Boolean = true,
    /** 当前分类命中的条数（当天） */
    val windowCount: Int = 0,
    /** 当天还没领取的任务数（＝右上角角标） */
    val pendingCount: Int = 0,
)

/**
 * 主页分类（用户 m15074：把各种状态都补进首页筛选，原来的四个横排 chip 改成「我的」+「分类▾」）。
 *
 * 语义仍是**单选**：点「我的」＝只看我领取/签到的（不分状态）；在「分类▾」里点一项＝只看那一个状态。
 * 状态码与文案都照服务端字典（[PatrolTaskState]），分类顺序也和原版任务中心的下拉一致。
 */
enum class TaskFilter(val label: String, val stateId: String? = null) {
    MINE("我的"),
    ALL("状态不限"),
    NOT_CLAIMED("未领取", PatrolTaskState.NOT_ISSUED),
    ISSUED("已领取", PatrolTaskState.ISSUED),
    RUNNING("执行中", PatrolTaskState.RUNNING),
    DONE("已完成", PatrolTaskState.COMPLETED),
    OVERDUE("已超期", PatrolTaskState.OVERDUE),
    OVERDUE_DONE("超期完成", PatrolTaskState.OVERDUE_COMPLETED),
    CANCELLED("已取消", PatrolTaskState.CANCELLED),
    ;

    companion object {
        /** 「分类▾」下拉里能选的那些（不含「我的」） */
        val categories: List<TaskFilter> = entries.filter { it != MINE }
    }
}

/**
 * 纯函数：某个状态码是否命中当前分类。抽出来是为了能被单测逐档钉死
 * （[TaskFilter.MINE] 不看状态、[TaskFilter.ALL] 全放行、其余精确匹配一个状态码）。
 */
internal fun taskMatchesFilter(stateId: String?, filter: TaskFilter, mineHit: Boolean): Boolean =
    when (filter) {
        TaskFilter.MINE -> mineHit
        TaskFilter.ALL -> true
        else -> stateId != null && stateId == filter.stateId
    }

class TaskListViewModel : ViewModel() {
    private val repo = Graph.patrolRepository

    private val filter = MutableStateFlow(TaskFilter.MINE)
    private val syncing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val dayStart = MutableStateFlow(dayStartOf(System.currentTimeMillis()))

    /** 本机记下「我领过的任务」（表里不带 executeUser，领取时自己记） */
    private val myClaimed = MutableStateFlow(Graph.session.myClaimedTaskNos)

    /**
     * 服务端认定「我领的」任务号 —— 用户 m05493 的「我的」漏单就是靠它补上的。
     *
     * 2026-10-14 在原版在线巡检首页 `XJOnlineHomeActivity` 里找到口径：请求带
     * `acquirerId = 登录账号`（就是界面上的「只显示我的」）。实测只带 staffId 会返回
     * 整个班组的单，带上 acquirerId 就只剩我领的那几张（见 [PatrolRepository.fetchMyServerTableNos]）。
     */
    private val serverMine = MutableStateFlow<Set<String>>(emptySet())

    /** 「我领的」＝ 本机记的 ∪ 服务端按领取人查出来的 */
    private val mineExtra = combine(myClaimed, serverMine) { claimed, srv -> claimed + srv }

    private data class Controls(
        val filter: TaskFilter,
        val syncing: Boolean,
        val error: String?,
        val day: Long,
    )

    private val controls = combine(filter, syncing, error, dayStart) { f, s, e, d ->
        Controls(f, s, e, d)
    }

    val state: StateFlow<TaskListUiState> = combine(
        repo.observeTasks(), controls, repo.observeMySignedTableNos(), mineExtra,
    ) { all, c, signedNos, extra ->
        val f = c.filter
        val day = c.day
        val mine = signedNos.toSet() + extra

        // 用户 m15940：不再按「班次」切窗口，整天都算（1h/4h/6h 间隔的账号都能覆盖）
        val winStart = day
        val winEnd = day + 86_400_000L
        val inWindow = all.filter { it.startTime in winStart until winEnd }
        val shown = inWindow.filter { matchesFilter(it, f, mine) }

        TaskListUiState(
            syncing = c.syncing,
            tasks = shown,
            claimCandidates = inWindow.filter { it.taskStateId == PatrolTaskState.NOT_ISSUED },
            error = c.error,
            filter = f,
            dayStart = day,
            dayLabel = dayLabel(day),
            isToday = day == dayStartOf(System.currentTimeMillis()),
            windowCount = shown.size,
            pendingCount = inWindow.count { it.taskStateId == PatrolTaskState.NOT_ISSUED },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState(syncing = true))

    init { sync() }

    fun onFilterChange(f: TaskFilter) { filter.value = f }

    fun onDayChange(dayStartMillis: Long) {
        dayStart.value = dayStartOf(dayStartMillis)
        sync()
    }

    /**
     * 只拉「选中那一天」，**不带状态条件**：
     * 状态筛选改成本地过滤（切 chip 不再打网络），而且是本地库要同时装着
     * 「已领取」和「未领取」两种任务 —— 后者是「领取任务」弹窗的候选来源。
     *
     * 用户 m15940：不再按「班次」切窗口（间隔有 1h/2h/4h/6h），整天一起拉。
     *
     * 注意：这里**不做全量替换**（曾经在「今天 + 全天 + 待检」时 `taskDao.clear()`
     * 再插入，后果是刚做过并上传过的任务在列表里凭空消失）。现在只增量 upsert。
     */
    fun sync() {
        if (syncing.value) return
        syncing.value = true
        error.value = null
        viewModelScope.launch {
            val day = dayStart.value
            val winStart = day
            val winEnd = day + 86_400_000L
            repo.syncTasks(
                taskStates = null,
                keyword = null,
                windowStart = winStart,
                windowEnd = winEnd,
                replaceAll = false,
            ).onFailure { e -> error.value = e.message ?: "同步失败（已显示本地缓存）" }
            myClaimed.value = Graph.session.myClaimedTaskNos
            // 用户 m05493：把服务端「我领的单」也并进「我的」（口径＝ acquirerId，见 repository）
            repo.fetchMyServerTableNos(day, day + 86_400_000L - 1L)
                .onSuccess { serverMine.value = it }
                .onFailure { /* 离线时拉不到，保留上一次的结果即可 */ }
            syncing.value = false
        }
    }

    fun consumeError() { error.value = null }

    /** `mine` = 我签过到的任务号 ∪ 本机记下我领过的任务号 */
    private fun matchesFilter(t: TaskEntity, f: TaskFilter, mine: Set<String>): Boolean =
        taskMatchesFilter(t.taskStateId, f, t.tableNo?.let { it in mine } == true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(onOpenTask: (TaskEntity) -> Unit) {
    val vm: TaskListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilter by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showClaim by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val groups = remember(state.tasks) { groupByTaskName(state.tasks) }

    if (showClaim) {
        // 用 remember(state) 固定住列表实例：否则每次重组都会生成新 List，
        // 弹窗里的 remember(candidates) 会被判定为「变了」→ 勾选状态被重置（表现为勾选抖动）。
        val candidates = remember(state.claimCandidates) { state.claimCandidates }
        ClaimTaskSheet(
            candidates = candidates,
            onDismiss = { showClaim = false },
            onMessage = { msg ->
                showClaim = false
                vm.sync()
                scope.launch { snackbar.showSnackbar(msg) }
            },
        )
    }

    if (showFilter) {
        FilterDialog(
            state = state,
            onPickDate = { showPicker = true },
            onDismiss = { showFilter = false },
        )
    }

    if (showPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.dayStart)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { vm.onDayChange(it) }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = picker)
        }
    }

    Scaffold(
        topBar = {
            // 用户 m07120：「顶部的留白还是很大，再缩小一些」。
            // 用户 m07325：顶部不要再是「棕色块」、也不要和状态栏分层 ——
            // 背景改成与整页一致的 surface 色，并且**先 background 再 statusBarsPadding**，
            // 这样这层背景会一直画到状态栏后面，和状态栏连成一片。
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
                        "巡检任务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        shortDayLabel(state.dayStart),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showFilter = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = "筛选日期")
                    }
                    BadgedBox(
                        badge = {
                            if (state.claimCandidates.isNotEmpty()) {
                                Badge { Text("${state.claimCandidates.size}") }
                            }
                        },
                    ) {
                        IconButton(onClick = { showClaim = true }) {
                            Icon(Icons.Filled.PlaylistAdd, contentDescription = "领取任务")
                        }
                    }
                    IconButton(onClick = vm::sync, enabled = !state.syncing) {
                        Icon(Icons.Filled.CloudSync, contentDescription = "获取任务")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.syncing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.filter == TaskFilter.MINE,
                    onClick = { vm.onFilterChange(TaskFilter.MINE) },
                    label = { Text(TaskFilter.MINE.label) },
                )
                // 用户 m15074：「分类▾」点开是所有状态，点一项就只看这一类（单选）。
                var categoryOpen by remember { mutableStateOf(false) }
                Box {
                    FilterChip(
                        selected = state.filter != TaskFilter.MINE,
                        onClick = { categoryOpen = true },
                        label = {
                            Text(
                                if (state.filter == TaskFilter.MINE) {
                                    "分类"
                                } else {
                                    state.filter.label
                                },
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "展开分类",
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = categoryOpen,
                        onDismissRequest = { categoryOpen = false },
                    ) {
                        TaskFilter.categories.forEach { f ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        f.label,
                                        color = if (state.filter == f) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    vm.onFilterChange(f)
                                    categoryOpen = false
                                },
                            )
                        }
                    }
                }
            }

            state.error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            when {
                state.syncing && state.tasks.isEmpty() -> Box(
                    Modifier.fillMaxSize(), Alignment.Center,
                ) { CircularProgressIndicator() }

                groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (state.filter) {
                                TaskFilter.MINE -> "这一天没有你领取的任务"
                                else -> "这一天没有「${state.filter.label}」的任务"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.dayLabel +
                                if (state.pendingCount > 0) {
                                    "：还有 ${state.pendingCount} 条未领取"
                                } else {
                                    "：当天没有任务"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (state.claimCandidates.isNotEmpty()) {
                                "点右上角第二个图标领取任务"
                            } else {
                                "换个日期，或点右上角同步图标获取任务"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(groups, key = { it.name }) { g ->
                        TaskGroupCard(g, onOpenTask)
                    }
                }
            }
        }
    }
}

/**
 * 用户 m06888：主页按**任务名称**分块，块内列出不同时间段；
 * 点某个时间段打开那一条任务的任务点列表。
 */
internal data class TaskGroup(val name: String, val slots: List<TaskEntity>)

internal fun groupByTaskName(tasks: List<TaskEntity>): List<TaskGroup> =
    tasks
        .groupBy { t ->
            t.taskName?.takeIf { n -> n.isNotBlank() }
                ?: t.routeName.orEmpty().ifBlank { "巡检任务" }
        }
        .map { (name, list) -> TaskGroup(name, list.sortedBy { it.startTime }) }
        .sortedBy { it.slots.firstOrNull()?.startTime ?: 0L }

/**
 * 用户 m07325：块内**去掉人员名单和「智慧安全系统（工艺）」**，
 * 只留「路线图标＋任务名（右侧带日期）」和一行三个的时间段小块。
 */
@Composable
private fun TaskGroupCard(group: TaskGroup, onClick: (TaskEntity) -> Unit) {
    val head = group.slots.firstOrNull()
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shortDayLabel(head?.startTime ?: 0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(8.dp))
            group.slots.chunked(3).forEach { line ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    line.forEach { task ->
                        SlotChip(task, Modifier.weight(1f)) { onClick(task) }
                    }
                    repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * 时间段小块（一行三个）。
 * - 主页（TaskGroupCard）：点开那一条任务的任务点列表。
 * - 领取弹窗（ClaimTaskSheet）：勾选要领取的时间段，`selected = false` 时画成描边空心。
 */
@Composable
internal fun SlotChip(
    task: TaskEntity,
    modifier: Modifier,
    selected: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = when (task.taskStateId) {
        PatrolTaskState.COMPLETED -> MaterialTheme.colorScheme.primary
        PatrolTaskState.RUNNING -> MaterialTheme.colorScheme.tertiary
        PatrolTaskState.OVERDUE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val fg = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Column {
            Text(
                slotLabelCompact(task.startTime, task.endTime),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                PatrolTaskState.label(task.taskStateId),
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDialog(
    state: TaskListUiState,
    onPickDate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选") },
        text = {
            Column {
                // 用户 m15940：去掉左右翻页箭头和「00-02 / 02-04…」那排时段 chip，
                // 只留一个「日期」入口（点开月历选），列表直接显示当天全部时间段。
                Surface(
                    onClick = onPickDate,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.dayLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "选日期",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "已领 ${state.windowCount} 条" +
                        if (state.pendingCount > 0) " · 待领 ${state.pendingCount} 条" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
internal fun StateBadge(stateId: String?, label: String?) {
    val text = label?.ifBlank { PatrolTaskState.label(stateId) } ?: PatrolTaskState.label(stateId)
    val color = when (stateId) {
        PatrolTaskState.COMPLETED -> MaterialTheme.colorScheme.primary
        PatrolTaskState.RUNNING -> MaterialTheme.colorScheme.tertiary
        PatrolTaskState.OVERDUE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(color = color.copy(alpha = 0.14f), shape = CircleShape) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun MetaText(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

internal val patrolDayFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
internal val patrolDateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
internal val patrolDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
internal val patrolLogFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
internal val patrolClockFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

internal fun fmt(millis: Long): String =
    if (millis <= 0L) "-" else patrolDayFormat.format(Date(millis))

/**
 * 任务详情顶部那张任务卡用的格式：**精确到秒**（用户 m12024）。
 *
 * 列表/区域行那些地方仍用 [fmt]（`MM-dd HH:mm`）省地方，只有顶部计划/实际时间要看到秒，
 * 方便对着后台逐秒比对。
 */
internal fun fmtSec(millis: Long): String =
    if (millis <= 0L) "-" else patrolDateTimeFormat.format(Date(millis))

/**
 * 任务详情里的「状态变更人」：服务端只回一个内部人员 ID，
 * 用本机人员名录（PersonDirectory，设置界面查工号时会缓存 9228 人全量名单）换算成姓名+工号。
 */
internal fun changeUserText(executeUserId: String?): String {
    val id = executeUserId?.takeIf { it.isNotBlank() } ?: return "（服务端未记录）"
    val person = runCatching { Graph.personDirectory.byId(id) }.getOrNull()
    return if (person != null) "${person.name}（工号 ${person.code}）" else id
}

/** 时间段标签：22:00 ~ 次日 00:00 */
internal fun slotLabel(start: Long, end: Long): String {
    if (start <= 0L) return "-"
    val s = patrolClockFormat.format(Date(start))
    if (end <= 0L) return "$s ~ -"
    val nextDay = dayStartOf(end) != dayStartOf(start)
    return "$s ~ ${if (nextDay) "次日 " else ""}${patrolClockFormat.format(Date(end))}"
}

/** 当天 00:00:00.000 的时间戳（本地时区） */
internal fun dayStartOf(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(dayStart: Long): String {
    val today = dayStartOf(System.currentTimeMillis())
    val text = patrolDateOnlyFormat.format(Date(dayStart))
    val diff = ((dayStart - today) / 86_400_000L).toInt()
    return when (diff) {
        0 -> "$text 今天"
        1 -> "$text 明天"
        -1 -> "$text 昨天"
        else -> text
    }
}

/** 紧凑日期：10-13 今天 / 10-14 明天（顶栏和任务块里省地方用） */
internal fun shortDayLabel(millis: Long): String {
    if (millis <= 0L) return "-"
    val day = dayStartOf(millis)
    val diff = ((day - dayStartOf(System.currentTimeMillis())) / 86_400_000L).toInt()
    val suffix = when (diff) {
        0 -> " 今天"
        1 -> " 明天"
        -1 -> " 昨天"
        else -> ""
    }
    return SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(day)) + suffix
}

/** 时间段小块的紧凑写法：22:00~00:00（不带「次日」两字，小块放不下） */
internal fun slotLabelCompact(start: Long, end: Long): String {
    if (start <= 0L) return "-"
    val s = patrolClockFormat.format(Date(start))
    if (end <= 0L) return "$s~-"
    return "$s~${patrolClockFormat.format(Date(end))}"
}

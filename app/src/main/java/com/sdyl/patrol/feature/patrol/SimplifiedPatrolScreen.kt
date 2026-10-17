package com.sdyl.patrol.feature.patrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.db.AreaEntity
import com.sdyl.patrol.core.data.db.TaskEntity
import com.sdyl.patrol.core.data.db.WorkItemEntity
import com.sdyl.patrol.feature.patrol.model.PatrolConclusion
import com.sdyl.patrol.feature.patrol.model.ResultEditMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random

/**
 * 「简化巡检」（用户 m08985 第 3 条）。
 *
 * 打开设置里的开关后，点任务列表里某个时间段的巡检任务就直接进这一页，
 * 不再走常规的区域/巡检项流程：把开始时间、每个巡检点的签到时间、提交时间
 * 一次性摆出来，填完按「上传并提交」一次搞定。
 *
 * 数据一律按本机填的时间落盘/上报（用户 m08985 第 2 条）。
 */
class SimplifiedPatrolViewModel(private val tableNo: String) : ViewModel() {

    private val repo = Graph.patrolRepository

    private val _task = MutableStateFlow<TaskEntity?>(null)
    val task = _task.asStateFlow()

    private val _areas = MutableStateFlow<List<AreaEntity>>(emptyList())
    val areas = _areas.asStateFlow()

    /** 每个巡检点（区域）的签到时间文本，key = areaId */
    private val _areaTimes = MutableStateFlow<Map<String, String>>(emptyMap())
    val areaTimes = _areaTimes.asStateFlow()

    private val _startText = MutableStateFlow(patrolDateTimeFormat.format(Date()))
    val startText = _startText.asStateFlow()

    private val _endText = MutableStateFlow(patrolDateTimeFormat.format(Date()))
    val endText = _endText.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    /** 「预览将要上传的数据」弹窗内容（纯本机装配，用户 m05768） */
    private val _preview = MutableStateFlow<String?>(null)
    val preview = _preview.asStateFlow()

    /**
     * 签到方式（用户 m04138：没有授权绝对不要用「手动签到」）。
     *
     * 之前的实现把这里写死成 [PatrolRepository.SIGN_TYPE_MANUAL]，纯属实现者的偷懒，
     * 原版和常规区域作业面板的默认都是 [PatrolRepository.SIGN_TYPE_NFC]（NFC刷卡签到）。
     * 现在跟着常规流程走：默认 NFC，要手工签必须由使用者显式切过去并填原因。
     */
    private val _signType = MutableStateFlow(PatrolRepository.SIGN_TYPE_NFC)
    val signType = _signType.asStateFlow()

    /** 手动签到的原因（原版 signInReason，选手工签时必填） */
    private val _signReason = MutableStateFlow("")
    val signReason = _signReason.asStateFlow()

    /** 每个巡检点（区域）的巡检项明细，key = areaId（从本地库同步下来的） */
    private val _areaItems = MutableStateFlow<Map<String, List<WorkItemEntity>>>(emptyMap())
    val areaItems = _areaItems.asStateFlow()

    /** 用户给每个巡检项选的「实际结果」（「是否」型就是 是/否），key = 巡检项 id */
    private val _itemValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val itemValues = _itemValues.asStateFlow()

    /** 与 [itemValues] 配套的结论码（正常/异常/可疑），key = 巡检项 id */
    private val _itemConclusions = MutableStateFlow<Map<String, String>>(emptyMap())
    val itemConclusions = _itemConclusions.asStateFlow()

    /** 选一个巡检项的「实际结果」（用户要的是否/正常异常设置） */
    fun pickItem(itemId: String, value: String, conclusion: String) {
        _itemValues.value = _itemValues.value + (itemId to value)
        _itemConclusions.value = _itemConclusions.value + (itemId to conclusion)
    }

    init {
        reload()
        viewModelScope.launch {
            repo.observeAreas(tableNo).collect { list: List<AreaEntity> ->
                _areas.value = list.sortedBy { it.sort }
                val old = _areaTimes.value
                val now = patrolDateTimeFormat.format(Date())
                _areaTimes.value = list.associate { it.id to (old[it.id] ?: now) }
                // 巡检项要先拉到本机，界面才能给出「实际结果」设置
                loadAreaItems()
            }
        }
    }

    /** 同步并读取每个区域的巡检项明细（供「实际结果」设置用） */
    private fun loadAreaItems() {
        viewModelScope.launch {
            val map = LinkedHashMap<String, List<WorkItemEntity>>()
            _areas.value.forEach { area ->
                runCatching { repo.syncWorkItems(tableNo, area.id) }
                map[area.id] = runCatching { Graph.db.workItemDao().listByArea(area.id) }
                    .getOrDefault(emptyList())
            }
            _areaItems.value = map
        }
    }

    fun reload() {
        viewModelScope.launch {
            runCatching { Graph.db.taskDao().byTableNo(tableNo) }.getOrNull()?.let { _task.value = it }
        }
        loadAreaItems()
    }

    fun setStart(text: String) { _startText.value = text }

    fun setEnd(text: String) { _endText.value = text }

    fun setSignType(type: String) { _signType.value = type }

    fun setSignReason(text: String) { _signReason.value = text }

    /**
     * 自动随机排时间（用户最新声明，2026-10-14）。
     *
     * 窗口取任务自身的计划时间，一次把三个东西都排好：
     *  1) 计划开始 < 实际开始 < 任何巡检点签到时间 < 实际结束 < 计划结束；
     *  2) 计划开始 < 实际开始 < 计划开始 + 15 分钟；
     *  3) 计划结束 − 15 分钟 < 实际结束 < 计划结束；
     *  4) 巡检点与「计划开始+15 分钟」「计划结束−15 分钟」无强制大小关系，可以重合；
     *  5) 任意两个巡检点之间 ≥ 120 秒。
     *
     * 实际开始、实际结束都在各自允许区间里**随机**取值（重试若干次直到中间放得下所有点位），
     * 点位之间至少 120 秒、实际开始→点1 与点n→实际结束各至少 1 分钟，
     * 富余时间随机切分补给各段，所以每次点出来都不一样。
     * （早前写死的「+1、+2、+2」只是某次现场口述流程，已删。）
     * 只改文本框，不写库、不联网。
     */
    fun arrangeTimes() {
        val planStart = _task.value?.startTime ?: 0L
        val planEnd = _task.value?.endTime ?: 0L
        if (planStart <= 0L || planEnd <= planStart) {
            _toast.value = "这个任务没有计划时间，先联网获取任务"
            return
        }
        val areas = _areas.value
        if (areas.isEmpty()) {
            _toast.value = "这个任务还没有巡检点，先联网获取任务"
            return
        }
        val minute = 60_000L
        val quarter = 15 * minute // 要求 2/3 的 ±15 分钟
        val pointGap = 2 * minute // 要求 5：点位之间 ≥120 秒
        val edge = minute // 实际开始→点1、点n→实际结束
        val n = areas.size
        val need = edge * 2 + pointGap * (n - 1)
        // 最紧的可行窗口：实际开始最早 = 计划开始+1 分钟，实际结束最晚 = 计划结束−1 秒
        val minWindow = minute + need + 1_000L
        val window = planEnd - planStart
        if (window < minWindow) {
            _toast.value =
                "计划窗口只有 ${window / minute} 分钟，排不下：$n 个巡检点至少要 " +
                    "${(minWindow + minute - 1) / minute} 分钟（实际开始≥计划开始+1 分钟、点间隔≥2 分钟、实际结束<计划结束）"
            return
        }
        // 要求 2/3：实际开始 ∈ (计划开始, 计划开始+15分钟)，实际结束 ∈ (计划结束−15分钟, 计划结束)
        var start = 0L
        var end = 0L
        var fitted = false
        repeat(64) {
            if (fitted) return@repeat
            val s = planStart + minute + Random.nextLong(quarter - minute)
            val e = planEnd - (1_000L + Random.nextLong(quarter - 1_000L))
            if (e - s >= need) {
                start = s
                end = e
                fitted = true
            }
        }
        if (!fitted) {
            // 计划窗口很紧：退到「最宽窗口」，实在不行才报错
            start = planStart + minute
            end = planEnd - 1_000L
            if (end - start < need) {
                _toast.value = "这个任务的计划窗口太窄，$n 个巡检点排不下（点间隔≥2 分钟）"
                return
            }
        }
        val slack = end - start - need
        // 富余时间随机切 n 段，补给 实际开始→点1 与各点之间的间隔；
        // 剩下的自然落在「最后一点→实际结束」上（至少 edge）
        val cuts = (0 until n).map { Random.nextLong(slack + 1) }.sorted()
        val extra = ArrayList<Long>(n + 1)
        var prev = 0L
        cuts.forEach { extra.add(it - prev); prev = it }
        extra.add(slack - prev)
        var t = start + edge + extra[0]
        val times = _areaTimes.value.toMutableMap()
        areas.forEachIndexed { i, area ->
            times[area.id] = patrolDateTimeFormat.format(Date(t))
            if (i < n - 1) t += pointGap + extra[i + 1]
        }
        _areaTimes.value = times
        _startText.value = patrolDateTimeFormat.format(Date(start))
        _endText.value = patrolDateTimeFormat.format(Date(end))
    }

    fun setAreaTime(areaId: String, text: String) {
        _areaTimes.value = _areaTimes.value.toMutableMap().apply { put(areaId, text) }
    }

    fun nowText(): String = patrolDateTimeFormat.format(Date())

    fun consumeToast() { _toast.value = null }

    /**
     * 「预览将要上传的数据」：只在本机组载荷，不发任何请求（用户 m05768）。
     * 断网也能点，用来确认载荷里的时间是本机填的、不是服务端的。
     */
    fun previewPayload() {
        if (_busy.value != null) return
        _busy.value = "正在本机装配载荷…"
        viewModelScope.launch {
            repo.previewUploadPayload(tableNo)
                .onSuccess { _preview.value = it }
                .onFailure { _toast.value = "预览失败：${it.message}" }
            _busy.value = null
        }
    }

    fun consumePreview() { _preview.value = null }

    /** 一键：把每个巡检点签到+完成 → 上报开始时间 → 上传各区域 → 提交任务 */
    fun submitAll() {
        if (_busy.value != null) return
        val start = parsePatrolTimeOrNull(_startText.value)
        val end = parsePatrolTimeOrNull(_endText.value)
        if (start == null || end == null) {
            _toast.value = "时间格式不对，应为 yyyy-MM-dd HH:mm:ss"
            return
        }
        val type = _signType.value
        val reason = _signReason.value.trim()
        if (type == PatrolRepository.SIGN_TYPE_MANUAL && reason.isEmpty()) {
            _toast.value = "手动签到必须填原因（原版也是必填）"
            return
        }
        val points = _areas.value.map { area ->
            area to (parsePatrolTimeOrNull(_areaTimes.value[area.id].orEmpty()) ?: start)
        }
        _busy.value = "正在写入本机…"
        viewModelScope.launch {
            // 用户要求：时间检测只提醒、不阻上传 —— 有问题也继续，先把提醒抛出来
            val issues = runCatching {
                repo.validateTaskTimesWithPoints(
                    tableNo = tableNo,
                    start = start,
                    end = end,
                    points = points.map { (area, time) -> time to (area.name ?: "巡检点") },
                )
            }.getOrDefault(emptyList())
            if (issues.isNotEmpty()) {
                _toast.value = "时间提醒：" + issues.first()
            }
            val staffName = Graph.session.reporterDisplayName
            val staffId = Graph.session.reporterRefId
            val result = runCatching {
                // 1. 每个巡检点：按填的时间签到 + 全部巡检项按同一时间置完成
                points.forEach { (area, time) ->
                    _busy.value = "签到：${area.name ?: area.id}"
                    repo.markAreaSigned(
                        areaId = area.id,
                        staff = staffName,
                        staffId = staffId,
                        signType = type,
                        cardTime = time,
                        signInReason = if (type == PatrolRepository.SIGN_TYPE_MANUAL) reason else null,
                    )
                    runCatching { repo.syncWorkItems(tableNo, area.id) }
                    repo.finishArea(tableNo, area.id, time).getOrThrow()
                    // 用户选过的「实际结果」（是否/正常异常）覆盖默认的「正常」
                    _areaItems.value[area.id].orEmpty().forEach { item ->
                        val v = _itemValues.value[item.id] ?: return@forEach
                        val c = _itemConclusions.value[item.id] ?: PatrolConclusion.NORMAL
                        runCatching {
                            repo.saveWorkItemResult(
                                id = item.id,
                                realValue = v,
                                concluse = c,
                                skipReason = null,
                                abnormalReason = null,
                                remark = null,
                                staffId = staffId,
                                staff = staffName,
                            )
                        }
                    }
                }
                // 2. 本机把开始/结束时间钉死：zip 载荷的 actualStartTime/actualEndTime 取自本机任务行
                _busy.value = "记录本机时间…"
                repo.pinLocalTimes(tableNo, start, end).getOrThrow()
                // 3. 直接把整单结果传上去（原版离线补传的路子）：载荷带 completed + 两个本机时间，
                //    服务端据此完成这单并采纳那两个时间；**绝不先推状态机**，
                //    否则它会用自己的时钟盖掉（用户 m10329），失败就如实报错。
                _busy.value = "上传各区域结果…"
                repo.uploadTask(tableNo) { step -> _busy.value = step }.getOrThrow()
                repo.finishSubmit(tableNo)
            }
            result
                .onSuccess { _toast.value = "已按填的时间上传并结束任务" }
                .onFailure { _toast.value = "结束任务失败：${it.message}" }
            _busy.value = null
            reload()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimplifiedPatrolScreen(
    tableNo: String,
    onBack: () -> Unit,
) {
    val vm: SimplifiedPatrolViewModel =
        viewModel(key = "simplified-$tableNo") { SimplifiedPatrolViewModel(tableNo) }
    val task by vm.task.collectAsStateWithLifecycle()
    val areas by vm.areas.collectAsStateWithLifecycle()
    val areaTimes by vm.areaTimes.collectAsStateWithLifecycle()
    val startText by vm.startText.collectAsStateWithLifecycle()
    val endText by vm.endText.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val signType by vm.signType.collectAsStateWithLifecycle()
    val signReason by vm.signReason.collectAsStateWithLifecycle()
    val areaItems by vm.areaItems.collectAsStateWithLifecycle()
    val itemValues by vm.itemValues.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }
    val startMs = parsePatrolTimeOrNull(startText)
    val endMs = parsePatrolTimeOrNull(endText)
    val timeBad = startMs == null || endMs == null ||
        areas.any { parsePatrolTimeOrNull(areaTimes[it.id].orEmpty()) == null }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data -> Snackbar(snackbarData = data) }
        },
        topBar = {
            TopAppBar(
                title = { Text("简化巡检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        task?.taskName ?: "巡检任务",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "规定时间  ${fmt(task?.startTime ?: 0L)} ~ ${fmt(task?.endTime ?: 0L)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            TimeField(
                label = "设置开始任务时间",
                value = startText,
                onValueChange = vm::setStart,
                onNow = { vm.setStart(vm.nowText()) },
                bad = startMs == null,
            )

            Text("巡检点（${areas.size}）", style = MaterialTheme.typography.titleMedium)
            areas.forEachIndexed { index, area ->
                TimeField(
                    label = "巡检点${index + 1} ${area.name.orEmpty()}",
                    value = areaTimes[area.id].orEmpty(),
                    onValueChange = { vm.setAreaTime(area.id, it) },
                    onNow = { vm.setAreaTime(area.id, vm.nowText()) },
                    bad = parsePatrolTimeOrNull(areaTimes[area.id].orEmpty()) == null,
                )
                // 「实际结果」设置（用户要求）：是否型给 是/否，其它给 正常/异常/可疑
                SimplifiedAreaItems(
                    items = areaItems[area.id].orEmpty(),
                    values = itemValues,
                    onPick = vm::pickItem,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::arrangeTimes) { Text("自动随机排实际开始 / 各巡检点 / 实际结束") }
            }

            TimeField(
                label = "设置结束任务时间",
                value = endText,
                onValueChange = vm::setEnd,
                onNow = { vm.setEnd(vm.nowText()) },
                bad = endMs == null,
            )

            // 签到方式：跟常规区域作业面板一致，默认 NFC刷卡签到；手动签到必须填原因（用户 m04138）
            Column {
                Text("签到方式", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = signType == PatrolRepository.SIGN_TYPE_NFC,
                        onClick = { vm.setSignType(PatrolRepository.SIGN_TYPE_NFC) },
                        label = { Text(PatrolRepository.SIGN_TYPE_NFC) },
                    )
                    FilterChip(
                        selected = signType == PatrolRepository.SIGN_TYPE_MANUAL,
                        onClick = { vm.setSignType(PatrolRepository.SIGN_TYPE_MANUAL) },
                        label = { Text(PatrolRepository.SIGN_TYPE_MANUAL) },
                    )
                }
                if (signType == PatrolRepository.SIGN_TYPE_MANUAL) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = signReason,
                        onValueChange = vm::setSignReason,
                        label = { Text("手工签到原因（必填）", fontWeight = FontWeight.Normal) },
                        singleLine = true,
                        isError = signReason.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (timeBad) {
                Text(
                    "时间格式应为 yyyy-MM-dd HH:mm:ss",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = vm::submitAll,
                enabled = busy == null && !timeBad && areas.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy != null) {
                    // 转圈错位修复：原来只设了 width(18dp)，内部默认高度 40dp → 又高又窄看着歪；
                    // 改成和图标同样的 18dp 方形，颜色跟随按钮内容色（禁用时和文字一致）。
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(busy ?: "上传并结束任务")
            }
            // 预览载荷（用户 m05768）：只在本机把要发出去的 JSON 装出来，不发请求。
            // 断网也能点，用来逐字段确认时间/数据全部来自本机。
            TextButton(
                onClick = vm::previewPayload,
                enabled = busy == null && areas.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("预览将要上传的数据（本机装配，不联网）")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val preview by vm.preview.collectAsStateWithLifecycle()
    preview?.let { json ->
        AlertDialog(
            onDismissRequest = vm::consumePreview,
            title = { Text("将要上传的载荷（本机装配）") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(json, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::consumePreview) { Text("知道了") } },
        )
    }
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onNow: () -> Unit,
    bad: Boolean,
) {
    // 用户 m06106：改成六个格子分块填入（中间的分隔符不用打）+ 日期/时间圆盘选择
    DateTimeField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        isError = bad,
        onNow = onNow,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 简化巡检里每个巡检点的「实际结果」设置（用户要求）。
 *
 * - 「是否」型：直接选 是 / 否（就是用户点名要的那个）；
 * - 其它型：选 正常 / 异常 / 可疑（与区域作业页一致）。
 *
 * 不选就按默认「正常」上传（保持原先一键提交的行为）。
 */
@Composable
private fun SimplifiedAreaItems(
    items: List<WorkItemEntity>,
    values: Map<String, String>,
    onPick: (String, String, String) -> Unit,
) {
    if (items.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val candidates = if (ResultEditMode.isWhether(item.editType, item.candidateValue)) {
                item.candidateValue?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            } else {
                emptyList()
            }
            val options: List<Pair<String, String>> = if (candidates.isNotEmpty()) {
                candidates.map { c ->
                    c to if (c == "是" || c == "正常") PatrolConclusion.NORMAL
                    else PatrolConclusion.ABNORMAL
                }
            } else {
                listOf(
                    "正常" to PatrolConclusion.NORMAL,
                    "异常" to PatrolConclusion.ABNORMAL,
                    "可疑" to PatrolConclusion.DOUBTFUL,
                )
            }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    item.content ?: "巡检项",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (label, conclusion) ->
                        FilterChip(
                            selected = values[item.id] == label,
                            onClick = { onPick(item.id, label, conclusion) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.sdyl.patrol.feature.patrol

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.db.AreaEntity
import com.sdyl.patrol.core.data.db.DeviceEntity
import com.sdyl.patrol.core.data.db.TaskEntity
import com.sdyl.patrol.core.data.db.WorkItemEntity
import com.sdyl.patrol.core.nfc.NfcBus
import com.sdyl.patrol.feature.patrol.model.PatrolConclusion
import com.sdyl.patrol.feature.patrol.model.PatrolTaskState
import com.sdyl.patrol.feature.patrol.model.ResultEditMode
import com.sdyl.patrol.feature.patrol.model.SignInPatch
import com.sdyl.patrol.feature.patrol.model.WorkItemPatch
import com.sdyl.patrol.feature.patrol.model.WorkItemState
import com.sdyl.patrol.feature.patrol.upload.PatrolUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/* ================================================================== *
 * 任务详情：区域列表
 * ================================================================== */

class TaskDetailViewModel(private val tableNo: String) : ViewModel() {
    private val repo = Graph.patrolRepository
    private val syncing = MutableStateFlow(false)
    private val busyLabel = MutableStateFlow<String?>(null)
    private val toast = MutableStateFlow<String?>(null)
    private val progress = MutableStateFlow<PatrolRepository.TaskProgress?>(null)

    /** 「打包提交任务」弹窗要展示的服务端已有时间（用户 m13590，只读查询） */
    private val serverInfo = MutableStateFlow<PatrolRepository.ServerTimes?>(null)
    private val serverInfoLoading = MutableStateFlow(false)
    private val serverInfoError = MutableStateFlow<String?>(null)

    val serverTimes: StateFlow<PatrolRepository.ServerTimes?> = serverInfo.asStateFlow()
    val serverTimesLoading: StateFlow<Boolean> = serverInfoLoading.asStateFlow()
    val serverTimesError: StateFlow<String?> = serverInfoError.asStateFlow()

    val areas: StateFlow<List<AreaEntity>> = repo.observeAreas(tableNo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isSyncing: StateFlow<Boolean> = syncing.asStateFlow()

    /** 非空表示正在跑一个写操作（上传/提交），文案直接显示给用户 */
    val busy: StateFlow<String?> = busyLabel.asStateFlow()
    val message: StateFlow<String?> = toast.asStateFlow()
    val taskProgress: StateFlow<PatrolRepository.TaskProgress?> = progress.asStateFlow()

    init {
        // 用户 m07648：**不再**点开页面就自动开始任务 —— 开始改成用户手动点。
        // （服务端在「开始任务」这一步同时写 realstartTime 与 executeUser（执行人），
        //  自动开始会让实际开始时间等于「点开看一眼」的时刻。）
        viewModelScope.launch { reloadProgress() }
    }

    fun consumeMessage() { toast.value = null }

    /**
     * 开始任务（用户 m08202）：默认只写本机，断网也能开始；
     * 勾了「同时上报到服务端」才把这次开始写进服务端。
     *
     * 用户 m14803：勾了「清除延迟开始原因」时，即使服务端已经有一条延迟原因，
     * 也强行覆盖为空（关闭时保持现状，不动服务端已有的值）。
     */
    fun startTask(time: Long, upload: Boolean, clearDelay: Boolean = false) {
        if (busyLabel.value != null) return
        busyLabel.value = if (upload) "正在开始任务并上报…" else "正在开始任务（仅本机）…"
        viewModelScope.launch {
            repo.startTask(tableNo, time, upload, clearDelayReason = clearDelay)
                .onSuccess {
                    toast.value = if (upload) {
                        "任务已开始并上报（实际开始时间 ${patrolDateTimeFormat.format(Date(it))}）"
                    } else {
                        "任务已开始（仅本机，实际开始时间 ${patrolDateTimeFormat.format(Date(it))}）"
                    }
                }
                .onFailure { toast.value = "开始任务失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }

    private suspend fun reloadProgress() {
        progress.value = repo.taskProgress(tableNo)
    }

    /** 获取任务：从服务端把这张任务单重新拉回来（区域、状态、开始/结束时间） */
    fun refresh() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            repo.syncTasks(taskStates = null, keyword = tableNo)
                .onSuccess { toast.value = "已获取任务：$it 条" }
                .onFailure { toast.value = "获取任务失败：${it.message}" }
            reloadProgress()
            syncing.value = false
        }
    }

    /**
     * 领取任务（用户 m15269）：任务详情页右上角也要能领取，不必回列表页。
     * 动作与列表页「领取任务」弹窗同一条路（[PatrolRepository.claimTasks]）——
     * `changeState = PATROL_taskState/issued`，服务端顺势把「状态变更人」写成当前上报人；
     * 成功后本地置「已领取」并回拉一次对账。
     */
    fun claimTask() {
        if (busyLabel.value != null) return
        busyLabel.value = "正在领取任务…"
        viewModelScope.launch {
            repo.claimTasks(listOf(tableNo))
                .onSuccess { toast.value = "已领取任务" }
                .onFailure { toast.value = "领取失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }

    /**
     * 取消领取（用户要求）：与 [claimTask] 共用同一个确认弹窗，
     * 把「已领取」退回「未领取」。见 [PatrolRepository.unclaimTasks]。
     */
    fun unclaimTask() {
        if (busyLabel.value != null) return
        busyLabel.value = "正在取消领取…"
        viewModelScope.launch {
            repo.unclaimTasks(listOf(tableNo))
                .onSuccess { toast.value = "已取消领取" }
                .onFailure { toast.value = "取消领取失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }

    /**
     * 上传任务：用户 m04603 要求「如果任务没有全部完成，则不要允许上传」。
     * 这里只做一次兜底——真正拒绝的逻辑在 [PatrolRepository.uploadTask] 里（本地也算得出来）。
     */
    fun uploadTask() {
        if (busyLabel.value != null) return
        busyLabel.value = "正在打包本地数据…"
        viewModelScope.launch {
            repo.uploadTask(tableNo) { step -> busyLabel.value = step }
                .onSuccess {
                    toast.value = "上传任务成功：${it.areaCount} 个区域 / ${it.itemCount} 条巡检项"
                }
                .onFailure { toast.value = "上传任务失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }

    /**
     * 打包提交（用户 m13590）：把本机的**开始时间 + 各巡检点签到时间 + 结束时间**
     * 连各区域巡检结果一起打包传给服务端 —— 就是原版断网巡检做完后的一次性补传，
     * 服务端会真正采纳本机记下的那几个时间。
     */
    fun packSubmit() {
        if (busyLabel.value != null) return
        busyLabel.value = "正在打包本地数据…"
        viewModelScope.launch {
            repo.uploadTask(tableNo, includeAreas = true) { step -> busyLabel.value = step }
                .onSuccess {
                    toast.value = "已打包上传：${it.areaCount} 个区域 / ${it.itemCount} 条巡检项"
                }
                .onFailure { toast.value = "打包上传失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }

    /** 只读查服务端已有的开始/结束/签到时间（打包弹窗打开时调一次） */
    fun loadServerTimes() {
        if (serverInfoLoading.value) return
        serverInfoLoading.value = true
        serverInfoError.value = null
        viewModelScope.launch {
            repo.fetchServerTimes(tableNo)
                .onSuccess { serverInfo.value = it }
                .onFailure { serverInfoError.value = it.message ?: "查询失败" }
            serverInfoLoading.value = false
        }
    }

    /** 撤销「仅本机」的开始：本机时间删掉并从服务端回拉 */
    fun cancelLocalStart() {
        if (busyLabel.value != null) return
        busyLabel.value = "正在撤销本机开始…"
        viewModelScope.launch {
            repo.cancelLocalStart(tableNo)
                .onSuccess { toast.value = "已撤销本机开始，状态以服务端为准" }
                .onFailure { toast.value = "撤销失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }

    /** 时间校验（用户 m12704：开始任务 / 巡检点签到 / 提交任务三处共用一条规则链） */
    suspend fun checkTimes(start: Long, end: Long = 0L): List<String> =
        repo.validateTaskTimes(tableNo, start, end)

    /**
     * 结束任务（用户 m13917：「提交任务」改名为「结束任务」；流程源自用户 m08202，顺序按 m10329 修）：
     * - 默认「仅写本机」：本机记结束时间，不碰服务端；
     * - 勾了上传：① 本机钉住时间 → ② **直接传 zip**（这才是原版离线补传的样子）。
     *
     * **这个界面不碰开始时间**（用户 m13917）：不显示、不编辑、也不上报。
     * 开始时间只归「开始任务」（勾了上报才传）与「打包提交任务」管 ——
     * 在这儿顺手把它一起报上去的话，服务端一有值就锁死了（用户 m13590 的痛点）。
     *
     * 为什么 zip 打头（用户 m10329）：原版离线整单跑完，本机状态就是「已完成」，
     * 补传时载荷里带着 `actualStartTime/actualEndTime` 与 completed 状态，
     * 服务端据此完成这单并采纳那两个时间。反过来先调 updatePatrolTask 的话，
     * 服务端会用**自己的时钟**把这单标成已开始/已结束（那两个时间一旦有值就不会再被载荷覆盖），
     * 用户设置的离线时间就丢了。所以**只传 zip，不推状态机**（2026-10-14 m10329 复现后改的）。
     */
    fun finishTask(end: Long, upload: Boolean, clearDelay: Boolean = false) {
        if (busyLabel.value != null) return
        busyLabel.value = if (upload) "正在上传并结束…" else "正在结束（仅本机）…"
        viewModelScope.launch {
            // 用户 m11701：服务端已有签字图 / 任务已完成时这次不补签字图，结束之后如实说明
            var signNote = ""
            val result = runCatching {
                if (upload) {
                    // ① 本机先把结束时间钉住：zip 载荷的 actualEndTime 取自本机任务行。
                    //    开始时间只读本机已有的值，没有就不编一个（绝不用"现在"凑）。
                    val localStart = Graph.db.taskDao().byTableNo(tableNo)
                        ?.actualStartTime?.takeIf { it > 0L } ?: 0L
                    busyLabel.value = "正在记录本机时间…"
                    if (localStart > 0L) {
                        repo.pinLocalTimes(tableNo, localStart, end).getOrThrow()
                    } else {
                        repo.endTask(tableNo, end, upload = false).getOrThrow()
                    }
                    // ② 只把任务级（状态 + 结束时间）传给服务端，**不带各区域**（用户 m12413：
                    //    开始时间 / 各区域签到时间 / 结束时间三条各自独立上传，
                    //    各区域结果由区域作业页的「上传本区域到服务端」负责）。
                    //    用户 m10329：**绝不能**先推状态机——那会让服务端用自己的时钟写时间，
                    //    载荷里的断网时间就被无视了。所以失败就如实报错，不退回旧路子。
                    busyLabel.value = "正在上传任务状态与结束时间…"
                    // 用户 m13590/m13917：只报**结束时间**（结束时间之后还能再上传覆盖）。
                    val outcome = repo.uploadTask(
                        tableNo,
                        includeAreas = false,
                        times = PatrolUploader.TimeScope.END_ONLY,
                        // 用户 m14803：勾了「清除延迟结束原因」就带空串覆盖为空
                        clearDelay = if (clearDelay) {
                            PatrolUploader.DelayClear.END
                        } else {
                            PatrolUploader.DelayClear.NONE
                        },
                    ) { step ->
                        busyLabel.value = step
                    }.getOrThrow()
                    if (outcome.signPicSkipped) signNote = "，服务端已有签字图未重复上传"
                    repo.finishSubmit(tableNo)
                    end
                } else {
                    repo.endTask(tableNo, end, upload = false).getOrThrow()
                }
            }
            result
                .onSuccess {
                    toast.value = if (upload) {
                        "已上传并结束任务$signNote（实际结束时间 ${patrolDateTimeFormat.format(Date(it))}）"
                    } else {
                        "已在本机结束任务（实际结束时间 ${patrolDateTimeFormat.format(Date(it))}）"
                    }
                }
                .onFailure { toast.value = "结束任务失败：${it.message}" }
            busyLabel.value = null
            reloadProgress()
        }
    }
}

@Composable
fun TaskDetailScreen(
    tableNo: String,
    onBack: () -> Unit,
    onOpenArea: (String) -> Unit,
) {
    val vm: TaskDetailViewModel = viewModel(key = "task-$tableNo") { TaskDetailViewModel(tableNo) }
    val areas by vm.areas.collectAsStateWithLifecycle()
    val syncing by vm.isSyncing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val toast by vm.message.collectAsStateWithLifecycle()
    val progress by vm.taskProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showStart by remember { mutableStateOf(false) }
    var showFinish by remember { mutableStateOf(false) }
    var showPack by remember { mutableStateOf(false) }
    // 领取/取消领取的二次确认弹窗（用户要求：右上角那不再是单纯一点就领的按钮）
    var showClaimConfirm by remember { mutableStateOf(false) }
    val serverTimes by vm.serverTimes.collectAsStateWithLifecycle()
    val serverTimesLoading by vm.serverTimesLoading.collectAsStateWithLifecycle()
    val serverTimesError by vm.serverTimesError.collectAsStateWithLifecycle()
    val task by Graph.db.taskDao().observeByTableNo(tableNo)
        .collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    if (showClaimConfirm) {
        val stateId = task?.taskStateId
        val isUnclaimed = stateId == PatrolTaskState.NOT_ISSUED
        val isIssued = stateId == PatrolTaskState.ISSUED
        AlertDialog(
            onDismissRequest = { showClaimConfirm = false },
            title = {
                Text(
                    when {
                        isUnclaimed -> "领取任务"
                        isIssued -> "取消领取"
                        else -> "任务状态"
                    },
                )
            },
            text = {
                Text(
                    when {
                        isUnclaimed ->
                            "确认领取这条任务？领取后就可以在区域作业页签到、录入巡检项。"
                        isIssued ->
                            "这条任务当前是「已领取」。取消领取会把它退回「未领取」，其他人可以再领；" +
                                "已经产生的本机签到/录入数据不会因此被删。"
                        else ->
                            "当前状态是「${PatrolTaskState.label(stateId)}」，不能领取或取消领取。"
                    },
                )
            },
            confirmButton = {
                when {
                    isUnclaimed -> TextButton(
                        onClick = {
                            showClaimConfirm = false
                            vm.claimTask()
                        },
                    ) { Text("确认领取") }
                    isIssued -> TextButton(
                        onClick = {
                            showClaimConfirm = false
                            vm.unclaimTask()
                        },
                    ) { Text("确认取消领取") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showClaimConfirm = false }) { Text("关闭") }
            },
        )
    }

    if (showStart) {
        StartTaskDialog(
            task = task,
            busy = busy != null,
            hasLocalStart = Graph.session.hasOfflineTimes(tableNo),
            validate = { time -> vm.checkTimes(time) },
            onDismiss = { showStart = false },
            onCancelLocal = {
                showStart = false
                vm.cancelLocalStart()
            },
            onConfirm = { time, upload, clearDelay ->
                showStart = false
                vm.startTask(time, upload, clearDelay)
            },
        )
    }

    if (showFinish) {
        FinishTaskDialog(
            task = task,
            busy = busy != null,
            // 时间规则链照样查（计划开始<实际开始<最早巡检点<…<实际结束<计划结束）：
            // 实际开始时间取本机已有的那个值，本机没有就传 0，那几条自然跳过
            validate = { end -> vm.checkTimes(task?.actualStartTime ?: 0L, end) },
            onDismiss = { showFinish = false },
            onConfirm = { end, upload, clearDelay ->
                showFinish = false
                vm.finishTask(end, upload, clearDelay)
            },
        )
    }

    if (showPack) {
        PackSubmitDialog(
            task = task,
            areas = areas,
            serverTimes = serverTimes,
            serverLoading = serverTimesLoading,
            serverError = serverTimesError,
            busy = busy != null,
            onReloadServer = vm::loadServerTimes,
            onDismiss = { showPack = false },
            onConfirm = {
                showPack = false
                vm.packSubmit()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 用户要求：这个按钮常驻 —— 未领取/已领取/执行中/已完成等所有状态都显示；
                    // 点开弹二次确认：未领取→领取，已领取→取消领取，其它状态只做说明。
                    IconButton(
                        onClick = { showClaimConfirm = true },
                        enabled = busy == null && !syncing && task != null,
                    ) {
                        Icon(
                            Icons.Filled.PlaylistAdd,
                            contentDescription = if (task?.taskStateId == PatrolTaskState.ISSUED) {
                                "取消领取"
                            } else {
                                "领取任务"
                            },
                        )
                    }
                    // 用户 m08202：开始任务 + 结束任务 两个小图标并排放在右上角，点开各自弹窗
                    IconButton(
                        onClick = { showStart = true },
                        enabled = busy == null && !syncing,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "开始任务")
                    }
                    IconButton(
                        onClick = { showFinish = true },
                        // 用户 m13004：只要本机有这条任务的区域就能结束 —— 「结束任务」只改任务状态
                        // 与结束时间（m12413/m13917），不再要求本机巡检项全部填完，否则原版客户端做完的
                        // 任务在咱们这边连入口都点不开。
                        enabled = (progress?.areaTotal ?: 0) > 0 && busy == null && !syncing,
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "结束任务")
                    }
                    // 用户 m13590：第三个按钮 —— 打包提交任务（开始时间 + 各巡检点签到时间
                    // + 结束时间 一起上传，等于原版断网巡检做完后的一次性补传）
                    IconButton(
                        onClick = {
                            showPack = true
                            vm.loadServerTimes()
                        },
                        enabled = (progress?.areaTotal ?: 0) > 0 && busy == null && !syncing,
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = "打包提交任务")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { TaskActionBar(progress = progress, busy = busy) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (syncing || busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())

            task?.let { t ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            t.taskName ?: "巡检任务",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "状态  ${PatrolTaskState.label(t.taskStateId)}    区域 ${areas.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "计划  ${fmtSec(t.startTime)} ~ ${fmtSec(t.endTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "实际  ${fmtSec(t.actualStartTime)} ~ ${fmtSec(t.actualEndTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Text(
                "巡检区域",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (areas.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "该任务没有区域数据\n可在任务列表点右上角同步图标重新获取",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(areas, key = { it.id }) { area ->
                        AreaCard(area) { onOpenArea(area.id) }
                    }
                }
            }
        }
    }
}

/**
 * 任务详情底部动作栏（用户 m04069 第 1 条 / m04603）。
 *
 * 三个按钮：
 *  - 「获取任务」：从服务端重新拉这张任务单；
 *  - 「上传任务」：整个任务的结果一次性上传 —— **任务没全部完成时按钮置灰**；
 *  - 「结束任务」：结束任务（服务端写 actualEndTime + 记录本次状态变更的操作人）。
 */
@Composable
private fun TaskActionBar(progress: PatrolRepository.TaskProgress?, busy: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                busy ?: progress?.let { "区域已签到 ${it.areaSigned}/${it.areaTotal}" }
                    ?: "正在统计完成情况…",
                style = MaterialTheme.typography.labelMedium,
                color = if (busy != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // 用户反馈：「本机还有 N 条巡检项没填完」不准（本机只同步过打开过的区域，
            // 没打开的区域的巡检项根本不在本机，所以这个数不是整单的真实完成度），已去掉。「巡检项已完成 0/0」同理。
            if (progress != null && !progress.allSigned) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "还有区域没签到，结束任务前请先在区域作业页签到",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AreaCard(area: AreaEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    area.name ?: area.code ?: "巡检区域",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (area.isSigned) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = CircleShape,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("已签到", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                        shape = CircleShape,
                    ) {
                        Text(
                            "未签到",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ================================================================== *
 * 区域作业页：设备 → 巡检项
 * ================================================================== */

class AreaWorkViewModel(private val tableNo: String, private val areaId: String) : ViewModel() {
    private val repo = Graph.patrolRepository
    private val syncing = MutableStateFlow(false)
    private val uploading = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val devices: StateFlow<List<DeviceEntity>> = repo.observeDevices(tableNo, areaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workItems: StateFlow<List<WorkItemEntity>> = repo.observeWorkItems(areaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前区域（要拿 signCode / isSigned）
     */
    val area: StateFlow<AreaEntity?> = repo.observeArea(areaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isSyncing: StateFlow<Boolean> = syncing.asStateFlow()
    val isUploading: StateFlow<Boolean> = uploading.asStateFlow()
    val toast: StateFlow<String?> = message.asStateFlow()

    init { sync() }

    fun sync() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            val d = repo.syncDevices(tableNo, areaId)
            val w = repo.syncWorkItems(tableNo, areaId)
            val err = d.exceptionOrNull() ?: w.exceptionOrNull()
            message.value = if (err != null) {
                "同步失败：${err.message}（显示本地缓存）"
            } else {
                "已同步 ${d.getOrDefault(0)} 个设备 / ${w.getOrDefault(0)} 个巡检项"
            }
            syncing.value = false
        }
    }

    fun consumeToast() { message.value = null }

    fun saveResult(
        id: String,
        realValue: String?,
        concluse: String?,
        skipReason: String?,
        abnormalReason: String?,
        remark: String?,
    ) {
        viewModelScope.launch {
            repo.saveWorkItemResult(
                id = id,
                realValue = realValue,
                concluse = concluse,
                skipReason = skipReason,
                abnormalReason = abnormalReason,
                remark = remark,
            ).onFailure { message.value = it.message }
        }
    }

    /**
     * 统一面板保存：签到 + 多条巡检项一次性写本地。**不上报**。
     */
    fun saveAreaWork(sign: SignInPatch, patches: List<WorkItemPatch>) {
        viewModelScope.launch {
            /*
             * 用户 m14256：签到卡上的「工号」是真工号（90000001 这种），
             * 而库里/上传接口要的是服务端内部 ID（1000000000000952 这种），
             * 这里用人员名录换算；名录里没有就原样存，不编一个 ID。
             * 顺带：只填了工号没填姓名时，把姓名从名录里补出来。
             */
            val resolved = withContext(Dispatchers.IO) {
                val person = Graph.personDirectory.byCode(sign.staffId.orEmpty())
                sign.copy(
                    staff = sign.staff?.takeIf { it.isNotBlank() } ?: person?.name,
                    staffId = Graph.personDirectory.toInternalId(sign.staffId),
                )
            }
            repo.saveAreaWork(
                areaId = areaId,
                staff = resolved.staff,
                staffId = resolved.staffId,
                patches = patches,
                sign = resolved,
            ).onSuccess {
                message.value = "已保存"
            }.onFailure { message.value = it.message }
        }
    }

    fun finishArea() {
        viewModelScope.launch {
            repo.finishArea(tableNo, areaId)
                .onSuccess { message.value = "已标记完成" }
                .onFailure { message.value = it.message }
        }
    }

    /**
     * 区域签到——**只落本地**。
     * 用户要求（m01570/m01571）：巡检人、巡检人 id、签到时间、签到方式都能自己指定，
     * 直接写本地库即可；联网时也绝不把签到结果发给服务端。
     */
    fun signIn(
        signType: String = PatrolRepository.SIGN_TYPE_NFC,
        staffName: String? = Graph.session.reporterDisplayName,
        // 用户 m14256：进这里的是**真工号**（90000001），下面用名录换成内部 ID 再写库
        staffCode: String? = Graph.session.reporterDisplayCode.takeIf { it.isNotBlank() },
        cardTime: Long = System.currentTimeMillis(),
        signCode: String? = null,
        signInReason: String? = null,
    ) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val person = Graph.personDirectory.byCode(staffCode.orEmpty())
                val name = staffName?.takeIf { it.isNotBlank() } ?: person?.name
                name to Graph.personDirectory.toInternalId(staffCode)
            }
            repo.markAreaSigned(
                areaId = areaId,
                staff = resolved.first,
                staffId = resolved.second,
                signType = signType,
                cardTime = cardTime,
                signCode = signCode,
                signInReason = signInReason,
            )
            message.value =
                "已签到：${resolved.first.orEmpty()}（$signType ${patrolLogFormat.format(Date(cardTime))}）"
        }
    }

    /** 撤销本地签到（服务端同样不受影响，只动本地库） */
    fun cancelSignIn() {
        viewModelScope.launch {
            repo.clearAreaSigned(areaId)
            message.value = "已撤销签到"
        }
    }

    /** 签到时间校验（用户 m12704）：把正在填的时间当作本区域的巡检点过一遍统一规则链 */
    suspend fun checkSignTime(time: Long): List<String> = repo.validateSignTime(tableNo, areaId, time)

    /**
     * 把本区域的结果上传给服务端。
     * 只有服务端确认成功后才会清掉本地 dirty 标记；失败保留数据可重试。
     */
    fun uploadArea() {
        if (uploading.value) return
        uploading.value = true
        viewModelScope.launch {
            repo.uploadArea(areaId) { step -> message.value = step }
                .onSuccess {
                    message.value = "上传成功：${it.areaCount} 个区域 / ${it.itemCount} 条巡检项"
                }
                .onFailure { message.value = "上传失败：${it.message}" }
            uploading.value = false
        }
    }
}

@Composable
fun AreaWorkScreen(tableNo: String, areaId: String, onBack: () -> Unit) {
    val vm: AreaWorkViewModel =
        viewModel(key = "area-$tableNo-$areaId") { AreaWorkViewModel(tableNo, areaId) }
    val devices by vm.devices.collectAsStateWithLifecycle()
    val items by vm.workItems.collectAsStateWithLifecycle()
    val area by vm.area.collectAsStateWithLifecycle()
    val syncing by vm.isSyncing.collectAsStateWithLifecycle()
    val uploading by vm.isUploading.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 用户 m07648：区域页只保留「录入/修改」与「上传本区域到服务端」两件事。
    // 用户 m12413：录入不再开弹窗 —— 表单直接长在页面里（见下方 AreaSignCard / AreaItemEditor）。

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("区域作业") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::sync, enabled = !syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {},
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (items.isEmpty() && devices.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (syncing) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "该区域暂无巡检项",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = vm::sync) { Text("重新同步") }
                    }
                }
            } else {
                // 用户 m12413：编辑不再开弹窗，签到与巡检项录入直接长在区域作业页里（共用一份表单状态）
                val form = rememberAreaWorkFormState(area, items)

                // 签到时间校验（用户 m12704）：与开始任务 / 提交任务共用同一套规则，边填边提示
                var signIssues by remember { mutableStateOf<List<String>>(emptyList()) }
                var signChecking by remember { mutableStateOf(false) }
                LaunchedEffect(form.timeText) {
                    val t = runCatching { patrolLogFormat.parse(form.timeText.trim())?.time }.getOrNull()
                    if (t == null) {
                        signIssues = emptyList()
                    } else {
                        signChecking = true
                        signIssues = runCatching { vm.checkSignTime(t) }.getOrDefault(emptyList())
                        signChecking = false
                    }
                }

                // 页面常驻监听刷卡：读到卡就自动把「方式=刷卡」并填入卡号（仍需人工确认时间/人）
                LaunchedEffect(area?.id, form.signCode) {
                    NfcBus.reads.collect { read ->
                        val ok = NfcBus.matches(read, form.signCode)
                        form.nfcOk = ok
                        form.cardCode = read.display
                        form.type = PatrolRepository.SIGN_TYPE_NFC
                        form.nfcHint = if (ok) {
                            "读到卡：${read.display}，与刷卡点一致"
                        } else {
                            "读到卡：${read.display}\n与本区域刷卡点（${form.signCode ?: "未配置"}）不一致，可自行决定是否继续"
                        }
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "__signinfo__") {
                        AreaSignCard(
                            area = area,
                            form = form,
                            issues = signIssues,
                            checking = signChecking,
                            onCancelSigned = vm::cancelSignIn,
                        )
                    }
                    items(devices, key = { it.id }) { device ->
                        val deviceItems = items.filter { it.deviceId == device.id }
                        DeviceSection(
                            device = device,
                            items = deviceItems,
                            form = form,
                        )
                    }
                    val ungrouped = items.filter { d -> devices.none { it.id == d.deviceId } }
                    if (ungrouped.isNotEmpty()) {
                        // 用户 m04512 第 3 条 + m05272 第 3 条：原来这块叫「未归属设备的巡检项」，
                        // 用户完全看不出「绑定」是什么。真相是：很多线路服务端根本不返回设备清单
                        // （getTaskAreaDevice 为空），巡检项自带的 deviceId 也是 null，原版巡检流程里
                        // 压根没有「设备」这一层。所以这里不再解释、不再起怪名字，直接平铺成巡检项。
                        item(key = "__ungrouped__") {
                            DeviceSection(
                                device = DeviceEntity(
                                    id = "__ungrouped__",
                                    taskTableNo = tableNo,
                                    areaId = areaId,
                                    code = null,
                                    name = if (devices.isEmpty()) "巡检项" else "其他巡检项",
                                    areaNum = null,
                                    operationStatusName = null,
                                    sort = 9999,
                                    updatedAt = 0L,
                                ),
                                items = ungrouped,
                                showMetaDash = false,
                                form = form,
                            )
                        }
                    }
                    item(key = "__save__") {
                        Button(
                            onClick = {
                                // toSign() 为 null = 签到时间格式不对，提示已在卡片上
                                form.toSign()?.let { sign ->
                                    vm.saveAreaWork(sign, form.patches(items))
                                }
                            },
                            enabled = signIssues.isEmpty(),
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) { Text("保存到本机") }
                    }
                    item(key = "__upload__") {
                        OutlinedButton(
                            onClick = vm::uploadArea,
                            // 时间不合法时连上传也挡住：否则用户以为刚改的时间跟着走了，其实传的是库里旧的
                            enabled = !uploading && signIssues.isEmpty(),
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            // 转圈动画的错位问题（用户反馈）：原来空闲时只有文字、上传时多出一个转圈，
                            // 文字会被挤着往右跳一下，看起来像“错位”。这里空闲态的领位图标与转圈都占
                            // 同样 18dp 的位置，两种状态布局一模一样，文字不会移动。
                            if (uploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    // 跟随按钮内容色：禁用时与文字同为灰，不然会突然冒出一圈亮色
                                    color = LocalContentColor.current,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (uploading) "正在上传…" else "上传本区域到服务端")
                        }
                    }
                    item(key = "__ids__") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                ReadOnlyRow("任务单据号", area?.taskTableNo ?: "-")
                                ReadOnlyRow("任务 ID", area?.taskId ?: "-")
                                ReadOnlyRow("区域 ID", area?.id ?: "-")
                                ReadOnlyRow("区域编号", area?.code ?: "-")
                                ReadOnlyRow("刷卡点", area?.signCode ?: "未配置")
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================================================================== *
 * 区域签到信息卡（用户 m12413：可编辑，直接长在区域作业页里，不再有单独的「修改」弹窗）
 * ================================================================== */

/**
 * 区域页顶部的签到卡。
 *
 * 用户 m04512 第 3 条曾反馈「签到」和两个「修改」按钮功能重复，当时把这张卡改成只读、
 * 唯一入口留顶栏（开的是同一个「区域作业」面板）。用户 m12413 又要求取消那个弹窗、
 * 把编辑直接合并进区域作业页——于是这张卡现在就是**签到表单本身**：
 * 方式 / 巡检人 / 巡检人 ID / 签到时间 / 卡号 / 签到原因都在这里就地改，底部「保存到本机」生效。
 */
@Composable
private fun AreaSignCard(
    area: AreaEntity?,
    form: AreaWorkFormState,
    issues: List<String>,
    checking: Boolean,
    onCancelSigned: () -> Unit,
) {
    val signed = area?.isSigned == true
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            // 卡里现在全是输入框，底色统一用 surfaceContainerLow —— 之前「已签到」用 primaryContainer，
            // 输入框的描边/标签压在暖色上对比度不够（用户 m12413 把编辑搬进来后才暴露）。
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (signed) "本区域已签到" else "本区域未签到",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (signed) {
                    Text(
                        area?.cardTime?.let { patrolLogFormat.format(Date(it)) } ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("签到方式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.type == PatrolRepository.SIGN_TYPE_NFC,
                    onClick = { form.type = PatrolRepository.SIGN_TYPE_NFC },
                    label = { Text("NFC 刷卡") },
                )
                FilterChip(
                    selected = form.type == PatrolRepository.SIGN_TYPE_MANUAL,
                    onClick = { form.type = PatrolRepository.SIGN_TYPE_MANUAL },
                    label = { Text("手动") },
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = form.staff,
                onValueChange = { form.staff = it },
                label = { Text("巡检人") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = form.staffCode,
                onValueChange = { form.staffCode = it },
                label = { Text("工号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            // 用户 m06106/m12704：三个界面的时间输入统一用 DateTimeField（只打数字、分隔符自动、
            // 带日期/时间圆盘）；这里原来是纯文本框，格式得手打。
            DateTimeField(
                label = "签到时间",
                value = form.timeText,
                onValueChange = { form.timeText = it; form.timeError = null },
                isError = form.timeError != null || issues.isNotEmpty(),
                onNow = {
                    form.timeText = patrolLogFormat.format(Date())
                    form.timeError = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = form.cardCode,
                onValueChange = { form.cardCode = it },
                label = { Text("卡号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = form.signReason,
                onValueChange = { form.signReason = it },
                label = { Text("签到原因") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            form.timeError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } ?: run {
                Spacer(Modifier.height(6.dp))
                TimeCheckResult(
                    parsed = runCatching { patrolLogFormat.parse(form.timeText.trim()) }.getOrNull() != null,
                    checking = checking,
                    issues = issues,
                )
            }
            // 用户 m12704：只保留功能本身，删掉常驻的「把巡检卡贴近…」这类说明；
            // 真读到卡时仍然给结果（成功/不一致）。
            form.nfcHint?.let { hint ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (form.nfcOk) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!form.nfcOk) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (signed) {
                TextButton(onClick = onCancelSigned, modifier = Modifier.fillMaxWidth()) {
                    Text("撤销本机签到")
                }
            }
        }
    }
}

/**
 * 区域作业表单状态（用户 m12413：编辑不再弹窗，直接长在区域作业页里）。
 *
 * 原来这份 remember 状态住在一个模态面板里；现在搬到页面级，
 * 顶部的签到卡、每个巡检项卡片、底部的「保存到本机」共用同一份，任何一处改动立刻互通。
 *
 * 用户要求（m01953 / m07827 / m07745）的规则全部保留：
 *  - 默认就是 NFC 刷卡；
 *  - 巡检人 / 工号不从上次保存值带出，固定取「我的 → 上报人员」（临时替别人签就改输入框）；
 *  - 结论默认「正常」，点一下可取消（=不记录）；保存时把**每条**巡检项都写下去；
 *  - 保存只写本机，绝不上传。
 */
private class AreaWorkFormState(
    area: AreaEntity?,
    items: List<WorkItemEntity>,
    staffName: String,
) {
    val signCode: String? = area?.signCode
    var nfcHint by mutableStateOf<String?>(null)
    var nfcOk by mutableStateOf(false)
    var staff by mutableStateOf(Graph.session.reporterDisplayName.ifBlank { staffName })

    /**
     * 用户 m14256：这里填/显示的是**真工号**（如 `90000001`），不再是服务端内部 ID。
     * 以前默认塞的是 `reporterRefId`（`1000000000000952` 那种），在界面上就是那串
     * 「不知所谓的巡检人 ID」。写库/上传前由 [AreaWorkViewModel.saveAreaWork]
     * 用人员名录换算回内部 ID——上传接口认的一直是内部 ID。
     */
    var staffCode by mutableStateOf(Graph.session.reporterDisplayCode)
    var timeText by mutableStateOf(
        patrolLogFormat.format(Date(area?.cardTime ?: System.currentTimeMillis())),
    )
    var cardCode by mutableStateOf("")
    var signReason by mutableStateOf(area?.signInReason.orEmpty())
    var type by mutableStateOf(PatrolRepository.SIGN_TYPE_NFC)
    var timeError by mutableStateOf<String?>(null)

    /* ---- 每条巡检项的本地结论 ---- */
    var values: Map<String, String> by mutableStateOf(
        items.associate { it.id to (it.realValue ?: it.defaultVal.orEmpty()) },
    )
    var concl: Map<String, String?> by mutableStateOf(
        // 用户 m07745：默认勾选「正常」（已经点过结论的沿用原值；再点一次可取消=不记录）
        items.associate { it.id to (it.concluse ?: PatrolConclusion.NORMAL) },
    )
    var remarks: Map<String, String> by mutableStateOf(
        items.associate { it.id to it.remark.orEmpty() },
    )
    var reasons: Map<String, String> by mutableStateOf(
        items.associate { it.id to it.abnormalReason.orEmpty() },
    )

    /** 用户真正动过的巡检项 id */
    var touched by mutableStateOf(emptySet<String>())

    /** 签到时间解不出来时返回 null，并把提示写进 [timeError] */
    fun toSign(): SignInPatch? {
        val millis = runCatching { patrolLogFormat.parse(timeText.trim())?.time }.getOrNull()
        if (millis == null) {
            timeError = "时间格式不对"
            return null
        }
        timeError = null
        return SignInPatch(
            signTypeLabel = type,
            payCardTypeId = PatrolRepository.payCardTypeOf(type),
            staff = staff.takeIf { it.isNotBlank() },
            // 用户 m14256：表单里填的是工号，换内部 ID 的活交给 saveAreaWork（那里在协程里）
            staffId = staffCode.takeIf { it.isNotBlank() },
            cardTime = millis,
            cardCode = cardCode.takeIf { it.isNotBlank() },
            signInReason = signReason.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 结论默认「正常」，所以保存时把**每条**巡检项都写下去
     * （未动过的沿用其原结论 / 跳过原因）。
     */
    fun patches(items: List<WorkItemEntity>): List<WorkItemPatch> = items.map { item ->
        val c = concl[item.id]
        WorkItemPatch(
            id = item.id,
            realValue = values[item.id].orEmpty().takeIf { it.isNotBlank() },
            concluse = c,
            skipReason = if (item.id in touched) null else item.skipReason,
            abnormalReason = reasons[item.id].orEmpty().takeIf { it.isNotBlank() },
            remark = remarks[item.id].orEmpty().takeIf { it.isNotBlank() },
            finished = !c.isNullOrBlank() || item.skipReason != null,
            taskDetailState = when {
                !c.isNullOrBlank() -> WorkItemState.CHECKED
                item.skipReason != null -> WorkItemState.SKIP
                else -> WorkItemState.PENDING
            },
        )
    }
}

/** 按区域 id + 巡检项 id 集合记住表单状态，避免库里数值刷新时把用户正在填的内容冲掉 */
@Composable
private fun rememberAreaWorkFormState(
    area: AreaEntity?,
    items: List<WorkItemEntity>,
): AreaWorkFormState {
    val staffName = Graph.session.reporterDisplayName
    val ids = items.map { it.id }
    return remember(area?.id, ids) { AreaWorkFormState(area, items, staffName) }
}

/**
 * 单条巡检项的就地编辑卡片（用户 m12413：字段直接摊在区域作业页里，不再弹窗）。
 */
@Composable
private fun AreaItemEditor(item: WorkItemEntity, form: AreaWorkFormState) {
    // 用户反馈：巡检项的左右宽度缩进不正常、过于贴边 —— 外层卡片有 16dp 内边距，
    // 但这条内容自己没加左右间距，文字/输入框直接顶到卡片圆角边。
    // 这里补上与设备标题一致的左右 16dp、上下 12dp。
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.content ?: "巡检项",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (item.finished) {
                Text(
                    "已填",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append("项号 ").append(item.itemNumber ?: "—")
                item.normalRange?.takeIf { it.isNotBlank() }?.let { append("   范围 ").append(it) }
                item.unit?.takeIf { it.isNotBlank() }?.let { append("   单位 ").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // 「是否」型巡检项：直接选候选值（是/否），把用户选的原始答案写进 form.values
        // （保存后落本地 realValue，上传时即 concluse），不再渲染结论按钮 + 实测值输入框。
        val whetherCandidates = if (ResultEditMode.isWhether(item.editType, item.candidateValue)) {
            item.candidateValue?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        } else {
            emptyList()
        }
        if (whetherCandidates.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PatrolConclusion.all.forEach { (value, label) ->
                    FilterChip(
                        selected = form.concl[item.id] == value,
                        onClick = {
                            form.concl = form.concl.toMutableMap().apply {
                                this[item.id] = if (this[item.id] == value) null else value
                            }
                            form.touched = form.touched + item.id
                        },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = form.values[item.id].orEmpty(),
                onValueChange = { v ->
                    form.values = form.values.toMutableMap().apply { this[item.id] = v }
                    form.touched = form.touched + item.id
                },
                label = { Text("实测值") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                whetherCandidates.forEach { candidate ->
                    FilterChip(
                        selected = form.values[item.id] == candidate,
                        onClick = {
                            form.values = form.values.toMutableMap().apply { this[item.id] = candidate }
                            form.concl = form.concl.toMutableMap().apply {
                                this[item.id] = if (candidate == "是" || candidate == "正常") {
                                    PatrolConclusion.NORMAL
                                } else {
                                    PatrolConclusion.ABNORMAL
                                }
                            }
                            form.touched = form.touched + item.id
                        },
                        label = { Text(candidate) },
                    )
                }
            }
        }
        if (PatrolConclusion.isAbnormal(form.concl[item.id])) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = form.reasons[item.id].orEmpty(),
                onValueChange = { v ->
                    form.reasons = form.reasons.toMutableMap().apply { this[item.id] = v }
                    form.touched = form.touched + item.id
                },
                label = { Text("异常原因") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = form.remarks[item.id].orEmpty(),
            onValueChange = { v ->
                form.remarks = form.remarks.toMutableMap().apply { this[item.id] = v }
                form.touched = form.touched + item.id
            },
            label = { Text("备注") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "巡检项 ID  ${item.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/** 只读的一行「字段名 : 值」，用于显示随任务/点位自动带出的 id */
@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ================================================================== *
 * 设备分组 + 巡检项行
 * ================================================================== */

@Composable
private fun DeviceSection(
    device: DeviceEntity,
    items: List<WorkItemEntity>,
    subtitle: String? = null,
    showMetaDash: Boolean = true,
    form: AreaWorkFormState,
) {
    var expanded by remember { mutableStateOf(true) }
    val done = items.count { it.finished }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name ?: device.code ?: "设备",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    val metaLine = buildString {
                        device.code?.let { append("编号 ").append(it).append("  ") }
                        device.areaNum?.let { append("区域号 ").append(it) }
                    }.trim()
                    if (metaLine.isNotBlank()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (subtitle.isNullOrBlank()) {
                        if (showMetaDash) {
                            Text(
                                "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "$done/${items.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                // 副标题放在标题行外面，避免和右侧的完成计数 / 展开箭头挤在一起
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    items.forEach { item ->
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                        AreaItemEditor(item = item, form = form)
                    }
                }
            }
        }
    }
}

/* ================================================================== *
 * 用户 m08202：开始任务 / 提交任务 两个弹窗
 * 时间可手改（断网补录），默认只写本机；勾选后才上报服务端。
 * ================================================================== */

/**
 * 把用户填的时间文本解成毫秒，解不出返回 null。
 *
 * 手机上各种输入法会给 `-` / `:` 前后塞空格（实测 `2026 - 10 - 14 01: 00: 00`），
 * 所以先把数字串抽出来再拼回标准格式，再按非宽松模式解析。
 */
internal fun parsePatrolTimeOrNull(text: String): Long? {
    val nums = Regex("""\d+""").findAll(text).map { it.value }.toList()
    if (nums.size < 5) return null
    val v = nums.mapNotNull { it.toIntOrNull() }
    if (v.size < 5) return null
    val normalized = "%04d-%02d-%02d %02d:%02d:%02d".format(
        v[0], v[1], v[2], v[3], v[4], v.getOrElse(5) { 0 },
    )
    return runCatching { strictTimeFormat.parse(normalized)?.time }.getOrNull()
}

/** 严格解析（非宽松）：2026-13-45 这种也会被判非法 */
private val strictTimeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
    .apply { isLenient = false }

@Composable
private fun StartTaskDialog(
    task: TaskEntity?,
    busy: Boolean,
    hasLocalStart: Boolean,
    validate: suspend (Long) -> List<String>,
    onDismiss: () -> Unit,
    onCancelLocal: () -> Unit,
    onConfirm: (time: Long, upload: Boolean, clearDelay: Boolean) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    var timeText by remember(task) {
        mutableStateOf(patrolDateTimeFormat.format(Date(task?.actualStartTime?.takeIf { it > 0L } ?: now)))
    }
    var upload by remember { mutableStateOf(false) }
    /** 用户 m14803：勾了就要求服务端把「延迟开始原因」覆盖为空 */
    var clearDelay by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf<List<String>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    val parsed = parsePatrolTimeOrNull(timeText)

    LaunchedEffect(timeText) {
        val t = parsePatrolTimeOrNull(timeText)
        if (t == null) {
            issues = emptyList()
            return@LaunchedEffect
        }
        checking = true
        issues = runCatching { validate(t) }.getOrDefault(emptyList())
        checking = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始任务") },
        text = {
            Column {
                // 用户 m06106：分块填入（不用打中间的符号）+ 日期/时间圆盘
                DateTimeField(
                    label = "开始时间",
                    value = timeText,
                    onValueChange = { timeText = it },
                    isError = parsed == null,
                    onNow = { timeText = patrolDateTimeFormat.format(Date()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = upload, onCheckedChange = { upload = it })
                    Text("上报到服务端", style = MaterialTheme.typography.bodyMedium)
                }
                // 用户 m14803：开启＝无论服务端是否已有延迟原因都覆盖为空；关闭＝保持现状
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clearDelay,
                        onCheckedChange = { clearDelay = it },
                        enabled = upload,
                    )
                    Text("清除延迟开始原因", style = MaterialTheme.typography.bodyMedium)
                }
                if (upload) {
                    // 用户 m13590：勾了才提醒 —— 上报那一刻服务端就把这个时间记死了
                    Text(
                        "实际开始时间一旦上传到服务器，后台记录后不可覆盖更改",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TimeCheckResult(parsed != null, checking, issues)
                if (hasLocalStart) {
                    TextButton(onClick = onCancelLocal) { Text("撤销本机开始") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(it, upload, clearDelay) } },
                enabled = parsed != null && issues.isEmpty() && !checking && !busy,
            ) { Text("确定开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 时间校验结果（用户 m12704）：解析失败 / 正在校验 / 通过 / 逐条问题。
 * 三个界面共用，避免各写一份。
 */
@Composable
private fun TimeCheckResult(
    parsed: Boolean,
    checking: Boolean,
    issues: List<String>,
) {
    when {
        !parsed -> Text(
            "时间格式不对",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        checking -> Text(
            "正在检查时间…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        issues.isEmpty() -> Text(
            "时间检查通过",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> Column {
            issues.forEach { line ->
                Text(
                    "• $line",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 「结束任务」里结束时间（= 实际结束）的默认值。
 *
 * 必须一开场就落在计划窗口内，否则「实际结束 ≤ 计划结束」这条校验会直接把
 * 确定按钮压灰，用户会以为结束功能坏了（用户 m13004 就是这个现象）。
 */
private fun initialSubmitTime(task: TaskEntity?, now: Long): Long {
    task?.actualEndTime?.takeIf { it > 0L }?.let { return it }
    val planStart = task?.startTime ?: 0L
    val planEnd = task?.endTime ?: 0L
    if (planEnd > 0L && now >= planEnd) return planEnd - 1000L
    if (planStart > 0L && now < planStart) return planStart
    return now
}

/**
 * 「结束任务」弹窗（用户 m13917 从「提交任务」改名）。
 *
 * 这个弹窗**只有结束时间**：开始时间既不显示也不可编辑（用户 m13917：
 * 「之前我让你不在这个界面编辑，你给改成灰色了」——那就干脆不出现），
 * 只留「上传结束时间」这一件事，勾了「上报到服务端」也只报结束时间。
 */
@Composable
private fun FinishTaskDialog(
    task: TaskEntity?,
    busy: Boolean,
    validate: suspend (end: Long) -> List<String>,
    onDismiss: () -> Unit,
    onConfirm: (end: Long, upload: Boolean, clearDelay: Boolean) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    var endText by remember(task) {
        mutableStateOf(patrolDateTimeFormat.format(Date(initialSubmitTime(task, now))))
    }
    var upload by remember { mutableStateOf(false) }
    /** 用户 m14803：勾了就要求服务端把「延迟结束原因」覆盖为空 */
    var clearDelay by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf<List<String>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }

    val end = parsePatrolTimeOrNull(endText)

    LaunchedEffect(endText) {
        if (end == null) {
            issues = emptyList()
            return@LaunchedEffect
        }
        checking = true
        issues = runCatching { validate(end) }.getOrDefault(emptyList())
        checking = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("结束任务") },
        text = {
            Column {
                DateTimeField(
                    label = "结束时间（实际结束）",
                    value = endText,
                    onValueChange = { endText = it },
                    isError = end == null,
                    onNow = { endText = patrolDateTimeFormat.format(Date()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = upload, onCheckedChange = { upload = it })
                    Text("上报到服务端", style = MaterialTheme.typography.bodyMedium)
                }
                // 用户 m14803：开启＝无论服务端是否已有延迟原因都覆盖为空；关闭＝保持现状
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clearDelay,
                        onCheckedChange = { clearDelay = it },
                        enabled = upload,
                    )
                    Text("清除延迟结束原因", style = MaterialTheme.typography.bodyMedium)
                }
                if (upload) {
                    Text(
                        // 用户 m13590/m13917：只报结束时间（开始时间归开始任务/打包提交任务管）
                        "只上报实际结束时间（结束时间之后还能再上传覆盖）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TimeCheckResult(end != null, checking, issues)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (end != null) onConfirm(end, upload, clearDelay) },
                enabled = end != null && issues.isEmpty() && !checking && !busy,
            ) { Text("确定结束") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 「打包提交任务」弹窗（用户 m13590）。
 *
 * 把这一单的三个时间按「本机 / 服务器」并排摊开，服务端那份是**只读查出来的**：
 *  - 实际开始时间（服务端已有值就不会再被覆盖）
 *  - 各巡检点签到时间
 *  - 实际结束时间
 *
 * 「打包上传」= 开始时间 + 各巡检点签到时间 + 结束时间 + 各区域巡检结果一起传上去，
 * 也就是原版断网巡检做完后的一次性补传，服务端会真正采纳本机记的这几个时间。
 */
@Composable
private fun PackSubmitDialog(
    task: TaskEntity?,
    areas: List<AreaEntity>,
    serverTimes: PatrolRepository.ServerTimes?,
    serverLoading: Boolean,
    serverError: String?,
    busy: Boolean,
    onReloadServer: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val serverText: (Long) -> String = { if (serverLoading) "查询中…" else fmtSec(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打包提交任务") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TimePairRow(
                    "实际开始时间",
                    fmtSec(task?.actualStartTime ?: 0L),
                    serverText(serverTimes?.actualStartTime ?: 0L),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "各巡检点签到时间",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (areas.isEmpty()) {
                    Text(
                        "本机没有这张单的区域数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                areas.forEach { area ->
                    TimePairRow(
                        area.name.orEmpty().ifBlank { area.id },
                        fmtSec(area.cardTime ?: 0L),
                        serverText(serverTimes?.cardTimeOf(area.id) ?: 0L),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TimePairRow(
                    "实际结束时间",
                    fmtSec(task?.actualEndTime ?: 0L),
                    serverText(serverTimes?.actualEndTime ?: 0L),
                )
                Spacer(Modifier.height(10.dp))
                val serverHasStart = (serverTimes?.actualStartTime ?: 0L) > 0L
                Text(
                    if (serverHasStart) {
                        "服务端已有实际开始时间，这次上传不会覆盖它"
                    } else {
                        "实际开始时间一旦上传，后台记录后不可覆盖更改"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (serverHasStart) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (serverError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "查服务端失败：$serverError",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onReloadServer) { Text("重新查询") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("打包上传") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 「本机 / 服务器」两行对照的一小段（打包弹窗用） */
@Composable
private fun TimePairRow(label: String, local: String, server: String) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("本机　$local", style = MaterialTheme.typography.bodySmall)
        Text(
            "服务器　$server",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

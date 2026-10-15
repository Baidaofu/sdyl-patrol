package com.sdyl.patrol.feature.patrol

import androidx.room.withTransaction
import com.sdyl.patrol.core.data.SessionStore
import com.sdyl.patrol.core.data.db.AreaEntity
import com.sdyl.patrol.core.data.db.DeviceEntity
import com.sdyl.patrol.core.data.db.SdylDatabase
import com.sdyl.patrol.core.data.db.TaskEntity
import com.sdyl.patrol.core.data.db.WorkItemEntity
import com.sdyl.patrol.core.device.DeviceIdentity
import com.sdyl.patrol.core.network.ApiClient
import com.sdyl.patrol.core.network.ApiException
import com.sdyl.patrol.core.network.BapResponse
import com.sdyl.patrol.feature.patrol.model.GetTaskAreaDetailRequest
import com.sdyl.patrol.feature.patrol.model.GetTaskListRequest
import com.sdyl.patrol.feature.patrol.model.PatrolBaseArea
import com.sdyl.patrol.feature.patrol.model.PatrolBaseRoute
import com.sdyl.patrol.feature.patrol.model.PatrolConclusion
import com.sdyl.patrol.feature.patrol.model.PatrolTask
import com.sdyl.patrol.feature.patrol.model.PatrolTaskDevice
import com.sdyl.patrol.feature.patrol.model.PatrolTaskState
import com.sdyl.patrol.feature.patrol.model.PatrolWorkItem
import com.sdyl.patrol.feature.patrol.model.SignInPatch
import com.sdyl.patrol.feature.patrol.model.WorkItemPatch
import com.sdyl.patrol.feature.patrol.model.WorkItemState
import kotlinx.coroutines.flow.Flow

/**
 * 巡检仓库：**本地优先**。
 *  - 在线：从服务端拉任务/区域/设备/巡检项，全部落 Room
 *  - 离线：页面只读 Room，断网照样能看任务、能录入
 *  - 录入结果写在 work_item 上并置 dirty，联网后由上传器消费
 */
class PatrolRepository(
    private val api: ApiClient,
    private val session: SessionStore,
    private val db: SdylDatabase,
    cacheDir: java.io.File,
    /**
     * 完成人签字图（用户 m05038）：按账号存一张图片，上传时打进 zip 的 `pics/`。
     * 账户隔离由 [SignStore] + [SessionStore.accountKey] 保证。
     */
    private val signStore: com.sdyl.patrol.core.data.SignStore,
) {
    private fun service(): PatrolApi = api.create(PatrolApi::class.java)

    private val uploader =
        com.sdyl.patrol.feature.patrol.upload.PatrolUploader(
            api = api,
            db = db,
            cacheDir = cacheDir,
            baseUrl = session.baseUrl,
            // 原版 XJUploadWorkBO 的两个设备/账号字段，每次上传时现取（设置改完立刻生效）
            imei = { session.uploadImei },
            phoneNum = { session.uploadPhoneNum },
            // 用户 m07827：本地没记到人时的兜底＝「我的 → 上报人员」（默认登录账号）
            reporterId = { session.reporterRefId },
            reporterName = { session.reporterDisplayName },
            // 签字图：当前账号那张（没有就不带，上传不因缺签字而失败）
            signPicFile = { signStore.fileFor(session).takeIf { it.isFile && it.length() > 0L } },
        )

    private val taskDao get() = db.taskDao()
    private val areaDao get() = db.areaDao()
    private val deviceDao get() = db.deviceDao()
    private val workDao get() = db.workItemDao()

    fun observeTasks(): Flow<List<TaskEntity>> = taskDao.observeAll()

    /**
     * 我签过到的任务号（主页「我的」分类用）。
     * 服务端任务投影不带 executeUser，也没有「按领取人过滤」的接口，只能本地反推。
     */
    fun observeMySignedTableNos(): Flow<List<String>> =
        areaDao.observeTaskNosSignedBy(session.staffId.toString())
    fun observeAreas(tableNo: String): Flow<List<AreaEntity>> = areaDao.observeByTask(tableNo)
    fun observeDevices(tableNo: String, areaId: String): Flow<List<DeviceEntity>> =
        deviceDao.observeByArea(tableNo, areaId)

    fun observeWorkItems(areaId: String): Flow<List<WorkItemEntity>> = workDao.observeByArea(areaId)

    /* ------------------------- 同步 ------------------------- */

    /**
     * 在线刷新任务列表（含区域），写入本地库。
     *
     * [windowStart]/[windowEnd]：只拉这个时间窗内的任务（服务端 startTime/endTime，毫秒）。
     * 传 null 表示不限时间——**只在用户显式选「全部日期」时这么用**，
     * 因为该账号 13368 条历史任务，全量拉取又慢又没意义。
     * [replaceAll]：先清空本地任务再写入（用于「全部日期」重新拉取的场景）。
     */
    suspend fun syncTasks(
        taskStates: String?,
        keyword: String?,
        windowStart: Long? = null,
        windowEnd: Long? = null,
        replaceAll: Boolean = false,
    ): Result<Int> = runCatching {
        val body = GetTaskListRequest().apply {
            this.pageNo = 1
            this.pageSize = 200
            this.paging = true
            this.staffId = session.staffId
            this.taskStates = taskStates
            windowStart?.let { this.startTime = it.toString() }
            windowEnd?.let { this.endTime = it.toString() }
            keyword?.takeIf { it.isNotBlank() }?.let { key ->
                // 用户 m07647 反馈「获取任务永远 0 条」：原来 tableNo 和 routeName 同时传同一个值，
                // 服务端按 AND 拼接就一条都匹配不上。按形状只传其中一个。
                if (key.startsWith("patrolTask", ignoreCase = true)) this.tableNo = key
                else this.routeName = key
            }
        }
        val tasks = unwrap(service().getTaskList(body)).items
        if (replaceAll) taskDao.clear()
        cacheTasks(tasks)
        tasks.size
    }

    /**
     * 「服务端认定这张单是我领的」→ 任务号集合。**靠 `getMyTaskListArea` 的 `acquirerId`**。
     *
     * 用户 m05347 报的 bug（在原版客户端领的 6~8 点余热发电任务进不了「我的」）原来判成
     * 「服务端没有可查的『谁领的』」——**错了**。2026-10-14 从原版在线巡检首页
     * `module_patrol_online/ui/XJOnlineHomeActivity.java:140` 找到口径：
     * `initView()` 里直接写 `searchBO.acquirerId = 登录账号`，界面上那个勾选框
     * `cbMyTask`（:191）取消勾选时把它置 null —— 这就是「只显示我的」。
     *
     * 实测（`probe/q_mine_acquirer.py`，查 2026-10-14 全天）：
     *  - 只带 `staffId`（我们原来的做法）→ 24 条，是整个工艺外操班组的单；
     *  - `staffId` + `acquirerId=我` → 只剩 `1941`/`1942` 两条，正是我领的那两张；
     *  - `acquirerId` 必须和 `staffId` 一起传（单传服务端 500）；
     *  - 时间窗口要传毫秒字符串（原版 `DateUtils.dateFormat` 返回的就是毫秒；
     *    传 `2026-10-14 00:00:00` 这种文本服务端同样 500）。
     */
    suspend fun fetchMyServerTableNos(dayStart: Long, dayEnd: Long): Result<Set<String>> = runCatching {
        val body = GetTaskListRequest().apply {
            this.pageNo = 1
            this.pageSize = 200
            this.paging = true
            this.staffId = session.staffId
            this.acquirerId = session.staffId
            this.taskStates = "${PatrolTaskState.NOT_ISSUED},${PatrolTaskState.ISSUED}," +
                "${PatrolTaskState.RUNNING},${PatrolTaskState.COMPLETED}"
            this.startTime = dayStart.toString()
            this.endTime = dayEnd.toString()
        }
        unwrap(service().getTaskList(body)).items.mapNotNull { it.tableNo }.toSet()
    }

    /**
     * 服务端这张单已经记下的时间（用户 m13590 的「打包提交任务」弹窗用）。
     * 0 = 服务端还没有这个时间。
     */
    data class ServerTimes(
        val actualStartTime: Long,
        val actualEndTime: Long,
        /** 区域 id / taskAreaId → 服务端已有的签到时间（毫秒） */
        val areaCardTimes: Map<String, Long>,
    ) {
        fun cardTimeOf(areaId: String): Long = areaCardTimes[areaId] ?: 0L
    }

    /**
     * **只读**查服务端这张单的时间（一个字段都不写本机库、不改任何状态）。
     *
     * 「打包提交任务」弹窗要能把「本机记的」和「服务端已有的」并排给用户看：
     * 服务端那边实际开始时间一旦有值就不再覆盖，用户得先看清再决定传不传。
     */
    suspend fun fetchServerTimes(tableNo: String): Result<ServerTimes> = runCatching {
        val body = GetTaskListRequest().apply {
            this.pageNo = 1
            this.pageSize = 20
            this.paging = true
            this.staffId = session.staffId
            this.tableNo = tableNo
        }
        val task = unwrap(service().getTaskList(body)).items
            .firstOrNull { it.tableNo.equals(tableNo, ignoreCase = true) }
            ?: throw ApiException(0, "服务端没有这张单：$tableNo")
        val cards = HashMap<String, Long>()
        task.taskArea.orEmpty().forEach { a ->
            val t = a.cardTime ?: 0L
            if (t <= 0L) return@forEach
            a.id?.takeIf { it.isNotBlank() }?.let { cards[it] = t }
            a.taskAreaId?.takeIf { it.isNotBlank() }?.let { cards[it] = t }
        }
        ServerTimes(
            actualStartTime = task.actualStartTime,
            actualEndTime = task.actualEndTime,
            areaCardTimes = cards,
        )
    }

    private suspend fun cacheTasks(tasks: List<PatrolTask>) {        val now = System.currentTimeMillis()
        // 新落库的任务号（下面用它们补「获取任务」日志）
        val newTableNos = ArrayList<String>()
        // 用户 m08202：本机「离线开始/收工」过的任务，其时间与状态不能被服务端的旧值顶掉。
        // 判据是 SessionStore 里的离线时间账本（它不随同步变化），而不是某一列。
        val localRows = runCatching { taskDao.listByTableNos(tasks.mapNotNull { it.tableNo }) }
            .getOrDefault(emptyList())
            .associateBy { it.tableNo }
        taskDao.upsertAll(tasks.map { t ->
            val entity = t.toEntity(now)
            val local = localRows[entity.tableNo]
            // 任务日志是本机攒的（获取任务/开始任务/结束任务），同步不许把它冲掉
            val carried = if (local != null) entity.copy(taskLog = local.taskLog) else entity
            if (local == null && entity.tableNo != null) newTableNos += entity.tableNo
            if (local != null && entity.tableNo != null && session.hasOfflineTimes(entity.tableNo)) {
                carried.copy(
                    actualStartTime = local.actualStartTime.takeIf { it > 0L } ?: entity.actualStartTime,
                    actualEndTime = local.actualEndTime.takeIf { it > 0L } ?: entity.actualEndTime,
                    taskStateId = local.taskStateId,
                    taskStateLabel = local.taskStateLabel,
                    executeUser = local.executeUser,
                )
            } else {
                carried
            }
        })
        // 用户 m05038 第 2 条：任务第一次落进本机时记一行「获取任务」
        // （原版 `XJTaskDTO.setLog` 就是这么写的，见抓包里的 `05:32:32 张三 获取任务`）
        newTableNos.forEach { appendTaskLog(it, ACTION_GET_TASK, now) }
        val areas = tasks.flatMap { task ->
            val tableNo = task.tableNo ?: return@flatMap emptyList()
            // 本地已签到但还没上传的区域，不能被服务端的旧状态覆盖掉
            val local = areaDao.listByTask(tableNo).associateBy { it.id }
            task.taskArea.orEmpty().map {
                it.toEntity(tableNo, task.taskId.toString(), now, local[it.id ?: it.taskAreaId ?: ""])
            }
        }
        if (areas.isNotEmpty()) areaDao.upsertAll(areas)
    }

    /** 区域实体里记着数字 taskId（写库时由 PatrolTask.taskId 落盘） */
    private suspend fun areaTaskId(areaId: String): Long? =
        runCatching { areaDao.byId(areaId)?.taskId?.toLongOrNull() }.getOrNull()

    /** 拉某区域的设备清单（服务端要求 taskId + taskAreaId） */
    suspend fun syncDevices(tableNo: String, areaId: String): Result<Int> = runCatching {
        val body = GetTaskAreaDetailRequest().apply {
            this.tableNo = tableNo
            this.taskId = areaTaskId(areaId)
            this.taskAreaId = areaId
            this.paging = false
        }
        val devices: List<PatrolTaskDevice> =
            runCatching { unwrap(service().getAreaDevices(body)) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        if (devices.isNotEmpty()) {
            deviceDao.upsertAll(
                devices.mapIndexed { index, d -> d.toEntity(tableNo, areaId, index, now) },
            )
        }
        devices.size
    }

    /** 拉某区域（可指定设备）的巡检项 */
    suspend fun syncWorkItems(
        tableNo: String,
        areaId: String,
        deviceId: String? = null,
    ): Result<Int> = runCatching {
        val body = GetTaskAreaDetailRequest().apply {
            this.tableNo = tableNo
            this.taskId = areaTaskId(areaId)
            this.taskAreaId = areaId
            this.deviceId = deviceId
            this.pageNo = 1
            this.pageSize = 500
        }
        val items = unwrap(service().getTaskWorks(body)).items
        val now = System.currentTimeMillis()
        val existing = workDao.listByArea(areaId).associateBy { it.id }
        workDao.upsertAll(
            items.map { item ->
                val local = existing[item.localId]
                item.toEntity(tableNo, areaId, local, now)
            },
        )
        items.size
    }

    /* ------------------------- 执行 ------------------------- */

    /**
     * 开始任务 —— 巡检流程的第一步。
     *
     * 用户说明（m03805）：没有「任务领取时间」，但有**任务开始时间**；
     * 必须先开始任务，才能对任务下的任务点做签到。
     * 时间顺序：实际开始时间 < 任务点签到时间 < 实际结束时间。
     * 服务端也确实这么校验：任务还是 notIssued / actualStartTime 为空时，
     * `uploadTaskResult` 会在写完区域与巡检项之后抛 `BAPException`（500）。
     *
     * 做法与原版 `XJUpdateTaskStateController` 一致，但**本地先行**：
     *  1. 先写本地 `actualStartTime` + 置「进行中」+ 记执行人（离线也能干活）；
     *  2. 再上报 `POST /msService/PATROL/mobile/patrolTask/updatePatrolTask`，
     *     body = `{mobileVersion, equipmentModel, macAddress, taskId, status, userId}`
     *     —— 与 `XJUpdateTaskStateBO` 逐字对齐，**不带 `startTime`**（见 [logFields]）；
     *     `userId` 缺了服务端会回「巡检任务ID和执行人员ID不能为空！」；
     *  3. 上报成功则把该任务重新同步一次——服务端会用自己的时间落 `actualStartTime`
     *     并把任务的 `executeUser`（用户说的「状态变更人」）写成当前登录人。
     *
     * 用户 m04512 第 2 条要的「状态变更人」就是服务端的 `executeUser`，
     * **由这一步（开始任务）写入**，不是结束任务、也不是领取任务。
     *
     * @return 生效后的实际开始时间（毫秒）
     */
    suspend fun startTask(
        tableNo: String,
        time: Long = System.currentTimeMillis(),
        upload: Boolean = true,
        /**
         * 上报成功后要不要「收尾」（撤本机账本 + 立刻回拉服务端）。
         *
         * 用户 m10329 的离线链路要求：本机时间必须活到 **上传 zip 那一刻**——
         * zip 载荷（`XJUploadDataBO.actualStartTime/actualEndTime`）才是服务端认的时间来源，
         * 而 `updatePatrolTask` 上的 `startTime` 服务端根本不看（实测传 04:10、落 04:19:47）。
         * 所以「上传并提交」里这些状态机调用都走 finalize=false，
         * 免得中间那一次回拉把本机时间冲掉。见 [pinLocalTimes]。
         */
        finalize: Boolean = true,
        /**
         * 用户 m14803：勾了「清除延迟开始原因」就把服务端那个字段强行覆盖为空（空字符串）。
         * 关闭（默认）时该字段整个不带，服务端已有的值不动。
         */
        clearDelayReason: Boolean = false,
    ): Result<Long> = runCatching {
        val task = taskDao.byTableNo(tableNo) ?: throw ApiException(0, "找不到任务：$tableNo")
        val now = System.currentTimeMillis()
        val prevState = task.taskStateId.orEmpty()
        // 上报人员跟随「我的 → 上报人员」设置（默认＝登录账号，见 SessionStore）
        val staffId = session.reporterRefId.takeIf { it.isNotBlank() }
        val staffName = session.reporterDisplayName

        taskDao.startTask(
            tableNo = tableNo,
            time = time,
            stateId = PatrolTaskState.RUNNING,
            stateLabel = PatrolTaskState.label(PatrolTaskState.RUNNING),
            staffId = staffId,
        )
        // 任务日志：原版 `setLog(currentTime, R.string.xj_start_task)`
        appendTaskLog(tableNo, ACTION_START_TASK, time, staffName)

        // 用户 m08202：默认「仅本机」——断网也能开始，时间先记在本机账本里，
        // 联网后提交时用的仍是这个时间（见 SessionStore.OfflineTimes）。
        if (!upload) {
            session.setOfflineStart(
                tableNo = tableNo,
                time = time,
                staffId = staffId.orEmpty(),
                staffName = staffName,
                prevState = prevState,
            )
            // 「只写本机」不再算「我领的任务」（用户 m09256：凝结水处理站被莫名归到「我的」）。
            // 本机账本已足够让界面标出「本机已开始·未上传」；真上报成功后才归入我的。
            return@runCatching time
        }

        // 用户 m13590：勾了「上报到服务端」，服务端要记下的就必须**正好是本机这个时间**。
        // 所以先走 zip 载荷的 `actualStartTime`（实测服务端认这个来源），再推状态机；
        // 反过来先推状态机的话，服务端会用自己的时钟写 actualStartTime，那个值一旦有值
        // 就不再被载荷覆盖（用户 m10329 实测：传 04:10 落 04:19:47）——他要的
        // 「上传后不可覆盖」前提是上传的正好是他选的时间。
        val areas = areaDao.listByTask(tableNo)
        if (areas.isEmpty()) throw ApiException(0, "该任务没有区域数据，请先联网获取任务")
        uploadTask(
            tableNo = tableNo,
            includeAreas = false,
            times = com.sdyl.patrol.feature.patrol.upload.PatrolUploader.TimeScope.START_ONLY,
            includeSignPic = false,
            clearDelay = if (clearDelayReason) {
                com.sdyl.patrol.feature.patrol.upload.PatrolUploader.DelayClear.START
            } else {
                com.sdyl.patrol.feature.patrol.upload.PatrolUploader.DelayClear.NONE
            },
        ).getOrThrow()

        val body = mutableMapOf<String, Any?>(
            "taskId" to task.taskId,
            "status" to PatrolTaskState.RUNNING,
            "userId" to session.reporterRefIdLong,
        )
        // 用户 m08985 第 2 条：时间一律以本机弹窗里设置的时间为准。
        // 原来只在「手改过时间」时才带 startTime（在线时让服务端自己记时），
        // 断网补录出来的时间就会被服务端的当前时钟顶掉 —— 超时的锅。
        body["startTime"] = time
        // 用户 m14803：「清除延迟开始原因」打开时强行覆盖为空
        // （原版 `XJUpdateTaskStateBO.delayedStartReason`，空串＝清空）
        if (clearDelayReason) body["delayedStartReason"] = ""
        body.putAll(logFields())

        val reported = runCatching { service().updateTaskState(body) }
        if (reported.getOrNull()?.isOk == true) {
            if (finalize) {
                // 服务端认了，以服务端为准；本机账本里那条离线时间可以撤了
                session.clearOfflineTimes(tableNo)
                runCatching { syncTasks(taskStates = null, keyword = tableNo) }
            }
        } else {
            // 状态机没成功：本机时间已经报上去了，账本先留着，状态可再点一次上报
            session.setOfflineStart(tableNo, time, staffId.orEmpty(), staffName)
        }
        // 开始过的任务也算「我的」
        session.addClaimedTasks(listOf(tableNo))
        time
    }

    /**
     * 撤销「仅本机」的开始：删掉本机账本里那条离线时间，再从服务端回拉这张任务单，
     * 本地状态随之回到服务端的真实状态（通常＝待检、实际时间空）。
     */
    suspend fun cancelLocalStart(tableNo: String): Result<Unit> = runCatching {
        val prev = session.offlineTimes(tableNo)?.prevState?.takeIf { it.isNotBlank() }
            ?: PatrolTaskState.ISSUED
        session.clearOfflineTimes(tableNo)
        taskDao.cancelLocalStart(tableNo, prev, PatrolTaskState.label(prev))
    }

    /**
     * 结束/提交任务 —— 巡检流程的最后一步（用户 m04512 第 1 条要找的「提交任务」按钮）。
     *
     * 服务端表现为：`actualEndTime` 落地、`taskState` 变「已完成」。
     *
     * 与原版 `XJUpdateTaskStateController.requestUpdateTaskState(false)` 一致，body 为
     * `{mobileVersion, equipmentModel, macAddress, taskId, status, userId, completeStaffId, signPicPath}`
     * （同样**不带 `startTime`**，见 [logFields]）；
     * 其中 `completeStaffId` 原版取 `completeStaff?.id ?: staffId`（本应用没有选人，用当前登录人员）。
     *
     * 先落本地（离线也能收工），上报失败则立刻回拉一次服务端把本地纠正回来。
     *
     * @return 生效后的实际结束时间（毫秒）
     */
    suspend fun endTask(
        tableNo: String,
        time: Long = System.currentTimeMillis(),
        upload: Boolean = true,
        /** 同 [startTask]：false＝上报后不撤账本、不回拉，本机时间留到 zip 上传那一刻 */
        finalize: Boolean = true,
    ): Result<Long> = runCatching {
        val task = taskDao.byTableNo(tableNo) ?: throw ApiException(0, "找不到任务：$tableNo")
        val now = System.currentTimeMillis()
        val staffId = session.reporterRefIdLong

        taskDao.endTask(
            tableNo = tableNo,
            time = time,
            stateId = PatrolTaskState.COMPLETED,
            stateLabel = PatrolTaskState.label(PatrolTaskState.COMPLETED),
        )
        // 任务日志：原版 `setLog(currentTime, R.string.xj_end_task)`
        appendTaskLog(tableNo, ACTION_END_TASK, time, session.reporterDisplayName)

        // 用户 m08202：默认「仅本机」——断网也能收工，结束时间记在本机账本
        if (!upload) {
            session.setOfflineEnd(tableNo, time)
            return@runCatching time
        }

        val body = mutableMapOf<String, Any?>(
            "taskId" to task.taskId,
            "status" to PatrolTaskState.COMPLETED,
            "userId" to session.reporterRefIdLong,
            "completeStaffId" to staffId,
            "signPicPath" to null,
        )
        // 用户 m08985 第 2 条：结束时间同样以本机弹窗里设置为准（原版这个字段也叫 startTime）
        body["startTime"] = time
        body.putAll(logFields())

        val resp = runCatching { service().updateTaskState(body) }
        val response = resp.getOrNull()
        if (response?.isOk != true) {
            // 服务端没确认：把本机时间留住（离线收工），并提示失败
            session.setOfflineEnd(tableNo, time)
            val err = resp.exceptionOrNull()
            throw ApiException(
                (err as? ApiException)?.code ?: 0,
                err?.message ?: response?.errorText ?: "结束任务失败",
            )
        }
        session.clearOfflineTimes(tableNo)
        if (finalize) runCatching { syncTasks(taskStates = null, keyword = tableNo) }
        time
    }

    /**
     * 把「本机设定的开始/结束时间」钉在本机任务行上（纯本地，不联网、不回拉）。
     *
     * 为什么需要它（用户 m10329）：原版的离线时间是由 **`uploadTaskResult` 的 zip 载荷**
     * 带到服务端的——`XJUploadDataBO.actualStartTime/actualEndTime` 取自本机 `XJTaskDTO`。
     * 而 `updatePatrolTask` 请求体里的 `startTime`（原版 `XJUpdateTaskStateBO.updateTime`）
     * 服务端并不采信：实测传 04:10，服务端落的是它自己的 04:19:47。
     *
     * 所以「上传并提交」的顺序必须是：先把两个时间写进本机任务行 →
     * （尽力而为地）推状态机 → **最后**传 zip，让载荷里的本机时间成为最终值。
     */
    suspend fun pinLocalTimes(tableNo: String, start: Long, end: Long): Result<Unit> = runCatching {
        val task = taskDao.byTableNo(tableNo) ?: throw ApiException(0, "找不到任务：$tableNo")
        val staffId = session.reporterRefId.takeIf { it.isNotBlank() }
        taskDao.startTask(
            tableNo = tableNo,
            time = start,
            stateId = PatrolTaskState.RUNNING,
            stateLabel = PatrolTaskState.label(PatrolTaskState.RUNNING),
            staffId = staffId,
        )
        taskDao.endTask(
            tableNo = tableNo,
            time = end,
            stateId = PatrolTaskState.COMPLETED,
            stateLabel = PatrolTaskState.label(PatrolTaskState.COMPLETED),
        )
        // 记账本：一是界面标「本机已开始·未上传」，二是同步时别把本机时间覆盖掉
        session.setOfflineStart(
            tableNo = tableNo,
            time = start,
            staffId = staffId.orEmpty(),
            staffName = session.reporterDisplayName,
            prevState = task.taskStateId.orEmpty(),
        )
        session.setOfflineEnd(tableNo, end)
        // 任务日志：这条链路（简化巡检「上传并提交」）不经过 startTask/endTask，
        // 所以在这里把「开始任务 / 结束任务」两行补上；重复调用不会重复追加。
        appendTaskLog(tableNo, ACTION_START_TASK, start)
        appendTaskLog(tableNo, ACTION_END_TASK, end)
    }

    /** zip 传完之后的收尾：撤本机账本 + 回拉服务端（此时服务端时间才是权威） */
    suspend fun finishSubmit(tableNo: String) {
        session.clearOfflineTimes(tableNo)
        runCatching { syncTasks(taskStates = null, keyword = tableNo) }
    }

    /**
     * 任务级时间校验（用户 m12704）：开始任务 / 提交任务两个界面共用，
     * 规则见 [TimeRules]。传 0 的那一项不校验（开始任务界面只填开始时间）。
     *
     * 「巡检点」= 各区域的签到时间（cardTime）。巡检项完成时间不参与——
     * 它不是巡检点，而且本应用点「正常/异常」就地盖当前时间戳，拿它当点位会误判。
     */
    suspend fun validateTaskTimes(tableNo: String, start: Long, end: Long): List<String> =
        validateTaskTimesWithPoints(tableNo, start, end, timePoints(tableNo))

    /**
     * 同 [validateTaskTimes]，但巡检点由调用方给（如简化的「一次搞定」页：点位还在输入框里，
     * 没落到库里）。
     */
    suspend fun validateTaskTimesWithPoints(
        tableNo: String,
        start: Long,
        end: Long,
        points: List<Pair<Long, String>>,
    ): List<String> {
        val task = taskDao.byTableNo(tableNo)
        return TimeRules.check(
            planStart = task?.startTime ?: 0L,
            planEnd = task?.endTime ?: 0L,
            start = start,
            end = end,
            points = points,
            fmt = ::taskTimeText,
        )
    }

    /**
     * 区域作业页的校验（用户 m12704）：把正要保存的[time]当成**该区域**的签到点，
     * 和其它区域的签到点一起过同一条规则链。
     */
    suspend fun validateSignTime(tableNo: String, areaId: String, time: Long): List<String> {
        val task = taskDao.byTableNo(tableNo)
        val area = areaDao.listByTask(tableNo).firstOrNull { it.id == areaId }
        val others = timePoints(tableNo, excludeAreaId = areaId)
        return TimeRules.check(
            planStart = task?.startTime ?: 0L,
            planEnd = task?.endTime ?: 0L,
            start = task?.actualStartTime ?: 0L,
            end = task?.actualEndTime ?: 0L,
            points = others + (time to (area?.name ?: "本区域")),
            fmt = ::taskTimeText,
        )
    }

    /** 任务的巡检点（区域签到时间）；[excludeAreaId] 用于「只想比其它区域」的场合 */
    private suspend fun timePoints(tableNo: String, excludeAreaId: String? = null): List<Pair<Long, String>> =
        areaDao.listByTask(tableNo)
            .filter { it.id != excludeAreaId }
            .mapNotNull { a -> a.cardTime?.takeIf { it > 0L }?.let { it to (a.name ?: "区域") } }
            .sortedBy { it.first }

    private fun taskTimeText(millis: Long): String =
        if (millis <= 0L) "--" else taskTimeFormat.format(java.util.Date(millis))

    /**
     * 往本机任务行追加一条任务日志（上传载荷 `potrolTask.log` 的来源，用户 m05038 第 2 条）。
     *
     * 原版 `XJTaskDTO.setLog(time, action)` 的格式（抓包实测）：
     * `2026-10-14 05:32:32 张三 获取任务；2026-10-14 06:00:09 张三 开始任务；...`
     * —— 一行「yyyy-MM-dd HH:mm:ss 姓名 动作」，行间中文分号连接。
     *
     * 同一条「时间+人+动作」已经在末尾出现过就不再重复追加
     * （「上传并提交」时 pinLocalTimes 与 startTask/endTask 会各写一次）。
     */
    private suspend fun appendTaskLog(
        tableNo: String,
        action: String,
        at: Long,
        who: String? = null,
    ) {
        val task = taskDao.byTableNo(tableNo) ?: return
        val name = who?.takeIf { it.isNotBlank() } ?: session.reporterDisplayName
        val line = "${CLAIM_TIME_FORMAT.format(java.util.Date(at))} $name $action"
        val old = task.taskLog.orEmpty()
        if (old.endsWith(line)) return
        taskDao.updateLog(tableNo, if (old.isBlank()) line else "$old$LOG_SEPARATOR$line")
    }

    /* ------------------------- 完成度 / 任务级上传 ------------------------- */

    /** 任务完成度 —— 用来判断「能不能上传/提交」 */
    data class TaskProgress(
        val areaTotal: Int,
        val areaSigned: Int,
        val itemTotal: Int,
        val itemFinished: Int,
    ) {
        val allSigned: Boolean get() = areaTotal > 0 && areaSigned == areaTotal

        /**
         * 本机没有这条任务的巡检项时**不算未完成**（用户 m13004）。
         *
         * 这种任务通常是在原版客户端做的，我们这边只有区域和签到时间、没有巡检项明细；
         * 以前要求 `itemTotal > 0` 会把「提交任务」整条路堵死（连同按钮一起压灰）。
         * 而且提交任务现在只上报任务级状态与两个时间（m12413），本机没有巡检项也不损失什么。
         */
        val allFinished: Boolean get() = itemFinished == itemTotal

        /** 全部区域已签到 + 所有巡检项已完成，才允许上传/提交 */
        val canSubmit: Boolean get() = allSigned && allFinished

        val summary: String
            get() = "区域已签到 $areaSigned/$areaTotal，巡检项已完成 $itemFinished/$itemTotal"
    }

    suspend fun taskProgress(tableNo: String): TaskProgress {
        val areas = areaDao.listByTask(tableNo)
        var itemTotal = 0
        var itemFinished = 0
        areas.forEach {
            itemTotal += workDao.totalOfArea(it.id)
            itemFinished += workDao.finishedOfArea(it.id)
        }
        return TaskProgress(
            areaTotal = areas.size,
            areaSigned = areas.count { it.isSigned },
            itemTotal = itemTotal,
            itemFinished = itemFinished,
        )
    }

    /**
     * 任务级上传（用户 m04603：加「上传任务」按钮，**任务没全部完成不许上传**）。
     *
     * 与逐区域上传的区别只有两点：一次传整个任务的区域；
     * 传之前先校验 [TaskProgress.canSubmit]。
     */
    suspend fun uploadTask(
        tableNo: String,
        forceRunning: Boolean = false,
        /**
         * 是否连各区域的签到时间与巡检结果一起传（用户 m12413）。
         *
         * - `true`（默认）：整单上传，与原版离线补传一致。
         * - `false`：「提交任务」界面用的口径 —— 只传任务状态与两个时间，
         *   各区域结果由区域作业页「上传本区域到服务端」各自独立上传。
         */
        includeAreas: Boolean = true,
        /**
         * 载荷里带哪几个任务级时间（用户 m13590）。
         *
         * - `ALL`：开始 + 结束（默认，「打包提交任务」/原版离线补传）
         * - `START_ONLY`：「开始任务」勾了上报，只把本机这个开始时间交给服务端
         * - `END_ONLY`：「提交任务」勾了上报，只报结束时间（结束时间之后还能再覆盖上传）
         * - `NONE`：都不带（「上传本区域」）
         */
        times: com.sdyl.patrol.feature.patrol.upload.PatrolUploader.TimeScope =
            com.sdyl.patrol.feature.patrol.upload.PatrolUploader.TimeScope.ALL,
        /**
         * 要不要把延迟开始/结束原因强行清空（用户 m14803）。
         *
         * - `NONE`（默认）：该键整个不带，服务端已有的延迟原因不动；
         * - `START`/`END`：带**空字符串**，覆盖为空。
         */
        clearDelay: com.sdyl.patrol.feature.patrol.upload.PatrolUploader.DelayClear =
            com.sdyl.patrol.feature.patrol.upload.PatrolUploader.DelayClear.NONE,
        /** 这次要不要带签字图。开始任务只报开始时间，没必要往文件服务加一张签字图 */
        includeSignPic: Boolean = true,
        onStep: (String) -> Unit = {},
    ): Result<com.sdyl.patrol.feature.patrol.upload.PatrolUploader.Outcome> = runCatching {
        val progress = taskProgress(tableNo)
        // 整单上传（含各区域结果）要求任务在本机全部完成；只传任务级状态与两个时间时
        // （includeAreas = false，用户 m12413/m13004）不要求巡检项填完——原版客户端做完的
        // 任务在咱们这边本来就没有巡检项明细。
        if (includeAreas && !progress.canSubmit) {
            throw ApiException(0, "任务还没全部完成，不能上传（${progress.summary}）")
        }
        val areas = areaDao.listByTask(tableNo)
        if (areas.isEmpty()) throw ApiException(0, "该任务没有区域数据，请先联网获取任务")
        // 签字图查重（用户 m11701）统一在 PatrolUploader.upload 里做，
        // 「提交任务 / 上传本区域 / 一键上传待上传数据」三条路径都吃同一份判据。
        //
        // 只按本机真实状态上传一次。
        //
        // 用户 m10329 的时间准则要求「后台显示断网时的时间」，而服务端只在
        // **这单还没被它自己推进过**、收到「已完成」的整单载荷时才会采纳
        // `actualStartTime/actualEndTime`。所以这里绝不能先用状态机把任务推成
        // 「进行中」（那会让服务端用自己的时钟写 actualStartTime，载荷里的时间就被无视了）。
        // 载荷被判非法时如实报错，不再自动退回「进行中」重传。
        uploader.upload(
            areas,
            onStep,
            forceRunning,
            includeSignPic = includeSignPic,
            includeAreas = includeAreas,
            times = times,
            clearDelay = clearDelay,
        )
    }

    /**
     * 预览「这次上传会发出去的载荷」——**纯本机装配，不发任何网络请求**（用户 m05768）。
     *
     * 断网状态下也能点：用来逐字段确认里面用的都是本机数据
     * （本机任务行的时间、本机签到的区域、本机完成的巡检项、本机填的身份与签字图），
     * 而不是服务端的「真实」时间。返回格式化后的 JSON 文本。
     */
    suspend fun previewUploadPayload(tableNo: String): Result<String> = runCatching {
        val areas = areaDao.listByTask(tableNo)
        if (areas.isEmpty()) throw ApiException(0, "该任务没有区域数据，请先联网获取任务")
        uploader.previewPayloadJson(areas)
    }

    /**
     * 保存单条巡检项结果——**纯本地，绝不上报**。
     *
     * 用户要求（m01570/m01571）：测试期间不许把巡检结果提交给服务端，只写本地库。
     */
    suspend fun saveWorkItemResult(
        id: String,
        realValue: String?,
        concluse: String?,
        skipReason: String?,
        abnormalReason: String?,
        remark: String?,
        reason: String? = null,
        passReason: String? = null,
        staffId: String? = null,
        staff: String? = null,
    ): Result<Unit> = runCatching {
        val origin = workDao.byId(id) ?: throw ApiException(0, "找不到巡检项：$id")
        val finished = !skipReason.isNullOrBlank() || !concluse.isNullOrBlank()
        workDao.upsert(
            origin.copy(
                realValue = realValue,
                concluse = concluse,
                skipReason = skipReason,
                abnormalReason = abnormalReason,
                remark = remark,
                reason = reason ?: abnormalReason,
                passReason = passReason,
                isPass = if (concluse.isNullOrBlank()) null else concluse == PatrolConclusion.NORMAL,
                taskDetailState = when {
                    !skipReason.isNullOrBlank() -> WorkItemState.SKIP
                    finished -> WorkItemState.CHECKED
                    else -> WorkItemState.PENDING
                },
                completeDate = if (finished) System.currentTimeMillis() else null,
                completeUser = if (finished) staffId else null,
                completeUserName = if (finished) staff else null,
                finished = finished,
                dirty = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        refreshAreaProgress(origin.areaId)
        Unit
    }

    /**
     * 统一面板批量保存：一次写多条巡检项 + 可选签到。
     *
     * `patches` 只包含用户真正动过的条目（未动的原样保留）。
     */
    suspend fun saveAreaWork(
        areaId: String,
        staff: String?,
        staffId: String?,
        patches: List<WorkItemPatch>,
        sign: SignInPatch?,
    ): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        patches.forEach { p ->
            workDao.saveResultFull(
                id = p.id,
                real = p.realValue,
                concluse = p.concluse,
                skipReason = p.skipReason,
                abnormalReason = p.abnormalReason,
                remark = p.remark,
                reason = p.abnormalReason,
                passReason = p.passReason,
                isPass = if (p.concluse.isNullOrBlank()) null else p.concluse == PatrolConclusion.NORMAL,
                finished = p.finished,
                state = p.taskDetailState,
                now = now,
                staffId = staffId,
                staff = staff,
            )
        }
        if (sign != null) {
            areaDao.markSigned(
                id = areaId,
                staff = sign.staff,
                staffId = sign.staffId,
                time = sign.cardTime,
                type = sign.signTypeLabel,
                code = sign.cardCode,
                payType = sign.payCardTypeId,
                reason = sign.signInReason,
                lat = sign.lat,
                lon = sign.lon,
            )
        }
        refreshAreaProgress(areaId)
        Unit
    }

    /**
     * 区域一键完成——**纯本地，绝不上报**。
     *
     * 语义：只把**当前这一个点位（区域）**下的全部巡检项标记完成，
     * 不是整个任务的所有点位。对应原版「区域一键完成」（范围 workArea）。
     */
    /**
     * 把区域里所有巡检项标记为「已检」。
     *
     * @param time 完成时间 —— 常规巡检走当前时钟；「简化巡检」按用户填的那个点位时间落盘。
     */
    suspend fun finishArea(
        tableNo: String,
        areaId: String,
        time: Long = System.currentTimeMillis(),
    ): Result<Unit> = runCatching {
        workDao.finishAllInArea(areaId, WorkItemState.CHECKED, time, null, null)
        refreshAreaProgress(areaId)
        Unit
    }

    suspend fun refreshAreaProgress(areaId: String) {
        val total = workDao.totalOfArea(areaId)
        val finished = workDao.finishedOfArea(areaId)
        val abnormal = workDao.abnormalOfArea(areaId, PatrolConclusion.NORMAL)
        areaDao.updateProgress(areaId, finished, abnormal)
        if (total > 0 && finished >= total) {
            // 全部完成时不自动改任务状态，交由用户显式提交
        }
    }

    /**
     * 区域签到——**纯本地写入，绝不上报**。
     *
     * 用户要求（m01570/m01571）：签到人、签到人 id、签到时间、签到方式都要能自己填，
     * 直接落到本地库就行，测试期间不许把签到结果传给服务端。
     */
    suspend fun markAreaSigned(
        areaId: String,
        staff: String?,
        staffId: String? = null,
        signType: String = SIGN_TYPE_NFC,
        cardTime: Long = System.currentTimeMillis(),
        signCode: String? = null,
        payCardTypeId: String? = null,
        signInReason: String? = null,
        lat: Double? = null,
        lon: Double? = null,
    ) {
        areaDao.markSigned(
            areaId, staff, staffId, cardTime, signType, signCode,
            payCardTypeId ?: payCardTypeOf(signType), signInReason, lat, lon,
        )
    }

    /** 撤销本地签到（同样只动本地） */
    suspend fun clearAreaSigned(areaId: String) {
        areaDao.clearSigned(areaId)
    }

    fun observeArea(areaId: String): Flow<AreaEntity?> = areaDao.observeById(areaId)

    suspend fun dirtyCountOfArea(areaId: String): Int =
        runCatching { areaDao.dirtyOfArea(areaId) }.getOrDefault(0)

    companion object {
        /** 校验提示里的时间格式 */
        private val taskTimeFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)

        /* ---- 任务日志（上传载荷 potrolTask.log，用户 m05038 第 2 条） ---- */
        /** 行间分隔符：原版用的是中文分号 */
        const val LOG_SEPARATOR = "；"
        const val ACTION_GET_TASK = "获取任务"
        const val ACTION_START_TASK = "开始任务"
        const val ACTION_END_TASK = "结束任务"

        /** 签到方式：只保留用户要的两种 */
        const val SIGN_TYPE_MANUAL = "手动签到"
        const val SIGN_TYPE_NFC = "NFC刷卡签到"

        /** 原版 XJConstant.SignType 的字典值 */
        const val PAY_CARD_TYPE_CARD = "PATROL_payCardType/card"
        const val PAY_CARD_TYPE_SIGN_IN = "PATROL_payCardType/signIn"

        /** 签到方式文案 → 原版字典 id（PATROL_payCardType/{card,signIn}） */
        fun payCardTypeOf(signTypeLabel: String?): String =
            if (signTypeLabel == SIGN_TYPE_NFC) PAY_CARD_TYPE_CARD else PAY_CARD_TYPE_SIGN_IN

        /* ---- 「领取任务」（临时任务）用的固定值，全部照抄原版 ---- */

        /** 原版 XJBaseRoutePresenter 里的 viewCode / permissionCode */
        const val ROUTE_VIEW_CODE = "PATROL_1.0.0_patrolRoute_workGroupRef"

        /** 原版 XJCreateTempTaskPresenter.getAreasByRouteId 的 datagridCode */
        const val AREA_GRID_CODE = "PATROL_1.0.0_patrolRoute_workGroupListdg1575500000000"

        /** 原版 XJBaseRoutePresenter 的 modelAlias */
        const val ROUTE_MODEL_ALIAS = "workGroup"

        /**
         * 时间段格式 —— 原版 `XJCreateTempTaskBO.startTime/endTime` 就是字符串，
         * 由 UI 层用 `"yyyy-MM-dd HH:mm:ss"` 填进去。
         */
        val CLAIM_TIME_FORMAT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)

        /** 原版 `XJLogBO.mobileVersion` —— 写死在代码里的版本串 */
        const val LOG_MOBILE_VERSION = "GenericMobile V1.0.0-0000000"
    }

    /**
     * 原版 `XJLogBO` 的三个字段：`mobileVersion` / `equipmentModel` / `macAddress`。
     *
     * **每一次任务状态变更（开始/结束/领取）都必须原样带上**，一个字都不能少：
     *  - 服务端据此判定这是「移动端本人的在线操作」，才会把任务的 `executeUser`
     *    （= 用户说的「状态变更人」）写成当前登录人；
     *  - 一旦请求体里出现 `startTime`（原版在线分支恒为 null），服务端会走
     *    「离线补传」分支：老老实实落 `actualStartTime`，但**不写 executeUser**。
     *    ⇒ 这正是 1937 的「状态变更人」为空、而 1938 补上这三个字段后立刻变成张三的原因。
     */
    private fun logFields(): Map<String, Any?> = mapOf(
        "mobileVersion" to LOG_MOBILE_VERSION,
        "equipmentModel" to DeviceIdentity.equipmentModel(),
        "macAddress" to DeviceIdentity.macAddress(),
    )

    suspend fun pendingUploadCount(): Int = workDao.pendingUpload().size

    /* --------------------- 领取任务（临时任务） --------------------- */

    /**
     * 线路列表。
     *
     * 原版：`XJBaseRoutePresenter.getBaseRoutes(true, pageNo, map)` →
     * `XJBasePageFastCondBO{pageNo, paging, pageSize, permissionCode, fastQueryCond}`
     * 其中 `fastQueryCond` 是 `FastQueryCondEntity` 的 **JSON 字符串**（注意是字符串，不是对象）。
     */
    suspend fun fetchRoutes(): Result<List<PatrolBaseRoute>> = runCatching {
        val fastCond = com.google.gson.JsonObject().apply {
            addProperty("viewCode", ROUTE_VIEW_CODE)
            addProperty("modelAlias", ROUTE_MODEL_ALIAS)
            add("subconds", com.google.gson.JsonArray())
        }.toString()
        val body = mutableMapOf<String, Any?>(
            "pageNo" to 1,
            "pageSize" to 500,
            "paging" to true,
            "permissionCode" to ROUTE_VIEW_CODE,
            "fastQueryCond" to fastCond,
        )
        unwrap(service().getBaseRoutes(body)).items
            .filter { it.code?.isNotBlank() == true }
            .sortedBy { it.displayName }
    }

    /** 线路下的区域。原版把 route 拼错成 `ruoteId`，服务端认的就是这个拼写。 */
    suspend fun fetchAreas(routeId: String): Result<List<PatrolBaseArea>> = runCatching {
        val body = mutableMapOf<String, Any?>(
            "pageNo" to 1,
            "pageSize" to 500,
            "paging" to true,
            "customCondition" to mutableMapOf<String, Any?>(
                "pageNo" to 1,
                "paging" to true,
                "pageSize" to 500,
                "ruoteId" to routeId,
            ),
        )
        unwrap(service().getBaseAreas(body, AREA_GRID_CODE)).items.sortedBy { it.sort }
    }

    /**
     * **领取任务** —— 原版离线任务页「领取任务」按钮的真实动作。
     *
     * 用户 m05272 第 2 条 / m05518 第 2 条逼着去翻原版，最后定位到：
     * `XJOfflineHomeActivity.tvGetTask` → `XJGetUploadTasksActivity(isGet=true)` →
     * `XJUpdateTasksStateController.updateTasksState(false, 勾选的任务)`
     * → `XJUpdateTasksStatePresenter` → `POST /msService/PATROL/patrolTask/potrolTask/uploadTaskState`
     * body = `{changeState: "PATROL_taskState/issued", idList: [任务 id…], mobileVersion, equipmentModel, macAddress}`。
     *
     * 也就是说：**领取 = 把已经下发给我的「未领取」任务置为「已领取」**，不是创建临时任务。
     * 服务端在这一步会把任务的 `executeUser`（服务端界面上显示的「状态变更人」）写成本次调用人。
     *
     * @param tableNos 本地任务号；内部换算成服务端 `taskId` 列表。
     */
    suspend fun claimTasks(tableNos: List<String>): Result<Int> = runCatching {
        if (tableNos.isEmpty()) throw ApiException(0, "请先勾选要领取的任务")
        val ids = tableNos.mapNotNull { tableNo ->
            taskDao.byTableNo(tableNo)?.taskId?.takeIf { it.isNotBlank() && it != "0" }
        }
        if (ids.isEmpty()) throw ApiException(0, "本地没有这些任务的服务端 ID，请先「获取任务」")

        val body = mutableMapOf<String, Any?>(
            "changeState" to PatrolTaskState.ISSUED,
            "idList" to ids,
        ).apply { putAll(logFields()) }
        val response = service().claimTasks(body)
        if (!response.isOk) {
            // 服务端对「已经被别人领走的单」回 500：「系统内部错误: 存在已获取的任务，请刷新重新获取任务」
            // （用户 m15731 实例：10-15 0~2 点那批单已被别班领取（并已开始执行），本地还显示未领取 → 点领取必 500）。
            // 别把裸 500 丢给用户：先刷新一次把本地状态对齐，再报一句人话。
            runCatching { syncTasks(taskStates = null, keyword = null, replaceAll = false) }
            if (response.errorText.contains("已获取")) {
                throw ApiException(response.code, "这张单已经被领取了（可能被别人领走），已刷新为最新状态")
            }
            throw ApiException(response.code, response.errorText)
        }

        // 本地先把勾选的任务置为「已领取」，再回拉一次对齐服务端
        tableNos.forEach { tableNo ->
            taskDao.updateState(tableNo, PatrolTaskState.ISSUED, PatrolTaskState.label(PatrolTaskState.ISSUED))
        }
        // 记下「我领过的任务」：主页「我的」分类要用（服务端投影不带 executeUser）
        session.addClaimedTasks(tableNos)
        syncTasks(taskStates = null, keyword = null, replaceAll = false).getOrDefault(tableNos.size)
    }

    /* ------------------------- 上传 ------------------------- */

    /** 待上传（本机签过到或录入过、还没传成功）的区域列表 */
    suspend fun pendingUploadAreas(): List<AreaEntity> = areaDao.listPendingUpload()

    /** 待上传区域数（兼容旧入口保留） */
    suspend fun pendingUploadAreaCount(): Int = areaDao.pendingUploadCount()

    /* ------------------------- 本机缓存 ------------------------- */

    /** 本机缓存规模：任务 / 区域 / 巡检项 三条计数（「我的」页「清理缓存」展示用） */
    suspend fun cacheSummary(): Triple<Int, Int, Int> = Triple(
        taskDao.count(),
        areaDao.count(),
        workDao.count(),
    )

    /**
     * 清理本机缓存：把任务、区域、设备、巡检项全部删掉。
     *
     * 只清离线业务数据 —— **登录状态、服务器地址、上传身份（imei/手机号）都保留**，
     * 清完回到「一台还没同步过的手机」，点右上角同步图标即可重新拉任务列表。
     * 返回被清掉的记录数（任务 + 区域 + 巡检项）。
     *
     * 注意：这会连带丢掉还没上传的签到/录入结果（用户已明确不要「离线数据上传」
     * 那个入口，所以这里不做拦截，只在界面上写清楚）。
     */
    suspend fun clearCache(): Int = db.withTransaction {
        val n = taskDao.count() + areaDao.count() + workDao.count()
        workDao.clear()
        deviceDao.clear()
        areaDao.clear()
        taskDao.clear()
        n
    }

    /**
     * 上传单个区域的结果（**覆盖式修正**：用户 m14238 强调这是必须可用的功能）；
     * 成功后本区域 dirty 清零、isUpload 置位。
     *
     * 载荷里必须带任务级的实际开始/结束时间（times=ALL，2026-10-14 修正）：
     * 我曾按「各时间独立上传」把它改成 times=NONE，真机上「上传本区域到服务端」随即必
     * 500「系统内部错误: 批量更新巡检任务失败！」——原版抓包 probe/orig_payload/xj_upload.json
     * 与 19:2x 上传成功的那几次载荷都带这两个时间，服务端批量更新巡检任务时要读它们。
     * 结论：区域结果的覆盖式修正优先，不回退这条。
     */
    suspend fun uploadArea(
        areaId: String,
        onStep: (String) -> Unit = {},
    ): Result<com.sdyl.patrol.feature.patrol.upload.PatrolUploader.Outcome> = runCatching {
        val area = areaDao.byId(areaId) ?: throw ApiException(0, "找不到区域：$areaId")
        uploader.upload(listOf(area), onStep)
    }

    /**
     * 一键上传全部待上传数据（按任务分组打包）。
     * 全部成功才算成功；任何一步失败都不动本地 dirty 标记，可重试。
     */
    suspend fun uploadAllPending(
        onStep: (String) -> Unit = {},
    ): Result<com.sdyl.patrol.feature.patrol.upload.PatrolUploader.Outcome> = runCatching {
        val areas = areaDao.listPendingUpload()
        if (areas.isEmpty()) throw ApiException(0, "没有待上传的数据")
        uploader.upload(areas, onStep)
    }

    /* ------------------------- 映射 ------------------------- */

    private fun <T> unwrap(response: BapResponse<T>): T {
        if (!response.isOk) throw ApiException(response.code, response.errorText)
        return response.payload() ?: throw ApiException(response.code, "服务端返回空数据")
    }

    private fun PatrolTask.toEntity(now: Long) = TaskEntity(
        id = tableNo ?: taskId.toString(),
        tableNo = tableNo,
        taskId = taskId.takeIf { it > 0L }?.toString() ?: id,
        tableInfoId = tableInfoId,
        taskName = displayName,
        taskType = taskType,
        taskStateId = stateId,
        taskStateLabel = stateLabel,
        routeTypeId = routeType,
        routeTypeLabel = typeLabel,
        routeName = routeName,
        startTime = startTime,
        endTime = endTime,
        actualStartTime = actualStartTime,
        actualEndTime = actualEndTime,
        isTemp = isTemp,
        isInOrder = isInOrder,
        areaCount = areaCount,
        groupName = groupName,
        stuffInCharge = stuffInCharge,
        groupId = groupId,
        groupCode = groupCode,
        advanceStartTime = advanceStartTime ?: 0.0,
        executeUser = executeUser?.id?.takeIf { it > 0L }?.toString(),
        updatedAt = now,
    )

    private fun com.sdyl.patrol.feature.patrol.model.PatrolTaskArea.toEntity(
        tableNo: String,
        taskId: String?,
        now: Long,
        local: AreaEntity? = null,
    ): AreaEntity {
        val localSigned = local?.isSigned == true
        val serverTypeId = payCardType?.id
        return AreaEntity(
            id = id ?: taskAreaId ?: "",
            taskTableNo = tableNo,
            taskId = taskId,
            workAreaId = workAreaId ?: local?.workAreaId,
            name = displayName,
            code = displayCode,
            sort = sort ?: 0,
            totalNum = totalNum,
            finishNum = finishNum,
            abnormalNum = abnormalNum,
            // 已签到就是已签到：服务端还没有我们的本地签到时就别抹掉
            isSigned = isSigned || localSigned,
            // 服务端的区域投影里根本没有 `isUpload` 键，直接取会把本地「已上传」冲成 false
            // （实测：上传成功后一刷新就变回「未上传」）。所以 isUpload 只看本地：
            //  - 已有行：保留本地的值
            //  - 服务端刚插入的新行：本机什么都没动过，直接算「已上传」
            //    （不然历史任务里「别人签过」的区域会全被算成「本机待上传」）
            isUpload = local?.isUpload ?: true,
            signStaffName = signStaffName ?: local?.takeIf { localSigned }?.signStaffName,
            signStaffId = signStaffId ?: local?.takeIf { localSigned }?.signStaffId,
            cardTime = cardTime ?: local?.takeIf { localSigned }?.cardTime,
            signCode = signCode ?: local?.signCode,
            signType = signTypeLabel?.takeIf { it.isNotBlank() }
                ?: local?.takeIf { localSigned }?.signType,
            serverCardType = serverTypeId ?: local?.serverCardType,
            // 本机签到后本地写的签到方式优先，别被服务端的旧值盖回
            payCardTypeId = (local?.takeIf { localSigned }?.payCardTypeId)
                ?: serverTypeId
                ?: local?.payCardTypeId,
            signInReason = local?.takeIf { localSigned }?.signInReason
                ?: signInReason?.value?.takeIf { it.isNotBlank() }
                ?: local?.signInReason,
            clockCoordinate = clockCoordinate ?: local?.clockCoordinate,
            isEndPoint = isEndPoint,
            intervalTime = intervalTime?.toInt() ?: 0,
            updatedAt = now,
        )
    }

    private fun PatrolTaskDevice.toEntity(
        tableNo: String,
        areaId: String,
        index: Int,
        now: Long,
    ) = DeviceEntity(
        id = id ?: code ?: "dev-$index",
        taskTableNo = tableNo,
        areaId = areaId,
        code = code,
        name = name,
        areaNum = areaNum,
        operationStatusName = operationStatusName,
        sort = index,
        updatedAt = now,
    )

    private fun PatrolWorkItem.toEntity(
        tableNo: String,
        areaId: String,
        local: WorkItemEntity?,
        now: Long,
    ) = WorkItemEntity(
        id = localId.ifBlank { "$areaId-$itemNumber-$deviceIndex" },
        taskTableNo = tableNo,
        areaId = areaId,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceCode = deviceCode,
        deviceAreaNum = deviceAreaNum,
        deviceIndex = deviceIndex,
        itemNumber = itemNumber,
        content = content,
        claim = claim,
        normalRange = normalRange,
        defaultVal = default,
        unit = unit,
        inputStandardCode = inputStandardCode?.id ?: inputStandardCode?.code,
        editType = inputStandardCode?.editType,
        candidateValue = inputStandardCode?.candidateValue,
        // 上传载荷需要的两个 id：巡检项定义 id 与表主键
        serverWorkItemId = workItemId ?: local?.serverWorkItemId,
        tableInfoId = tableInfoId ?: local?.tableInfoId,
        isPhone = isPhone == "true" || isPhone == "1",
        isAutoJudge = isAutoJudge == "true" || isAutoJudge == "1",
        sort = sort ?: 0,
        // 保留本地已录入结果
        realValue = local?.realValue,
        concluse = local?.concluse,
        skipReason = local?.skipReason ?: skipReason,
        abnormalReason = local?.abnormalReason ?: abnormalReason?.id,
        remark = local?.remark ?: remark,
        reason = local?.reason,
        passReason = local?.passReason ?: passReason?.id,
        taskDetailState = local?.taskDetailState,
        completeDate = local?.completeDate,
        completeUser = local?.completeUser,
        completeUserName = local?.completeUserName,
        isConclusionModify = local?.isConclusionModify ?: true,
        isPass = local?.isPass,
        finished = local?.finished ?: false,
        uploaded = local?.uploaded ?: isUpload,
        dirty = local?.dirty ?: false,
        updatedAt = now,
    )
}

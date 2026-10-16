package com.sdyl.patrol.feature.patrol.upload

import com.google.gson.Gson
import com.sdyl.patrol.core.data.db.AreaEntity
import com.sdyl.patrol.core.data.db.TaskEntity
import com.sdyl.patrol.core.data.db.WorkItemEntity
import com.sdyl.patrol.feature.patrol.PatrolRepository
import com.sdyl.patrol.feature.patrol.model.PatrolConclusion
import com.sdyl.patrol.feature.patrol.model.WorkItemState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 上传载荷的"契约测试"。
 *
 * 服务端按 JSON 字段名反序列化到原版 BO 上，字段名拼错一个字母就会被静默丢弃，
 * 表现为"上传成功但数据没上去"。所以这里把 JSON 逐字钉死。
 */
class UploadPayloadTest {

    private val gson = Gson()

    private lateinit var savedTimeZone: java.util.TimeZone

    /**
     * 签字图名/时间断言用的是"设备本机墙上时间"。真机在东八区，而 CI runner 默认 UTC——
     * 不钉住默认时区，同一个毫秒值会格式化成相差 8 小时的另一个名字（Actions 上就挂在这一条）。
     */
    @Before
    fun pinTimeZone() {
        savedTimeZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() {
        java.util.TimeZone.setDefault(savedTimeZone)
    }

    private fun work(
        concluse: String? = PatrolConclusion.NORMAL,
        realValue: String? = "5.2",
        state: String? = WorkItemState.CHECKED,
        abnormalReason: String? = null,
        reason: String? = null,
        skipReason: String? = null,
    ) = WorkItemEntity(
        id = "1000000000000746",
        taskTableNo = "patrolTask_20261012_2545",
        areaId = "1000000000000736",
        deviceId = "NULL_ID",
        deviceName = "无设备",
        deviceCode = "NULL_CODE",
        deviceAreaNum = "NULL_CODE",
        deviceIndex = 0,
        itemNumber = "—",
        content = "检查水池液位是否正常。",
        claim = null,
        normalRange = null,
        defaultVal = null,
        unit = null,
        inputStandardCode = "1001",
        editType = "PATROL_editType/whether",
        candidateValue = "是,否",
        serverWorkItemId = "1000000000000746",
        tableInfoId = "1000000000000433",
        isPhone = false,
        isAutoJudge = false,
        sort = 0,
        realValue = realValue,
        concluse = concluse,
        skipReason = skipReason,
        abnormalReason = abnormalReason,
        remark = "备注",
        reason = reason,
        passReason = null,
        taskDetailState = state,
        completeDate = 1791825255000L,
        completeUser = "1000000000000952",
        completeUserName = "张三",
        isConclusionModify = true,
        isPass = concluse == PatrolConclusion.NORMAL,
        finished = true,
        uploaded = false,
        dirty = true,
        updatedAt = 1791825255000L,
    )

    /** 最关键的一条：载荷里 realValue 必须是结论码、concluse 必须是实测值 */
    @Test
    fun `realValue carries conclusion code and concluse carries measured value`() {
        val bo = PatrolUploader.workBo(work(concluse = PatrolConclusion.NORMAL, realValue = "5.2"))
        assertEquals(PatrolConclusion.NORMAL, bo.realValue?.id)
        assertEquals("5.2", bo.concluse)
    }

    /**
     * 「是否」型：用户在区域作业页点「是」（原始答案落本地 realValue），
     * 载荷里必须带 `concluse="是"`，同时 `realValue` 是结论码。
     */
    @Test
    fun `whether candidate answer is carried as concluse`() {
        val yes = PatrolUploader.workBo(work(concluse = PatrolConclusion.NORMAL, realValue = "是"))
        assertEquals("是", yes.concluse)
        assertEquals(PatrolConclusion.NORMAL, yes.realValue?.id)

        val no = PatrolUploader.workBo(work(concluse = PatrolConclusion.ABNORMAL, realValue = "否"))
        assertEquals("否", no.concluse)
        assertEquals(PatrolConclusion.ABNORMAL, no.realValue?.id)
    }

    /** 是否型候选值就是"是,否"（真机库实测 hex e698af2ce590a6），UI 据此渲染两个按钮 */
    @Test
    fun `whether candidates split into yes and no`() {
        val candidates = "是,否".split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("是", "否"), candidates)
    }

    @Test
    fun `normal conclusion clears abnormal reason and reason`() {
        val bo = PatrolUploader.workBo(
            work(
                concluse = PatrolConclusion.NORMAL,
                realValue = "5.2",
                abnormalReason = "PATROL_abnormalReason/x",
                reason = "PATROL_abnormalReason/x",
            ),
        )
        assertNull(bo.abnormalReason)
        assertNull(bo.reason)
    }

    @Test
    fun `abnormal text goes to reason and never to abnormalReason`() {
        val bo = PatrolUploader.workBo(
            work(
                concluse = PatrolConclusion.ABNORMAL,
                realValue = "12.5",
                abnormalReason = "泵体异响",
            ),
        )
        assertEquals("泵体异响", bo.reason)
        assertNull(bo.abnormalReason) // 自由文本不能塞进字典字段
    }

    @Test
    fun `skip conclusion clears real value and concluse`() {
        val bo = PatrolUploader.workBo(
            work(concluse = null, realValue = null, state = WorkItemState.SKIP, skipReason = "设备停用"),
        )
        assertNull(bo.realValue)
        assertNull(bo.concluse)
        assertEquals("设备停用", bo.skipReason)
        assertTrue(bo.isRealPass)
    }

    @Test
    fun `work bo uses taskWorkId as id when server workItemId missing`() {
        val bo = PatrolUploader.workBo(work().copy(serverWorkItemId = null))
        assertEquals("1000000000000746", bo.id)
        assertEquals("1000000000000746", bo.taskWorkId)
        assertEquals("1000000000000746", bo.taskItemDetilId)
    }

    @Test
    fun `full payload json uses original field names`() {
        val task = TaskEntity(
            id = "patrolTask_20261012_2545",
            tableNo = "patrolTask_20261012_2545",
            taskId = "1000000000000432",
            tableInfoId = "1000000000000433",
            taskName = "炼油区闭式循环水场-工艺外操-2-hour",
            taskType = "PATROL_taskType/dailyPatrol",
            taskStateId = "PATROL_taskState/running",
            taskStateLabel = "进行中",
            routeTypeLabel = "智慧安全系统（工艺）",
            routeName = "炼油区闭式循环水场-工艺外操-2-hour",
            startTime = 1783872000000L,
            endTime = 1783879200000L,
            actualStartTime = 1783877403370L,
            actualEndTime = 0L,
            isTemp = false,
            isInOrder = false,
            areaCount = 1,
            groupName = "g",
            stuffInCharge = "张三",
            updatedAt = 0L,
        )
        val area = AreaEntity(
            id = "1000000000000736",
            taskTableNo = "patrolTask_20261012_2545",
            taskId = "1000000000000432",
            workAreaId = "1000000000000504",
            name = "第六站 循环水、海水排水提升池",
            code = "d65a6935-f792-4fd7-9e96-e2f79191ab1a",
            sort = 0,
            totalNum = 1,
            finishNum = 1,
            abnormalNum = 0,
            isSigned = true,
            isUpload = false,
            signStaffName = "张三",
            signStaffId = "1000000000000952",
            cardTime = 1791825255000L,
            signCode = "BQ1421-1-0006",
            signType = PatrolRepository.SIGN_TYPE_NFC,
            serverCardType = null,
            payCardTypeId = null,
            signInReason = null,
            clockLat = null,
            clockLon = null,
            clockCoordinate = "{\"lat\":0.0,\"lon\":0.0}",
            isEndPoint = false,
            intervalTime = 0,
            isNeedSign = true,
            updatedAt = 0L,
        )

        val bos = UploadDataBos().apply {
            uploadTaskResultDTOs = listOf(
                UploadDataBo().apply {
                    actualStartTime = task.actualStartTime
                    potrolTask = PatrolUploader.taskBo(task)
                    workAreas = listOf(PatrolUploader.areaBo(area, task))
                    workItems = listOf(PatrolUploader.workBo(work()))
                },
            )
        }

        val json = gson.toJson(bos)

        // 载荷外壳与任务
        assertTrue(json.contains("\"uploadTaskResultDTOs\""))
        assertTrue(json.contains("\"potrolTask\"")) // 原版拼写，不能"修正"
        assertEquals(false, json.contains("\"patrolTask\""))
        assertTrue(json.contains("\"tableInfoId\":\"1000000000000433\""))
        assertTrue(json.contains("\"taskState\":{\"id\":\"PATROL_taskState/running\",\"value\":\"进行中\"}"))
        assertTrue(json.contains("\"mobileVersion\""))
        assertTrue(json.contains("\"equipmentModel\""))

        // 区域：SignStaff 首字母大写；id 用基础区域 id（workAreaId）而不是 taskAreaId
        assertTrue(json.contains("\"workAreas\""))
        assertTrue(json.contains("\"SignStaff\":\"张三\""))
        assertTrue(json.contains("\"id\":\"1000000000000504\""))
        assertTrue(json.contains("\"staffId\":\"1000000000000952\""))
        assertTrue(json.contains("\"payCardType\":{\"id\":\"PATROL_payCardType/card\""))

        // 巡检项：id 是 workItemId，taskItemDetilId 是原版拼写
        assertTrue(json.contains("\"workItems\""))
        assertTrue(json.contains("\"taskItemDetilId\""))
        assertTrue(json.contains("\"realValue\":{\"id\":\"PATROL_realValue/normal\"}"))
        assertTrue(json.contains("\"concluse\":\"5.2\""))
        assertTrue(json.contains("\"taskDetailState\":{\"id\":\"PATROL_taskDetailState/checked\"}"))
        assertTrue(json.contains("\"completeUser\":{\"id\":\"1000000000000952\",\"name\":\"张三\"}"))
        assertTrue(json.contains("\"isRealPhoto\":false"))
        assertTrue(json.contains("\"abnormalEamDetail\":false"))

        // 空值不写（朴素 Gson 不会输出 null 值）
        // 注意：不能简单找 "null" 子串 —— 单元测试里 android.os.Build 的字段都是 null，
        // equipmentModel 会长成 "null null,Android null"，那是测试环境产物，真机上是有值的。
        assertFalse(json.contains(":null"))
    }

    /**
     * 任务日志与签字图（用户 m05038 第 1、2 条）。
     *
     * 原版载荷长这样（抓包实测 `probe/orig_payload/xj_upload.json`）：
     * `"log":"2026-10-14 05:32:32 张三 获取任务；2026-10-14 06:00:09 张三 开始任务；..."`、
     * `"signPicPath":"xjTaskPic_20261014_061320.jpg"`。
     */
    @Test
    fun `task log and signature file name go into payload`() {
        val log = "2026-10-14 05:32:32 张三 获取任务；2026-10-14 06:00:09 张三 开始任务" +
            "；2026-10-14 06:11:49 张三 结束任务"
        val task = TaskEntity(
            id = "patrolTask_20261013_1942",
            tableNo = "patrolTask_20261013_1942",
            taskId = "1000000000000640",
            tableInfoId = "1000000000000641",
            taskName = "炼油区余热发电-工艺外操-2-hour",
            taskType = "PATROL_taskType/dailyPatrol",
            taskStateId = "PATROL_taskState/completed",
            taskStateLabel = "已完成",
            routeTypeLabel = "智慧安全系统（工艺）",
            routeName = "炼油区余热发电-工艺外操-2-hour",
            startTime = 1791928800000L,
            endTime = 1791936000000L,
            actualStartTime = 1791928809117L,
            actualEndTime = 1791929509337L,
            isTemp = false,
            isInOrder = false,
            areaCount = 3,
            groupName = "g",
            stuffInCharge = "张三",
            taskLog = log,
            updatedAt = 0L,
        )

        val bo = PatrolUploader.taskBo(
            t = task,
            baseUrl = "https://supos.example.com:8443",
            reporterId = "1000000000000952",
            reporterName = "张三",
            signPicName = "xjTaskPic_20261014_061320.jpg",
        )
        val json = gson.toJson(bo)

        assertEquals(log, bo.log)
        assertEquals("xjTaskPic_20261014_061320.jpg", bo.signPicPath)
        assertTrue(json.contains("\"log\":\"$log\""))
        assertTrue(json.contains("\"signPicPath\":\"xjTaskPic_20261014_061320.jpg\""))
    }

    /** 没有签字图时，`signPicPath` 不能写空串（空值干脆不写，与服务端一致） */
    @Test
    fun `no signature means no signPicPath field`() {
        val task = TaskEntity(
            id = "t",
            tableNo = "t",
            taskId = "1",
            tableInfoId = "2",
            taskName = "n",
            taskType = "PATROL_taskType/dailyPatrol",
            taskStateId = "PATROL_taskState/running",
            taskStateLabel = "进行中",
            routeTypeLabel = null,
            routeName = "n",
            startTime = 1L,
            endTime = 2L,
            actualStartTime = 0L,
            actualEndTime = 0L,
            isTemp = false,
            isInOrder = false,
            areaCount = 1,
            groupName = null,
            stuffInCharge = null,
            updatedAt = 0L,
        )
        val json = gson.toJson(PatrolUploader.taskBo(task))
        assertFalse(json.contains("signPicPath"))
        assertFalse(json.contains("\"log\""))
    }

    /** 打包形状：zip 里必须是 `xj_upload.json` + `pics/<签字图名>`（原版抓包实测） */
    @Test
    fun `zip contains json and signature picture under pics`() {
        val dir = java.nio.file.Files.createTempDirectory("xj_upload_test").toFile()
        val pic = java.io.File(dir, "src.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val name = "xjTaskPic_20261014_061320.jpg"

        val bos = UploadDataBos().apply {
            uploadTaskResultDTOs = listOf(UploadDataBo())
        }
        val zip = PatrolUploader.writeZipTo(dir, bos, pic, name)

        val entries = java.util.zip.ZipFile(zip).use { zf ->
            zf.entries().toList().map { it.name }.sorted()
        }
        assertEquals(listOf(PatrolUploader.JSON_FILE_NAME, "pics/$name").sorted(), entries)
    }

    /** 没有签字图时 zip 里只有 json —— 上传绝不因为缺签字而失败 */
    @Test
    fun `zip without signature has only json`() {
        val dir = java.nio.file.Files.createTempDirectory("xj_upload_test2").toFile()
        val bos = UploadDataBos().apply { uploadTaskResultDTOs = listOf(UploadDataBo()) }
        val zip = PatrolUploader.writeZipTo(dir, bos, null, null)
        val entries = java.util.zip.ZipFile(zip).use { zf -> zf.entries().toList().map { it.name } }
        assertEquals(listOf(PatrolUploader.JSON_FILE_NAME), entries)
    }

    /** 签字图文件名要按原版形状生成（`xjTaskPic_yyyyMMdd_HHmmss.jpg`） */
    @Test
    fun `signature picture name follows original convention`() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        cal.set(2026, java.util.Calendar.OCTOBER, 14, 6, 13, 20)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals(
            "xjTaskPic_20261014_061320.jpg",
            PatrolUploader.signPicNameOf(cal.timeInMillis),
        )
    }

    /**
     * 2026-10-14 Actions(UTC) 上这条挂过：`signPicFormat` 是 companion 里的常驻实例，
     * `SimpleDateFormat` 构造时就把当时（UTC）的默认时区拷走了，@Before 再 setDefault 也没用。
     * 现在格式化器每次现造并接受显式时区，这条在任何时区下都必须过。
     */
    @Test
    fun `signature picture name uses the zone it is given`() {
        val shanghai = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val cal = java.util.Calendar.getInstance(shanghai)
        cal.set(2026, java.util.Calendar.OCTOBER, 14, 6, 13, 20)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals(
            "xjTaskPic_20261014_061320.jpg",
            PatrolUploader.signPicNameOf(cal.timeInMillis, shanghai),
        )
    }

    /** 离线巡逻用的任务行：时间就是本机断网时填的那两个毫秒值（用户 m05768 的核心问题） */
    private fun localTask(
        actualStart: Long = 1791929930253L, // 2026-10-14 06:18:50
        actualEnd: Long = 1791930427916L, // 2026-10-14 06:27:07
    ) = TaskEntity(
        id = "patrolTask_20261013_1942",
        tableNo = "patrolTask_20261013_1942",
        taskId = "1000000000000640",
        tableInfoId = "1000000000000641",
        taskName = "炼油区余热发电-工艺外操-2-hour",
        taskType = "PATROL_taskType/dailyPatrol",
        taskStateId = "PATROL_taskState/completed",
        taskStateLabel = "已完成",
        routeTypeLabel = "智慧安全系统（工艺）",
        routeName = "炼油区余热发电-工艺外操-2-hour",
        startTime = 1791928800000L,
        endTime = 1791936000000L,
        actualStartTime = actualStart,
        actualEndTime = actualEnd,
        isTemp = false,
        isInOrder = false,
        areaCount = 3,
        groupName = "g",
        stuffInCharge = "张三",
        updatedAt = 0L,
    )

    /** 信封上的两个时间一律来自本机任务行，且逐毫秒一致（服务端实测照单采纳） */
    @Test
    fun `envelope times come from the local task row`() {
        val row = localTask()
        val bo = PatrolUploader.dataBo(
            t = row,
            potrolTask = PatrolUploader.taskBo(row),
            areas = emptyList(),
            works = emptyList(),
        )
        assertEquals(1791929930253L, bo.actualStartTime)
        assertEquals(1791930427916L, bo.actualEndTime)
        val json = gson.toJson(UploadDataBos().apply { uploadTaskResultDTOs = listOf(bo) })
        assertTrue(json.contains("\"actualStartTime\":1791929930253"))
        assertTrue(json.contains("\"actualEndTime\":1791930427916"))
    }

    /** 本机没有时间（0）时，宁可整条不带，也绝不拿"当下时间"或服务端时钟凑一个 */
    @Test
    fun `envelope omits times the local row never had`() {
        val row = localTask(actualStart = 0L, actualEnd = 0L)
        val bo = PatrolUploader.dataBo(
            t = row,
            potrolTask = PatrolUploader.taskBo(row),
            areas = emptyList(),
            works = emptyList(),
        )
        assertNull(bo.actualStartTime)
        assertNull(bo.actualEndTime)
        val json = gson.toJson(UploadDataBos().apply { uploadTaskResultDTOs = listOf(bo) })
        assertFalse(json.contains("actualStartTime"))
        assertFalse(json.contains("actualEndTime"))
    }

    /**
     * 用户 m13590：三个时间各自独立上传 —— 「开始任务」勾了上报只带实际开始时间，
     * 「提交任务」勾了上报只带实际结束时间，免得一个动作顺手把另一个时间也写死在后台。
     */
    @Test
    fun `start only upload carries actual start and omits actual end`() {
        val row = localTask()
        val bo = PatrolUploader.dataBo(
            t = row,
            potrolTask = PatrolUploader.taskBo(row),
            areas = emptyList(),
            works = emptyList(),
            times = PatrolUploader.TimeScope.START_ONLY,
        )
        assertEquals(1791929930253L, bo.actualStartTime)
        assertNull(bo.actualEndTime)
        val json = gson.toJson(UploadDataBos().apply { uploadTaskResultDTOs = listOf(bo) })
        assertTrue(json.contains("\"actualStartTime\":1791929930253"))
        assertFalse(json.contains("actualEndTime"))
    }

    /** 「提交任务」勾了上报：只带实际结束时间（结束时间后台允许再覆盖） */
    @Test
    fun `end only upload carries actual end and omits actual start`() {
        val row = localTask()
        val bo = PatrolUploader.dataBo(
            t = row,
            potrolTask = PatrolUploader.taskBo(row),
            areas = emptyList(),
            works = emptyList(),
            times = PatrolUploader.TimeScope.END_ONLY,
        )
        assertNull(bo.actualStartTime)
        assertEquals(1791930427916L, bo.actualEndTime)
        val json = gson.toJson(UploadDataBos().apply { uploadTaskResultDTOs = listOf(bo) })
        assertFalse(json.contains("actualStartTime"))
        assertTrue(json.contains("\"actualEndTime\":1791930427916"))
    }

    /** 「上传本区域」只送签到与巡检项，任务级的两个时间都不带 */
    @Test
    fun `scope none omits both times`() {
        val row = localTask()
        val bo = PatrolUploader.dataBo(
            t = row,
            potrolTask = PatrolUploader.taskBo(row),
            areas = emptyList(),
            works = emptyList(),
            times = PatrolUploader.TimeScope.NONE,
        )
        assertNull(bo.actualStartTime)
        assertNull(bo.actualEndTime)
    }

    /** 载荷里除了本机任务行，任务级字段只来自本机行本身（不掺服务端/网络数据） */
    @Test
    fun `task level fields all trace back to the local row`() {
        val row = localTask()
        val bo = PatrolUploader.taskBo(
            t = row,
            baseUrl = "https://supos.example.com:8443",
            reporterId = "1000000000000952",
            reporterName = "张三",
            signPicName = null,
        )
        assertEquals(row.taskId, bo.id)
        assertEquals(row.tableNo, bo.tableNo)
        assertEquals(row.tableInfoId, bo.tableInfoId)
        assertEquals("PATROL_taskState/completed", bo.taskState?.id)
        assertEquals(row.startTime, bo.startTime)
        assertEquals(row.endTime, bo.endTime)
        assertEquals("1000000000000952", bo.completeStaff?.id)
        assertEquals("张三", bo.completeStaff?.name)
    }

    /**
     * 用户 m14803：延迟开始/结束原因只有勾了「清除」开关才带值，而且带的是**空字符串**
     * （覆盖为空）；不勾时该键整个不出现 —— 空串与"键不存在"在服务端是两件事：
     * 前者＝清空，后者＝保持原值。
     */
    @Test
    fun `delay reasons are only sent when clearing is asked`() {
        val row = localTask()
        val keep = PatrolUploader.taskBo(row)
        assertNull(keep.delayedStartReason)
        assertNull(keep.delayedEndReason)
        val keepJson = gson.toJson(keep)
        assertFalse(keepJson.contains("delayedStartReason"))
        assertFalse(keepJson.contains("delayedEndReason"))

        val clearStart = PatrolUploader.taskBo(row, clearDelay = PatrolUploader.DelayClear.START)
        assertEquals("", clearStart.delayedStartReason)
        assertNull(clearStart.delayedEndReason)
        val startJson = gson.toJson(clearStart)
        assertTrue(startJson.contains("\"delayedStartReason\":\"\""))
        assertFalse(startJson.contains("delayedEndReason"))

        val clearEnd = PatrolUploader.taskBo(row, clearDelay = PatrolUploader.DelayClear.END)
        assertNull(clearEnd.delayedStartReason)
        assertEquals("", clearEnd.delayedEndReason)
        val endJson = gson.toJson(clearEnd)
        assertFalse(endJson.contains("delayedStartReason"))
        assertTrue(endJson.contains("\"delayedEndReason\":\"\""))
    }
}

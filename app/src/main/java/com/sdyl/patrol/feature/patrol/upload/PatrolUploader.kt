package com.sdyl.patrol.feature.patrol.upload

import android.os.Build
import com.google.gson.Gson
import com.sdyl.patrol.BuildConfig
import com.sdyl.patrol.core.data.db.AreaEntity
import com.sdyl.patrol.core.data.db.SdylDatabase
import com.sdyl.patrol.core.data.db.TaskEntity
import com.sdyl.patrol.core.data.db.WorkItemEntity
import com.sdyl.patrol.core.device.DeviceIdentity
import com.sdyl.patrol.core.network.ApiClient
import com.sdyl.patrol.core.network.ApiException
import com.sdyl.patrol.feature.patrol.PatrolApi
import com.sdyl.patrol.feature.patrol.PatrolRepository
import com.sdyl.patrol.feature.patrol.model.PatrolConclusion
import com.sdyl.patrol.feature.patrol.model.PatrolTaskState
import com.sdyl.patrol.feature.patrol.model.WorkItemState
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 离线巡检结果上传器——**两步走，与原版一字不差**：
 *
 *  1. 把 `xj_upload.json`（内容 = [UploadDataBos] 的 JSON）打成 `xj_upload.zip`，
 *     以 `multipart/form-data` 的 `zipFile` 字段 POST 到文件服务
 *     `/inter-api/file-server/v1/file/upload/file`，拿回附件 `path`；
 *  2. `POST /msService/PATROL/uploadTaskResult?isOnLine=false`，body 只有 `{"filePath": path}`。
 *
 * 原版对应实现：
 *  - `XJUploadTasksController.uploadTasks()` → `createZipFile()` → `uploadZipFile()` → `uploadXJData()`
 *  - `XJUploadTaskPresenter.uploadZipFile/uploadXJData`
 *  - `Util.createZipFileForm(File)`（字段名 `zipFile`，类型 `application/zip`）
 *  - `Api.setTimeOut(600)`（上传超时 600 秒，已在 [ApiClient.createLong] 对齐）
 *
 * 只有第 2 步服务端确认成功，才会把本地 `dirty` 清掉、`isUpload` 置位。
 * 中途任何一步失败都原样保留本地数据，用户可反复重试。
 */
class PatrolUploader(
    private val api: ApiClient,
    private val db: SdylDatabase,
    private val cacheDir: File,
    /** 当前服务地址，如 `https://supos.example.com:8443`；用于 `potrolTask.ip` 与 `workRoute.ip` */
    private val baseUrl: String? = null,
    /**
     * 设备 IMEI —— 原版 `XJUploadWorkBO.imei = DeviceInfoUtil.getImeiWithManual(ctx)`，
     * 即**软件设置里手填的 IMEI**（Android 10+ 读不到真机 IMEI）。
     * 用户诉求（m04512 第 6 条）：原巡检 apk 签到时会上传这个码，本软件要补上。
     */
    private val imei: () -> String = { "" },
    /** 账号手机号 —— 原版 `XJUploadWorkBO.phoneNum = AccountInfo.getMobile()` */
    private val phoneNum: () -> String = { "" },
    /**
     * 上报人员兜底（用户 m07827）：本机记录里没写人时（例如服务端同步下来的区域
     * 从没在本机签过）用「我的 → 上报人员」配置的人，默认就是登录账号。
     */
    private val reporterId: () -> String = { "" },
    private val reporterName: () -> String = { "" },
    /**
     * 完成人签字图（用户 m05038）—— 当前登录账号那张图片（见 SignStore）。
     *
     * 原版签字=现场手写、存成 `xjTaskPic_yyyyMMdd_HHmmss.jpg`，打包时放进 zip 的 `pics/`，
     * 并把文件名写进 `potrolTask.signPicPath`（抓包实测：
     * `pics/xjTaskPic_20261014_061320.jpg` + `"signPicPath":"xjTaskPic_20261014_061320.jpg"`）。
     * 本应用不要求现场签字，用「我的」里选中的图片走同一条路；没有图就整条不带，
     * **上传绝不因缺签字而失败**。
     */
    private val signPicFile: () -> File? = { null },
) {

    /**
     * 这次载荷带哪些**任务级**时间（用户 m13590）。
     *
     * 用户的原话思路：开始时间、各巡检点签到时间、结束时间三条**各自独立上传**——
     * 「开始任务」只报开始时间（一报就被服务端记死，不可覆盖），
     * 「上传本区域」只报签到与巡检项（绝不能顺手把任务的两个时间写上去），
     * 「提交任务」只报结束时间（结束时间之后还能再上传覆盖），
     * 断网整单跑完再用「打包提交任务」把三条一起报（就是原版离线补传的样子）。
     */
    /**
     * 这次载荷要不要把**延迟开始/结束原因**强行清空（用户 m14803）。
     *
     * 用户要求：开关关闭时保持现状（该键整个不带，服务端已有的值不动）；
     * 开关打开时「无论服务器是否有信息，强行覆盖为空」——所以带的是**空字符串**，
     * 而不是 null（朴素 Gson 会把 null 键省略掉，那就等于什么都没说）。
     */
    enum class DelayClear {
        /** 不带（默认，服务端已有的延迟原因保持不动） */
        NONE,
        /** 清空延迟开始原因（「开始任务」勾了开关） */
        START,
        /** 清空延迟结束原因（「结束任务」勾了开关） */
        END,
    }

    enum class TimeScope {
        /** 开始 + 结束（原版离线整单补传 / 「打包提交任务」） */
        ALL,
        /** 只带实际开始时间（「开始任务」勾了上报） */
        START_ONLY,
        /** 只带实际结束时间（「提交任务」勾了上报） */
        END_ONLY,
        /** 两个都不带（「上传本区域」只报签到与巡检项） */
        NONE,
    }

    /** 上传结果 */
    data class Outcome(
        /** 成功上传的区域数 */
        val areaCount: Int,
        /** 成功上传的巡检项条数 */
        val itemCount: Int,
        /** 文件服务返回的附件路径 */
        val filePath: String,
        /** 服务端返回的任务 id（uploadTaskResult 的 data） */
        val serverTaskId: Long?,
        /** 这次**没有**带签字图（服务端已有 / 任务已完成，用户 m11701） */
        val signPicSkipped: Boolean = false,
    )

    /** 朴素的 Gson —— 原版 `GsonUtil.gsonString` 就是 `new Gson()`，null 字段直接省略 */
    private val gson = Gson()

    private val taskDao get() = db.taskDao()
    private val areaDao get() = db.areaDao()
    private val workDao get() = db.workItemDao()

    /**
     * 上传若干区域的结果（必须属于同一任务或不同任务都行，按任务分组打包）。
     *
     * @param onStep 过程回调，用于在界面上显示"正在打包/正在上传"等阶段文案。
     */
    suspend fun upload(
        areas: List<AreaEntity>,
        onStep: (String) -> Unit = {},
        /**
         * 载荷里 `potrolTask.taskState` 是否强行按「进行中」上报。
         *
         * 原版离线补传时**照实**上报本机状态（离线整单跑完就是「已完成」），
         * 服务端据此完成这单并采纳载荷里的 `actualStartTime/actualEndTime`——
         * 这正是用户 m10329 要的「后台显示断网时的时间」。
         * 只有服务端因为「已完成」拒收（`Error parsing device running information!`）时，
         * 才退回 true 重试一次（保住「提交后还能改签到时间再上传」）。
         */
        forceRunning: Boolean = false,
        /**
         * 这次载荷要不要带签字图（用户 m11701）。默认 true = 由 [shouldSkipSignPic] 先查服务端：
         * 已有这单的签字图就自动跳过。
         *
         * 原版（以及本应用以前）每提交一次就往文件服务加一张 `xjTaskPic_*.jpg`，
         * 用户那条任务因此攒了好几张（手机 A、手机 B、本应用各一次，m12024 时已是 5 张）。
         * **只影响签字图**，`potrolTask.signPicPath` 也一并留空，
         * 服务端既有的那张图和 `workAreas[].SignPic`（= 任务 tableInfoId）都不受影响。
         */
        includeSignPic: Boolean = true,
        /**
         * 载荷里要不要带 `workAreas[] / workItems[]`（用户 m12413）。
         *
         * 默认 true = 整单上传（原版离线补传的形态）。
         * 「提交任务」界面改成 **false**：只把任务级状态与 `actualStartTime/actualEndTime`
         * 送上去，各区域的签到时间与巡检结果交给区域作业页的「上传本区域」——
         * 三条时间（开始 / 签到 / 结束）各自独立上传，避免提交任务时又把别处
         * （原版手机补传）已经落地的签到时间覆盖掉。
         */
        includeAreas: Boolean = true,
        /**
         * 载荷里带哪几个任务级时间（用户 m13590）。默认 ALL = 原版离线补传的样子。
         */
        times: TimeScope = TimeScope.ALL,
        /**
         * 要不要把延迟开始/结束原因清空（用户 m14803）。
         *
         * 服务端这两个字段走 `potrolTask.delayedStartReason/delayedEndReason`
         * （原版 `XJUploadTaskBO` 上的同名字段）：传空字符串＝覆盖为空，
         * 整个键不带＝保持服务端原值。
         */
        clearDelay: DelayClear = DelayClear.NONE,
    ): Outcome {
        if (areas.isEmpty()) throw ApiException(0, "没有待上传的数据")

        // 签字图查重（用户 m11701 / m12024）—— 特意放在这里而不是调用方：
        // 「提交任务」「上传本区域」「一键上传待上传数据」三条路径最后都会走 upload()，
        // 之前只有「提交任务」那条查过服务端，用户单区域上传又给同一单攒出两张签字图。
        val skipSign = includeSignPic && shouldSkipSignPic(areas, onStep)

        // 签字图（用户 m05038）：名字按原版约定 `xjTaskPic_yyyyMMdd_HHmmss.jpg` 现取一次，
        // 既作 zip 里的条目名（`pics/<名字>`），也作载荷里的 `potrolTask.signPicPath`。
        val signPic = if (includeSignPic && !skipSign) {
            signPicFile()?.takeIf { it.isFile && it.length() > 0L }
        } else {
            null
        }
        val signPicName = signPic?.let { signPicNameOf(System.currentTimeMillis()) }

        onStep("正在打包本地数据…")
        val bos = buildPayload(areas, forceRunning, signPicName, includeAreas, times, clearDelay)
        if (bos.uploadTaskResultDTOs.isNullOrEmpty()) {
            throw ApiException(0, "本地缺少任务主数据，请先联网刷新一次任务列表")
        }
        val zip = writeZip(bos, signPic, signPicName)

        val svc = api.createLong(PatrolApi::class.java)

        onStep("正在上传数据包（${zip.length() / 1024} KB）…")
        val part = MultipartBody.Part.createFormData(
            "zipFile",
            zip.name,
            zip.asRequestBody("application/zip".toMediaTypeOrNull()),
        )
        val uploaded = svc.uploadZipFile(part)
        if (!uploaded.isOk) {
            throw ApiException(uploaded.code, "数据包上传失败：${uploaded.errorText}")
        }
        val path = uploaded.payload()?.path?.takeIf { it.isNotBlank() }
            ?: throw ApiException(uploaded.code, "文件服务未返回附件路径，无法继续提交")

        onStep("正在提交巡检结果…")
        val done = svc.uploadOfflineTaskResult(false, mutableMapOf<String, Any?>("filePath" to path))
        if (!done.isOk) {
            throw ApiException(done.code, "巡检结果提交失败：${done.errorText}")
        }

        // 服务端已确认，才允许清 dirty。
        // 只传任务级（includeAreas = false）时，区域/巡检项这次没送出去，
        // 绝不能把它们的「已上传」标记打上（用户 m12413：三条上传各自独立）。
        var itemCount = 0
        var areaCount = 0
        if (includeAreas) {
            areas.forEach { area ->
                val dirty = workDao.dirtyByArea(area.id)
                if (dirty.isNotEmpty()) {
                    workDao.markUploaded(dirty.map { it.id })
                    itemCount += dirty.size
                }
                areaDao.markUploaded(area.id, System.currentTimeMillis())
                areaCount++
            }
        }
        onStep("上传完成")
        return Outcome(areaCount, itemCount, path, done.payload(), signPicSkipped = !includeSignPic || skipSign)
    }

    /**
     * 这单要不要跳过签字图（用户 m11701 的要求；m12024 补齐到所有上传路径）。
     *
     * 先问服务端：`GET /inter-api/file-server/v1/file/upload-list?linkId=<任务 tableInfoId>`。
     * 实测那个 `linkId` 正是任务区域 `SignPic` 字段里写的那个数
     * （1942：`tableInfoId=1000000000000641`，里面有手机 A / 手机 B / 本应用历次传的
     * `xjTaskPic_*.jpg`，`propertyCode` 都是 `PATROL_1.0.0_patrolTask_PotrolTask_signPic`）。
     *
     * 接口异常/断网时退回用户给的兜底判据：**本机这单已记成「已完成」**就不补传签字图
     * （那是「回头改时间再传」的场景）。
     */
    private suspend fun shouldSkipSignPic(areas: List<AreaEntity>, onStep: (String) -> Unit): Boolean {
        val serverHas = try {
            val tableInfoId = taskTableInfoIds(areas).firstOrNull()
            if (tableInfoId == null) {
                null
            } else {
                val resp = api.create(PatrolApi::class.java).listUploadedFiles(tableInfoId)
                if (resp.isOk) resp.payload().orEmpty().any { it.isSignPic } else null
            }
        } catch (t: Throwable) {
            null
        }
        if (serverHas == true) {
            onStep("服务端已有签字图，本次不再重复上传")
            return true
        }
        if (serverHas == false) return false
        if (localTaskCompleted(areas)) {
            onStep("任务已完成，本次不再补传签字图")
            return true
        }
        return false
    }

    /** 这些区域所属任务的 `tableInfoId` 列表（去重；它同时是签字图附件挂的 `linkId`） */
    private suspend fun taskTableInfoIds(areas: List<AreaEntity>): List<String> {
        val out = ArrayList<String>()
        areas.forEach { area ->
            val id = taskDao.byTableNo(area.taskTableNo)?.tableInfoId
            if (!id.isNullOrBlank() && !out.contains(id)) out.add(id)
        }
        return out
    }

    /** 本机这单是否已记成「已完成」（查不到服务端时的兜底判据） */
    private suspend fun localTaskCompleted(areas: List<AreaEntity>): Boolean {
        areas.forEach { area ->
            val task = taskDao.byTableNo(area.taskTableNo) ?: return@forEach
            if (task.taskStateId == PatrolTaskState.COMPLETED || task.taskStateLabel == "已完成") return true
        }
        return false
    }

    /* ------------------------------ 载荷装配 ------------------------------ */

    private suspend fun buildPayload(
        areas: List<AreaEntity>,
        forceRunning: Boolean = false,
        signPicName: String? = null,
        /** false = 只装任务级载荷（用户 m12413，「提交任务」不带各区域巡检时间） */
        includeAreas: Boolean = true,
        /** 带哪几个任务级时间（用户 m13590） */
        times: TimeScope = TimeScope.ALL,
        /** 要不要把延迟开始/结束原因清空（用户 m14803） */
        clearDelay: DelayClear = DelayClear.NONE,
    ): UploadDataBos {
        val list = ArrayList<UploadDataBo>()
        var missingWorkArea = 0
        // 原版每条 XJUploadWorkBO 都带设备和账号身份，取一次复用（值为空时保持 null，Gson 会省略）
        val imeiValue = imei().takeIf { it.isNotBlank() }
        val phoneValue = phoneNum().takeIf { it.isNotBlank() }
        areas.groupBy { it.taskTableNo }.forEach { (tableNo, group) ->
            val task = taskDao.byTableNo(tableNo)
            if (task == null) {
                missingWorkArea += group.size
                return@forEach
            }
            val areaBos = ArrayList<UploadAreaBo>(group.size)
            val workBos = ArrayList<UploadWorkBo>()
            /*
             * 用户 m14256：上传的人（执行人/完成人/签到人）以**区域作业页里记下的巡检人**为准，
             * 「我的 → 上报人员」只做兜底。
             */
            val (staffId, staffName) = localSigner(group, reporterId(), reporterName())
            // 只传任务级时，区域这一层整个不装（也不做签到校验，免得挡住「单独提交结束时间」）
            if (!includeAreas) {
                list += dataBo(
                    t = task,
                    potrolTask = taskBo(
                        t = task,
                        baseUrl = baseUrl,
                        reporterId = staffId,
                        forceRunning = forceRunning,
                        reporterName = staffName,
                        signPicName = signPicName,
                        clearDelay = clearDelay,
                    ),
                    areas = emptyList(),
                    works = emptyList(),
                    times = times,
                )
                return@forEach
            }
            group.forEach { area ->
                val workAreaId = area.workAreaId
                if (workAreaId.isNullOrBlank()) {
                    // 原版上传时 workAreas[].id 用的是基础区域 id（XJTaskAreaDTO 的 `id` 字段），
                    // 拿不到就不能瞎填，否则服务端落到错误区域上。
                    throw ApiException(0, "区域「${area.name.orEmpty()}」缺少 workAreaId，请先联网刷新任务列表")
                }
                if (area.cardTime == null || !area.isSigned) {
                    throw ApiException(0, "区域「${area.name.orEmpty()}」还没有签到，请先签到再上传")
                }
                areaBos += areaBo(area, task, staffId, staffName)
                // 与原版一致：只传**已完成**的巡检项（getTaskWorks(..., true)）
                workDao.finishedByArea(area.id).forEach { w ->
                    workBos += workBo(w, area, staffId, staffName).apply {
                        this.imei = imeiValue
                        this.phoneNum = phoneValue
                    }
                }
            }
            list += dataBo(
                t = task,
                potrolTask = taskBo(
                    t = task,
                    baseUrl = baseUrl,
                    reporterId = staffId,
                    forceRunning = forceRunning,
                    reporterName = staffName,
                    signPicName = signPicName,
                    clearDelay = clearDelay,
                ),
                areas = areaBos,
                works = workBos,
                times = times,
            )
        }
        if (list.isEmpty()) {
            throw ApiException(0, "本地缺少任务主数据（$missingWorkArea 个区域），请先联网刷新任务列表")
        }
        return UploadDataBos().apply { this.uploadTaskResultDTOs = list }
    }

    /**
     * 只在本机把「这次上传会发出去的载荷」装出来，**不发任何网络请求、不写盘**
     * （用户 m05768）。界面用它做「预览将要上传的数据」，让人在断网状态下也能
     * 逐字段确认：用到的每个值都来自本机库/本机设置/本机签字图。
     */
    suspend fun previewPayloadJson(areas: List<AreaEntity>): String {
        if (areas.isEmpty()) throw ApiException(0, "没有可预览的数据")
        val signPic = signPicFile()?.takeIf { it.isFile && it.length() > 0L }
        val signPicName = signPic?.let { signPicNameOf(System.currentTimeMillis()) }
        return gson.toJson(buildPayload(areas, false, signPicName))
    }

    /* ------------------------------ 载荷装配 ------------------------------ */
    /* ------------------------------ 打包 ------------------------------ */

    private fun writeZip(bos: UploadDataBos, signPic: File? = null, signPicName: String? = null): File =
        writeZipTo(File(cacheDir, "xj"), bos, signPic, signPicName)

    companion object {
        /** 原版 `XJUploadTasksController` 里的文件名常量 */
        const val JSON_FILE_NAME = "xj_upload.json"
        const val ZIP_FILE_NAME = "xj_upload.zip"

        /** zip 里放签字图的目录（原版 `pics/`） */
        const val PIC_DIR_NAME = "pics"

        /** 原版签字图名前缀，完整形如 `xjTaskPic_20261014_061320.jpg` */
        const val SIGN_PIC_PREFIX = "xjTaskPic_"

        /**
         * 签字图文件名（原版形状 `xjTaskPic_yyyyMMdd_HHmmss.jpg`）。
         *
         * 格式化器**每次现造**：`SimpleDateFormat` 在构造时就把当时的默认时区拷走了，
         * 复用同一个实例会在「设备/测试改了时区」之后仍按老时区输出——Actions(UTC) 上
         * 签字图名断言就是这么跟本机(+08)差 8 小时挂掉的。zone 参数只为让单测钉死时区。
         */
        fun signPicNameOf(
            timestamp: Long,
            zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
        ): String {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
            fmt.timeZone = zone
            return "${SIGN_PIC_PREFIX}${fmt.format(java.util.Date(timestamp))}.jpg"
        }

        /**
         * 用户 m14256：上传的人（任务级执行人/完成人）以**区域作业页里记下的巡检人**为准，
         * 「我的 → 上报人员」只做兜底——用户在巡检任务里临时改的人必须生效。
         *
         * 取**最近签到**的那条：完成人通常就是最后签的那个人。只想传任务级时间
         * （没装区域）时 areas 里没有区域，自然回落到「我的」那套。
         */
        internal fun localSigner(
            areas: List<AreaEntity>,
            fallbackId: String,
            fallbackName: String,
        ): Pair<String, String> {
            val sign = areas
                .filter { it.isSigned && !it.signStaffName.isNullOrBlank() }
                .maxByOrNull { it.cardTime ?: 0L }
            val id = sign?.signStaffId?.takeIf { it.isNotBlank() } ?: fallbackId
            val name = sign?.signStaffName?.takeIf { it.isNotBlank() } ?: fallbackName
            return id to name
        }

        /**
         * 打包上传 zip —— 原版 `XJUploadTasksController.createZipFile`：
         * 一个 `xj_upload.json`，外加（有签字的）`pics/<签字图名>`。
         *
         * 抽成静态函数，好让单元测试直接验证 zip 里的条目名
         * （名字对不上，服务端就找不到完成人签字）。
         */
        internal fun writeZipTo(
            dir: File,
            bos: UploadDataBos,
            signPic: File? = null,
            signPicName: String? = null,
        ): File {
            dir.mkdirs()
            val json = File(dir, JSON_FILE_NAME)
            json.writeText(Gson().toJson(bos), Charsets.UTF_8)
            val zip = File(dir, ZIP_FILE_NAME)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                zos.putNextEntry(ZipEntry(JSON_FILE_NAME))
                zos.write(json.readBytes())
                zos.closeEntry()
                // 签字图：原版 zip 里就是 `pics/xjTaskPic_*.jpg`（抓包实测），条目名要与
                // 载荷里的 `potrolTask.signPicPath` 一致
                if (signPic != null && !signPicName.isNullOrBlank()) {
                    zos.putNextEntry(ZipEntry("$PIC_DIR_NAME/$signPicName"))
                    zos.write(signPic.readBytes())
                    zos.closeEntry()
                }
            }
            return zip
        }

        /**
         * 任务行 + 区域载荷 + 巡检项载荷 → 一条 `uploadTaskResultDTOs`。
         *
         * **时间只认本机任务行**（用户 m05768 问「能不能完全用本地信息上传」）：
         * `actualStartTime/actualEndTime` 就是离线期间 `pinLocalTimes`/`startTask`/`endTask`
         * 写进本机库的那两个毫秒值（也就是界面「开始任务时间」「提交任务时间」里填的）；
         * 是 0 就整条不带（Gson 省略），**绝不用服务端时钟或当下时间凑一个**。
         * 服务端 1942 实测就是照单采纳这两个值（用户 m05747 的 06:27:07 证据）。
         */
        internal fun dataBo(
            t: TaskEntity,
            potrolTask: UploadTaskBo,
            areas: List<UploadAreaBo>,
            works: List<UploadWorkBo>,
            /** 带哪几个任务级时间（用户 m13590）：不带的那一项留 null，Gson 会把这个键整个省掉 */
            times: TimeScope = TimeScope.ALL,
        ) = UploadDataBo().apply {
            val withStart = times == TimeScope.ALL || times == TimeScope.START_ONLY
            val withEnd = times == TimeScope.ALL || times == TimeScope.END_ONLY
            this.actualStartTime = if (withStart) t.actualStartTime.takeIf { it > 0 } else null
            this.actualEndTime = if (withEnd) t.actualEndTime.takeIf { it > 0 } else null
            this.potrolTask = potrolTask
            this.workAreas = areas
            this.workItems = works
        }

        /**
         * 任务 → `potrolTask`。
         *
         * 原版这一块是 `GsonUtil.gsonToBean(gsonString(XJTaskDTO), XJUploadTaskBO.class)`，
         * 只有 JSON 键名对得上的字段能存活，存活下来的就是
         * `id / tableNo / tableInfoId / taskType / taskState / workRoute / routeTypeName /
         *  startTime / endTime / advanceStartTime / executeUser / isTemp / ip / 日志三件套`。
         * `workRoute` 是必填外键——少了它服务端保存任务会失败。
         */
        internal fun taskBo(
            t: TaskEntity,
            baseUrl: String? = null,
            reporterId: String? = null,
            forceRunning: Boolean = false,
            reporterName: String? = null,
            /** 签字图文件名（zip 里的 `pics/<这个名字>`），没有签字图时为空 */
            signPicName: String? = null,
            /** 要不要把延迟开始/结束原因清空（用户 m14803） */
            clearDelay: DelayClear = DelayClear.NONE,
        ) = UploadTaskBo().apply {
            mobileVersion = PatrolRepository.LOG_MOBILE_VERSION
            equipmentModel = DeviceIdentity.equipmentModel()
            macAddress = DeviceIdentity.macAddress()
            id = t.taskId
            tableNo = t.tableNo
            tableInfoId = t.tableInfoId
            taskName = t.taskName
            taskType = t.taskType
            /*
             * 完成人：原版 `XJUploadDataBO(z=false, …)` 一定会写
             * `potrolTask.completeStaff = StringIdNameEntity(dto.completeStaff){ name = dto.completeStaffName }`
             * （`XJUploadDataBO.java:33-38`）。离线整单是在「提交任务」时把完成人写在本机的，
             * 本应用对应「上报人员」，所以这里补上——**漏了它服务端判「已完成」时没有完成人，
             * 会直接 500 `Error parsing device running information!`**（2026-10-14 那次复现）。
             */
            completeStaff = run {
                val id = reporterId?.takeIf { it.isNotBlank() }
                val name = reporterName?.takeIf { it.isNotBlank() }
                if (id == null && name == null) null else UploadIdName(id, name)
            }
            completeStaffName = reporterName?.takeIf { it.isNotBlank() }
            /*
             * 任务日志（用户 m05038 第 2 条）：原版 `potrolTask.log` 是
             * `2026-10-14 05:32:32 张三 获取任务；2026-10-14 06:00:09 张三 开始任务；…`，
             * 本机在「获取/开始/结束任务」时逐行累积（见 PatrolRepository.appendTaskLog）。
             */
            log = t.taskLog?.takeIf { it.isNotBlank() }
            /*
             * 签字图（用户 m05038 第 1 条）：原版这里放的是现场签字的文件名，
             * 图片本体作为 `pics/<同名>` 打进同一个 zip（见 writeZip）。
             */
            signPicPath = signPicName?.takeIf { it.isNotBlank() }
            /*
             * 延迟开始/结束原因（用户 m14803）：开关打开时带**空字符串**强行覆盖为空，
             * 关闭时保持 null（朴素 Gson 省略该键）＝服务端已有的值不动。
             * 原版对应 `XJUploadTaskBO.delayedStartReason/delayedEndReason`。
             */
            if (clearDelay == DelayClear.START) delayedStartReason = ""
            if (clearDelay == DelayClear.END) delayedEndReason = ""
            // 用户 m10329：**照实**上报本机状态才是原版离线补传的样子——
            // 服务端靠 `potrolTask.taskState` 判断这是「离线整单跑完」，
            // 才会采纳载荷里的 `actualStartTime/actualEndTime`（离线时的时间）。
            // 强行改成「进行中」会让服务端以为这单还在跑，于是它用自己的时钟盖时间。
            // 只有传「已完成」被服务端拒收（HTTP 500 `Error parsing device running information!`）
            // 时，才由 [PatrolRepository.uploadTask] 退回 forceRunning=true 重试。
            taskState = if (forceRunning && t.taskStateId == PatrolTaskState.COMPLETED) {
                UploadIdValue(PatrolTaskState.RUNNING, PatrolTaskState.label(PatrolTaskState.RUNNING))
            } else {
                UploadIdValue(t.taskStateId, t.taskStateLabel)
            }
            routeTypeName = t.routeTypeId?.let { UploadIdValue(it, t.routeTypeLabel) }
            startTime = t.startTime.takeIf { it > 0 }
            endTime = t.endTime.takeIf { it > 0 }
            advanceStartTime = t.advanceStartTime.takeIf { it > 0.0 }
            // 用户 m14256：区域作业页记的巡检人优先，其次才是本机任务行上的执行人
            executeUser = (reporterId?.takeIf { it.isNotBlank() } ?: t.executeUser)
                ?.let { UploadIdOnly(it) }
            isTemp = t.isTemp
            ip = baseUrl
            workRoute = t.groupId?.let { gid ->
                UploadRouteBo().apply {
                    id = gid
                    code = t.groupCode
                    name = t.routeName ?: t.groupName
                    ip = baseUrl
                    isInOrder = t.isInOrder
                }
            }
            // 原版 `if (!isOnline) potrolTask.eamsOperateState = GsonUtil.gsonString(hashSet)`：
            // 没有任何设备状态变化时就是空数组字符串。这一项漏了服务端会 500。
            eamsOperateState = "[]"
        }

        /**
         * 区域 → `workAreas[]`。
         *
         * 原版这块是 XJTaskAreaDTO 的 Gson 往返，**只有** `id`(=workAreaId)、`cardTime`、
         * `clockCoordinate`、`coordinate`、`payCardType`、`remark`、`signInReason`、
         * `signPicNames`、`staffId`、`SignStaff` 能存活；`areaId`/`taskId`/`taskMold`/`nfcCode`/
         * `pciName`/`pciPath`/`distanceDValue` 恒为 null（Gson 省略）。
         * 所以这里**不填** areaId/taskId，免得服务端把它们当成另一套主键。
         *
         * `signInReason` 在原版是 `StringIdEntity`（字典项），本应用是自由文本，
         * 硬塞进去会被当成解析不到的字典 id，因此改放自由文本字段 `remark`。
         */
        internal fun areaBo(
            a: AreaEntity,
            task: TaskEntity,
            reporterId: String? = null,
            reporterName: String? = null,
        ) = UploadAreaBo().apply {
            id = a.workAreaId
            SignStaff = a.signStaffName ?: reporterName?.takeIf { it.isNotBlank() }
            staffId = a.signStaffId ?: reporterId?.takeIf { it.isNotBlank() }
            cardTime = a.cardTime
            payCardType = UploadIdValue(
                a.payCardTypeId ?: PatrolRepository.payCardTypeOf(a.signType),
                a.signType,
            )
            completeStaff = UploadIdName(
                a.signStaffId ?: reporterId?.takeIf { it.isNotBlank() },
                a.signStaffName ?: reporterName?.takeIf { it.isNotBlank() },
            )
            remark = a.signInReason?.takeIf { it.isNotBlank() }
            clockCoordinate = a.clockCoordinate ?: buildClockCoordinate(a)
        }

        internal fun buildClockCoordinate(a: AreaEntity): String? {
            val lat = a.clockLat ?: return null
            val lon = a.clockLon ?: return null
            return "{\"lat\":$lat,\"lon\":$lon}"
        }

        /**
         * 一条巡检项 → 上传 BO。
         *
         * **注意 `realValue` / `concluse` 的方向**：载荷必须与原版一致 ——
         * `realValue` = 结论码（`PATROL_realValue/normal|abnormal|doubtful`），
         * `concluse` = 用户填的实测值/原始输入。
         * 而本应用本地库这两列的含义是**反的**（`concluse` 存结论码、`realValue` 存实测值，
         * 见 `PatrolDetailScreens.kt` 的 `WorkItemPatch(realValue = values[item.id], concluse = c)`
         * 与 `WorkItemDao.abnormalOfArea`），所以这里交换一次，保证发给服务端的语义正确。
         *
         * 没选结论但已完成时，结论码补 `normal`
         * （原版 `XJCalculateUtil.setTaskWorkConclusion`：非自动判定且 `realValue` 为空 → 补 normal）。
         *
         * 其余裁剪规则逐条照抄原版 `XJUploadDataBO.getWorkItems(isMerge, list)`：
         * 结论为「正常」时清空异常原因与通用原因；跳过的条目清空结论与实测值。
         */
        internal fun workBo(
            w: WorkItemEntity,
            area: AreaEntity? = null,
            reporterId: String? = null,
            reporterName: String? = null,
        ): UploadWorkBo {
            val state = w.taskDetailState ?: WorkItemState.CHECKED
            val bo = UploadWorkBo()
            bo.id = w.serverWorkItemId ?: w.id
            bo.taskWorkId = w.id
            bo.taskItemDetilId = w.id
            bo.tableInfoId = w.tableInfoId
            bo.taskDetailState = UploadIdOnly(state)

            // 本应用本地：concluse = 结论码，realValue = 实测值 —— 交换后发出
            val conclusion = w.concluse?.takeIf { it.isNotBlank() }
                ?: if (state != WorkItemState.SKIP) PatrolConclusion.NORMAL else null
            bo.realValue = conclusion?.let { UploadIdValue(it) }
            bo.concluse = w.realValue?.takeIf { it.isNotBlank() }

            val staffId = w.completeUser?.takeIf { it.isNotBlank() }
                ?: area?.signStaffId
                ?: reporterId?.takeIf { it.isNotBlank() }
            val staffName = w.completeUserName?.takeIf { it.isNotBlank() }
                ?: area?.signStaffName
                ?: reporterName?.takeIf { it.isNotBlank() }
            bo.completeUser = if (staffId != null || staffName != null) {
                UploadIdName(staffId, staffName)
            } else {
                null
            }
            bo.completeUserName = staffName
            bo.completeDate = w.completeDate
            bo.remark = w.remark
            bo.skipReason = w.skipReason
            // 只把「异常原因」文字放进 `reason`，`abnormalReason` 一律留空。
            //
            // 依据：原版 `abnormalReason` 装的是**异常原因字典项**（`SystemCodeEntity`），
            // 由 `XJTaskWorkAdapter.java:688` 的 `SystemCodeManager.getSystemCodeEntity(str)` 解析；
            // 而用户手输的文字走的是另一个字段 `reason`
            // （`XJAbnormalSelectDialog` 的「原因描述」→ `XJUploadDataBOS.java:35`
            //   `xJTaskWorkDTO.reason = ...deviceReason.get(i).yysm`，`yysm` 即"原因说明"）。
            // 本应用没有异常原因字典，面板里那个「异常原因」是自由文本，
            // 塞进 `abnormalReason` 会被服务端当成解析不到的字典 id，所以只走 `reason`。
            bo.abnormalReason = null
            bo.reason = w.reason ?: w.abnormalReason
            bo.passReason = w.passReason?.takeIf { it.isNotBlank() }?.let { UploadIdOnly(it) }
            bo.isRealPass = state == WorkItemState.SKIP
            bo.isRealPhoto = false
            bo.abnormalEamDetail = false
            if (PatrolConclusion.NORMAL == conclusion) {
                bo.abnormalReason = null
                bo.reason = null
            }
            if (state == WorkItemState.SKIP) {
                bo.realValue = null
                bo.concluse = null
                bo.collectionValue = null
            }
            return bo
        }
    }
}

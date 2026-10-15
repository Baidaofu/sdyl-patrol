package com.sdyl.patrol.core.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/* ------------------------------------------------------------------ *
 * 离线巡检本地库
 * 设计要点：从服务端拉到的任务/区域/设备/巡检项全部落库；
 * 现场录入的结果直接写在 work_item 上（dirty=true），联网后再上传。
 * ------------------------------------------------------------------ */

@Entity(tableName = "task")
data class TaskEntity(
    @PrimaryKey val id: String,
    val tableNo: String?,
    /** 服务端任务数字主键（= 原版 XJTASK_DTO.ID），上传载荷 potrolTask.id 用它 */
    val taskId: String? = null,
    /** 原版 XJTASK_DTO.TABLE_INFO_ID，上传载荷 potrolTask.tableInfoId 用它 */
    val tableInfoId: String? = null,
    val taskName: String?,
    val taskType: String?,
    val taskStateId: String?,
    val taskStateLabel: String?,
    /** 路线类型 id，如 PATROL_routeType/dps —— 上传载荷 potrolTask.routeTypeName.id */
    val routeTypeId: String? = null,
    val routeTypeLabel: String?,
    val routeName: String?,
    val startTime: Long,
    val endTime: Long,
    val actualStartTime: Long,
    val actualEndTime: Long,
    val isTemp: Boolean,
    val isInOrder: Boolean,
    val areaCount: Int,
    val groupName: String?,
    val stuffInCharge: String?,
    /** 路线/班组 id —— 上传载荷 `potrolTask.workRoute.id` 用它 */
    val groupId: String? = null,
    /** 路线/班组 code —— 上传载荷 `potrolTask.workRoute.code` 用它 */
    val groupCode: String? = null,
    /** 允许提前开始的小时数，对应原版 ADVANCE_START_TIME */
    val advanceStartTime: Double = 0.0,
    /** 任务执行人（「开始任务」时写入），对应原版 EXECUTE_USER.id */
    val executeUser: String? = null,
    /**
     * 任务日志 —— 对应原版 `XJTASK_DTO.LOG`（上传载荷 `potrolTask.log`）。
     *
     * 用户 m05038 第 2 条要求实现。原版格式（抓包实测）：
     * `2026-10-14 05:32:32 张三 获取任务；2026-10-14 06:00:09 张三 开始任务；2026-10-14 06:11:49 张三 结束任务`
     * —— 每行「yyyy-MM-dd HH:mm:ss 姓名 动作」，行间用中文分号「；」连接。
     */
    val taskLog: String? = null,
    val updatedAt: Long,
)

@Entity(tableName = "task_area")
data class AreaEntity(
    @PrimaryKey val id: String,
    val taskTableNo: String,
    val taskId: String?,
    /**
     * 服务端 workAreaId（基础区域 id）。
     * 原版 XJTaskAreaDTO 的 `id` 字段映射的就是它，上传载荷 workAreas[].id 必须用它。
     */
    val workAreaId: String? = null,
    val name: String?,
    val code: String?,
    val sort: Int,
    val totalNum: Int,
    val finishNum: Int,
    val abnormalNum: Int,
    val isSigned: Boolean,
    val isUpload: Boolean,
    val signStaffName: String?,
    /** 巡检人 id（工号/人员主键）——本轮新增，离线模拟签到时由用户指定 */
    val signStaffId: String? = null,
    val cardTime: Long?,
    /** 刷卡点编号，如 "BQ1421-1-0006"；NFC 刷卡时用来核对 */
    val signCode: String? = null,
    /** 签到方式文案，如「刷卡签到」/「手动」 */
    val signType: String? = null,
    /** 服务端签到的原始签到方式（PATROL_payCardType/card 等），仅作对照 */
    val serverCardType: String? = null,
    /** 签到方式字典全路径，对应原版 PAY_CARD_TYPE.id：PATROL_payCardType/signIn | /card */
    val payCardTypeId: String? = null,
    /** 签到原因，对应原版 SIGN_IN_REASON */
    val signInReason: String? = null,
    /** 打卡坐标，对应原版 CLOCK_COORDINATE（本应用不定位，默认空） */
    val clockLat: Double? = null,
    val clockLon: Double? = null,
    /** 服务端给的原始打卡坐标 JSON 字符串，上传时原样回传 */
    val clockCoordinate: String? = null,
    /** 是否终点，对应原版 IS_END_POINT */
    val isEndPoint: Boolean = false,
    /** 间隔时间（分钟），对应原版 INTERVAL_TIME */
    val intervalTime: Int = 0,
    /** 是否需要签到，对应原版 IS_NEED_SIGN */
    val isNeedSign: Boolean = true,
    val updatedAt: Long,
)

@Entity(tableName = "task_device")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val taskTableNo: String,
    val areaId: String,
    val code: String?,
    val name: String?,
    val areaNum: String?,
    val operationStatusName: String?,
    val sort: Int,
    val updatedAt: Long,
)

@Entity(tableName = "work_item")
data class WorkItemEntity(
    @PrimaryKey val id: String,
    val taskTableNo: String,
    val areaId: String,
    val deviceId: String?,
    val deviceName: String?,
    val deviceCode: String?,
    val deviceAreaNum: String?,
    val deviceIndex: Int,
    val itemNumber: String?,
    val content: String?,
    val claim: String?,
    val normalRange: String?,
    val defaultVal: String?,
    val unit: String?,
    val inputStandardCode: String?,
    /** 录入方式，如 PATROL_editType/whether（服务端是对象，已压平） */
    val editType: String? = null,
    /** 候选值，如 "是,否"；空表示自由输入 */
    val candidateValue: String? = null,
    /** 巡检项定义 id（服务端字段 workItemId）——上传载荷 workItems[].id 用它 */
    val serverWorkItemId: String? = null,
    /** 原版 XJTASK_WORK_DTO.TABLE_INFO_ID，上传载荷 workItems[].tableInfoId 用它 */
    val tableInfoId: String? = null,
    val isPhone: Boolean,
    val isAutoJudge: Boolean,
    val sort: Int,
    /* ---- 本地录入结果（离线可用） ---- */
    val realValue: String? = null,
    val concluse: String? = null,
    val skipReason: String? = null,
    val abnormalReason: String? = null,
    val remark: String? = null,
    /** 通用原因，对应原版 REASON */
    val reason: String? = null,
    /** 通过原因，对应原版 PASS_REASON */
    val passReason: String? = null,
    /** 明细状态，对应原版 TASK_DETAIL_STATE：PATROL_taskDetailState/{checked,pending,skip,exemption,uncheck} */
    val taskDetailState: String? = null,
    /** 完成时间，对应原版 COMPLETE_DATE */
    val completeDate: Long? = null,
    /** 完成人，对应原版 COMPLETE_USER / COMPLETE_USER_NAME */
    val completeUser: String? = null,
    val completeUserName: String? = null,
    /** 是否允许修改结论，对应原版 IS_CONCLUSION_MODIFY */
    val isConclusionModify: Boolean = true,
    /** 是否合格，对应原版 IS_PASS */
    val isPass: Boolean? = null,
    /** 注意：本应用按用户要求**不做拍照**，因此没有照片字段 */
    val finished: Boolean = false,
    val uploaded: Boolean = false,
    val dirty: Boolean = false,
    val updatedAt: Long = 0L,
)

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TaskEntity>)

    @Query("SELECT * FROM task ORDER BY startTime DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE tableNo = :tableNo LIMIT 1")
    suspend fun byTableNo(tableNo: String): TaskEntity?

    /** 任务详情页盯着这一行看：开始/提交改完时间要立刻反映到界面 */
    @Query("SELECT * FROM task WHERE tableNo = :tableNo LIMIT 1")
    fun observeByTableNo(tableNo: String): Flow<TaskEntity?>

    /** 批量取本机已有的行（同步时用来保住「离线开始/收工」记下的时间与状态） */
    @Query("SELECT * FROM task WHERE tableNo IN (:tableNos)")
    suspend fun listByTableNos(tableNos: List<String>): List<TaskEntity>

    @Query("UPDATE task SET taskStateId = :stateId, taskStateLabel = :stateLabel WHERE tableNo = :tableNo")
    suspend fun updateState(tableNo: String, stateId: String, stateLabel: String)

    /** 撤销「仅本机」的开始：清掉实际开始/结束时间，状态回退到离线开始之前 */
    @Query(
        "UPDATE task SET actualStartTime = 0, actualEndTime = 0, " +
            "taskStateId = :stateId, taskStateLabel = :stateLabel WHERE tableNo = :tableNo"
    )
    suspend fun cancelLocalStart(tableNo: String, stateId: String, stateLabel: String)

    /**
     * 开始任务：写入**实际开始时间**并置为进行中。
     *
     * 原版对应 `XJUpdateTaskStateController.updateOffLineTaskInfo(true)`：
     * `actualStartTime = currentTime`、`taskState = PATROL_taskState/running`、
     * `executeUser = StringIdEntity(staffId)`、`setLog(now, "开始任务")`。
     *
     * 先落本地（离线可用），再由 [PatrolRepository.startTask] 尝试上报。
     */
    @Query(
        "UPDATE task SET actualStartTime = :time, taskStateId = :stateId, " +
            "taskStateLabel = :stateLabel, executeUser = :staffId WHERE tableNo = :tableNo",
    )
    suspend fun startTask(
        tableNo: String,
        time: Long,
        stateId: String,
        stateLabel: String,
        staffId: String?,
    )

    @Query("UPDATE task SET taskLog = :log WHERE tableNo = :tableNo")
    suspend fun updateLog(tableNo: String, log: String?)

    @Query("DELETE FROM task")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM task")
    suspend fun count(): Int

    /**
     * 结束/提交任务：写入**实际结束时间**并置为已完成。
     *
     * 原版对应 `XJUpdateTaskStateController.updateOffLineTaskInfo(false)`：
     * `actualEndTime = currentTime`、`taskState = PATROL_taskState/completed`、
     * `completeStaff = StringIdEntity(staffId)`、`signPicPath`。先落本地，再由
     * [PatrolRepository.endTask] 上报服务端（服务端才是「状态变更人」的来源）。
     */
    @Query(
        "UPDATE task SET actualEndTime = :time, taskStateId = :stateId, " +
            "taskStateLabel = :stateLabel WHERE tableNo = :tableNo",
    )
    suspend fun endTask(tableNo: String, time: Long, stateId: String, stateLabel: String)
}

@Dao
interface AreaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AreaEntity>)

    @Query("SELECT * FROM task_area WHERE taskTableNo = :tableNo ORDER BY sort")
    fun observeByTask(tableNo: String): Flow<List<AreaEntity>>

    @Query("SELECT * FROM task_area WHERE taskTableNo = :tableNo ORDER BY sort")
    suspend fun listByTask(tableNo: String): List<AreaEntity>

    @Query("SELECT * FROM task_area WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AreaEntity?

    @Query("SELECT * FROM task_area WHERE taskTableNo = :tableNo ORDER BY sort")
    suspend fun listOfTask(tableNo: String): List<AreaEntity>

    /**
     * 我签过到的任务（主页「我的」分类用）。
     * 服务端任务投影不带 executeUser，没法直接查「我领的任务」，用「我签过到的区域」反推。
     */
    @Query(
        "SELECT DISTINCT IFNULL(taskTableNo, '') FROM task_area " +
            "WHERE signStaffId = :staffId AND signStaffId <> '' AND taskTableNo IS NOT NULL",
    )
    fun observeTaskNosSignedBy(staffId: String): Flow<List<String>>

    /** 上传成功后标记本区域已上传 */
    @Query("UPDATE task_area SET isUpload = 1, updatedAt = :now WHERE id = :id")
    suspend fun markUploaded(id: String, now: Long)

    /**
     * 待上传的区域：本机签过到、或本机录入过巡检项（dirty），且还没上传成功的。
     * 对应原版「任务下有未上传数据」的判定。
     */
    @Query(
        "SELECT * FROM task_area WHERE isUpload = 0 AND (isSigned = 1 OR EXISTS " +
            "(SELECT 1 FROM work_item w WHERE w.areaId = task_area.id AND w.dirty = 1)) " +
            "ORDER BY taskTableNo, sort",
    )
    suspend fun listPendingUpload(): List<AreaEntity>

    @Query("SELECT COUNT(*) FROM task_area WHERE isUpload = 0 AND (isSigned = 1 OR EXISTS " +
        "(SELECT 1 FROM work_item w WHERE w.areaId = task_area.id AND w.dirty = 1))")
    suspend fun pendingUploadCount(): Int

    /**
     * 一次性对账：把「服务端已完成、本机又没有任何改动」的区域标成已上传。
     *
     * 历史任务同步下来时 `isSigned` 是从服务端抄的（别人签的也算），而 `isUpload`
     * 服务端投影里没有 —— 不修的话「我的」页会显示上千条假的「待上传」，
     * 而且「上传全部待上传数据」会把历史数据整批推给服务端。
     * 已完成的任务本来就传不上去（上传器会拒），所以这些直接判为已上传。
     */
    @Query(
        "UPDATE task_area SET isUpload = 1 WHERE isUpload = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM work_item w WHERE w.areaId = task_area.id AND w.dirty = 1) " +
            "AND taskTableNo IN (SELECT tableNo FROM task WHERE taskStateId = 'PATROL_taskState/completed')",
    )
    suspend fun reconcileFinishedUploadFlags(): Int

    @Query("UPDATE task_area SET finishNum = :finish, abnormalNum = :abnormal WHERE id = :id")
    suspend fun updateProgress(id: String, finish: Int, abnormal: Int)

    @Query("DELETE FROM task_area")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM task_area")
    suspend fun count(): Int

    /**
     * 本地签到：巡检人、巡检人 id、签到时间、签到方式、刷到的卡号、签到原因全部可自定义。
     * 只改本地库，联网时也绝不把签到结果发给服务端。
     */
    @Query(
        "UPDATE task_area SET isUpload = 0, isSigned = 1, signStaffName = :staff, signStaffId = :staffId, " +
            "cardTime = :time, signType = :type, payCardTypeId = :payType, " +
            "signInReason = :reason, " +
            "signCode = COALESCE(:code, signCode), " +
            "clockLat = COALESCE(:lat, clockLat), clockLon = COALESCE(:lon, clockLon) " +
            "WHERE id = :id",
    )
    suspend fun markSigned(
        id: String,
        staff: String?,
        staffId: String?,
        time: Long,
        type: String?,
        code: String?,
        payType: String?,
        reason: String?,
        lat: Double?,
        lon: Double?,
    )

    /** 撤销本地签到（只动本地，服务端那边不受影响；刷卡点 SIGN_CODE 是任务自带数据，不清） */
    @Query(
        "UPDATE task_area SET isUpload = 1, isSigned = 0, signStaffName = NULL, signStaffId = NULL, " +
            "cardTime = NULL, signType = NULL, payCardTypeId = NULL, signInReason = NULL, " +
            "clockLat = NULL, clockLon = NULL WHERE id = :id",
    )
    suspend fun clearSigned(id: String)

    @Query("SELECT * FROM task_area WHERE isSigned = 0")
    suspend fun listUnsigned(): List<AreaEntity>

    /** 本地已录入的巡检项条数（用于「本地有改动」提示） */
    @Query("SELECT COUNT(*) FROM work_item WHERE areaId = :areaId AND dirty = 1")
    suspend fun dirtyOfArea(areaId: String): Int

    @Query("SELECT * FROM task_area WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<AreaEntity?>
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DeviceEntity>)

    @Query("SELECT * FROM task_device WHERE taskTableNo = :tableNo AND areaId = :areaId ORDER BY sort")
    fun observeByArea(tableNo: String, areaId: String): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM task_device WHERE taskTableNo = :tableNo AND areaId = :areaId ORDER BY sort")
    suspend fun listByArea(tableNo: String, areaId: String): List<DeviceEntity>

    @Query("DELETE FROM task_device")
    suspend fun clear()
}

@Dao
interface WorkItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WorkItemEntity>)

    @Query("SELECT * FROM work_item WHERE areaId = :areaId ORDER BY deviceIndex, sort")
    fun observeByArea(areaId: String): Flow<List<WorkItemEntity>>

    @Query("SELECT * FROM work_item WHERE areaId = :areaId ORDER BY deviceIndex, sort")
    suspend fun listByArea(areaId: String): List<WorkItemEntity>

    @Query("SELECT * FROM work_item WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): WorkItemEntity?

    @Query("SELECT * FROM work_item WHERE dirty = 1")
    suspend fun pendingUpload(): List<WorkItemEntity>

    /** 本区域待上传的巡检项（只传用户真正动过的） */
    @Query("SELECT * FROM work_item WHERE areaId = :areaId AND dirty = 1 ORDER BY deviceIndex, sort")
    suspend fun dirtyByArea(areaId: String): List<WorkItemEntity>

    /**
     * 该区域**已完成**的巡检项。
     *
     * 原版上传时取的正是这个集合：`XJCacheUtil.getTaskWorks(tableNo, areaId, true)`
     * 的第三个参数 `true` 就是按 `IS_FINISHED` 过滤。
     */
    @Query("SELECT * FROM work_item WHERE areaId = :areaId AND finished = 1 ORDER BY deviceIndex, sort")
    suspend fun finishedByArea(areaId: String): List<WorkItemEntity>

    @Query("SELECT COUNT(*) FROM work_item WHERE areaId = :areaId")
    suspend fun totalOfArea(areaId: String): Int

    @Query("SELECT COUNT(*) FROM work_item WHERE areaId = :areaId AND finished = 1")
    suspend fun finishedOfArea(areaId: String): Int

    @Query("SELECT COUNT(*) FROM work_item WHERE areaId = :areaId AND concluse IS NOT NULL AND concluse != '' AND concluse != :normal")
    suspend fun abnormalOfArea(areaId: String, normal: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WorkItemEntity)

    @Query("UPDATE work_item SET uploaded = 1, dirty = 0 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("DELETE FROM work_item")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM work_item")
    suspend fun count(): Int

    @Query("UPDATE work_item SET skipReason = :reason, finished = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun markSkipped(id: String, reason: String?, now: Long)

    @Query(
        "UPDATE work_item SET finished = 1, taskDetailState = :state, completeDate = :now, " +
            "completeUser = :staffId, completeUserName = :staff, dirty = 1, updatedAt = :now WHERE areaId = :areaId",
    )
    suspend fun finishAllInArea(
        areaId: String,
        state: String?,
        now: Long,
        staffId: String?,
        staff: String?,
    )

    /** 统一面板保存：一条巡检项的所有本地结论字段一次写全 */
    @Query(
        "UPDATE work_item SET realValue = :real, concluse = :concluse, skipReason = :skipReason, " +
            "abnormalReason = :abnormalReason, remark = :remark, reason = :reason, passReason = :passReason, " +
            "isPass = :isPass, finished = :finished, taskDetailState = :state, completeDate = :now, " +
            "completeUser = :staffId, completeUserName = :staff, dirty = 1, updatedAt = :now WHERE id = :id",
    )
    suspend fun saveResultFull(
        id: String,
        real: String?,
        concluse: String?,
        skipReason: String?,
        abnormalReason: String?,
        remark: String?,
        reason: String?,
        passReason: String?,
        isPass: Boolean?,
        finished: Boolean,
        state: String?,
        now: Long,
        staffId: String?,
        staff: String?,
    )
}

@Database(
    entities = [TaskEntity::class, AreaEntity::class, DeviceEntity::class, WorkItemEntity::class],
    version = 8,
    exportSchema = true,
)
abstract class SdylDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun areaDao(): AreaDao
    abstract fun deviceDao(): DeviceDao
    abstract fun workItemDao(): WorkItemDao

    companion object {
        fun build(context: Context): SdylDatabase =
            Room.databaseBuilder(context, SdylDatabase::class.java, "sdyl_patrol.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

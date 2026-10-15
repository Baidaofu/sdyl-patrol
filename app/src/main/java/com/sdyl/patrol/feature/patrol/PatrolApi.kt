package com.sdyl.patrol.feature.patrol

import com.sdyl.patrol.core.network.BapPage
import com.sdyl.patrol.core.network.BapResponse
import com.sdyl.patrol.feature.patrol.model.GetTaskAreaDetailRequest
import com.sdyl.patrol.feature.patrol.model.GetTaskListRequest
import com.sdyl.patrol.feature.patrol.model.PatrolBaseArea
import com.sdyl.patrol.feature.patrol.model.PatrolBaseRoute
import com.sdyl.patrol.feature.patrol.model.PatrolTask
import com.sdyl.patrol.feature.patrol.model.PatrolTaskDevice
import com.sdyl.patrol.feature.patrol.model.PatrolWorkItem
import com.sdyl.patrol.feature.patrol.upload.UploadAttachmentDto
import com.sdyl.patrol.feature.patrol.upload.UploadedFileDto
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * 巡检接口（路径与原 App module_patrol_common / module_patrol_online 完全一致）
 */
interface PatrolApi {

    /** 我的巡检任务列表 */
    @POST("/msService/PATROL/getMyTaskListArea")
    suspend fun getTaskList(@Body body: GetTaskListRequest): BapResponse<BapPage<PatrolTask>>

    /** 在线巡检合并任务（双预防），可选 */
    @POST("/msService/PATRSECURITY/getSecurityTaskList")
    suspend fun getMergeTaskList(@Body body: GetTaskListRequest): BapResponse<BapPage<PatrolTask>>

    /** 区域内设备 */
    @POST("/msService/PATROL/getTaskAreaDevice")
    suspend fun getAreaDevices(@Body body: GetTaskAreaDetailRequest): BapResponse<List<PatrolTaskDevice>>

    /** 设备下巡检项 */
    @POST("/msService/PATROL/getTaskAreaDetail")
    suspend fun getTaskWorks(@Body body: GetTaskAreaDetailRequest): BapResponse<BapPage<PatrolWorkItem>>

    /** 更新任务状态 */
    @POST("/msService/PATROL/mobile/patrolTask/updatePatrolTask")
    suspend fun updateTaskState(@Body body: MutableMap<String, Any?>): BapResponse<Any>

    /**
     * **领取任务** —— 原版离线任务页「领取任务」按钮走的就是这个接口。
     *
     * `XJUpdateTasksStatePresenter.updateTasksState(...)` 组 body：
     * `{changeState: "PATROL_taskState/issued", idList: [任务 id…], mobileVersion, equipmentModel, macAddress}`。
     * 服务端据此把任务置为「已领取」，并写入任务实体的 `statusChangeStaff`（＝服务端界面上的
     * 「状态变更人」）与 `acquirer`（领取人），两者都是本次调用人。
     */
    @POST("/msService/PATROL/patrolTask/potrolTask/uploadTaskState")
    suspend fun claimTasks(@Body body: MutableMap<String, Any?>): BapResponse<String>

    /**
     * 原版离线任务列表（含 `executeUser` = 执行人）。
     *
     * 注意：本应用主列表用的 `getMyTaskListArea` 投影里**没有** `executeUser`，
     * 只有这个接口会返回它。
     */
    @POST("/msService/PATROL/patrolTask/potrolTask/getMobilePotrolTaskListList")
    suspend fun getOfflineTaskList(@Body body: MutableMap<String, Any?>): BapResponse<BapPage<PatrolTask>>

    /** 区域一键完成 / 一键跳过 */
    @POST("/msService/PATROL/saveTaskAreaDetailCompleteAll")
    suspend fun finishOrSkipAll(@Body body: MutableMap<String, Any?>): BapResponse<String>

    /** 逐条上传巡检结果（在线巡检用） */
    @POST("/msService/PATROL/uploadOnLineTaskResult")
    suspend fun uploadTaskResult(
        @Body body: MutableMap<String, Any?>,
        @Query("isOnLine") isOnLine: Int = 1,
    ): BapResponse<Any>

    /**
     * 离线巡检结果上传 —— 第二步。
     *
     * 原版：`XJHttpClient.uploadTaskResult(false, {"filePath": path})`，
     * 对应 `NetworkAPI.uploadTaskResult(@Query("isOnLine") boolean, @Body Map)`。
     * 查询参数必须是 `isOnLine=false`，body 里只有 `filePath` 一个键。
     */
    @POST("/msService/PATROL/uploadTaskResult")
    suspend fun uploadOfflineTaskResult(
        @Query("isOnLine") isOnLine: Boolean = false,
        @Body body: MutableMap<String, Any?>,
    ): BapResponse<Long>

    /**
     * 离线巡检结果上传 —— 第一步：把 `xj_upload.zip` 传到文件服务，拿回 `path`。
     *
     * 原版：`MiddlewareHttpClient.bapUploadFile(Util.createZipFileForm(file))`，
     * multipart 字段名固定为 `zipFile`，Content-Type `application/zip`。
     */
    @Multipart
    @POST("/inter-api/file-server/v1/file/upload/file")
    suspend fun uploadZipFile(@Part zipFile: MultipartBody.Part): BapResponse<UploadAttachmentDto>

    /**
     * 服务端某业务对象已经存了哪些附件（用户 m11701：避免签字图一单攒成好几张）。
     *
     * 逆向自原版 `NetworkAPI.getFileList`：
     * `GET /inter-api/file-server/v1/file/upload-list?linkId=<任务 tableInfoId>&type=&propertyCode=`。
     * 实测 `linkId=1000000000000641`（=`patrolTask_20261013_1942` 的 tableInfoId，
     * 也正是它三个区域 `SignPic` 里写的那个数）返回 3 个文件，`propertyCode` 全是
     * `PATROL_1.0.0_patrolTask_PotrolTask_signPic` —— 手机 A、手机 B、本应用各传了一张签字图。
     * `type` / `propertyCode` 传空串即可（传 `PATROL_1.0.0_patrolTask` 反而筛不到）。
     */
    @GET("/inter-api/file-server/v1/file/upload-list")
    suspend fun listUploadedFiles(
        @Query("linkId") linkId: String,
        @Query("type") type: String = "",
        @Query("propertyCode") propertyCode: String = "",
    ): BapResponse<List<UploadedFileDto>>

    /** 基础数据同步 */
    @POST("/msService/PATROL/getPatrolBaseDatas")
    suspend fun getPatrolBaseData(@Body body: MutableMap<String, Any?>): BapResponse<Any>

    /** 系统配置（巡检设置项） */
    @GET("/inter-api/systemconfig/v1/config/catalog/by/module")
    suspend fun getSysConfig(
        @Query("moduleCode") moduleCode: String,
        @Query("key") key: String,
    ): BapResponse<Any>

    /* ------------------------ 领取任务（临时任务） ------------------------ */

    /**
     * 线路列表。原版：`OnlinePatrolHttpClient.getBaseRoutes(XJBasePageFastCondBO)`，
     * permissionCode / viewCode 固定为 `PATROL_1.0.0_patrolRoute_workGroupRef`，
     * modelAlias = `workGroup`，`fastQueryCond` 是这段 JSON 的**字符串**。
     */
    @POST("/msService/PATROL/patrolRoute/workGroup/workGroupRef-query")
    suspend fun getBaseRoutes(@Body body: MutableMap<String, Any?>): BapResponse<BapPage<PatrolBaseRoute>>

    /**
     * 线路下的区域。原版：`OnlinePatrolHttpClient.getAreasByRouteId(datagridCode, XJBasePageCusCondBO)`，
     * 容器是 `customCondition = GetAreasBOXJ{ruoteId}`（**原版字段名就把 route 拼成了 ruote**）。
     */
    @POST("/msService/PATROL/patrolRoute/workArea/data-dg1575500000000")
    suspend fun getBaseAreas(
        @Body body: MutableMap<String, Any?>,
        @Query("datagridCode") datagridCode: String,
    ): BapResponse<BapPage<PatrolBaseArea>>

    /** 按线路创建临时任务（原版「领取任务」的真正落地动作） */
    @POST("/msService/PATROL/patrolTask/potrolTask/createTempTaskByRouteMobile")
    suspend fun createTempTask(@Body body: MutableMap<String, Any?>): BapResponse<Any>
}

package com.sdyl.patrol.feature.patrol.upload

/**
 * 离线巡检结果上传载荷。
 *
 * 字段名逐字照抄原 App（Gson 按字段名序列化，一个字母都不能差）：
 * `com.supcon.mes.module_patrol_common.bean.bo.{XJUploadDataBOS, XJUploadDataBO,
 * XJUploadTaskBO, XJLogBO, XJUploadAreaBO, XJUploadWorkBO}`
 * 以及 `entity.{StringIdEntity, StringIdNameEntity}`。
 *
 * 序列化用**朴素的 `Gson()`**（原版 `GsonUtil.gsonString` 就是 `new Gson()`）：
 * null 字段直接省略；`Boolean` 是基本类型，永远会被写出来（原版也一样）。
 */

/** 对应 StringIdEntity：`{"id":"..."}` */
class UploadIdOnly(var id: String? = null)

/** 对应 StringIdNameEntity：`{"id":"...","name":"..."}` */
class UploadIdName(var id: String? = null, var name: String? = null)

/** 对应 BaseIdValueEntity：`{"id":"...","value":"..."}` */
class UploadIdValue(var id: String? = null, var value: String? = null)

/** 对应 XJLogBO（设备与版本信息，纯遥测） */
open class UploadLogBo {
    var mobileVersion: String? = null
    var equipmentModel: String? = null
    var macAddress: String? = null
}

/**
 * 对应 XJUploadTaskBO。
 * 注意 `potrolTask` 这个拼写是原版的，不要"修正"。
 */
class UploadTaskBo : UploadLogBo() {
    var completeStaff: UploadIdName? = null
    var delayedEndReason: String? = null
    var delayedStartReason: String? = null
    var eamsOperateState: String? = null
    var endTime: Long? = null
    var id: String? = null
    var isTemp: Boolean = false
    var log: String? = null
    var signPicPath: String? = null
    var source: UploadIdOnly? = null
    var startTime: Long? = null
    var tableInfoId: String? = null
    var tableNo: String? = null
    var taskMold: String? = null
    var taskName: String? = null
    var taskState: UploadIdValue? = null
    var taskType: String? = null
    var workRoute: UploadRouteBo? = null
    var routeTypeName: UploadIdValue? = null
    var completeStaffName: String? = null
    var executeUser: UploadIdOnly? = null
    var advanceStartTime: Double? = null
    var ip: String? = null
}

/**
 * 对应 XJBaseRouteDTO 的子集。
 *
 * 原版 `XJUploadTaskBO.workRoute` 是 XJTaskDTO 往返后的完整路线对象，
 * 真机库里长这样（`XJTASK_DTO.WORK_ROUTE`）：
 * `{"code":"a7e28c50-...","equipSkipPatrol":"","id":"1000000000000720",
 *   "ip":"https://supos.example.com:8443","isInOrder":false,"isRun":false,
 *   "name":"炼油区闭式循环水场-工艺外操-2-hour","sort":0,"valid":false}`
 */
class UploadRouteBo {
    var code: String? = null
    var equipSkipPatrol: String = ""
    var id: String? = null
    var ip: String? = null
    var isInOrder: Boolean = false
    var isRun: Boolean = false
    var name: String? = null
    var sort: Int = 0
    var valid: Boolean = false
}

/** 对应 XJUploadAreaBO（`SignStaff` 首字母大写是原版的） */
class UploadAreaBo {
    var SignStaff: String? = null
    var areaId: String? = null
    var cardTime: Long? = null
    var clockCoordinate: String? = null
    var completeStaff: UploadIdName? = null
    var coordinate: String? = null
    var distanceDValue: Double? = null
    var id: String? = null
    var nfcCode: String? = null
    var payCardType: UploadIdValue? = null
    var pciName: String? = null
    var pciPath: String? = null
    var remark: String? = null
    var signInReason: UploadIdValue? = null
    var signPicNames: String? = null
    var staffId: String? = null
    var taskId: String? = null
    var taskMold: String? = null
}

/** 对应 XJUploadWorkBO（`taskItemDetilId` 的拼写也是原版的） */
class UploadWorkBo {
    var abnormalEam: UploadIdOnly? = null
    var abnormalEamDetail: Boolean = false
    var abnormalReason: String? = null
    var collectionValue: String? = null
    var completeDate: Long? = null
    var completeUser: UploadIdName? = null
    var completeUserName: String? = null
    var concluse: String? = null
    var durationTime: Long? = null
    var id: String? = null
    var isRealPass: Boolean = false
    var isRealPhoto: Boolean = false
    var passReason: UploadIdOnly? = null
    var realValue: UploadIdValue? = null
    var reason: String? = null
    var remark: String? = null
    var skipReason: String? = null
    var tableInfoId: String? = null
    var taskDetailState: UploadIdOnly? = null
    var taskItemDetilId: String? = null
    var taskWorkId: String? = null
    var testPointRealValue: String? = null
    var workItemId: String? = null
    var xjImgName: String? = null
    var xjImgNameStatus: String? = null
    var imei: String? = null
    var phoneNum: String? = null
}

/** 对应 XJUploadDataBO */
class UploadDataBo {
    var actualEndTime: Long? = null
    var actualStartTime: Long? = null
    var potrolTask: UploadTaskBo? = null
    var workAreas: List<UploadAreaBo>? = null
    var workItems: List<UploadWorkBo>? = null
}

/** 对应 XJUploadDataBOS —— 写进 zip 里的 `xj_upload.json` 就是它的 JSON */
class UploadDataBos {
    var uploadTaskResultDTOs: List<UploadDataBo>? = null
}

/** 对应 AttachmentEntity（文件服务返回），只需要 path */
class UploadAttachmentDto {
    var id: String? = null
    var name: String? = null
    var path: String? = null
    var size: Long? = null
    var fileType: String? = null
}

/**
 * 对应原版 `FileEntity` —— `/inter-api/file-server/v1/file/upload-list` 的条目。
 *
 * 实用字段：`propertyCode`（本应用只关心以 `signPic` 结尾的签字图，实测为
 * `PATROL_1.0.0_patrolTask_PotrolTask_signPic`）、`name`（原版形如
 * `xjTaskPic_20261014_062711.jpg`）、`createTime`（毫秒）。
 */
class UploadedFileDto {
    var id: String? = null
    var name: String? = null
    var propertyCode: String? = null
    var path: String? = null
    var createTime: Long? = null

    /** 是不是签字图（用户 m11701 的判据） */
    val isSignPic: Boolean
        get() = propertyCode?.endsWith("signPic", ignoreCase = true) == true ||
            name?.startsWith("xjTaskPic_") == true
}

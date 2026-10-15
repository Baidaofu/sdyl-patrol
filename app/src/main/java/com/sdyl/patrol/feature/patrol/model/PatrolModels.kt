package com.sdyl.patrol.feature.patrol.model

import com.sdyl.patrol.core.network.IdValue
import com.sdyl.patrol.core.network.PageRequest

/* ------------------------------------------------------------------ *
 * 巡检任务列表（POST /msService/PATROL/getMyTaskListArea）
 *
 * 字段名以 2026-10-12 真机抓到的服务端 JSON 为准：
 * {
 *   "taskId": 1000000000000200, "tableNo": "patrolTask_20260712_276",
 *   "tableInfoId": 1000000000000201, "planId": 1000000000000720,
 *   "taskType": "PATROL_taskType/dailyPatrol",
 *   "taskState": {"id":"PATROL_taskState/completed","value":"已完成"},
 *   "routeType": "PATROL_routeType/dps",
 *   "routeTypeName": {"id":"PATROL_routeType/dps","value":"智慧安全系统（工艺）"},
 *   "startTime": 1783872000000, "endTime": 1783879200000,
 *   "actualStartTime": 1783877403370, "actualEndTime": 1783878140657,
 *   "advanceStartTime": 0.25, "delayStartTime": null, "delayEndTime": null,
 *   "groupCode": "...", "groupName": "炼油区海水取排水及海水泵房-工艺外操-2-hour",
 *   "groupId": 1000000000000736, "stuffInCharge": "吴九,郑十,...",
 *   "equipSkipPatrol": "", "isTemp": false, "isRun": true, "isInOrder": null,
 *   "taskArea": [ { "taskAreaId":1000000000000504, "patrolTaskId":1000000000000200,
 *                   "workAreaId":1000000000000504, "areaName":"第三站 集水井",
 *                   "areaCode":"5b81913d-...", "signCode":"BQ1427-1-0003",
 *                   "payCardType":{"id":"PATROL_payCardType/card","value":"刷卡签到"},
 *                   "SignStaff":"张三", "completeStaff":"张三", "SignPic":1000000000000201,
 *                   "staffId":1000000000000224, "cardTime":1783878123103,
 *                   "totalNum":1, "finishNum":1, "abnormalitem":0,
 *                   "coordinate":null, "intervalTime":null, "signInReason":{"id":null,"value":""} } ]
 * }
 * 注意：响应里【没有】taskName / workRoute / completeStaff，任务名取 groupName。
 * ------------------------------------------------------------------ */

class GetTaskListRequest : PageRequest() {
    var staffId: Long = 0L
    /** PATROL_taskState/notIssued | issued | running | completed | overdue，多个用逗号分隔 */
    var taskStates: String? = null
    var startTime: String? = null
    var endTime: String? = null
    var routeName: String? = null
    var tableNo: String? = null
    var patrolType: String? = null
    var acquirerId: Long? = null
}

class PatrolTask {
    /** 服务端真实主键字段名 */
    var taskId: Long = 0L

    /** 服务端只回一个 id 的人员引用（`executeUser`） */
    class PersonRef {
        var id: Long? = null
    }

    /** 兼容旧字段（响应里没有，恒为 null） */
    var id: String? = null
    var tableNo: String? = null
    var tableInfoId: String? = null
    var planId: Long = 0L
    var taskName: String? = null
    var taskType: String? = null
    var taskMold: String? = null
    var taskState: IdValue? = null

    /**
     * 「执行人」——服务端移动端投影里的 `executeUser`。
     *
     * 别和 Web 列表上的「状态变更人」混淆：那是任务实体的另一个字段 `statusChangeStaff`，
     * **领取**时写入（同时写 `acquirer`）；实测领取后本字段只会被建出来、`id` 仍是 null，
     * 只有**开始任务**（`updatePatrolTask` 带 `userId`）才写进真实的人员 ID。
     */
    var executeUser: PersonRef? = null
    var routeType: String? = null
    var routeTypeName: IdValue? = null
    var startTime: Long = 0L
    var endTime: Long = 0L
    var actualStartTime: Long = 0L
    var actualEndTime: Long = 0L
    var advanceStartTime: Double? = null
    var delayStartTime: Double? = null
    var delayEndTime: Double? = null
    var delayedStartReason: String? = null
    var delayedEndReason: String? = null
    var isTemp: Boolean = false
    var isRun: Boolean = false
    var isInOrder: Boolean = false
    var isFinished: Boolean = false
    var groupId: String? = null
    var groupCode: String? = null
    var groupName: String? = null
    var stuffInCharge: String? = null
    var completeStaff: String? = null
    var completeStaffName: String? = null
    var signPicPath: String? = null
    var equipSkipPatrol: String? = null
    var ip: String? = null
    var workRoute: PatrolRoute? = null
    var taskArea: List<PatrolTaskArea>? = null

    val stateId: String get() = taskState?.id.orEmpty()
    val stateLabel: String
        get() = taskState?.value?.takeIf { it.isNotBlank() }
            ?: PatrolTaskState.label(stateId)

    /** 任务名：优先路线名，其次班组名 */
    val routeName: String
        get() = workRoute?.name?.takeIf { it.isNotBlank() }
            ?: groupName.orEmpty()

    val displayName: String get() = taskName?.takeIf { it.isNotBlank() } ?: routeName

    val typeLabel: String
        get() = routeTypeName?.value?.takeIf { it.isNotBlank() }
            ?: PatrolTaskType.label(taskType)

    val areaCount: Int get() = taskArea?.size ?: 0
    val areaDoneCount: Int get() = taskArea?.count { it.isDone } ?: 0
    val key: String get() = tableNo ?: taskId.toString()

    /** 是否可进入巡检（未完成） */
    val isOpen: Boolean get() = stateId != PatrolTaskState.COMPLETED
}

class PatrolRoute {
    var id: String? = null
    var _id: Long = 0L
    var code: String? = null
    var name: String? = null
    var remark: String? = null
    var patrolType: IdValue? = null
    var isInOrder: Boolean = false
}

/* ------------------------------------------------------------------ *
 * 「领取任务」的两份基础数据 —— 原版离线任务页的领取流程
 *
 * 真相（逆向自 XJCreateTempTaskActivity / XJCreateTempTaskPresenter）：
 *   原版根本没有「把已下发的任务领走」这一步。所谓「领取任务」= 按线路**临时创建一条任务**：
 *     选线路 → 选时间段 → 勾区域 → POST createTempTaskByRouteMobile
 *   请求体里的 acquirerId = 当前登录人的 staffId，这就是服务端记的「领取人 / 状态变更人」。
 *   两个基础数据接口：
 *     线路列表   POST /msService/PATROL/patrolRoute/workGroup/workGroupRef-query
 *     线路下区域 POST /msService/PATROL/patrolRoute/workArea/data-dg1575500000000?datagridCode=...
 * ------------------------------------------------------------------ */

/** 对应原版 XJBaseRouteDTO */
class PatrolBaseRoute {
    var id: String? = null
    var _id: Long = 0L
    var code: String? = null
    var name: String? = null
    var remark: String? = null
    var patrolType: IdValue? = null
    var dept: IdValue? = null
    var equipSkipPatrol: String? = null
    var isInOrder: Boolean = false
    var isRun: Boolean = true
    var valid: Boolean = true
    var sort: Int = 0

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: code.orEmpty()
    val patrolTypeLabel: String get() = patrolType?.value.orEmpty()
}

/** 对应原版 XJBaseAreaDTO */
class PatrolBaseArea {
    var id: String? = null
    var _id: Long = 0L
    var code: String? = null
    var name: String? = null
    var signCode: String? = null
    var routeId: String? = null
    var coordinate: String? = null
    var intervalTime: Long? = null
    var isEndPoint: Boolean = false
    var isDevice: Boolean = false
    var workGroupId: IdValue? = null
    var sort: Int = 0
    var valid: Boolean = true

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: code.orEmpty()
}

class PatrolTaskArea {
    var id: String? = null
    var taskAreaId: String? = null
    var patrolTaskId: String? = null
    var workAreaId: String? = null
    var taskId: String? = null
    var tableNo: String? = null

    /** 服务端真实字段 */
    var areaName: String? = null
    var areaCode: String? = null
    /** 刷卡点位编号（NFC/刷卡签到用） */
    var signCode: String? = null

    /** 兼容旧字段 */
    var name: String? = null
    var code: String? = null

    var sort: Int? = null
    var totalNum: Int = 0
    var finishNum: Int = 0
    /** 服务端字段名是 abnormalitem */
    var abnormalitem: Int = 0
    val abnormalNum: Int get() = abnormalitem
    var payCardType: IdValue? = null
    /** 签到人姓名（服务端字段首字母大写） */
    var SignStaff: String? = null
    var completeStaff: String? = null
    /** 签到照片对象 id */
    var SignPic: String? = null
    var staffId: String? = null
    val signStaffName: String? get() = SignStaff
    val signStaffId: String? get() = staffId
    /** 服务端给的签到方式标识，如 PATROL_payCardType/card；仅作对照 */
    val serverCardType: String? get() = payCardType?.id
    var cardTime: Long? = null
    var intervalTime: Long? = null
    var coordinate: String? = null
    var clockCoordinate: String? = null
    var signInReason: IdValue? = null
    var isUpload: Boolean = false
    var isEndPoint: Boolean = false
    var remark: String? = null

    val displayName: String
        get() = areaName?.takeIf { it.isNotBlank() } ?: name.orEmpty()

    val displayCode: String
        get() = areaCode?.takeIf { it.isNotBlank() } ?: code.orEmpty()

    /** 已签到：有刷卡时间或有签到人 */
    val isDone: Boolean
        get() = (cardTime != null && cardTime!! > 0L) || !SignStaff.isNullOrBlank()

    val isSigned: Boolean get() = isDone
    val signTypeLabel: String get() = payCardType?.value.orEmpty()

    val progressText: String get() = "$finishNum/$totalNum"
}

/* ------------------------------------------------------------------ *
 * 任务详情：区域 / 设备 / 巡检项
 * ------------------------------------------------------------------ */

class GetTaskAreaDetailRequest : PageRequest() {
    var tableNo: String? = null

    /** 服务端要的是数字，用 Long 让 Gson 输出 1000000000000128 而不是字符串 */
    var taskId: Long? = null
    var taskAreaId: String? = null
    var areaId: String? = null
    var deviceId: String? = null
    var deviceCode: String? = null
    var nfcCode: String? = null
    var state: String? = null
    var ip: String? = null
}

class PatrolTaskDevice {
    var id: String? = null
    var tableNo: String? = null
    var taskAreaId: String? = null
    var code: String? = null
    var name: String? = null
    var areaNum: String? = null
    var operationStatus: String? = null
    var operationStatusName: String? = null
    var initOperationStatus: String? = null
    var isMea: Boolean = false
    var ip: String? = null

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: code.orEmpty()
}

/**
 * 巡检项，对应 POST /msService/PATROL/getTaskAreaDetail 的 data.result[]：
 * {
 *   "taskWorkId": 1000000000000203, "workItemId": 1000000000000048,
 *   "xjImgId": 1000000000000203, "patrolTaskId": 1000000000000128,
 *   "taskAreaId": 1000000000000200, "tableInfoId": 1000000000000129,
 *   "workGroupId": 1000000000000488,
 *   "content": "1、检查1#2#汽封抽气器、各管线、阀门等是否正常。…",
 *   "inputStandardCode": {"id":1001,"code":"M-RJY-1-002","candidateValue":"是,否",
 *                         "editType":"","seismicMode":"","unitID":"","valType":"","decimalPlace":""},
 *   "taskState": "PATROL_taskState/notIssued",     // 这里是字符串，不是 {id,value}
 *   "isPhone": 0, "autoJudge": 0, "sort": null,
 *   "realValue": null, "concluse": null, "normalRange": null, "unit": null,
 *   "claim": null, "skipReason": null, "remark": null, "deviceId": null,
 *   "deviceCode": null, "deviceName": null, "deviceAreaNum": null
 * }
 */
class PatrolWorkItem {
    /** 服务端真实主键：taskWorkId（工作实例 id） */
    var taskWorkId: String? = null

    /** 巡检项目定义 id */
    var workItemId: String? = null
    var xjImgId: String? = null
    var patrolTaskId: String? = null

    /** 兼容旧字段（响应里没有） */
    var id: String? = null
    var tableInfoId: String? = null
    var areaId: String? = null
    var taskAreaId: String? = null
    var deviceId: String? = null
    var deviceName: String? = null
    var deviceCode: String? = null
    var deviceAreaNum: String? = null
    var deviceIndex: Int = 0
    var itemNumber: String? = null
    var content: String? = null
    var claim: String? = null
    var normalRange: String? = null
    var defaultVal: String? = null
    /** 服务端字段名 defaultValue */
    var defaultValue: String? = null
    var realValue: String? = null
    var concluse: String? = null
    var unit: String? = null
    var inputStandardCode: InputStandard? = null
    var skipReason: String? = null
    var remark: String? = null
    var waRemark: String? = null
    var abnormalReason: IdValue? = null
    var passReason: IdValue? = null
    /** 服务端为数字 0/1 */
    var isPhone: String? = null
    /** 服务端字段名 autoJudge（数字 0/1） */
    var autoJudge: String? = null
    val isAutoJudge: String? get() = autoJudge
    var isSeismic: String? = null
    var isThermometric: String? = null
    var isRealPass: String? = null
    var taskState: String? = null
    var deviceTaskState: String? = null
    var imgUrl: String? = null
    var pictureId: String? = null
    var isFinished: Boolean = false
    var isUpload: Boolean = false
    var sort: Int? = null

    /** 本地主键：优先 taskWorkId */
    val localId: String get() = taskWorkId ?: id ?: workItemId.orEmpty()

    val displayName: String get() = content?.takeIf { it.isNotBlank() } ?: claim.orEmpty()

    val default: String? get() = defaultVal ?: defaultValue

    /** 需要显示测量输入框（非"是否"型） */
    val needsValue: Boolean get() = !ResultEditMode.isWhether(inputStandardCode?.editType, inputStandardCode?.candidateValue)

    /** 是否属于振动测点（AS1000 相关，暂不实现） */
    val isVib: Boolean get() = isSeismic == "1" || isSeismic == "true" || isSeismic == "YES"
}

/**
 * 录入标准，对应 inputStandardCode 内嵌对象。
 * candidateValue 形如 "是,否"（"是否"型）或 "0,1"、"低,中,高"（下拉型）。
 */
class InputStandard {
    var id: String? = null
    var code: String? = null
    var name: String? = null
    var candidateValue: String? = null
    var editType: String? = null
    var decimalPlace: String? = null
    var seismicMode: String? = null
    var unitID: String? = null
    var valType: String? = null
    var valid: String? = null
    var status: String? = null
    var version: Int = 0

    /** 候选值列表 */
    val candidates: List<String>
        get() = candidateValue?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    val isSelect: Boolean get() = candidates.isNotEmpty()
}

/* ------------------------------------------------------------------ *
 * 状态 / 类型 常量（原 App XJConstant）
 * ------------------------------------------------------------------ */

object PatrolTaskState {
    const val NOT_ISSUED = "PATROL_taskState/notIssued"
    const val ISSUED = "PATROL_taskState/issued"
    const val RUNNING = "PATROL_taskState/running"
    const val COMPLETED = "PATROL_taskState/completed"
    const val OVERDUE = "PATROL_taskState/overdue"
    /** 超期后才完成（服务端按计划窗口与实际时间判定，客户端只发状态码） */
    const val OVERDUE_COMPLETED = "PATROL_taskState/overdueCompleted"
    const val CANCELLED = "PATROL_taskState/cancelled"

    /** 文案照服务端字典（巡检任务.xlsx → hidden@PATROL_taskState），别自创说法 */
    fun label(id: String?): String = when (id) {
        NOT_ISSUED -> "未领取"
        ISSUED -> "已领取"
        RUNNING -> "执行中"
        COMPLETED -> "已完成"
        OVERDUE -> "已超期"
        OVERDUE_COMPLETED -> "超期完成"
        CANCELLED -> "已取消"
        else -> "未知"
    }
}

object PatrolTaskType {
    const val DAILY = "PATROL_taskType/dailyPatrol"
    const val SPOT = "PATROL_taskType/spotCheck"

    fun label(type: String?): String = when {
        type == null -> "巡检"
        type.contains("dailyPatrol") -> "日常巡检"
        type.contains("spotCheck") -> "点检"
        else -> "巡检"
    }
}

/** 巡检项结论（原版 XJConstant.PatrolConclusion） */
object PatrolConclusion {
    const val NORMAL = "PATROL_realValue/normal"
    const val ABNORMAL = "PATROL_realValue/abnormal"
    const val DOUBTFUL = "PATROL_realValue/doubtful"

    val all = listOf(NORMAL to "正常", ABNORMAL to "异常", DOUBTFUL to "可疑")

    fun label(value: String?): String = when (value) {
        NORMAL -> "正常"
        ABNORMAL -> "异常"
        DOUBTFUL -> "可疑"
        else -> ""
    }

    fun isAbnormal(value: String?): Boolean = value == ABNORMAL || value == DOUBTFUL
}

/** 录入标准（原版 XJConstant.ResultEditMode） */
object ResultEditMode {
    const val INPUT = "PATROL_editType/input"
    const val MULTI_SELECT = "PATROL_editType/multipleSelect"
    const val SINGLE_SELECT = "PATROL_editType/singleSelect"
    const val WHETHER = "PATROL_editType/whether"

    /** 是否为"是否"型（正常/异常二选一）。真机数据里 editType 常为空，靠 candidateValue="是,否" 判定 */
    fun isWhether(editType: String?, candidateValue: String? = null): Boolean {
        if (!editType.isNullOrBlank() && editType.contains("whether")) return true
        val c = candidateValue?.trim().orEmpty()
        return c == "是,否" || c == "否,是" || c == "正常,异常" || c == "异常,正常"
    }
}

/** 巡检项明细状态（原版 XJConstant.TaskWorkState） */
object WorkItemState {
    const val CHECKED = "PATROL_taskDetailState/checked"
    const val PENDING = "PATROL_taskDetailState/pending"
    const val SKIP = "PATROL_taskDetailState/skip"
    const val EXEMPTION = "PATROL_taskDetailState/exemption"
    const val UNCHECK = "PATROL_taskDetailState/uncheck"

    fun label(value: String?): String = when (value) {
        CHECKED -> "已检"
        PENDING -> "待检"
        SKIP -> "跳过"
        EXEMPTION -> "免检"
        UNCHECK -> "未检"
        else -> ""
    }
}

/** 签到方式（原版 XJConstant.SignType） */
object SignType {
    const val CARD = "PATROL_payCardType/card"
    const val MANUAL = "PATROL_payCardType/signIn"

    fun label(value: String?): String = when (value) {
        CARD -> "刷卡签到"
        MANUAL -> "手动签到"
        else -> ""
    }
}

/**
 * 统一面板里「一条巡检项的本地改动」。
 * 只有用户真正动过的条目才会被下发（未动的不进这个列表）。
 */
data class WorkItemPatch(
    val id: String,
    val realValue: String? = null,
    val concluse: String? = null,
    val skipReason: String? = null,
    val abnormalReason: String? = null,
    val remark: String? = null,
    val passReason: String? = null,
    val finished: Boolean = false,
    val taskDetailState: String? = null,
)

/**
 * 统一面板里「这次签到要写的字段」。
 * 全部由用户在面板上确认后才写入本地库；**绝不发往服务端**。
 */
data class SignInPatch(
    /** 面板上的签到方式文案：NFC刷卡签到 / 手动签到 */
    val signTypeLabel: String,
    /** 原版字典值 PATROL_payCardType/{card,signIn} */
    val payCardTypeId: String?,
    val staff: String?,
    val staffId: String?,
    val cardTime: Long,
    /** 刷到的卡号（刷卡方式才有） */
    val cardCode: String? = null,
    /** 签到原因（补签说明等） */
    val signInReason: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

package com.sdyl.patrol.core.network

/**
 * 原平台 统一响应信封，对应原 App 的 BAP5CommonEntity。
 * 成功码白名单：200 / 100000000 / 2000000。
 */
class BapResponse<T> {
    var code: Int = 0
    var data: T? = null
    var list: T? = null
    var message: String? = null
    var msg: String? = null
    var error: String? = null
    var path: String? = null

    /**
     * 注意：login 接口的扁平响应里 status 是字符串 "ok"，
     * 而多数接口根本不带 status。声明成 Int 会让 Gson 直接抛
     * JsonSyntaxException（Expected an int but was STRING），故此处置为 String?。
     */
    var status: String? = null
    var success: Boolean = true
    var timestamp: String? = null

    val isOk: Boolean get() = success && code in OK_CODES

    val errorText: String
        get() = msg?.takeIf { it.isNotBlank() }
            ?: message?.takeIf { it.isNotBlank() }
            ?: error?.takeIf { it.isNotBlank() }
            ?: "请求失败(code=$code)"

    /** 取 data，其次取 list */
    fun payload(): T? = data ?: list

    companion object {
        val OK_CODES = setOf(200, 100000000, 2000000)
    }
}

/** 分页结果，对应 BapPageResultEntity */
class BapPage<T> {
    var result: List<T>? = null
    var pageNo: Int = 1
    var pageSize: Int = 10
    var hasNext: Boolean = false
    var hasPre: Boolean = false
    var nextPage: Int = 0
    var prePage: Int = 0
    var totalCount: Int = 0

    val items: List<T> get() = result.orEmpty()
}

/** 通用 id/value 结构化值，对应 BaseIdValueEntity */
open class IdValue {
    var id: String? = null
    var value: String? = null
}

/** 分页请求基类，对应 XJBasePageRequestBO */
open class PageRequest {
    var pageNo: Int = 1
    var pageSize: Int = 20
    var paging: Boolean = true
}

/** 业务异常：把信封里的错误码/文案带出来 */
class ApiException(val code: Int, message: String) : RuntimeException(message)

/**
 * 账号已在别处登录。服务端在 POST /inter-api/auth/login 里返回
 * {"needForceLogin":true,"kickoutMsg":"10.188.0.37"} 且 ticket 为空，
 * 此时应让用户确认后再带 forceLogin=true 重发。
 */
class ForceLoginRequiredException(val kickoutMsg: String?) :
    RuntimeException(kickoutMsg?.takeIf { it.isNotBlank() }?.let { "该账号已在 $it 登录" } ?: "该账号已在其他设备登录")

package com.sdyl.patrol.core.network.dto

import com.google.gson.annotations.SerializedName
import com.sdyl.patrol.core.network.BapResponse

/** POST /inter-api/auth/login 请求体（对齐原 App TokenPresenter.getAccessToken） */
class LoginRequest {
    var userName: String = ""
    var password: String = ""
    var clientId: String = "android"
    var userAgent: String = ""
    var forceLogin: Boolean? = null
    var code: String? = null
    var key: String? = null
}

/**
 * POST /inter-api/auth/login 的真实响应。
 *
 * 实测（2026-10-12，supos.example.com:8443）该接口**不套信封**，成功时直接返回扁平结构：
 *   {"companies":[{"code":"default_org_company","id":1000,"name":"示例企业"}],
 *    "currentCompany":{...},"expire":3600,"isThreeRole":false,"loginType":"0",
 *    "status":"ok","tenantId":"dt","ticket":"UgMD5NgvEaq9CKmlTdzTf",
 *    "uniqueLogin":true,"user":{"id":1000000000000688,"userName":"90000001","userType":0},
 *    "userId":1000000000000688}
 * 只有失败时才出现 code/message 字段，所以两套字段都收进来，由 [isOk] 统一判定。
 *
 * 对照：/inter-api/auth/v1/password/config、/v1/getCurrentLoginInfo 都是标准信封
 * （{"code":200,"success":true,"data":{...},"msg":"..."}）。同一后端的信封并不统一。
 */
class LoginResponse {
    // ---------- 成功（扁平） ----------
    var ticket: String? = null
    var data: String? = null
    var expire: Long = 0L
    var loginType: String? = null
    var tenantId: String? = null
    var status: String? = null
    var uniqueLogin: Boolean? = null
    var isThreeRole: Boolean = false
    var userId: Long = 0L
    var needForceLogin: Boolean? = null
    var kickoutMsg: String? = null
    var user: AccountDto? = null
    var companies: List<CompanyDto>? = null
    var currentCompany: CompanyDto? = null

    // ---------- 失败（信封） ----------
    var code: Int = 0
    var message: String? = null
    var msg: String? = null
    var error: String? = null
    var success: Boolean = true

    val token: String get() = (ticket ?: data).orEmpty()

    val isOk: Boolean
        get() = token.isNotBlank() || status == "ok" || code in BapResponse.OK_CODES

    val errorText: String
        get() = kickoutMsg?.takeIf { it.isNotBlank() }
            ?: msg?.takeIf { it.isNotBlank() }
            ?: message?.takeIf { it.isNotBlank() }
            ?: error?.takeIf { it.isNotBlank() }
            ?: if (needForceLogin == true) "该账号已在其他设备登录"
            else "请求失败(code=$code)"
}

/** TokenEntity：票据与公司信息（保留兼容旧调用） */
class TokenDto {
    var ticket: String? = null
    var expire: Long = 0L
    var loginType: String? = null
    var kickoutMsg: String? = null
    var needForceLogin: Boolean? = null
    var uniqueLogin: Boolean? = null

    /** 兼容 data 同名字段 */
    var data: String? = null

    var user: AccountDto? = null
    var companies: List<CompanyDto>? = null
    var currentCompany: CompanyDto? = null

    val token: String get() = ticket ?: data ?: ""
}

class AccountDto {
    var id: Long = 0L
    var userName: String? = null
    var name: String? = null
    var staffId: Long = 0L
    var staffCode: String? = null
    var companyId: Long = 0L
    var companyName: String? = null
    var departmentId: Long = 0L
    var departmentName: String? = null
    var mobile: String? = null
    var email: String? = null
}

class CompanyDto {
    var id: Long = 0L
    var code: String? = null
    var name: String? = null
    var fullname: String? = null
    var parentId: Long = 0L
}

/** GET /inter-api/auth/v1/password/config */
class PasswordConfigDto {
    @SerializedName(value = "isEncrypted", alternate = ["encrypted", "encrypt"])
    var isEncrypted: Boolean = false

    val encryptedEnabled: Boolean get() = isEncrypted
}

/** BaseCodeIdNameEntity */
open class CodeIdName {
    var id: Long = 0L
    var code: String? = null
    var name: String? = null
}

/** CurrentLoginInfo */
class CurrentLoginInfoDto {
    var serverAddress: String? = null
    var user: CodeIdName? = null
    var staff: CodeIdName? = null
    var company: CodeIdName? = null
    var department: CodeIdName? = null
    var mainPosition: CodeIdName? = null
    var currentDepartment: List<CodeIdName>? = null
    var currentPosition: List<CodeIdName>? = null
}

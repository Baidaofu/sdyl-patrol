package com.sdyl.patrol.feature.auth

import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.SessionStore
import com.sdyl.patrol.core.data.isPlaceholderName
import com.sdyl.patrol.core.network.ApiClient
import com.sdyl.patrol.core.network.ApiException
import com.sdyl.patrol.core.network.BapResponse
import com.sdyl.patrol.core.network.ForceLoginRequiredException
import com.sdyl.patrol.core.network.WebCookies
import com.sdyl.patrol.core.network.dto.CurrentLoginInfoDto
import com.sdyl.patrol.core.network.dto.LoginRequest
import com.sdyl.patrol.core.network.dto.LoginResponse
import com.sdyl.patrol.core.network.dto.PasswordConfigDto
import com.sdyl.patrol.core.network.dto.TokenDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

interface AuthApi {
    /** 注意：该接口响应是扁平的，不能用 BapResponse 包（见 LoginResponse 注释） */
    @POST("/inter-api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("/inter-api/auth/v1/getCurrentLoginInfo")
    suspend fun currentLoginInfo(): BapResponse<CurrentLoginInfoDto>

    @GET("/inter-api/auth/v1/password/config")
    suspend fun passwordConfig(): BapResponse<PasswordConfigDto>

    @PUT("/inter-api/auth/token/refresh")
    suspend fun refresh(): BapResponse<TokenDto>

    @PUT("/inter-api/auth/logout")
    suspend fun logout(): BapResponse<Any>
}

/**
 * 登录时序（逆向自 TokenPresenter / BAP5ResponseInterceptor）：
 *  1. 记住/切换服务器地址 → invalidate Retrofit
 *  2. GET  password/config 判断密码是否 AES（本实现先按明文提交，服务端要求加密时再补）
 *  3. POST auth/login      取 ticket
 *  4. GET  getCurrentLoginInfo  必须补打，业务请求依赖其中的 staffId / companyId
 */
class AuthRepository(
    private val api: ApiClient,
    private val session: SessionStore,
) {
    private fun service(): AuthApi = api.create(AuthApi::class.java)

    val isLoggedIn: Boolean get() = session.isLoggedIn
    val displayName: String get() = session.staffName.ifBlank { session.userName }

    suspend fun login(
        host: String,
        userName: String,
        password: String,
        useHttps: Boolean = true,
        forceLogin: Boolean = false,
    ): Result<Unit> = runCatching {
        val normalized = SessionStore.normalizeHost(host)
        require(normalized.isNotEmpty()) { "服务器地址不能为空" }

        // 用户 m16405：换账号之前先把上一个账号落盘，否则它只在旧的「记住密码」键里，
        // userName 一改就从「切换账号」列表里消失了。
        if (userName != session.userName) session.persistCurrentAccount()

        session.serverHost = normalized
        session.useHttps = useHttps
        api.invalidate()

        val svc = service()
        val request = LoginRequest().apply {
            this.userName = userName
            this.password = password
            this.clientId = "android"
            this.userAgent = ApiClient.defaultUserAgent()
            if (forceLogin) this.forceLogin = true
        }

        val response = svc.login(request)

        // 账号已在别处登录：服务端给 needForceLogin=true 且不给 ticket，
        // 必须由用户确认后再带 forceLogin=true 重发（原 App 也是这样弹框的）。
        if (response.needForceLogin == true && response.token.isBlank()) {
            if (!forceLogin) throw ForceLoginRequiredException(response.kickoutMsg)
        }

        if (!response.isOk) throw ApiException(response.code, response.errorText)
        val ticket = response.token
        if (ticket.isBlank()) {
            throw ApiException(response.code, response.kickoutMsg ?: "未获取到登录票据")
        }

        session.ticket = ticket
        session.userName = userName
        session.staffId = response.user?.staffId ?: 0L
        session.companyId = response.currentCompany?.id ?: response.user?.companyId ?: 0L
        session.staffName = userName
        // 手机号：原版 XJUploadWorkBO.phoneNum 取的就是 AccountInfo.getMobile()。
        // 服务端不给就留空，由「我的 → 上传身份」里手填兜底。
        response.user?.mobile?.takeIf { it.isNotBlank() }?.let { session.mobile = it }
        // 工号：原版 AccountInfo.getStaffCode()，「领取任务」的请求体必填
        response.user?.staffCode?.takeIf { it.isNotBlank() }?.let { session.staffCode = it }

        // 服务端下发 H5（视频播放页）靠 cookie 认身份，票据要同步进 WebView
        WebCookies.sync(session.serverHost, ticket, session.useHttps)

        // 关键：再补一次当前登录信息，业务请求依赖其中的 staffId / companyId，
        // 且这里才能拿到真实姓名（login 响应里 user.name 为空）
        runCatching { svc.currentLoginInfo() }.getOrNull()?.let { info ->
            if (info.isOk) {
                info.payload()?.let { dto ->
                    dto.staff?.let { s ->
                        if (s.id != 0L) session.staffId = s.id
                        s.name?.takeIf { it.isNotBlank() }?.let { session.staffName = it }
                    }
                    dto.company?.let { c -> if (c.id != 0L) session.companyId = c.id }
                }
            }
        }

        // 用户 m17221：服务端 login 与 getCurrentLoginInfo 都没给姓名时（staffName 还是工号），
        // 用本机人员名录按工号换一次，否则「登录过的账号」列表里会出现「90000002」这种工号。
        if (isPlaceholderName(session.staffName, userName)) {
            runCatching { Graph.personDirectory.nameByCode(userName) }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { session.staffName = it }
        }

        // 用户 m16146：登录成功就把这个账号记进「登录过的账号」表（切换账号、登录页选账号都用它）。
        // 这里不知道登录页「记住密码」勾没勾 —— 没勾时由登录页再用空密码覆盖一次。
        session.upsertAccount(
            user = userName,
            password = password,
            staffName = session.staffName,
            host = normalized,
            useHttps = useHttps,
        )
        Unit
    }

    suspend fun logout(): Result<Unit> = runCatching {
        runCatching { service().logout() }
        // 用户 m16405：退出前把当前账号留在「登录过的账号」表里，下次不用重新输账号密码
        session.persistCurrentAccount()
        session.clearLogin()
        WebCookies.clear()
        Unit
    }

    suspend fun checkConnectivity(): Result<String> = runCatching {
        val svc = service()
        val res = svc.passwordConfig()
        if (res.isOk) "连接成功" else throw ApiException(res.code, res.errorText)
    }
}

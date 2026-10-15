package com.sdyl.patrol.core.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sdyl.patrol.BuildConfig
import com.sdyl.patrol.core.device.DeviceIdentity

/**
 * 服务器地址 + 登录态 + 当前用户信息 + 软件设置。
 * 对应原 App 的 SharedPreferences 语义（ip/port/url/URL_ENABLE/HTTPS_ENABLE/ACCESS_TOKEN/IMEI_MANUAL）。
 */
class SessionStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val sp: SharedPreferences =
        context.getSharedPreferences("sdyl_session", Context.MODE_PRIVATE)

    /** "supos.example.com:8443" */
    var serverHost: String
        get() = sp.getString(KEY_HOST, DEFAULT_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        set(value) = sp.edit().putString(KEY_HOST, value.trim()).apply()

    var useHttps: Boolean
        get() = sp.getBoolean(KEY_HTTPS, true)
        set(value) = sp.edit().putBoolean(KEY_HTTPS, value).apply()

    /** 原平台 票据，登录接口响应里的 ticket */
    var ticket: String
        get() = sp.getString(KEY_TICKET, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TICKET, value).apply()

    var userName: String
        get() = sp.getString(KEY_USER, "").orEmpty()
        set(value) = sp.edit().putString(KEY_USER, value).apply()

    var staffName: String
        get() = sp.getString(KEY_STAFF_NAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_STAFF_NAME, value).apply()

    var staffId: Long
        get() = sp.getLong(KEY_STAFF_ID, 0L)
        set(value) = sp.edit().putLong(KEY_STAFF_ID, value).apply()

    /**
     * 工号 —— 对应原版 `AccountInfo.getStaffCode()`。
     * 「领取任务」（createTempTaskByRouteMobile）的请求体里必须带 staffCode，
     * 原版 `XJCreateTempTaskPresenter` 就取的这个值；服务端查不到工号会创建失败。
     * 服务端没给时退回用户名（本账号两者都是 90000001，与原版库 ACCOUNT_INFO 一致）。
     */
    var staffCode: String
        get() = sp.getString(KEY_STAFF_CODE, "").orEmpty().ifBlank { userName }
        set(value) = sp.edit().putString(KEY_STAFF_CODE, value.trim()).apply()

    var companyId: Long
        get() = sp.getLong(KEY_COMPANY_ID, 0L)
        set(value) = sp.edit().putLong(KEY_COMPANY_ID, value).apply()

    /**
     * 账号键 —— 用来把「跟账号走」的本机文件（签字图，见 [SignStore]）分开存。
     * 用户 m05038：签字要跟随账户，多账户不共享。优先用人员 ID（服务端主键，换工号也不会变），
     * 拿不到才退回登录名。
     */
    val accountKey: String
        get() = if (staffId > 0L) {
            "staff_$staffId"
        } else {
            "user_" + userName.ifBlank { "anonymous" }
        }

    /* ------------------------- 软件设置 ------------------------- */

    /**
     * 手填 IMEI —— 对应原版 `XJConstant.SPKey.IMEI_MANUAL`（键名就是 `IMEI_MANUAL`）。
     *
     * 用户诉求（m04512 第 6 条）：原巡检 apk 签到时会上传软件设置里填的 IMEI 码，
     * 本软件没有这个设置项 → 在这里补上。
     * 注意 Android 10+ 读不到真机 IMEI（原版 `getImei` 在 SDK>=29 直接返回 null），
     * 所以 **实际能上传的就是这个手填值**。
     */
    var imeiManual: String
        get() = sp.getString(KEY_IMEI_MANUAL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_IMEI_MANUAL, value.trim()).apply()

    /** 账号手机号 —— 对应原版 `AccountInfo.getMobile()`，上传巡检项时作 `phoneNum` */
    var mobile: String
        get() = sp.getString(KEY_MOBILE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_MOBILE, value.trim()).apply()

    /** 上传巡检项时随包上报的 IMEI（原版 `XJUploadWorkBO.imei`） */
    val uploadImei: String get() = DeviceIdentity.imeiWithManual(appContext, imeiManual)

    /** 上传巡检项时随包上报的手机号（原版 `XJUploadWorkBO.phoneNum`） */
    val uploadPhoneNum: String get() = mobile

    /** 是否记住密码（登录页勾选框的持久状态） */
    var rememberPassword: Boolean
        get() = sp.getBoolean(KEY_REMEMBER, true)
        set(value) = sp.edit().putBoolean(KEY_REMEMBER, value).apply()

    /**
     * 记住的密码 —— 用户 m11540：被踢出登录后要能一键重新登录。
     *
     * 原版 `LoginActivity` 同样是按「用户名 + 服务器」把密码记在本机
     * （`AccountInfoDaoUtil.getAccountPwdByUserName(user, host)`，勾选框 `savePassword`），
     * 这里存进同一个 app 私有 prefs（与票据同级别，只有本应用读得到）。
     * 键里带 host + user，换服务器或换账号都不会串。
     */
    private fun passwordKey(user: String, host: String): String = "$host|$user"

    /** 取该账号在本服务器上记住的密码；没记过、或换了账号/服务器都返回 null */
    fun savedPasswordFor(user: String = userName, host: String = serverHost): String? {
        // 先查多账号表（用户 m16146「切换账号」用），再退回旧的单个「记住密码」键
        savedAccounts().firstOrNull { it.user == user && it.host == host }
            ?.password?.ifBlank { null }?.let { return it }
        return legacySavedPassword(user, host)
    }

    /** 记住密码（顺带把「记住密码」开关置为开） */
    fun rememberUserPassword(user: String, host: String, password: String) {
        sp.edit()
            .putBoolean(KEY_REMEMBER, true)
            .putString(KEY_PWD_ACCOUNT, passwordKey(user, host))
            .putString(KEY_PWD, password)
            .apply()
    }

    /** 忘掉密码（取消勾选「记住密码」，或密码已失效时） */
    fun forgetUserPassword() {
        sp.edit()
            .putBoolean(KEY_REMEMBER, false)
            .remove(KEY_PWD_ACCOUNT)
            .remove(KEY_PWD)
            .apply()
        // 账号本身留在「登录过的账号」表里（还能一键切过去），只是不再记密码
        val list = savedAccounts()
        if (list.any { it.user == userName && it.host == serverHost && it.password.isNotBlank() }) {
            saveAccounts(
                list.map {
                    if (it.user == userName && it.host == serverHost) it.copy(password = "") else it
                }
            )
        }
    }

    /* ------------------------- 登录过的账号（用户 m16146：切换账号） ------------------------- */

    /**
     * 登录成功过的账号：`「我的 → 切换账号」`和登录页的账号选择都用这张表。
     * 用户原话：「能不能加一个切换账号功能？可以直接切换登录过的账户，并且允许查看账户和密码和人名。
     * 登陆界面也能选择已登录的账户直接输入账户密码，并且密码自动跟随输入的账号变化。」
     */
    fun savedAccounts(): List<SavedAccount> {
        val raw = sp.getString(KEY_ACCOUNTS, "").orEmpty()
        val type = object : TypeToken<List<SavedAccount>>() {}.type
        val list = if (raw.isBlank()) {
            emptyList()
        } else {
            (runCatching { Gson().fromJson<List<SavedAccount>>(raw, type) }.getOrNull() ?: emptyList())
                .filter { it.user.isNotBlank() }
        }
        // 老版本升级上来：账号表还是空的，但登录态和旧的「记住密码」都在 —— 把当前账号补进去，
        // 否则第一次进「切换账号」是空列表（非要再登录一次才出现）。
        if (userName.isNotBlank() && list.none { it.user == userName && it.host == serverHost }) {
            val pwd = legacySavedPassword(userName, serverHost)
            if (pwd != null) {
                return listOf(
                    SavedAccount(
                        user = userName,
                        password = pwd,
                        staffName = staffName,
                        host = serverHost,
                        useHttps = useHttps,
                    )
                ) + list
            }
        }
        return list
    }

    /** 旧版那一个「记住密码」键的读法（多账号表出现之前就存在的那一份） */
    private fun legacySavedPassword(user: String, host: String): String? {
        val key = sp.getString(KEY_PWD_ACCOUNT, "").orEmpty()
        if (key.isEmpty() || key != passwordKey(user, host)) return null
        return sp.getString(KEY_PWD, "").orEmpty().ifBlank { null }
    }

    private fun saveAccounts(list: List<SavedAccount>) {
        sp.edit().putString(KEY_ACCOUNTS, Gson().toJson(list)).apply()
    }

    /** 登录成功后记一笔（同「服务器 + 账号」覆盖，最近登录的排最前） */
    fun upsertAccount(
        user: String,
        password: String,
        staffName: String = "",
        host: String = serverHost,
        useHttps: Boolean = this.useHttps,
    ) {
        if (user.isBlank()) return
        val entry = SavedAccount(
            user = user,
            password = password,
            staffName = staffName.ifBlank { user },
            host = host,
            useHttps = useHttps,
            lastAt = System.currentTimeMillis(),
        )
        saveAccounts(mergeSavedAccount(savedAccounts(), entry))
    }

    fun removeAccount(user: String, host: String = serverHost) {
        saveAccounts(savedAccounts().filterNot { it.user == user && it.host == host })
    }

    /**
     * 把「当前登录的这个账号」落盘（用户 m16405）。
     *
     * 换账号登录 / 退出登录之前必须调一次：以前这个账号可能只活在旧的单个
     * 「记住密码」键里（[legacySavedPassword]），一旦 [userName] 被改成新账号、
     * 或者密码键被新密码覆盖，旧账号就从列表里消失了 ——
     * 用户看到的现象是「登录了一个新账号，切换账号里只剩当前账户」。
     */
    fun persistCurrentAccount() {
        val user = userName
        if (user.isBlank()) return
        val host = serverHost
        val pwd = savedPasswordFor(user, host).orEmpty()
        upsertAccount(
            user = user,
            password = pwd,
            staffName = staffName,
            host = host,
            useHttps = useHttps,
        )
    }

    /**
     * 批量导入账号（用户 m16405：「把两个安全管控平台的本地 db 里的账户导入咱们的软件」）。
     *
     * 同「服务器 + 账号」覆盖，最多留 [MAX_SAVED_ACCOUNTS] 个。返回真正并入的条数。
     */
    fun importAccounts(list: List<SavedAccount>): Int {
        val valid = list.filter { it.user.isNotBlank() }
        if (valid.isEmpty()) return 0
        var merged = savedAccounts()
        valid.forEach { a ->
            merged = mergeSavedAccount(
                merged,
                a.copy(
                    host = if (a.host.isBlank()) serverHost else a.host,
                    lastAt = if (a.lastAt > 0L) a.lastAt else System.currentTimeMillis(),
                ),
            )
        }
        saveAccounts(merged)
        return valid.size
    }

    /** 按服务器 + 账号取一个账号（登录页按账号自动带密码用） */
    fun accountFor(user: String, host: String = serverHost): SavedAccount? =
        savedAccounts().firstOrNull { it.user == user && it.host == host }

    val currentAccount: SavedAccount?
        get() = accountFor(userName, serverHost)

    /* ------------------------- 上报人员 ------------------------- */

    /**
     * 用户 m07827：`巡检任务.xlsx` 里「跟随用户固定」的就是这几个值 ——
     * 主表的 `执行人.人员编码/员工姓名`、`完成人.人员编码/员工姓名`，
     * 区域表的签到人，明细表的修改人。它们默认跟随登录账号，
     * 也可以在「我的」里固定成另一个人（代做场景）。
     */
    var reporterFollowAccount: Boolean
        get() = sp.getBoolean(KEY_REPORTER_FOLLOW, true)
        set(value) = sp.edit().putBoolean(KEY_REPORTER_FOLLOW, value).apply()

    /**
     * 「简化巡检」（用户 m08985 第 3 条）：打开后，点任务列表里某个时间段的巡检任务，
     * 不再进常规的任务详情页，而是进一页把所有时间都摆出来的表单——
     * 开始时间 / 每个巡检点的签到时间 / 提交时间，填完一键上传提交。
     */
    var simplifiedPatrol: Boolean
        get() = sp.getBoolean(KEY_SIMPLIFIED_PATROL, false)
        set(value) = sp.edit().putBoolean(KEY_SIMPLIFIED_PATROL, value).apply()

    /** 固定上报人姓名（`reporterFollowAccount = false` 时生效） */
    var reporterName: String
        get() = sp.getString(KEY_REPORTER_NAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_REPORTER_NAME, value.trim()).apply()

    /**
     * 固定上报人的**工号（人员编码）**。
     *
     * 用户 m08022：`巡检任务.xlsx` 里只有工号是「完全和用户对应不变」的标识
     * （登录账号 90000001 ↔ 表格里的 `执行人.人员编码`），导出的表格里**根本没有**
     * 服务端内部 ID（1000000000000952 那串）。所以设置界面只让用户填工号，
     * 内部 ID 由 [com.sdyl.patrol.core.data.PersonDirectory] 按工号换出来。
     */
    var reporterStaffCode: String
        get() = sp.getString(KEY_REPORTER_STAFF_CODE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_REPORTER_STAFF_CODE, value.trim()).apply()

    /** 固定上报人的人员 ID（服务端 `userId` / `executeUser.id` / `completeUser.id`），由工号换算得来 */
    var reporterStaffId: String
        get() = sp.getString(KEY_REPORTER_STAFF_ID, "").orEmpty()
        set(value) = sp.edit().putString(KEY_REPORTER_STAFF_ID, value.trim()).apply()

    /** 上报人生效时的工号（固定时用工号，跟随账号时用登录账号的工号） */
    val reporterDisplayCode: String
        get() = if (!reporterFollowAccount && reporterStaffCode.isNotBlank()) {
            reporterStaffCode
        } else {
            staffCode.ifBlank { userName }
        }

    /** 实际用于上报的姓名 */
    val reporterDisplayName: String
        get() = if (!reporterFollowAccount && reporterName.isNotBlank()) {
            reporterName
        } else {
            staffName.ifBlank { userName }
        }

    /** 实际用于上报的人员 ID（字符串，走 JSON 的 `{"id":...}`） */
    val reporterRefId: String
        get() = if (!reporterFollowAccount && reporterStaffId.isNotBlank()) {
            reporterStaffId
        } else {
            staffId.takeIf { it > 0L }?.toString().orEmpty()
        }

    /**
     * 实际用于上报的人员 ID（数值，走 `userId` / `completeStaffId`）
     */
    val reporterRefIdLong: Long get() = reporterRefId.toLongOrNull() ?: staffId

    /* ------------------------- 离线任务时间（本机账本） ------------------------- */

    /**
     * 用户 m08202：原版的离线巡检 —— 断网也能「开始任务 / 提交任务」，
     * 时间先落本机；等联网后提交时，用的仍是断网期间填的那些时间。
     *
     * 这就是那份「本机时间」的账本。只要某个 `tableNo` 记在里面：
     *  - 同步任务时本机的时间/状态**不会被服务端顶掉**（见 `PatrolRepository.cacheTasks`）；
     *  - 上报成功后清掉该条，之后以服务端为准。
     */
    data class OfflineTimes(
        val start: Long = 0L,
        val end: Long = 0L,
        val staffId: String = "",
        val staffName: String = "",
        /** 离线开始之前本机记的任务状态，用于「撤销本机开始」时回退 */
        val prevState: String = "",
    )

    private fun offlineMap(): MutableMap<String, OfflineTimes> {
        val raw = sp.getString(KEY_OFFLINE_TIMES, null)
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, OfflineTimes>>() {}.type
        val parsed: MutableMap<String, OfflineTimes>? =
            runCatching { Gson().fromJson<MutableMap<String, OfflineTimes>>(raw, type) }.getOrNull()
        return parsed ?: mutableMapOf()
    }

    private fun saveOfflineMap(map: Map<String, OfflineTimes>) {
        sp.edit().putString(KEY_OFFLINE_TIMES, Gson().toJson(map)).apply()
    }

    /** 该任务在本机有没有「离线时间」记录 */
    fun offlineTimes(tableNo: String): OfflineTimes? = offlineMap()[tableNo]

    fun hasOfflineTimes(tableNo: String): Boolean = offlineMap().containsKey(tableNo)

    fun setOfflineStart(
        tableNo: String,
        time: Long,
        staffId: String,
        staffName: String,
        prevState: String = "",
    ) {
        val map = offlineMap()
        val old = map[tableNo]
        map[tableNo] = OfflineTimes(
            start = time,
            end = old?.end ?: 0L,
            staffId = staffId.ifBlank { old?.staffId.orEmpty() },
            staffName = staffName.ifBlank { old?.staffName.orEmpty() },
            prevState = prevState.ifBlank { old?.prevState.orEmpty() },
        )
        saveOfflineMap(map)
    }

    fun setOfflineEnd(tableNo: String, time: Long) {
        val map = offlineMap()
        val old = map[tableNo] ?: OfflineTimes()
        map[tableNo] = old.copy(end = time)
        saveOfflineMap(map)
    }

    fun clearOfflineTimes(tableNo: String) {
        val map = offlineMap()
        if (map.remove(tableNo) != null) saveOfflineMap(map)
    }

    /**
     * 本机记下「我领过的任务」（tableNo），供主页「我的」分类使用。
     * 服务端任务投影既不带 executeUser、也没有按领取人过滤的接口，只能本地记。
     */
    var myClaimedTaskNos: Set<String>
        get() = sp.getStringSet(KEY_MY_CLAIMED, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_MY_CLAIMED, value).apply()

    fun addClaimedTasks(tableNos: Collection<String>) {
        if (tableNos.isEmpty()) return
        myClaimedTaskNos = myClaimedTaskNos + tableNos
    }

    val baseUrl: String get() = "${if (useHttps) "https" else "http"}://$serverHost"

    /** 原 App 的 BapServer 头，值是 host:port */
    val bapServer: String get() = serverHost

    val isLoggedIn: Boolean get() = ticket.isNotEmpty()

    fun saveLogin(t: String, user: String, name: String, staff: Long, company: Long) {
        sp.edit()
            .putString(KEY_TICKET, t)
            .putString(KEY_USER, user)
            .putString(KEY_STAFF_NAME, name)
            .putLong(KEY_STAFF_ID, staff)
            .putLong(KEY_COMPANY_ID, company)
            .apply()
    }

    fun clearLogin() {
        sp.edit()
            .remove(KEY_TICKET)
            .remove(KEY_STAFF_ID)
            .remove(KEY_COMPANY_ID)
            .remove(KEY_STAFF_NAME)
            .apply()
    }

    companion object {
        /** 仓库内只保留占位地址；真实地址由本机 local.properties 的 sdyl.defaultServer 注入。 */
        val DEFAULT_HOST: String
            get() = BuildConfig.DEFAULT_SERVER.ifBlank { "supos.example.com:8443" }
        private const val KEY_HOST = "server_host"
        private const val KEY_HTTPS = "server_https"
        private const val KEY_TICKET = "ticket"
        private const val KEY_USER = "user_name"
        private const val KEY_STAFF_NAME = "staff_name"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_CODE = "staff_code"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_REMEMBER = "remember_password"

        /** 记住的密码 + 它属于哪个「服务器|账号」 */
        private const val KEY_PWD = "saved_password"
        private const val KEY_PWD_ACCOUNT = "saved_password_account"

        /** 登录过的账号表（JSON 数组，见 [SavedAccount]） */
        private const val KEY_ACCOUNTS = "saved_accounts"
        private const val KEY_IMEI_MANUAL = "IMEI_MANUAL"
        private const val KEY_MOBILE = "mobile"
        private const val KEY_MY_CLAIMED = "my_claimed_tasks"

        /** 离线任务时间账本（JSON：tableNo -> OfflineTimes） */
        private const val KEY_OFFLINE_TIMES = "offline_task_times"
        private const val KEY_REPORTER_FOLLOW = "reporter_follow_account"
        private const val KEY_SIMPLIFIED_PATROL = "simplified_patrol"
        private const val KEY_REPORTER_NAME = "reporter_name"
        private const val KEY_REPORTER_STAFF_ID = "reporter_staff_id"
        private const val KEY_REPORTER_STAFF_CODE = "reporter_staff_code"

        /** 把用户输入的地址规范成 host[:port] */
        fun normalizeHost(input: String): String = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
    }
}

/**
 * 一个「登录过的账号」（用户 m16146）。密码明文存在 app 私有 prefs 里，
 * 与原版 `AccountInfoDaoUtil` 同一级别：只有本应用读得到。
 */
data class SavedAccount(
    val user: String = "",
    val password: String = "",
    val staffName: String = "",
    val host: String = "",
    val useHttps: Boolean = true,
    val lastAt: Long = 0L,
) {
    /** 列表里给人看的一行：优先姓名（同名的人靠账号区分） */
    val title: String get() = staffName.ifBlank { user }

    /**
     * 列表里显示的「姓名（账号）」。
     *
     * 用户 m16405：「登录界面的登陆账号会显示工号（工号），有问题」——
     * 姓名缺失（或和账号一样）时不能再补一个括号，否则会出现「90000001（90000001）」。
     */
    val label: String
        get() = if (staffName.isBlank() || staffName == user) user else "$staffName（$user）"

    /** 密码打码后的样子 */
    fun maskedPassword(): String = if (password.isBlank()) "（没记密码）" else "•".repeat(password.length)
}

/** 最多记这么多个账号，再多就丢最久没用过的（用户 m16405：原版库里一次就有十几个） */
const val MAX_SAVED_ACCOUNTS = 50

/**
 * 名字算不算「没拿到」：空，或者干脆被填成了工号。
 * 服务端 login 响应里 `user.name` 为空、`getCurrentLoginInfo` 又没拿到时会退回工号，
 * 这种值不许覆盖已经查到过的真名（用户 m17221）。
 */
fun isPlaceholderName(name: String?, user: String): Boolean =
    name.isNullOrBlank() || name == user

/**
 * 把一个账号并进已登录列表：同「服务器 + 账号」覆盖（刷新密码/姓名/时间），
 * 并把它挪到最前面。抽成纯函数是为了能单测（不要动 [SessionStore] 里的存盘逻辑）。
 */
fun mergeSavedAccount(
    existing: List<SavedAccount>,
    entry: SavedAccount,
    max: Int = MAX_SAVED_ACCOUNTS,
): List<SavedAccount> {
    if (entry.user.isBlank()) return existing
    val prev = existing.firstOrNull { it.user == entry.user && it.host == entry.host }
    // 新来的名字是工号（没查到姓名）而老记录里有真名时，保留老的真名
    val fixed = if (prev != null &&
        isPlaceholderName(entry.staffName, entry.user) &&
        !isPlaceholderName(prev.staffName, prev.user)
    ) {
        entry.copy(staffName = prev.staffName)
    } else {
        entry
    }
    return (listOf(fixed) + existing.filterNot { it.user == entry.user && it.host == entry.host })
        .take(max)
}


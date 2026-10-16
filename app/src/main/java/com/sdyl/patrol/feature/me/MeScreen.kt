package com.sdyl.patrol.feature.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.LegacyAccountImporter
import com.sdyl.patrol.core.data.SavedAccount
import com.sdyl.patrol.core.data.isPlaceholderName
import com.sdyl.patrol.core.network.ForceLoginRequiredException
import com.sdyl.patrol.core.network.dto.PersonItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
/**
 * 「切换账号」弹窗标题的连点解锁：相邻两下的最大间隔
 * （用户 m17028：5 秒内连续点击标题「切换账号」5 次）。
 */
private const val SWITCH_TAP_WINDOW_MS = 5_000L

@Composable
fun MeScreen(onLoggedOut: () -> Unit) {
    val session = Graph.session
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cache by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearMsg by remember { mutableStateOf("") }
    var imeiManual by remember { mutableStateOf(session.imeiManual) }
    var mobile by remember { mutableStateOf(session.mobile) }
    var reporterFollow by remember { mutableStateOf(session.reporterFollowAccount) }
    var simplified by remember { mutableStateOf(session.simplifiedPatrol) }
    var reporterName by remember { mutableStateOf(session.reporterName) }
    var reporterStaffId by remember { mutableStateOf(session.reporterStaffId) }
    var reporterCode by remember { mutableStateOf(session.reporterStaffCode) }
    var looking by remember { mutableStateOf(false) }
    var reporterMsg by remember { mutableStateOf("") }

    /** 重名候选（用户 m14256：同名的人不止一个，弹出来让用户自己挑） */
    var pickCandidates by remember { mutableStateOf<List<PersonItem>?>(null) }

    /*
     * 人员名录（用户 m17358）：卡片上显示「N 人 · 最后更新 …」+ 手动「更新名录」按钮；
     * 打开「我的」时若本机没有名录、或超过 7 天没更新，就后台自动刷一次。
     */
    var dirBusy by remember { mutableStateOf(false) }
    var dirMsg by remember { mutableStateOf("") }
    var dirVersion by remember { mutableStateOf(0) }

    fun refreshDirectory(auto: Boolean = false) {
        if (dirBusy) return
        dirBusy = true
        if (!auto) dirMsg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { Graph.personDirectory.refresh() }
            dirBusy = false
            dirVersion++
            dirMsg = r.fold(
                onSuccess = { n -> "名录已更新：$n 人。" },
                onFailure = { e -> "名录更新失败：${e.message ?: "未知错误"}" },
            )
        }
    }

    // 用户 m17358 第 2、3 条：打开「我的」时，本机没有名录、或超过 7 天没更新 → 后台自动刷一次
    LaunchedEffect(Unit) {
        if (!Graph.personDirectory.isCached || Graph.personDirectory.isStale()) {
            refreshDirectory(auto = true)
        }
    }

    /** 把选定的人写进「上报人员」：工号、姓名、内部 ID 一起定下来 */
    fun adoptReporter(person: PersonItem) {
        val code = person.code.orEmpty()
        val name = person.name.orEmpty()
        val id = person.id.toString()
        session.reporterStaffCode = code
        session.reporterName = name
        session.reporterStaffId = id
        reporterCode = code
        reporterName = name
        reporterStaffId = id
        reporterMsg = "已识别：$name（工号 ${code.ifBlank { "无" }}）"
    }

    /**
     * 用户 m14256：查询要**双向**——工号查名字，也允许名字查工号。
     *
     * 工号/姓名精确命中一个人就直接用；命中多个人（重名）就弹列表让用户自己挑；
     * 名录里没有就先拉一次全量再查。名录来源见 [PersonDirectory]：
     * `巡检任务.xlsx` 里跟人走的是工号，写接口要的是内部 ID，靠这份名录换算。
     */
    fun lookUpReporter(input: String) {
        val key = input.trim()
        if (key.isEmpty()) return
        looking = true
        reporterMsg = ""
        scope.launch {
            // 自己那一条不用联网：登录接口已经给了 id ↔ 工号 ↔ 姓名
            val selfCode = session.staffCode.ifBlank { session.userName }
            val selfName = session.staffName.ifBlank { session.userName }
            if (key == selfCode || key == selfName) {
                session.reporterStaffCode = selfCode
                session.reporterName = selfName
                session.reporterStaffId = session.staffId.toString()
                reporterCode = selfCode
                reporterName = selfName
                reporterStaffId = session.reporterStaffId
                reporterMsg = "已识别：$selfName（工号 $selfCode）"
                looking = false
                return@launch
            }
            val dir = Graph.personDirectory
            var hits = runCatching { dir.candidates(key) }.getOrDefault(emptyList())
            if (hits.isEmpty()) {
                val r = dir.refresh()
                if (r.isFailure) {
                    reporterMsg = "人员名录获取失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
                    looking = false
                    return@launch
                }
                hits = runCatching { dir.candidates(key) }.getOrDefault(emptyList())
            }
            when {
                hits.isEmpty() ->
                    reporterMsg = "名录里没有「$key」（名录 ${dir.count} 人），请确认工号或姓名。"
                hits.size == 1 -> adoptReporter(hits.first())
                else -> {
                    pickCandidates = hits
                    reporterMsg = ""
                }
            }
            looking = false
        }
    }

    /* ---------------- 重新登录（用户 m11540：被踢出登录后的快捷登录） ---------------- */

    var reloginBusy by remember { mutableStateOf(false) }
    var reloginMsg by remember { mutableStateOf("") }
    var reloginPwd by remember { mutableStateOf("") }
    var pwdDialog by remember { mutableStateOf(false) }
    var pwdInput by remember { mutableStateOf("") }
    var pwdRemember by remember { mutableStateOf(session.rememberPassword) }
    var pwdError by remember { mutableStateOf("") }
    var forcePrompt by remember { mutableStateOf<String?>(null) }

    /**
     * 用给定密码把当前账号重新登录一次。
     *
     * 被别处登录后旧票据作废，业务请求会一直 401；这里重新走
     * `POST /inter-api/auth/login` 换新票据。服务端回「已在其他设备登录」时
     * 由 [forcePrompt] 弹框确认，再带 `forceLogin` 重发（与原版弹框一致）。
     * 成功后顺手把今天的任务增量同步一遍，不用回列表页自己拉。
     */
    fun doRelogin(password: String, force: Boolean) {
        if (reloginBusy) return
        reloginBusy = true
        pwdError = ""
        reloginPwd = password
        scope.launch {
            val r = Graph.authRepository.login(
                host = session.serverHost,
                userName = session.userName,
                password = password,
                useHttps = session.useHttps,
                forceLogin = force,
            )
            reloginBusy = false
            r.fold(
                onSuccess = {
                    forcePrompt = null
                    pwdDialog = false
                    // 勾了「记住密码」就（重新）落本机，供下次一键重登
                    if (pwdRemember || session.rememberPassword) {
                        session.rememberUserPassword(session.userName, session.serverHost, password)
                    }
                    reloginMsg = "已重新登录：${session.staffName.ifBlank { session.userName }}"
                    val c = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val day0 = c.timeInMillis
                    runCatching {
                        // replaceAll = false：只增量 upsert，不动本机已记的签到与巡检结果
                        Graph.patrolRepository.syncTasks(
                            taskStates = null,
                            keyword = null,
                            windowStart = day0,
                            windowEnd = day0 + 86_400_000L - 1L,
                            replaceAll = false,
                        )
                    }.onFailure { e ->
                        reloginMsg = "已重新登录；任务同步失败：${e.message ?: "网络不可用"}"
                    }
                },
                onFailure = { e ->
                    val msg = e.message ?: "重新登录失败"
                    if (e is ForceLoginRequiredException) {
                        forcePrompt = msg
                    } else {
                        reloginMsg = "重新登录失败：$msg"
                        pwdError = msg
                        if (!pwdDialog) {
                            // 记住的密码失效（改过密码/账号被踢）时弹出来让他重填
                            pwdInput = session.savedPasswordFor().orEmpty()
                            pwdDialog = true
                        }
                    }
                },
            )
        }
    }

    /** 点「重新登录」：本机记着密码就直接登，没记过就弹框要密码 */
    fun startRelogin() {
        if (reloginBusy) return
        reloginMsg = ""
        pwdError = ""
        val saved = session.savedPasswordFor()
        if (saved.isNullOrBlank()) {
            pwdInput = ""
            pwdRemember = session.rememberPassword
            pwdDialog = true
        } else {
            pwdRemember = true
            doRelogin(saved, force = false)
        }
    }

    /* ---------------- 退出登录 / 切换账号（用户 m16146、m16405） ---------------- */

    var loggingOut by remember { mutableStateOf(false) }
    var accountsDialog by remember { mutableStateOf(false) }
    var accountsReveal by remember { mutableStateOf(false) }
    var switchMsg by remember { mutableStateOf("") }
    var switching by remember { mutableStateOf(false) }

    /** 账号表在界面上的刷新计数：删除 / 导入 / 切换之后就 +1，让列表重新读一次 prefs */
    var accountsVersion by remember { mutableStateOf(0) }

    /** 没记住密码的账号：点「登录」时就地要密码（用户 m16405：不要跳到登录页） */
    var switchPwdAccount by remember { mutableStateOf<SavedAccount?>(null) }
    var switchPwdInput by remember { mutableStateOf("") }

    /**
     * 切换账号时遇到的「已在别处登录」（用户 m17109）：弹「需要强制登录」确认框，
     * 确认后带 forceLogin 重发。此时旧账号已经退掉，取消就只能回登录页。
     */
    var switchForceAccount by remember { mutableStateOf<SavedAccount?>(null) }
    var switchForcePwd by remember { mutableStateOf("") }
    var switchForcePrompt by remember { mutableStateOf<String?>(null) }

    /** 从原版「安全管控平台」本地库导入账号（用户 m16405） */
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf("") }

    /*
     * 用户 m16789：「把从原版导入账号功能隐藏」；
     * 用户 m17028：解锁手势＝在「切换账号」弹窗里，5 秒内连续点击标题「切换账号」5 次。
     * 只在本进程内有效（重开应用又藏起来）。
     */
    var switchTaps by remember { mutableStateOf(0) }
    var lastSwitchTapAt by remember { mutableStateOf(0L) }
    var importUnlocked by remember { mutableStateOf(false) }

    /** 记一次标题「切换账号」上的连点：5 秒以内算同一串，凑满 5 下放出「从原版导入账号」。 */
    fun noteTitleTap() {
        val now = System.currentTimeMillis()
        switchTaps = if (now - lastSwitchTapAt <= SWITCH_TAP_WINDOW_MS) switchTaps + 1 else 1
        lastSwitchTapAt = now
        if (switchTaps >= 5) {
            importUnlocked = true
            switchTaps = 0
        }
    }

    /**
     * 退出登录：回登录界面。
     *
     * 走 [Graph] 的 `authRepository.logout()`：它先调服务端 `/inter-api/auth/logout`
     * 把票据作废、再清本机票据和 WebView 里的登录 cookie。
     * 老代码这里只有 `session.clearLogin()` —— 本机看着像退出了，服务端票据和
     * WebView cookie 都还在（用户 m16146：「点退出登录没效果」）。
     */
    fun doLogout() {
        if (loggingOut) return
        loggingOut = true
        scope.launch {
            runCatching { Graph.authRepository.logout() }
            loggingOut = false
            onLoggedOut()
        }
    }

    /**
     * 切到另一个登录过的账号（用户 m16146、m16405、m17109）。
     *
     * 用户 m16405：「点击登录按钮自动在当前页面切换登录，不需要跳转到登录页面」——
     * 所以这里不再回登录页：有密码直接在本页登录；没密码就弹个输入框就地补。
     *
     * 用户 m17109：①先把旧账号退掉（服务端也退）再登新账号，否则旧会话留在服务端，
     * 回头再切回来会被判成「已在别处登录」；②新账号已在别处登录时要弹「需要强制
     * 登录」确认框（旧代码只写一句红字，因而切不过去）。
     */
    fun switchTo(account: SavedAccount, password: String = account.password, force: Boolean = false) {
        if (switching) return
        if (password.isBlank()) {
            switchMsg = ""
            switchPwdInput = ""
            switchPwdAccount = account
            return
        }
        switchMsg = ""
        switching = true
        // 退出前的原账号信息，登录失败时用来把原账号登回去
        val oldUser = session.userName
        val oldHost = session.serverHost
        val oldHttps = session.useHttps
        val oldPwd = session.savedPasswordFor()
        val hadLogin = session.isLoggedIn
        scope.launch {
            // 先退旧账号（服务端 + 本地 + WebView cookie）
            if (hadLogin && !force) runCatching { Graph.authRepository.logout() }
            val r = Graph.authRepository.login(
                host = account.host,
                userName = account.user,
                password = password,
                useHttps = account.useHttps,
                forceLogin = force,
            )
            switching = false
            r.fold(
                onSuccess = {
                    switchPwdAccount = null
                    switchForceAccount = null
                    switchForcePrompt = null
                    accountsVersion++
                    reloginMsg = "已切换账号：${session.staffName.ifBlank { account.user }}"
                    val c = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val day0 = c.timeInMillis
                    runCatching {
                        Graph.patrolRepository.syncTasks(
                            taskStates = null,
                            keyword = null,
                            windowStart = day0,
                            windowEnd = day0 + 86_400_000L - 1L,
                            replaceAll = false,
                        )
                    }
                },
                onFailure = { e ->
                    val msg = e.message ?: "未知错误"
                    if (e is ForceLoginRequiredException) {
                        // 已在别处登录：弹框确认后带 forceLogin 重发（用户 m17109）
                        switchForceAccount = account
                        switchForcePwd = password
                        switchForcePrompt = msg
                    } else {
                        // 旧账号已经退掉了：能用记住的密码登回去就登回去，否则只能回登录页
                        var restored = false
                        if (hadLogin && !oldPwd.isNullOrBlank()) {
                            restored = runCatching {
                                Graph.authRepository.login(
                                    host = oldHost,
                                    userName = oldUser,
                                    password = oldPwd,
                                    useHttps = oldHttps,
                                    forceLogin = false,
                                )
                            }.isSuccess
                        }
                        if (restored) {
                            switchMsg = "切换失败：$msg（已退回原账号）"
                        } else {
                            switchMsg = "切换失败：$msg"
                            // 回登录页时把目标账号填好，省得再手输一遍
                            runCatching { session.userName = account.user }
                            onLoggedOut()
                        }
                    }
                },
            )
        }
    }

    /** 删掉一个登录过的账号（用户 m16405：「切换账户右侧加一个删除按钮」） */
    fun forgetAccount(account: SavedAccount) {
        session.removeAccount(account.user, account.host)
        accountsVersion++
        switchMsg = ""
    }

    /**
     * 账号的名字：存的是工号（登录时服务端没下发姓名）时，拿本机人员名录按工号换真名。
     * 用户 m17221：「又有两个账户该显示名称的地方变成显示工号了」。
     */
    fun nameOf(account: SavedAccount): String {
        if (!isPlaceholderName(account.staffName, account.user)) return account.staffName
        return runCatching { Graph.personDirectory.nameByCode(account.user) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: account.user
    }

    // 当前登录账号的名字也可能存成了工号（比如这次登录时服务端没给姓名）：补一次并落盘
    LaunchedEffect(Unit) {
        if (isPlaceholderName(session.staffName, session.userName)) {
            runCatching { Graph.personDirectory.nameByCode(session.userName) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let {
                    session.staffName = it
                    // SessionStore 里的字段不是 Compose 状态，补完名手动催一次重组
                    accountsVersion++
                }
        }
    }

    // 打开「切换账号」时，把列表里名字还是工号的条目补成真名并落盘（用户 m17221）
    LaunchedEffect(accountsDialog, accountsVersion) {
        if (!accountsDialog) return@LaunchedEffect
        var fixed = 0
        session.savedAccounts().forEach { a ->
            if (isPlaceholderName(a.staffName, a.user)) {
                val nm = nameOf(a)
                if (nm != a.user && nm.isNotBlank()) {
                    session.upsertAccount(a.user, a.password, nm, a.host, a.useHttps)
                    fixed++
                }
            }
        }
        if (fixed > 0) accountsVersion++
    }

    /**
     * 从原版「安全管控平台」的本地库导入账号（用户 m16405：「我手机上的两个安全管控平台的
     * 本地 db 数据库应该都保存了很多账户，你把账户导入到咱们的软件里」）。
     * 借 root 拷出原版的 `supPlant.db` 再读，见 [LegacyAccountImporter]。
     */
    fun importLegacyAccounts() {
        if (importing) return
        importing = true
        importMsg = ""
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    LegacyAccountImporter.import(
                        cacheDir = context.cacheDir,
                        externalDir = context.getExternalFilesDir(null),
                    )
                }
            }
            importing = false
            r.fold(
                onSuccess = { list ->
                    val n = session.importAccounts(list)
                    accountsVersion++
                    importMsg = if (n == 0) "原版库里没读到账号。" else "已导入 $n 个账号。"
                },
                onFailure = { e ->
                    val diag = LegacyAccountImporter.lastDiagnostic
                    importMsg = (e.message ?: "导入失败") + if (diag.isBlank()) "" else "［$diag］"
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        cache = Graph.patrolRepository.cacheSummary()
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmClear = false },
            title = { Text("清理本机缓存？") },
            text = {
                Text(
                    "将删除本机存的任务、区域、巡检项数据，并回到「一台还没同步过的手机」。" +
                        "登录状态、服务器地址、上传身份（imei / 手机号）会保留。\n\n" +
                        "注意：还没上传的签到与巡检结果也会一并删除，删了不可恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        scope.launch {
                            val n = runCatching { Graph.patrolRepository.clearCache() }
                                .getOrDefault(0)
                            cache = Graph.patrolRepository.cacheSummary()
                            clearing = false
                            confirmClear = false
                            clearMsg = "已清理 $n 条本机记录，点右上角同步图标可重新获取任务"
                        }
                    },
                ) { Text("确认清理") }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = { confirmClear = false },
                ) { Text("取消") }
            },
        )
    }

    // 没记住密码（或记住的密码已失效）时，就地补一次密码，不退出当前页面
    if (pwdDialog) {
        AlertDialog(
            onDismissRequest = { if (!reloginBusy) pwdDialog = false },
            title = { Text("重新登录") },
            text = {
                Column {
                    Text(
                        "账号 ${session.userName}　服务器 ${session.serverHost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pwdInput,
                        onValueChange = { pwdInput = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pwdRemember, onCheckedChange = { pwdRemember = it })
                        Spacer(Modifier.width(4.dp))
                        Text("记住密码（下次一键重新登录）", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (pwdError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            pwdError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reloginBusy && pwdInput.isNotBlank(),
                    onClick = { doRelogin(pwdInput, force = false) },
                ) { Text(if (reloginBusy) "登录中…" else "登录") }
            },
            dismissButton = {
                TextButton(enabled = !reloginBusy, onClick = { pwdDialog = false }) { Text("取消") }
            },
        )
    }

    forcePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { if (!reloginBusy) forcePrompt = null },
            title = { Text("需要强制登录") },
            text = { Text("$prompt\n\n继续会把该账号从其他设备上挤下线，是否继续？") },
            confirmButton = {
                TextButton(
                    enabled = !reloginBusy,
                    onClick = { doRelogin(reloginPwd, force = true) },
                ) { Text("强制登录") }
            },
            dismissButton = {
                TextButton(enabled = !reloginBusy, onClick = { forcePrompt = null }) { Text("取消") }
            },
        )
    }

    /* 切换账号遇到「已在别处登录」（用户 m17109）：旧账号此刻已经退掉了 */
    switchForcePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {
                if (!switching) {
                    // 旧账号已经退了，取消就只能回登录页
                    switchForcePrompt = null
                    switchForceAccount = null
                    onLoggedOut()
                }
            },
            title = { Text("需要强制登录") },
            text = { Text("$prompt\n\n继续会把该账号从其他设备上挤下线，是否继续？") },
            confirmButton = {
                TextButton(
                    enabled = !switching,
                    onClick = {
                        val a = switchForceAccount
                        switchForcePrompt = null
                        if (a != null) switchTo(a, switchForcePwd, force = true)
                    },
                ) { Text(if (switching) "登录中…" else "强制登录") }
            },
            dismissButton = {
                TextButton(
                    enabled = !switching,
                    onClick = {
                        switchForcePrompt = null
                        switchForceAccount = null
                        onLoggedOut()
                    },
                ) { Text("取消") }
            },
        )
    }

    /* 切换账号（用户 m16146、m16405）：列出登录过的账号，右侧「登录」「删除」 */
    if (accountsDialog) {
        val accounts = remember(accountsVersion) { session.savedAccounts() }
        AlertDialog(
            onDismissRequest = {
                accountsDialog = false
                switchTaps = 0
            },
            title = {
                // 用户 m17028：5 秒内连点标题「切换账号」5 次 → 放出「从原版导入账号」。
                // indication = null：不给水波纹，免得一眼看出标题能点
                Text(
                    "切换账号",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { noteTitleTap() },
                )
            },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (accounts.isEmpty()) {
                        Text("本机还没有登录过的账号。", style = MaterialTheme.typography.bodyMedium)
                    }
                    accounts.forEachIndexed { index, a ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        val current = a.user == session.userName && a.host == session.serverHost
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                /*
                                 * 姓名 / 工号 / 密码 各占一行。
                                 * 原来写「姓名（工号）」一行，三个字的名字（周八、孙七…）
                                 * 加上括号里的工号就超出左边这一栏，被右边的「登录/删除」按钮
                                 * 挤掉后半截——用户 m16789 看到的「有人能显示出工号，有人不能」
                                 * 就是这个原因（不是数据里没有工号）；挤在一行时连密码点也会被
                                 * 挤没，所以干脆一行一项。
                                 */
                                Text(
                                    nameOf(a) + if (current) " · 当前" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "工号 ${a.user}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Text(
                                    if (accountsReveal) {
                                        "密码 ${a.password.ifBlank { "（没记密码）" }}"
                                    } else {
                                        "密码 ${a.maskedPassword()}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            TextButton(
                                enabled = !switching,
                                onClick = { switchTo(a) },
                            ) { Text("登录") }
                            TextButton(
                                enabled = !switching,
                                onClick = { forgetAccount(a) },
                            ) { Text("删除") }
                        }
                    }
                    if (switching) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在切换…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (switchMsg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            switchMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 用户 m16789：导入入口默认藏起来，连点 5 次「切换账号」才出现
                    if (importUnlocked) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        // 用户 m16405：原版两个 app 的本地库里存着登录过的账号，一次性导进来
                        TextButton(
                            enabled = !importing,
                            onClick = { importLegacyAccounts() },
                        ) {
                            if (importing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (importing) "正在导入…" else "从原版导入账号")
                        }
                        if (importMsg.isNotBlank()) {
                            Text(
                                importMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { accountsReveal = !accountsReveal }) {
                    Text(if (accountsReveal) "隐藏密码" else "显示密码")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountsDialog = false }) { Text("取消") }
            },
        )
    }

    /* 没记住密码的账号：就地输密码切换（用户 m16405：不跳登录页） */
    switchPwdAccount?.let { a ->
        AlertDialog(
            onDismissRequest = { if (!switching) switchPwdAccount = null },
            title = { Text("登录 ${a.label}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = switchPwdInput,
                        onValueChange = { switchPwdInput = it },
                        label = { Text("密码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (switchMsg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            switchMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !switching && switchPwdInput.isNotBlank(),
                    onClick = { switchTo(a, switchPwdInput) },
                ) { Text(if (switching) "登录中…" else "登录") }
            },
            dismissButton = {
                TextButton(enabled = !switching, onClick = { switchPwdAccount = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            // 用户 m07325：与主页统一 —— 不要棕色块、不要和状态栏分层。
            // 先 background 再 statusBarsPadding，背景一直画到状态栏后面。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "我的",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            /*
             * 账号信息 + 服务器合成一块（用户 m16146：「服务器可以和顶部的账户信息合并到一个块里，
             * 这样重新登录和退出登录按钮也不会显得拥挤」）。
             * 原来单独那块里的「人员 ID / 公司 ID」只有内部字段名，对用户没有任何意义，删掉。
             */
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)) {
                    Text(
                        session.staffName.ifBlank { session.userName },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "账号 ${session.userName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "服务器 ${session.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (reloginMsg.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            reloginMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (switchMsg.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            switchMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 三个按钮等宽平分（weight），不再和上面的长名字挤在同一行：
                    // 之前两个 TextButton 与占满剩余宽度的名字同处一个 Row，
                    // 「退出登录」的点击区被压得很窄（用户 m16146：点退出登录没效果）。
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = !reloginBusy,
                            onClick = { startRelogin() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (reloginBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "重新登录",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                        TextButton(
                            enabled = !reloginBusy && !switching,
                            onClick = {
                                switchMsg = ""
                                accountsReveal = false
                                // 解锁靠弹窗标题上的连点（用户 m17028），点按钮照旧直接弹窗
                                switchTaps = 0
                                accountsDialog = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.SwitchAccount,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "切换账号",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                        TextButton(
                            enabled = !reloginBusy && !loggingOut,
                            onClick = { doLogout() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (loggingOut) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Logout,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "退出登录",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            /*
             * 上传身份设置（用户 m04512 第 6 条）。
             *
             * 原版每条巡检项都带 `imei`（DeviceInfoUtil.getImeiWithManual）和
             * `phoneNum`（AccountInfo.getMobile）。Android 10 以后系统不再给 IMEI，
             * 所以原版实际用的就是软件设置里手填的那串 —— 这里给出同样的入口。
             */
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("上传身份", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "随每条巡检项上报的 imei / phoneNum，对应原版「软件设置」里的那两项。" +
                            "Android 10 起系统不再提供 IMEI，请在此手填。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = imeiManual,
                        onValueChange = {
                            imeiManual = it
                            session.imeiManual = it
                        },
                        label = { Text("IMEI（手填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mobile,
                        onValueChange = {
                            mobile = it
                            session.mobile = it
                        },
                        label = { Text("手机号 phoneNum") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "当前上报值：imei=${session.uploadImei.ifBlank { "（空）" }}  " +
                            "phoneNum=${session.uploadPhoneNum.ifBlank { "（空）" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    /*
                     * 上报人员（用户 m07827）——用户反馈：和上面的 imei / 手机号合并到同一组。
                     *
                     * `巡检任务.xlsx` 里「跟随用户固定」的就是这几个值：主表的
                     * 执行人/完成人（人员编码 + 员工姓名）、区域表的签到人、明细表的修改人。
                     * 默认跟随登录账号；要换成别人（代做）时在这里固定，巡检任务与上传都用它。
                     */
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(18.dp))
                    Text("上报人员", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "巡检任务与服务端记录里的「执行人 / 完成人 / 签到人」用这里的人，" +
                            "区域作业面板里的巡检人也默认取它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动跟随登录账号", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭后可固定成另一个人（代做场景）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = reporterFollow,
                            onCheckedChange = {
                                reporterFollow = it
                                session.reporterFollowAccount = it
                            },
                        )
                    }
                    /*
                     * 人员名录（用户 m17358）：跟随登录账号时也要能看到/刷新它——
                     * 账号列表补真名、任务里的签到人、查询候选都靠这份名录，与「上报人员」选谁无关。
                     */
                    Spacer(Modifier.height(12.dp))
                    val dirCached = remember(dirVersion) { Graph.personDirectory.isCached }
                    val dirCount = remember(dirVersion) { Graph.personDirectory.count }
                    val dirUpdated = remember(dirVersion) { Graph.personDirectory.lastUpdatedAt }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                if (dirCached) {
                                    append("本机已存人员名录 $dirCount 人")
                                    if (dirUpdated > 0L) {
                                        append(" · 最后更新 ${formatDirectoryTime(dirUpdated)}")
                                    }
                                    append("。")
                                } else {
                                    append("本机还没有人员名录，点右边「更新名录」拉一次全量（约 9200 人）。")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { refreshDirectory() }, enabled = !dirBusy) {
                            if (dirBusy) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("更新名录")
                            }
                        }
                    }
                    if (dirMsg.isNotBlank()) {
                        Text(
                            dirMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!reporterFollow) {
                        /*
                         * 用户 m08022：表格里只有「工号（人员编码）」是稳定且与用户一一对应的，
                         * 导出里没有服务端内部 ID。所以这里只让用户填工号，内部 ID 由人员名录换算。
                         * 用户 m14256：查询改成**双向**——工号查名字、名字查工号；
                         * 同名的人不止一个时弹列表让用户自己挑。
                         */
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = reporterCode,
                            onValueChange = {
                                reporterCode = it
                                reporterMsg = ""
                            },
                            label = { Text("工号或姓名") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { lookUpReporter(reporterCode) },
                            enabled = !looking && reporterCode.isNotBlank(),
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            if (looking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (looking) "正在查询…" else "查询并保存")
                        }
                        if (reporterMsg.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                reporterMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // 重名时让用户自己挑（用户 m14256）；标题与排版按用户 m16405：
                        // 「查询到 N 个」，内容分行、不要括号、每行等宽、行间加分割线
                        pickCandidates?.let { list ->
                            AlertDialog(
                                onDismissRequest = { pickCandidates = null },
                                title = { Text("查询到 ${list.size} 个") },
                                text = {
                                    Column(
                                        Modifier
                                            .heightIn(max = 360.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        list.forEachIndexed { index, person ->
                                            if (index > 0) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            }
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        adoptReporter(person)
                                                        pickCandidates = null
                                                    }
                                                    .padding(vertical = 10.dp),
                                            ) {
                                                Text(
                                                    person.name.orEmpty().ifBlank { "（无名）" },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                if (!person.code.isNullOrBlank()) {
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        "工号 ${person.code}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                person.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        "手机 $phone",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                // 用户 m17358：查询结果多显示部门 / 岗位（接口没有邮箱字段）
                                                person.deptPosition?.let { dp ->
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        dp,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                if (!person.onWork) {
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        "非在职",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                        maxLines = 1,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { pickCandidates = null }) { Text("取消") }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "当前上报：${session.reporterDisplayName}" +
                            "（工号 ${session.reporterDisplayCode.ifBlank { "无" }}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            /*
             * 完成人签字（用户 m05038 第 1 条）。
             *
             * 用户原话：「把签字功能实装到我们的程序上吧，但不要真的要求签字，
             * 选中一个图片作为签字图片就行，签字跟随账户，不要多账户共享。」
             *
             * 所以这里不弹手写板、提交时也不拦人：选一张图当签字图，按账号存（SignStore），
             * 上传时打进 zip 的 `pics/xjTaskPic_*.jpg` 并写 `potrolTask.signPicPath` ——
             * 与原版离线补传的打包形状完全一致（抓包实测）。
             */
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("完成人签字", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "上传任务时随包提交的签字图片，对应原版提交时的「完成人签字」。" +
                            "不要求现场手写，选一张图片即可；图片按账号保存，换账号互不影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))

                    val ctx = LocalContext.current
                    var signPath by remember(session.accountKey) {
                        mutableStateOf(
                            Graph.signStore.fileFor(session)
                                .takeIf { it.isFile && it.length() > 0L }?.absolutePath,
                        )
                    }
                    var signMsg by remember(session.accountKey) { mutableStateOf("") }
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) { uri ->
                        if (uri != null) {
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val input = ctx.contentResolver.openInputStream(uri)
                                            ?: throw IllegalArgumentException("读不到这张图片")
                                        input.use { Graph.signStore.save(session.accountKey, it) }
                                    }
                                }
                                result
                                    .onSuccess {
                                        signPath = it.absolutePath
                                        signMsg = "已保存：${it.name}（${it.length() / 1024} KB）"
                                    }
                                    .onFailure {
                                        signMsg = "保存失败：${it.message ?: "未知错误"}"
                                    }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bmp = remember(signPath) {
                            signPath?.let { BitmapFactory.decodeFile(it) }
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "完成人签字",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surface),
                            )
                            Spacer(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (signPath == null) "还没有签字图片" else "签字图片已就绪",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (signPath == null) {
                                    "上传时不会带签字（不影响上传）"
                                } else {
                                    "上传时放进 pics/ 并写 signPicPath"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { picker.launch("image/*") },
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) { Text(if (signPath == null) "选择图片" else "重新选择") }
                        if (signPath != null) {
                            OutlinedButton(
                                onClick = {
                                    Graph.signStore.clear(session.accountKey)
                                    signPath = null
                                    signMsg = "已清除签字图片"
                                },
                                shape = MaterialTheme.shapes.extraLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) { Text("清除") }
                        }
                    }
                    if (signMsg.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            signMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            /*
             * 简化巡检（用户 m08985 第 3 条）：
             * 打开后点任务列表里的某个时间段，直接进一页把
             * 开始时间 / 每个巡检点的签到时间 / 提交时间都摆出来，填完一键上传提交。
             */
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("简化巡检", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "打开后，点任务列表里任意时间段的巡检任务，进入一页表单：" +
                            "设置开始时间、每个巡检点的签到时间、结束任务时间，填完直接上传并结束任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("进入简化表单", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关掉则回到原来的任务详情页",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = simplified,
                            onCheckedChange = {
                                simplified = it
                                session.simplifiedPatrol = it
                            },
                        )
                    }
                }
            }

            /*
             * 本机缓存清理（用户 m06888：删掉「离线数据上传」，换成「清理缓存」）。
             *
             * 删掉的是 Room 里的任务 / 区域 / 设备 / 巡检项；
             * SessionStore 里的登录态与上传身份不动，所以清完不用重新登录。
             */
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("本机缓存", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cache?.let { (t, a, w) ->
                            "已缓存：任务 $t 条 · 区域 $a 个 · 巡检项 $w 项"
                        } ?: "正在统计…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (clearMsg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            clearMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = !clearing,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        if (clearing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (clearing) "正在清理…" else "清理缓存")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** 人员名录的「最后更新」时间（用户 m17358） */
private fun formatDirectoryTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        .format(java.util.Date(millis))

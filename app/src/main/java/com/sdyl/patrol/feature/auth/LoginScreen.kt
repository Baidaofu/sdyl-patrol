package com.sdyl.patrol.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sdyl.patrol.Graph
import com.sdyl.patrol.core.data.SavedAccount
import com.sdyl.patrol.core.data.SessionStore
import com.sdyl.patrol.core.network.ForceLoginRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val host: String = "",
    val userName: String = "",
    val password: String = "",
    /** 记住密码（被踢出后可一键重新登录，见「我的 → 重新登录」） */
    val remember: Boolean = true,
    val useHttps: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
    /** 非空表示需要用户确认"强制登录"，内容是提示文案 */
    val forcePrompt: String? = null,
)

class LoginViewModel : ViewModel() {
    private val session: SessionStore = Graph.session
    private val repo: AuthRepository = Graph.authRepository

    private val _state = MutableStateFlow(
        LoginUiState(
            host = session.serverHost,
            useHttps = session.useHttps,
            // 上次登录的账号 + 记住的密码直接预填：重新登录时只差点一下「登录」
            userName = session.userName,
            password = session.savedPasswordFor().orEmpty(),
            remember = session.rememberPassword,
        )
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onHostChange(v: String) { _state.value = _state.value.copy(host = v) }

    fun onUserChange(v: String) {
        // 用户 m16146：「密码自动跟随输入的账号变化」——换账号就把该账号记住的密码带出来
        passwordTouched = false
        _state.value = _state.value.copy(userName = v)
        autofillPassword()
    }

    fun onPasswordChange(v: String) {
        passwordTouched = true
        _state.value = _state.value.copy(password = v)
    }

    /** 用户是否手动改过密码框；改过之后就不再被账号自动带出的密码覆盖 */
    private var passwordTouched = false

    /** 按当前「服务器 + 账号」把记住的密码填进密码框（没有就填空） */
    private fun autofillPassword() {
        if (passwordTouched) return
        val s = _state.value
        val pwd = session
            .savedPasswordFor(s.userName, SessionStore.normalizeHost(s.host))
            .orEmpty()
        _state.value = _state.value.copy(password = pwd)
    }

    /** 登录页直接选一个登录过的账号（用户 m16146）：填服务器 + 账号，密码跟着带出来 */
    fun pickAccount(account: SavedAccount) {
        passwordTouched = false
        _state.value = _state.value.copy(
            host = account.host,
            userName = account.user,
            useHttps = account.useHttps,
            password = account.password,
        )
    }

    fun onHttpsChange(v: Boolean) { _state.value = _state.value.copy(useHttps = v) }
    fun onRememberChange(v: Boolean) { _state.value = _state.value.copy(remember = v) }
    fun consumeError() { _state.value = _state.value.copy(error = null) }

    /**
     * 吃掉「登录成功」这个一次性事件（用户 m16405）。
     *
     * 这个 ViewModel 挂在 Activity 上，退出登录再回到登录页时**还是同一个实例**；
     * 之前 `success` 一直是 true，登录页刚一出现就把 onLoggedIn() 又调了一次 ——
     * 现象就是用户说的「点退出登录会回到主页，而不是登录界面」。
     */
    fun consumeSuccess() { _state.value = _state.value.copy(success = false) }

    fun cancelForceLogin() { _state.value = _state.value.copy(forcePrompt = null) }

    /** @param force 用户已在弹框里确认"强制登录" */
    fun login(force: Boolean = false) {
        val s = _state.value
        if (s.host.isBlank() || s.userName.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(error = "请填写服务器地址、用户名和密码")
            return
        }
        _state.value = s.copy(loading = true, error = null, forcePrompt = null)
        viewModelScope.launch {
            repo.login(s.host, s.userName, s.password, s.useHttps, force).fold(
                onSuccess = {
                    // 记住密码：勾了就记在本机（供「我的 → 重新登录」一键重登），没勾就清掉
                    if (s.remember) {
                        session.rememberUserPassword(
                            s.userName,
                            SessionStore.normalizeHost(s.host),
                            s.password,
                        )
                    } else {
                        session.forgetUserPassword()
                    }
                    _state.value = _state.value.copy(loading = false, success = true)
                },
                onFailure = { e ->
                    if (e is ForceLoginRequiredException) {
                        _state.value = _state.value.copy(
                            loading = false,
                            forcePrompt = e.message ?: "该账号已在其他设备登录",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = e.message ?: "登录失败",
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val vm: LoginViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }
    var accountsOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // 登录成功是一次性事件：先吃掉再跳，避免退出登录后回到登录页又被这个陈旧状态弹回主页
    LaunchedEffect(state.success) {
        if (state.success) {
            vm.consumeSuccess()
            onLoggedIn()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("安全管控平台", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "移动巡检 · 轻量版",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = vm::onHostChange,
                        label = { Text("服务器地址") },
                        placeholder = { Text("supos.example.com:8443") },
                        leadingIcon = { Icon(Icons.Filled.Dns, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("使用 HTTPS", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = state.useHttps, onCheckedChange = vm::onHttpsChange)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.userName,
                        onValueChange = vm::onUserChange,
                        label = { Text("用户名") },
                        leadingIcon = { Icon(Icons.Filled.Badge, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 用户 m16146：登录过的账号可以直接选（选了会自动填账号，密码跟着账号变）
                    val savedAccounts = Graph.session.savedAccounts()
                    if (savedAccounts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { accountsOpen = true }) {
                                Icon(
                                    Icons.Filled.Badge,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("选择已登录的账号（${savedAccounts.size}）")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = vm::onPasswordChange,
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Filled.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.remember, onCheckedChange = vm::onRememberChange)
                        Spacer(Modifier.width(4.dp))
                        Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "被踢出后可一键重新登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.login() },
                enabled = !state.loading,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("登录", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(40.dp))
        }

        state.forcePrompt?.let { prompt ->
            AlertDialog(
                onDismissRequest = vm::cancelForceLogin,
                title = { Text("需要强制登录") },
                text = {
                    Text("$prompt\n\n继续会把该账号从其他设备上挤下线，是否继续？")
                },
                confirmButton = {
                    TextButton(onClick = { vm.login(force = true) }) { Text("强制登录") }
                },
                dismissButton = {
                    TextButton(onClick = vm::cancelForceLogin) { Text("取消") }
                },
            )
        }

        /* 选择登录过的账号（用户 m16146） */
        if (accountsOpen) {
            val accounts = Graph.session.savedAccounts()
            AlertDialog(
                onDismissRequest = { accountsOpen = false },
                title = { Text("选择账号") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (accounts.isEmpty()) {
                            Text("本机还没有登录过的账号。", style = MaterialTheme.typography.bodyMedium)
                        }
                        accounts.forEach { a ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                onClick = {
                                    vm.pickAccount(a)
                                    accountsOpen = false
                                },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        a.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "密码 ${if (a.password.isBlank()) "（没记密码）" else "•".repeat(a.password.length)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { accountsOpen = false }) { Text("关闭") }
                },
            )
        }
    }
}

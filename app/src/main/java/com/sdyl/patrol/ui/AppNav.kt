package com.sdyl.patrol.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.sdyl.patrol.Graph
import com.sdyl.patrol.feature.auth.LoginScreen
import com.sdyl.patrol.feature.me.MeScreen
import com.sdyl.patrol.feature.patrol.PatrolNav
import com.sdyl.patrol.feature.video.VideoNav

private enum class Tab(val label: String, val icon: ImageVector) {
    PATROL("巡检", Icons.Filled.Checklist),
    VIDEO("视频", Icons.Filled.Videocam),
    ME("我的", Icons.Filled.Person),
}

@Composable
fun AppRoot() {
    var loggedIn by remember { mutableStateOf(Graph.session.isLoggedIn) }

    if (!loggedIn) {
        LoginScreen(onLoggedIn = { loggedIn = true })
    } else {
        MainScaffold(onLoggedOut = {
            // 换账号时清掉主页选中的日期，避免上一个账号的选日带到下一个账号
            Graph.patrolSelectedDayStart = null
            loggedIn = false
        })
    }
}

@Composable
private fun MainScaffold(onLoggedOut: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.PATROL) }
    // 用户反馈：在主页选好日期后切到「我的」再切回来就会跳回今天。
    // 原因是 when(tab) 切走时整个子树的 remember/rememberSaveable 被丢掉，
    // PatrolNav 的 NavController（连同它名下的 TaskListViewModel）被重建。
    // 用 SaveableStateHolder 把每个标签页的 saveable 状态留住，切回来即恢复。
    val tabStateHolder = rememberSaveableStateHolder()
    // 视频横屏时把底部导航栏收起来，让播放页真正铺满
    val hideBottomBar by UiMode.videoLandscape

    Scaffold(
        // 顶层 Scaffold**不要**替子页面申请系统栏内边距：子页面自己的顶栏会再加一次状态栏高度，
        // 标题上方就凭空多出一整条空档（用户 m07325 说的「文字顶部还有很大一块留白」就是这个）。
        // 底部导航栏自己会处理手势条内边距，内容仍由 bottomBar 高度撑开。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!hideBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            tabStateHolder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.PATROL -> PatrolNav()
                    Tab.VIDEO -> VideoNav()
                    Tab.ME -> MeScreen(onLoggedOut = onLoggedOut)
                }
            }
        }
    }
}

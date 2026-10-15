package com.sdyl.patrol.feature.video

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sdyl.patrol.Graph
import com.sdyl.patrol.feature.video.model.VideoModes
import com.sdyl.patrol.ui.UiMode

/**
 * 播放页：直接内嵌服务端下发的 H5（unitPlayer），协议与参数完全照抄原 App。
 *
 * 为什么不自研播放器：原版就没有原生播放器，全靠 WebView 跑 H5，
 * MSE 走 WebSocket + fMP4、回放走 HTTP-FLV，协议栈都在页面里。
 * 我们复刻同一路径，既省事又保证与服务端版本兼容。
 *
 * 横屏三条要点（之前「横屏播放不太正常」的根因）：
 *  1. Activity 必须声明 configChanges，否则旋转会重建 Activity，Compose 状态被重置、
 *     WebView 被销毁 → 方向抖回竖屏 + 流断开；
 *  2. 还原方向只能在真正离开页面时做，不能挂在 `DisposableEffect(landscape)` 的
 *     onDispose 上（那个 onDispose 每次切换都会跑，等于刚转过去就转回来）；
 *  3. H5 播放器自带的全屏按钮走 requestFullscreen，必须实现
 *     WebChromeClient.onShowCustomView/onHideCustomView，否则点了没反应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPlayerScreen(
    channel: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var landscape by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(true) }

    // H5 自带全屏（requestFullscreen）时挂上来的 View，以及它对应的宿主容器
    var customView by remember { mutableStateOf<View?>(null) }
    var hostRef by remember { mutableStateOf<FrameLayout?>(null) }

    val url = remember(channel) {
        VideoModes.liveUrl(Graph.session.baseUrl, channel)
    }

    /** 把 H5 的全屏视图摘掉，并把控制权交回页面 */
    fun exitCustomView() {
        val view = customView ?: return
        hostRef?.removeView(view)
        customView = null
        landscape = false
    }

    BackHandler(enabled = landscape || customView != null) {
        if (customView != null) exitCustomView() else landscape = false
    }

    // 方向：只在 landscape 变化时设置一次，不要在 onDispose 里还原
    LaunchedEffect(landscape) {
        // 让底部导航栏跟着收起/展开
        UiMode.videoLandscape.value = landscape
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (landscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 离开页面：还原方向 + 恢复系统栏
    DisposableEffect(Unit) {
        onDispose {
            UiMode.videoLandscape.value = false
            val window = activity?.window
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Scaffold(
        topBar = {
            if (!landscape) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "通道 $channel · MSE 实时流",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { landscape = !landscape }) {
                            Icon(Icons.Filled.ScreenRotation, contentDescription = "横屏播放")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (landscape) PaddingValues(0.dp) else padding)
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val host = FrameLayout(ctx)
                    hostRef = host
                    val web = WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        // 播放页是 https，取流地址是内网 http（如 intranet.example.com:8282），必须放开混合内容
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        keepScreenOn = true

                        webViewClient = object : WebViewClient() {
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?,
                            ) {
                                // 企业内网自签证书，与原 App 行为一致：直接放行
                                handler?.proceed()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    progress = false
                                    pageError = "加载失败：${error?.description ?: "未知错误"}"
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                val host2 = hostRef
                                if (host2 == null || view == null || customView != null) {
                                    callback?.onCustomViewHidden()
                                    return
                                }
                                customView = view
                                host2.addView(
                                    view,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                // H5 全屏一律配合横屏，否则竖屏里全屏没有意义
                                landscape = true
                            }

                            override fun onHideCustomView() {
                                exitCustomView()
                            }
                        }
                        loadUrl(url, webHeaders())
                    }
                    host.addView(web)
                    host
                },
                onRelease = { host ->
                    (0 until host.childCount)
                        .map { host.getChildAt(it) }
                        .filterIsInstance<WebView>()
                        .forEach { it.destroy() }
                    hostRef = null
                },
            )

            if (progress) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            pageError?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }

            // 横屏时没有 TopAppBar，给一个悬浮的退出按钮。
            // 放正上方：H5 播放页四角都被它自己的控件占了（左上返回、右上关闭、
            // 右下静音），只有顶边中间是空的，放这儿不会和它打架。
            if (landscape) {
                Surface(
                    onClick = {
                        if (customView != null) exitCustomView() else landscape = false
                    },
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("退出横屏", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 原 App 的 `BaseX5WebViewActivity.loadUrl()` 就是这么干的：
 * 把 cookie 与 Authorization 作为**主文档请求头**发过去。
 * 只设 CookieManager 不够——播放页的首屏判定看的是这两个头。
 */
private fun webHeaders(): Map<String, String> {
    val host = Graph.session.serverHost
    val ticket = Graph.session.ticket
    if (host.isBlank() || ticket.isBlank()) return emptyMap()
    return mapOf(
        "Cookie" to "suposTicket=$ticket; suposTicketForFrontend=$ticket; language=zh_CN",
        "Authorization" to "Bearer $ticket",
    )
}

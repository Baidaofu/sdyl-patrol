package com.sdyl.patrol.ui

import androidx.compose.runtime.mutableStateOf

/**
 * 极简全局 UI 状态桥。
 *
 * 视频横屏时要把 App 自己的底部导航栏收起来（否则横屏下面永远压着一条导航栏，
 * 视频没法铺满）。但底部导航栏在 `MainScaffold`、播放页在 feature/video 深处，
 * 中间隔着 NavHost，与其层层透传，不如用一个可观察的全局状态。
 */
object UiMode {

    /** 播放页是否处于横屏全屏状态 */
    val videoLandscape = mutableStateOf(false)
}

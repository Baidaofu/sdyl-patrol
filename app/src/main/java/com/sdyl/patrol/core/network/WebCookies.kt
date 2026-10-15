package com.sdyl.patrol.core.network

import android.webkit.CookieManager

/**
 * 把登录票据同步进 WebView 的 cookie。
 *
 * 实测（PC 直连）：`POST /inter-api/auth/login` 的响应头带
 * `Set-Cookie: suposTicket=<ticket>; Path=/; HttpOnly; SameSite=Lax`。
 * 服务端下发的那套 H5（视频播放页 unitPlayer 等）就是靠这个 cookie 认身份的——
 * 原 App 之所以要 CookieUtil/CookieTempUtil 做「双轨 cookie 同步」也是这个原因。
 * 不同步的话，WebView 里打开的播放页拿不到流。
 */
object WebCookies {

    /** 登录成功后调用；重复调用无副作用 */
    fun sync(host: String, ticket: String, https: Boolean = true) {
        if (host.isBlank() || ticket.isBlank()) return
        val origin = "${if (https) "https" else "http"}://$host"
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        runCatching {
            cm.setAcceptCookie(true)
            cm.setCookie(origin, "suposTicket=$ticket; path=/; SameSite=Lax")
            cm.setCookie(origin, "suposTicketForFrontend=$ticket; path=/; SameSite=Lax")
            // 播放页靠这个决定中英文（见 unitPlayer 页面里的 getDplayerLanguage）
            cm.setCookie(origin, "language=zh_CN; path=/")
            cm.flush()
        }
    }

    /** 退出登录时清掉，别把票据留给下一个人 */
    fun clear() {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        runCatching {
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}

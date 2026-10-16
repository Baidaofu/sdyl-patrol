package com.sdyl.patrol

import android.app.Application
import com.sdyl.patrol.core.data.PersonDirectory
import com.sdyl.patrol.core.data.SessionStore
import com.sdyl.patrol.core.data.SignStore
import com.sdyl.patrol.core.data.db.SdylDatabase
import com.sdyl.patrol.core.network.ApiClient
import com.sdyl.patrol.core.network.WebCookies
import com.sdyl.patrol.feature.auth.AuthRepository
import com.sdyl.patrol.feature.patrol.PatrolRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SdylApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/**
 * 极简依赖容器。第 1 步（垂直切片）刻意不引入 Hilt，
 * 等 Room / WorkManager 进来后再评估是否替换。
 */
object Graph {
    lateinit var session: SessionStore
        private set
    lateinit var api: ApiClient
        private set
    lateinit var db: SdylDatabase
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var patrolRepository: PatrolRepository
        private set
    lateinit var personDirectory: PersonDirectory
        private set
    /** 完成人签字图（用户 m05038）：按账号分文件存，多账号不共享 */
    lateinit var signStore: SignStore
        private set

    /**
     * 主页当前选中的日期（当天 00:00 时间戳），只放内存、不落盘。
     *
     * 修「在主页选好日期 → 切到『我的』→ 切回主页又跳回今天」：底部标签切换时
     * PatrolNav / TaskListViewModel 可能被重建，靠这个进程级的值把选中的日期留住。
     */
    @Volatile
    var patrolSelectedDayStart: Long? = null

    val isReady: Boolean get() = ::session.isInitialized

    fun init(app: Application) {
        if (::session.isInitialized) return
        val s = SessionStore(app)
        val c = ApiClient(s)
        val database = SdylDatabase.build(app)
        session = s
        api = c
        db = database
        authRepository = AuthRepository(c, s)
        signStore = SignStore(app)
        patrolRepository = PatrolRepository(c, s, database, app.cacheDir, signStore)
        personDirectory = PersonDirectory(c, app.filesDir)
        // 一次性对账：历史任务（服务端已完成）的区域不该再算「本机待上传」。
        // 老逻辑把「服务端说已签到」当成「本机签到」，导致「我的」页显示上千条假待上传。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { database.areaDao().reconcileFinishedUploadFlags() }
        }
        // 冷启动时票据已在 SP 里（不会走 login()），但 WebView 的 cookie 是内存态，
        // 必须补一次同步，否则服务端 H5（视频播放页）会跳登录页。
        if (s.isLoggedIn) WebCookies.sync(s.serverHost, s.ticket, s.useHttps)
    }
}

package com.sdyl.patrol.core.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 从原版「安全管控平台」的本地库里导入登录过的账号（用户 m16405）。
 *
 * 原版把登录过的账号（工号、姓名、**明文密码**、服务器地址）存在自己私有目录的
 * `supPlant.db` 的 `ACCOUNT_INFO` 表里 —— 那两个包名是
 * `com.supcon.supplant`（安全管控平台）和 `com.supcon.supplanu`。
 *
 * 我们读不到别的应用私有目录，所以借 root（这台机器是 KernelSU）把库拷进自己的
 * cache 再读；没 root 就抛 [NotRootedException]，由界面把话说清楚。
 *
 * 只读原版的库，不写、不改；导入的账号写进本应用自己的 prefs（[SessionStore.importAccounts]）。
 */
object LegacyAccountImporter {
    /** 手机上装的两个原版（用户 m16405：「两个安全管控平台的本地 db 数据库」） */
    val PACKAGES = listOf("com.supcon.supplant", "com.supcon.supplanu")

    private const val DB_NAME = "supPlant.db"
    private const val TABLE = "ACCOUNT_INFO"

    /** 别人（adb / 文件管理器）把原版的库拷到本应用可读目录时，放这里的文件名前缀 */
    private const val PREPLACED_DIR = "legacy"

    class NotRootedException :
        Exception("没有 root 权限，读不到原版的本地库。可以在 KernelSU / Magisk 里给本应用授权，或者让别人用 adb 把原版的 supPlant.db 拷到本应用可读目录。")

    class NothingFoundException(detail: String = "") :
        Exception("没找到原版的账号库" + if (detail.isBlank()) "" else "（$detail）")

    class EmptyLibraryException :
        Exception("读到了原版的库，但里面没有账号记录（原版可能没登录过账号）")

    /**
     * 借 root 跑命令的参数组合，按顺序试。
     *
     * `-M`（`--mount-master`）= 在**全局挂载命名空间**里跑。用户 m17600 实测：
     * 应用自己的挂载命名空间里看不到别的应用私有目录（`cat /data/data/com.supcon.supplanu/…`
     * 报 `No such file or directory`，而在 adb 的全局命名空间里文件明明在），
     * 所以必须先试 `su -M -c`，不行再退回普通的 `su -c`。
     */
    private val SU_ARG_SETS = listOf(listOf("-M", "-c"), listOf("-c"))

    /** 最近一次失败的原因，出错时拼进异常消息，方便定位（用户 m17600） */
    var lastDiagnostic: String = ""
        private set

    /** 借 root 跑一条命令，返回 exit=0 时的输出；两种参数组合都失败则返回 null */
    private fun rootText(cmd: String): String? {
        lastDiagnostic = ""
        SU_ARG_SETS.forEach { args ->
            val out = runCatching {
                val p = ProcessBuilder(listOf("su") + args + listOf(cmd))
                    .redirectErrorStream(true)
                    .start()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                if (p.waitFor() == 0) text else null
            }.getOrNull()
            if (out != null) return out
        }
        return null
    }

    /** 能不能拿到 root（KernelSU 第一次会弹授权框，用户同意后才会返回 uid=0） */
    fun isRootAvailable(): Boolean = rootText("id")?.contains("uid=0") == true

    /**
     * 读两个原版的库，返回导入的账号列表。
     * 同一「服务器 + 账号」只留一条（后读到的覆盖先读到的）。
     *
     * 两条来源，先看有没有现成的、再借 root：
     * 1. [cacheDir]/legacy/`supPlant_<包名>.db`（或 [externalDir]/legacy/…）——事先拷好的副本；
     * 2. `/data/data/<包名>/databases/supPlant.db` —— 借 root 用 `cat` 流出来（用户 m17600：
     *    不用 `cp` 写别人目录，免得被 SELinux/权限卡住；只读源文件，字节流由本应用自己落盘）。
     */
    fun import(cacheDir: File, externalDir: File? = null): List<SavedAccount> {
        val out = LinkedHashMap<String, SavedAccount>()
        val notes = mutableListOf<String>()
        var sawDb = false

        // 1) 事先拷到本应用可读目录的副本（内部 cache 优先，其次是外部私有目录）
        val preDirs = listOfNotNull(
            File(cacheDir, PREPLACED_DIR),
            externalDir?.let { File(it, PREPLACED_DIR) },
        )
        PACKAGES.forEach { pkg ->
            preDirs.forEach { dir ->
                val pre = File(dir, "supPlant_$pkg.db")
                if (pre.exists() && pre.length() > 0L) {
                    sawDb = true
                    readAccounts(pre).forEach { out["${it.host}|${it.user}"] = it }
                }
            }
        }

        // 2) 借 root 直接读原版私有目录
        if (out.isEmpty() && isRootAvailable()) {
            PACKAGES.forEach { pkg ->
                val src = "/data/data/$pkg/databases/$DB_NAME"
                val dst = File(cacheDir, "legacy_$pkg.db")
                val streamed = streamAsRoot(src, dst)
                if (streamed) {
                    sawDb = true
                    readAccounts(dst).forEach { out["${it.host}|${it.user}"] = it }
                    dst.delete()
                } else {
                    val exists = rootText("[ -f '$src' ] && echo yes")?.contains("yes") == true
                    notes += if (exists) "$pkg 的库读不出来" else "$pkg 没有库"
                }
            }
            if (!sawDb) {
                throw NothingFoundException(notes.joinToString("；"))
            }
            if (out.isEmpty()) throw EmptyLibraryException()
        } else if (!sawDb) {
            // 两边都没有：分清楚是「没授权 root」还是「找不到原版库」，好让界面把话说准
            if (!isRootAvailable()) throw NotRootedException()
            throw NothingFoundException(notes.joinToString("；"))
        }
        return out.values.toList()
    }

    /**
     * 借 root 把原版库「流」进本应用的 cache，而不是让 root 往我们目录里 `cp`。
     * 这样 root 只需要**读**源文件，落盘由本应用自己做，不会被写权限/SELinux 卡住；
     * 参数按 [SU_ARG_SETS] 依次试（先 `su -M -c` 走全局挂载命名空间）。
     */
    private fun streamAsRoot(src: String, dst: File): Boolean {
        val diag = mutableListOf<String>()
        SU_ARG_SETS.forEach { args ->
            dst.delete()
            val good = runCatching {
                val p = ProcessBuilder(listOf("su") + args + listOf("cat '$src'")).start()
                var ioError: String? = null
                val copied = runCatching {
                    p.inputStream.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                }.onFailure { ioError = it.message }.isSuccess
                val err = runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("")
                val code = p.waitFor()
                val ok = copied && code == 0 && dst.exists() && dst.length() > 0L
                if (!ok) {
                    diag += "su ${args.first()} exit=$code err=${err.trim()} io=${ioError.orEmpty()}"
                }
                ok
            }.getOrDefault(false)
            if (good) {
                lastDiagnostic = ""
                return true
            }
        }
        lastDiagnostic = diag.joinToString(" / ")
        dst.delete()
        return false
    }

    private fun readAccounts(file: File): List<SavedAccount> = runCatching {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("select USER_NAME, STAFF_NAME, PASSWORD, IP from $TABLE", null).use { c ->
                val list = mutableListOf<SavedAccount>()
                while (c.moveToNext()) {
                    accountFromRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
                        ?.let { list += it }
                }
                list
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 原版 `ACCOUNT_INFO` 的一行 → 我们的账号。
     * `USER_NAME` 就是工号、`STAFF_NAME` 是姓名、`PASSWORD` 是明文、`IP` 是完整地址（带 http/https）。
     * 抽成纯函数便于单测。
     */
    fun accountFromRow(
        user: String?,
        staffName: String?,
        password: String?,
        ip: String?,
    ): SavedAccount? {
        val u = user.orEmpty().trim()
        if (u.isEmpty()) return null
        val raw = ip.orEmpty().trim()
        return SavedAccount(
            user = u,
            password = password.orEmpty(),
            staffName = staffName.orEmpty().trim().ifBlank { u },
            host = SessionStore.normalizeHost(raw),
            useHttps = !raw.startsWith("http://"),
        )
    }
}

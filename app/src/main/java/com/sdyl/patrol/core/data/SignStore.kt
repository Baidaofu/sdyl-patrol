package com.sdyl.patrol.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 完成人签字图片（用户 m05038）。
 *
 * 用户原话要点：「把签字功能实装到我们的程序上吧，但不要真的要求签字，
 * 选中一个图片作为签字图片就行，签字跟随账户，不要多账户共享。」
 *
 * 于是这里不做画板、不弹签字框，只让用户在「我的」里挑一张图：
 *  - 每个账号一份文件（文件名 = 账号键），换账号看到的就是自己那份，互不共享；
 *  - 落在 App 私有目录 `filesDir/sign/`，不占相册、也不依赖相册读权限；
 *  - 上传时打进 zip 的 `pics/<xjTaskPic_yyyyMMdd_HHmmss>.jpg`，
 *    并把文件名写进 `potrolTask.signPicPath`（原版就是这样打包签字的，
 *    见抓包 `probe/orig_payload/xj_upload.zip`：`pics/xjTaskPic_20261014_061320.jpg`
 *    ＋ `"signPicPath":"xjTaskPic_20261014_061320.jpg"`）。
 */
class SignStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private fun dir(): File = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** 某账号的签字图文件（不存在也返回路径，用 [exists] 判断有没有图） */
    fun file(accountKey: String): File = File(dir(), "${safe(accountKey)}.jpg")

    fun exists(accountKey: String): Boolean = file(accountKey).let { it.isFile && it.length() > 0L }

    /** 当前账号有没有签字图 */
    fun existsFor(session: SessionStore): Boolean = exists(session.accountKey)

    fun fileFor(session: SessionStore): File = file(session.accountKey)

    /**
     * 存一张签字图：相册里挑来的可能是 png/webp/超大的照片，统一转成 JPEG。
     * 原版签字图是 jpg（`xjTaskPic_*.jpg`），统一格式省得过服务端/查看器出岔子。
     */
    fun save(accountKey: String, input: InputStream): File {
        val bitmap = BitmapFactory.decodeStream(input)
            ?: throw IllegalArgumentException("这张图片读不出来，请换一张")
        val scaled = scaleDown(bitmap, MAX_EDGE)
        val target = file(accountKey)
        FileOutputStream(target).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (scaled !== bitmap) bitmap.recycle()
        bitmap.recycle()
        return target
    }

    fun clear(accountKey: String) {
        file(accountKey).delete()
    }

    private fun safe(key: String): String =
        key.ifBlank { "unknown" }.replace(Regex("[^0-9A-Za-z_.-]"), "_")

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val DIR_NAME = "sign"
        private const val MAX_EDGE = 1600
    }
}

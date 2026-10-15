package com.sdyl.patrol.core.nfc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 一次 NFC 刷卡读到的原始信息。
 *
 * 现场用的是「刷卡点」标签，标签里可能写 NDEF 文本，也可能只有 UID，
 * 所以两种都留着，比对时任一命中就算匹配。
 */
data class NfcTagRead(
    /** 卡片 UID，大写十六进制、无分隔符 */
    val uid: String,
    /** NDEF 里的文本/URI 内容（如果有），已 trim */
    val text: String? = null,
    val at: Long = System.currentTimeMillis(),
) {
    /** 依次拿文本与 UID 去比，够用且宽容 */
    val candidates: List<String> get() = listOfNotNull(text?.takeIf { it.isNotBlank() }, uid)

    /** 给用户看的一句话 */
    val display: String get() = text?.takeIf { it.isNotBlank() } ?: uid
}

/**
 * 全局 NFC 事件总线。
 *
 * MainActivity 在前台开 ReaderMode，读到标签就 emit 到这里；
 * 需要签到的页面 collect 即可，不用把回调层层往下传。
 * **只读，不写卡**——用户明确只保留 NFC 刷卡签到。
 */
object NfcBus {
    private val _reads = MutableSharedFlow<NfcTagRead>(extraBufferCapacity = 8)
    val reads: SharedFlow<NfcTagRead> = _reads

    /** 读到的标签是否与本区域的刷卡点编号匹配 */
    fun matches(read: NfcTagRead, signCode: String?): Boolean {
        val target = normalize(signCode) ?: return false
        return read.candidates.any { normalize(it) == target }
    }

    /** 去掉分隔符/大小写差异，便于卡号比对 */
    fun normalize(raw: String?): String? =
        raw?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotEmpty() }

    internal fun emit(read: NfcTagRead) {
        _reads.tryEmit(read)
    }
}

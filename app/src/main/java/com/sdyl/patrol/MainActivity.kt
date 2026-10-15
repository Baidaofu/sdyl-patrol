package com.sdyl.patrol

import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sdyl.patrol.core.nfc.NfcBus
import com.sdyl.patrol.core.nfc.NfcTagRead
import com.sdyl.patrol.ui.AppRoot
import com.sdyl.patrol.ui.theme.SdylTheme

/**
 * 唯一 Activity。
 *
 * 除了承载 Compose 界面，还负责 NFC 前台读卡：
 * 巡检签到只保留「NFC 刷卡」和「手动」两种方式（用户要求），
 * 所以这里用 ReaderMode 常驻监听，读到标签就丢进 [NfcBus]。
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = runCatching { NfcAdapter.getDefaultAdapter(this) }.getOrNull()
        setContent {
            SdylTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReader()
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun enableNfcReader() {
        val adapter = nfcAdapter ?: return
        runCatching {
            adapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id?.joinToString("") { "%02X".format(it) }.orEmpty()
        NfcBus.emit(NfcTagRead(uid = uid, text = readNdefText(tag)))
    }

    /** 尽力从 NDEF 里抠出一段文本；抠不到就返回 null，调用方退回 UID 比对 */
    private fun readNdefText(tag: Tag): String? = runCatching {
        val message = android.nfc.tech.Ndef.get(tag)?.cachedNdefMessage ?: return null
        message.records.firstNotNullOfOrNull { record -> decodeRecord(record) }
    }.getOrNull()

    private fun decodeRecord(record: NdefRecord): String? {
        record.toUri()?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val payload = record.payload ?: return null
        if (payload.isEmpty()) return null
        return when {
            // 文本记录：第 1 字节是编码/语言长度，跳过语言码
            record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type?.contentEquals(NdefRecord.RTD_TEXT) == true -> {
                val langLen = payload[0].toInt() and 0x3F
                val start = 1 + langLen
                if (start > payload.size) null
                else String(payload, start, payload.size - start, Charsets.UTF_8)
            }
            // 其它记录乐观地当 UTF-8 文本读
            else -> runCatching { String(payload, Charsets.UTF_8) }.getOrNull()
        }?.trim()?.takeIf { it.isNotBlank() && it.all { c -> c.code in 32..0xFFFF } }
    }
}

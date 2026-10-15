package com.sdyl.patrol.core.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

/**
 * 设备身份 —— 一字不差地复刻原版的 IMEI 取值逻辑。
 *
 * 原版 `com.supcon.mes.middleware.util.DeviceInfoUtil`：
 *  - `getImei(Context)`：只有 `SDK_INT < 29 && SDK_INT > 26`（即 27、28）才去读真机 IMEI；
 *    `SDK_INT > 26` 的其余情况（>= 29）**一律返回 null**；更老的系统走 `getDeviceId(0)`。
 *    ⇒ Android 10 以后系统不再把 IMEI 给普通应用，读到的永远是 null。
 *  - `getImeiWithManual(Context)`：`isBlank(imei) ? SharedPreferences("IMEI_MANUAL") : imei`
 *    ⇒ 读不到就退回**「软件设置」里手填的 IMEI**。
 *
 * 原版 `XJUploadWorkBO` 正是用 `getImeiWithManual` 给每条巡检项填 `imei`，
 * 用 `SupPlantApplication.getAccountInfo().getMobile()` 填 `phoneNum`。
 */
object DeviceIdentity {

    /** 原版写死的上下界：只有 27..28 才尝试读真机 IMEI */
    private val SDK_WITH_REAL_IMEI = (Build.VERSION_CODES.O + 1)..Build.VERSION_CODES.P

    /** 真机 IMEI；取不到返回 null（Android 10+ 必然为 null） */
    @SuppressLint("HardwareIds", "MissingPermission", "NewApi")
    fun imei(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return runCatching {
            when {
                Build.VERSION.SDK_INT in SDK_WITH_REAL_IMEI -> tm.imei
                Build.VERSION.SDK_INT > Build.VERSION_CODES.O -> null
                else -> @Suppress("DEPRECATION") tm.deviceId
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 原版 `getImeiWithManual`：真机取不到就用软件设置里手填的那个 */
    fun imeiWithManual(context: Context, manual: String): String = imei(context) ?: manual.trim()

    /**
     * 原版 `XJLogBO.equipmentModel` = `DeviceUtils.getModel() + "," + DeviceUtils.getSDKVersionName()`。
     */
    fun equipmentModel(): String = "${Build.MODEL},${Build.VERSION.RELEASE}"

    /**
     * 原版 `XJLogBO.macAddress` = `DeviceUtils.getMacAddress()`。
     * Android 6 起系统不再返回真实 MAC，原版实际上拿到的也是这个占位值。
     */
    fun macAddress(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { !it.isLoopback && it.hardwareAddress != null }
            ?.hardwareAddress
            ?.joinToString(":") { b -> "%02X".format(b) }
    }.getOrNull() ?: PLACEHOLDER_MAC

    /** 与原版 `DeviceUtils` 一致的兜底 MAC */
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"
}

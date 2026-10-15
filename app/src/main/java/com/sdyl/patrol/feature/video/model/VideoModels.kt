package com.sdyl.patrol.feature.video.model

import com.google.gson.annotations.SerializedName

/**
 * 视频监控模型。
 *
 * 数据来源实测（PC 直连 `GET /msService/VideoPlayWeb/queryGroupChannels?type=1`）：
 * ```
 * {"code":200,"data":[
 *   {"id":1000,"name":"示例企业","code":"default_org_company","pid":-1,"channelsVOList":[]},
 *   {"id":3720330664692160,"name":"原厂(ZKCSBM)","code":"ZKCSBM","pid":3941084738537648,
 *    "channelsVOList":[{"channel":"54","channelName":"1125-2#重整-K202HM22",
 *                       "snapUrl":null,"onLine":true,"errorString":null,
 *                       "cameraType":"VideoPlayWeb_cameraType/gunMachine"}]}]}
 * ```
 */
data class VideoGroupDto(
    @SerializedName(value = "id", alternate = ["groupId"])
    val id: Long = 0L,
    val name: String? = null,
    val code: String? = null,
    val pid: Long? = null,
    @SerializedName(value = "channelsVOList", alternate = ["channels", "channelList"])
    val channels: List<VideoChannelDto>? = null,
)

data class VideoChannelDto(
    /** 通道号，播放时拼 `channel=` 用 */
    val channel: String? = null,
    val channelName: String? = null,
    val snapUrl: String? = null,
    val onLine: Boolean = false,
    val errorString: String? = null,
    /** 如 VideoPlayWeb_cameraType/gunMachine（枪机） */
    val cameraType: String? = null,
)

/** 界面用的分组 */
data class VideoGroup(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val channels: List<VideoChannel>,
)

data class VideoChannel(
    val channel: String,
    val name: String,
    val online: Boolean,
    val cameraType: String?,
    val snapUrl: String?,
) {
    val cameraTypeLabel: String
        get() = when {
            cameraType.isNullOrBlank() -> "—"
            cameraType.contains("gunMachine", true) -> "枪机"
            cameraType.contains("ballMachine", true) -> "球机"
            cameraType.contains("hemisphere", true) -> "半球"
            else -> cameraType.substringAfterLast('/')
        }
}

object VideoModes {
    /** 直播走 MSE（WebSocket + fMP4），与原 App 一致 */
    const val LIVE_MODE = "cameraLive"
    const val LIVE_PROTOCOL = "mse"

    /** 回放走 HTTP-FLV */
    const val PLAYBACK_MODE = "videotape"
    const val PLAYBACK_PROTOCOL = "flv"

    fun liveUrl(host: String, channel: String): String =
        "$host/msService/VideoPlayWeb/videoplayweb/playWeb/unitPlayer" +
            "?mode=$LIVE_MODE&protocol=$LIVE_PROTOCOL&channel=$channel"

    fun playbackUrl(host: String, id: String): String =
        "$host/msService/VideoPlayWeb/videoplayweb/playWeb/unitPlayer" +
            "?mode=$PLAYBACK_MODE&protocol=$PLAYBACK_PROTOCOL&id=$id"
}

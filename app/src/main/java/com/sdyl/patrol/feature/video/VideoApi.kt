package com.sdyl.patrol.feature.video

import com.sdyl.patrol.core.network.ApiClient
import com.sdyl.patrol.core.network.BapResponse
import com.sdyl.patrol.feature.video.model.VideoChannel
import com.sdyl.patrol.feature.video.model.VideoGroup
import com.sdyl.patrol.feature.video.model.VideoGroupDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 视频通道接口。
 *
 * 逆向 + 实测结论：原 App 的 `queryGroupChannels` 是取通道最干净的入口，
 * 返回的是「组织树 + 该组织下的通道」（见 VideoGroupDto 注释里的真实报文）。
 * 其它几个入口（MobileVideo/list、getDeptsAndVideos、getVideoInfosByOrgId）
 * 要么返回空，要么结构更绕，不采用。
 */
interface VideoApi {

    @GET("/msService/VideoPlayWeb/queryGroupChannels")
    suspend fun queryGroupChannels(@Query("type") type: Int = 1): BapResponse<List<VideoGroupDto>>
}

class VideoRepository(private val client: ApiClient) {

    private val service: VideoApi by lazy { client.api<VideoApi>() }

    /**
     * 只保留真正挂了通道的组织节点，拍平成「分组 → 通道」两层。
     * 组织树里绝大多数空节点对巡检员没用，直接丢掉。
     */
    suspend fun loadGroups(): Result<List<VideoGroup>> = runCatching {
        val raw = service.queryGroupChannels(1).payload()
            ?: throw IllegalStateException("视频通道响应为空")

        raw.mapNotNull { dto ->
            val channels = dto.channels.orEmpty().mapNotNull { c ->
                val no = c.channel?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VideoChannel(
                    channel = no,
                    name = c.channelName?.takeIf { it.isNotBlank() } ?: "通道 $no",
                    online = c.onLine,
                    cameraType = c.cameraType,
                    snapUrl = c.snapUrl,
                )
            }
            if (channels.isEmpty()) return@mapNotNull null
            VideoGroup(
                id = dto.id,
                name = dto.name?.takeIf { it.isNotBlank() } ?: dto.code.orEmpty().ifBlank { "未命名分组" },
                parentId = dto.pid,
                channels = channels,
            )
        }
    }
}

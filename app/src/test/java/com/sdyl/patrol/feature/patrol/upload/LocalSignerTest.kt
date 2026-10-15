package com.sdyl.patrol.feature.patrol.upload

import com.sdyl.patrol.core.data.db.AreaEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户 m14256：任务上传的人（执行人/完成人）要以**区域作业页里记下的巡检人**为准，
 * 「我的 → 上报人员」只做兜底——他在巡检任务里临时改的人必须真的生效。
 */
class LocalSignerTest {

    private fun area(
        id: String,
        signed: Boolean,
        name: String?,
        staffId: String?,
        cardTime: Long?,
    ) = AreaEntity(
        id = id,
        taskTableNo = "patrolTask_20261014_1942",
        taskId = "1000000000000640",
        workAreaId = "790e6f0c-a047-4d0c-92ea-000c$id",
        name = "第二站 机泵",
        code = null,
        sort = 0,
        totalNum = 1,
        finishNum = 1,
        abnormalNum = 0,
        isSigned = signed,
        isUpload = false,
        signStaffName = name,
        signStaffId = staffId,
        cardTime = cardTime,
        updatedAt = 0L,
    )

    @Test
    fun `latest signed area wins over the reporter setting`() {
        val areas = listOf(
            area("1", true, "张三", "1000000000000952", 1791932648000L),
            area("2", true, "王五", "1000000000000064", 1791932880000L),
        )
        assertEquals(
            "1000000000000064" to "王五",
            PatrolUploader.localSigner(areas, "1000000000000065", "李四"),
        )
    }

    @Test
    fun `unsigned areas do not count`() {
        val areas = listOf(
            area("1", true, "张三", "1000000000000952", 1791932648000L),
            area("2", false, "王五", "1000000000000064", null),
        )
        assertEquals(
            "1000000000000952" to "张三",
            PatrolUploader.localSigner(areas, "1000000000000065", "李四"),
        )
    }

    @Test
    fun `no local sign at all falls back to the reporter setting`() {
        val areas = listOf(
            area("1", false, null, null, null),
            area("2", false, null, null, null),
        )
        assertEquals(
            "1000000000000065" to "李四",
            PatrolUploader.localSigner(areas, "1000000000000065", "李四"),
        )
    }

    @Test
    fun `empty area list (task level only upload) falls back too`() {
        assertEquals(
            "1000000000000065" to "李四",
            PatrolUploader.localSigner(emptyList(), "1000000000000065", "李四"),
        )
    }

    @Test
    fun `signed but nameless area does not blank the person`() {
        val areas = listOf(area("1", true, "   ", "   ", 1791932648000L))
        assertEquals(
            "1000000000000065" to "李四",
            PatrolUploader.localSigner(areas, "1000000000000065", "李四"),
        )
    }
}

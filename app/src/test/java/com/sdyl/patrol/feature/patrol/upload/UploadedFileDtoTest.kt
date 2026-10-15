package com.sdyl.patrol.feature.patrol.upload

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「服务端已有签字图就别再传一张」的判据测试（用户 m11701）。
 *
 * 判据取自实测：`GET /inter-api/file-server/v1/file/upload-list?linkId=<任务 tableInfoId>`
 * 返回的条目里，签字图的 `propertyCode` 是 `PATROL_1.0.0_patrolTask_PotrolTask_signPic`、
 * `name` 是 `xjTaskPic_20261014_062711.jpg`。这条任务实测返回 3 个文件（手机 A/B/本应用各一张），
 * 所以「任意一条命中」就等于服务端已有签字图 —— 正好复用原版 `NetworkAPI.getFileList`
 * 的响应结构（这里逐字钉住，字段名改了要立刻发现）。
 */
class UploadedFileDtoTest {

    private val gson = Gson()

    @Test
    fun `service response parses and sign pictures are recognized`() {
        // 真实响应（截取实测量的前两条 + 一条非签字附件）
        val json = """
            {"code":100000000,"message":"操作成功","success":true,"data":[
              {"id":1000000000000168,"name":"xjTaskPic_20261014_062711.jpg",
               "propertyCode":"PATROL_1.0.0_patrolTask_PotrolTask_signPic",
               "path":"/1000000000000641/1791930322635/xjTaskPic_20261014_062711.jpg",
               "createTime":1791930322660},
              {"id":1000000000000688,"name":"xjTaskPic_20261014_185042.jpg",
               "propertyCode":"PATROL_1.0.0_patrolTask_PotrolTask_signPic",
               "path":"/1000000000000641/1791975042913/xjTaskPic_20261014_185042.jpg",
               "createTime":1791975042923},
              {"id":1,"name":"现场照片.jpg","propertyCode":"PATROL_1.0.0_patrolTask_PotrolTask_pic",
               "path":"/x/1.jpg","createTime":1791975043000}
            ]}
        """.trimIndent()

        val resp = gson.fromJson(json, UploadedFileEnvelope::class.java)
        assertEquals(100000000, resp.code)
        val files = resp.data!!
        assertEquals(3, files.size)

        // 签字图被认出来（propertyCode 结尾 signPic）
        assertTrue(files[0].isSignPic)
        assertTrue(files[1].isSignPic)
        // 普通现场照片不算签字图
        assertFalse(files[2].isSignPic)
        // 「任意一条命中」= 服务端已有签字图 → 本次不再传
        assertTrue(files.any { it.isSignPic })
        assertEquals("1000000000000168", files[0].id)
        assertEquals(1791930322660L, files[0].createTime)
        assertEquals("xjTaskPic_20261014_062711.jpg", files[0].name)
    }

    @Test
    fun `empty data means no signature yet`() {
        val resp = gson.fromJson(
            """{"code":100000000,"message":"操作成功","data":[]}""",
            UploadedFileEnvelope::class.java,
        )
        assertEquals(0, resp.data!!.size)
        assertFalse(resp.data!!.any { it.isSignPic })
    }

    @Test
    fun `name prefix alone is enough even if propertyCode is missing`() {
        // 老数据可能没有 propertyCode，退化成看文件名前缀
        val f = gson.fromJson("""{"name":"xjTaskPic_20261014_060600.jpg"}""", UploadedFileDto::class.java)
        assertTrue(f.isSignPic)
        val other = gson.fromJson("""{"name":"IMG_0001.jpg"}""", UploadedFileDto::class.java)
        assertFalse(other.isSignPic)
    }
}

/** 只为本测试复刻 `BapResponse<List<UploadedFileDto>>` 的信封形状 */
private class UploadedFileEnvelope {
    var code: Int = 0
    var message: String? = null
    var data: List<UploadedFileDto>? = null
}

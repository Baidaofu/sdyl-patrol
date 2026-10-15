package com.sdyl.patrol.core.data

import com.sdyl.patrol.core.network.dto.DepartmentRef
import com.sdyl.patrol.core.network.dto.PersonItem
import com.sdyl.patrol.core.network.dto.PositionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 m14256：「我的」里的查询要**双向**——工号查名字、名字查工号；
 * 重名的人不止一个时列出候选让用户自己挑。
 *
 * 这里只测 [PersonDirectory] 的纯函数查询规则（真机名录 9228 人、7.6MB，不进仓库）。
 */
class PersonDirectoryLookupTest {

    /** 测试数据全是编的（人名、工号、手机号都是占位值）。 */
    private val people = listOf(
        PersonItem(id = 1000000000000952L, code = "90000001", name = "张三"),
        PersonItem(id = 1000000000000064L, code = "90000003", name = "王五"),
        PersonItem(id = 1000000000000065L, code = "90000004", name = "李四"),
        PersonItem(id = 1000000000000066L, code = "90000005", name = "张伟"),
        PersonItem(id = 1000000000000067L, code = "90000006", name = "张伟"),
    )

    @Test
    fun `code lookup returns that one person`() {
        val hits = PersonDirectory.candidatesIn(people, "90000004")
        assertEquals(1, hits.size)
        assertEquals("李四", hits.first().name)
    }

    @Test
    fun `name lookup returns the person and their real code`() {
        val hits = PersonDirectory.candidatesIn(people, "王五")
        assertEquals(1, hits.size)
        assertEquals("90000003", hits.first().code)
        assertEquals(1000000000000064L, hits.first().id)
    }

    @Test
    fun `duplicate name returns every candidate so the picker can ask`() {
        val hits = PersonDirectory.candidatesIn(people, "张伟")
        assertEquals(2, hits.size)
        assertEquals(listOf("90000005", "90000006"), hits.map { it.code })
    }

    @Test
    fun `unknown name falls back to substring match`() {
        val hits = PersonDirectory.candidatesIn(people, "王")
        assertEquals(1, hits.size)
        assertEquals("90000003", hits.first().code)
    }

    @Test
    fun `nothing matches an unknown keyword`() {
        assertTrue(PersonDirectory.candidatesIn(people, "不存在的人").isEmpty())
        assertTrue(PersonDirectory.candidatesIn(people, "   ").isEmpty())
    }

    @Test
    fun `internal id conversion maps the code and leaves other input alone`() {
        assertEquals(
            "1000000000000065",
            PersonDirectory.internalIdIn(people, "90000004"),
        )
        // 已经是内部 ID（旧数据里存的就是它）就原样带上，不编一个
        assertEquals(
            "1000000000000065",
            PersonDirectory.internalIdIn(people, "1000000000000065"),
        )
        // 名录里没有的工号也照原样存（上传时服务端会自己判）
        assertEquals("99999999", PersonDirectory.internalIdIn(people, "99999999"))
        assertNull(PersonDirectory.internalIdIn(people, "  "))
        assertNull(PersonDirectory.internalIdIn(people, null))
    }

    /**
     * 用户 m17358：查询结果要能显示电话 / 部门 / 岗位。
     * 接口给的是嵌套数组（`department[]` / `position[]`），落盘前拍平成两个字符串，
     * 免得把 6.8 MB 的嵌套结构整份存进本机。
     */
    @Test
    fun `slimmed keeps contact info and drops the heavy nested arrays`() {
        val raw = PersonItem(
            id = 1L,
            code = "90000006",
            name = "冯十一",
            phone = "13900000001",
            gender = "sys_gender/male",
            status = "sys_person_status/onWork",
            departmentRaw = listOf(
                DepartmentRef(name = "炼油一部", fullPath = "示例企业/炼油一部"),
                DepartmentRef(name = "二班", fullPath = "示例企业/炼油一部/二班", currentPosition = true),
            ),
            positionRaw = listOf(
                PositionRef(name = "内操"),
                PositionRef(name = "外操", mainPosition = true),
            ),
        ).let { it.slimmed() }

        assertEquals("二班", raw.deptName)
        assertEquals("示例企业/炼油一部/二班", raw.deptPath)
        assertEquals("外操", raw.positionName)
        assertEquals("二班 · 外操", raw.deptPosition)
        assertEquals(true, raw.onWork)
        assertEquals("男", raw.genderLabel)
        // 嵌套数组被清掉（落盘体积）
        assertNull(raw.departmentRaw)
        assertNull(raw.positionRaw)
    }

    @Test
    fun `dept position and status degrade gracefully when fields are missing`() {
        val bare = PersonItem(id = 2L, code = "x", name = "无信息").slimmed()
        assertNull(bare.deptPosition)
        assertNull(bare.genderLabel)
        // status 缺失按在职算（老名录里没有这个字段）
        assertEquals(true, bare.onWork)

        val left = PersonItem(
            id = 3L,
            code = "y",
            name = "离职的人",
            status = "sys_person_status/dimission",
        ).slimmed()
        assertEquals(false, left.onWork)

        val onlyDept = PersonItem(
            id = 4L,
            code = "z",
            name = "只有部门",
            departmentRaw = listOf(DepartmentRef(name = "炼油二部")),
        ).slimmed()
        assertEquals("炼油二部", onlyDept.deptPosition)

        val female = PersonItem(id = 5L, code = "w", name = "女同事", gender = "sys_gender/female")
            .slimmed()
        assertEquals("女", female.genderLabel)
    }

    @Test
    fun `dept and position are deduped when the server fills the same name`() {
        val same = PersonItem(
            id = 6L,
            code = "s",
            name = "部门岗位同名",
            departmentRaw = listOf(DepartmentRef(name = "董事会(00010001)")),
            positionRaw = listOf(PositionRef(name = "董事会(00010001)")),
        ).slimmed()
        assertEquals("董事会(00010001)", same.deptPosition)

        val diff = PersonItem(
            id = 7L,
            code = "d",
            name = "岗位不同",
            departmentRaw = listOf(DepartmentRef(name = "炼油二部")),
            positionRaw = listOf(PositionRef(name = "工艺外操")),
        ).slimmed()
        assertEquals("炼油二部 · 工艺外操", diff.deptPosition)
    }
}

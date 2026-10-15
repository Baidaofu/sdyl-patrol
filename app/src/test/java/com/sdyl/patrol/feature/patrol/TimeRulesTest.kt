package com.sdyl.patrol.feature.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 用户 m12704 的时间规则单测：
 *
 * ```
 * 计划开始 < 实际开始 < 最早巡检点 < 最晚巡检点 < 实际结束 < 计划结束
 * 任意两个巡检点之间间隔 ≥ 2 分钟
 * ```
 */
class TimeRulesTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val fmt: (Long) -> String = { TimeRulesTest.format(it) }

    @Test
    fun `完整合法的一条链没有问题`() {
        val issues = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 5),
            end = at(7, 30),
            points = listOf(at(6, 10) to "第一站", at(6, 13) to "第二站", at(6, 16) to "第三站"),
            fmt = fmt,
        )
        assertEquals(emptyList<String>(), issues)
    }

    @Test
    fun `实际开始必须晚于计划开始`() {
        val issues = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 0),
            end = 0L,
            points = emptyList(),
            fmt = fmt,
        )
        assertTrue(issues.toString(), issues.any { it.contains("必须晚于计划开始时间") })
    }

    @Test
    fun `实际结束不能晚于计划结束`() {
        val issues = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = 0L,
            end = at(8, 0) + 1_000L,
            points = emptyList(),
            fmt = fmt,
        )
        assertTrue(issues.toString(), issues.any { it.contains("不能晚于计划结束时间") })
    }

    @Test
    fun `巡检点必须落在实际开始与实际结束之间`() {
        val before = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 10),
            end = at(7, 0),
            points = listOf(at(6, 5) to "第一站"),
            fmt = fmt,
        )
        assertTrue(before.toString(), before.any { it.contains("必须晚于实际开始时间") })

        val after = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 10),
            end = at(7, 0),
            points = listOf(at(7, 1) to "第一站"),
            fmt = fmt,
        )
        assertTrue(after.toString(), after.any { it.contains("必须早于实际结束时间") })
    }

    @Test
    fun `两个巡检点间隔不足 120 秒要报出来`() {
        val issues = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 5),
            end = at(7, 30),
            points = listOf(at(6, 10) to "第一站", at(6, 11) to "第二站"),
            fmt = fmt,
        )
        assertTrue(issues.toString(), issues.any { it.contains("间隔不足 2 分钟") })
    }

    @Test
    fun `刚好 120 秒算通过`() {
        val issues = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 5),
            end = at(7, 30),
            points = listOf(at(6, 10) to "第一站", at(6, 12) to "第二站"),
            fmt = fmt,
        )
        assertEquals(emptyList<String>(), issues)
    }

    @Test
    fun `传 0 的项不参与校验（开始任务界面只填开始时间）`() {
        val issues = TimeRules.check(
            planStart = at(6, 0),
            planEnd = at(8, 0),
            start = at(6, 5),
            end = 0L,
            points = listOf(at(6, 10) to "第一站", at(6, 30) to "第二站"),
            fmt = fmt,
        )
        // end=0 时不校验「点必须早于实际结束」，也不校验「点必须晚于实际结束」
        assertEquals(emptyList<String>(), issues)
    }

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance(zone).apply {
        set(2026, Calendar.OCTOBER, 14, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        private val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
        fun format(millis: Long): String = sdf.format(java.util.Date(millis))
    }
}

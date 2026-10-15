package com.sdyl.patrol.feature.patrol

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 分块时间框的显示映射（用户 m06106 要的"不用打分隔符"）必须有单测兜着：
 * Compose 的 `ValidatingOffsetMapping` 对 `OffsetMapping` 有硬性约束，
 * 一旦越界就抛 IllegalStateException **闪退**（2026-10-14 真机打字实测崩过一次：
 * 数字刚好 6 位时多算了一个分隔符，映射到 out.length 之外）。
 */
class DateTimeFieldTest {

    @Test
    fun `14 位数字显示成标准时间串`() {
        assertEquals("2026-10-14 06:00:30", formatTimeDigits("20261014060030"))
        // 不足 14 位时按已有位数显示，分隔符照样补
        assertEquals("2026-10", formatTimeDigits("202610"))
        assertEquals("2026-10-14 06", formatTimeDigits("2026101406"))
        assertEquals("", formatTimeDigits(""))
    }

    @Test
    fun `每个长度前缀的偏移映射都满足 Compose 的校验`() {
        val full = "20261014060030"
        for (n in 0..full.length) {
            val digits = full.take(n)
            val transformed = TimeSeparatorTransformation.filter(AnnotatedString(digits))
            val out = transformed.text.text
            val map = transformed.offsetMapping

            assertEquals("n=$n", 0, map.originalToTransformed(0))
            assertEquals("n=$n", out.length, map.originalToTransformed(digits.length))
            assertEquals("n=$n", 0, map.transformedToOriginal(0))
            assertEquals("n=$n", digits.length, map.transformedToOriginal(out.length))

            var prev = -1
            for (o in 0..digits.length) {
                val t = map.originalToTransformed(o)
                assertTrue("n=$n o=$o 越界：$t > ${out.length}", t in 0..out.length)
                assertTrue("n=$n 单调性破了：$t < $prev", t >= prev)
                prev = t
                assertEquals("n=$n 往返不等：o=$o", o, map.transformedToOriginal(t))
            }
            // 反向也要有界且单调
            var prevBack = -1
            for (j in 0..out.length) {
                val o = map.transformedToOriginal(j)
                assertTrue("n=$n j=$j 越界：$o", o in 0..digits.length)
                assertTrue("n=$n 反向单调性破了", o >= prevBack)
                prevBack = o
            }
        }
    }

    /**
     * 圆盘回读日期用的 [dateMillisUtc] 必须固定按 **UTC** 解——
     * M3 `DatePickerState.selectedDateMillis` 的定义就是"UTC 当天零点"。
     * 断言与运行机器时区无关：CI(UTC) 与本机(Asia/Shanghai) 结果必须一致
     * （踩过一次坑：`UploadPayloadTest` 用默认时区造期望值，CI 上漂了 8 小时）。
     */
    @Test
    fun `圆盘日期按 UTC 零点口径`() {
        val millis = dateMillisUtc("20261014060030")
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        assertEquals(2026, utc.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, utc.get(Calendar.MONTH))
        assertEquals(14, utc.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, utc.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, utc.get(Calendar.MINUTE))
        // 同一毫秒在东八区正好是当天 08:00，证明确实存的"UTC 零点"而不是本地零点
        val sh = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply { timeInMillis = millis }
        assertEquals(8, sh.get(Calendar.HOUR_OF_DAY))
        assertEquals(14, sh.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * 六列滚轮（用户 m15940）里「日」列要跟着年和月变：
     * 大小月和 2 月平年/闰年都必须对，否则滚轮上会出现「2 月 30 号」这种不存在的日期。
     */
    @Test
    fun `每个月的天数（含闰年二月）`() {
        assertEquals(31, daysInMonth(2026, 1))
        assertEquals(28, daysInMonth(2026, 2))
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(31, daysInMonth(2026, 3))
        assertEquals(30, daysInMonth(2026, 4))
        assertEquals(31, daysInMonth(2026, 12))
    }

    /** 滚轮把 6 段数字拆开再拼回去必须一字不差（**秒**不能被吞掉，这是 m15940 的重点）。 */
    @Test
    fun `滚轮六段拆分与回拼`() {
        val parts = splitTimeParts("2026-10-15 00:11:55")
        assertEquals(listOf("2026", "10", "15", "00", "11", "55"), parts)
        assertEquals("2026-10-15 00:11:55", rebuildTimeText(parts))
    }
}

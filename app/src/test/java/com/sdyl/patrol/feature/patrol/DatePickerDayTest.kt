package com.sdyl.patrol.feature.patrol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 主页日期选择（用户反馈：「每次点开日历，默认选中都是已选日期的前一天」）。
 *
 * 根因是 Material3 `DatePickerState` 的日期口径是 **UTC 零点**，而库里存的是本机零点。
 * 这组单测把两个方向的换算都钉死，并特意覆盖东八区（会差一天）和西五区（会差到前一天）。
 */
class DatePickerDayTest {

    private lateinit var original: TimeZone

    @Before
    fun setUp() {
        original = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    /** 在指定时区下造一个「某年某月某日 00:00」的本机时间戳 */
    private fun localDay(year: Int, month: Int, day: Int, tz: TimeZone): Long {
        TimeZone.setDefault(tz)
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
    }

    private fun utcParts(millis: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `东八区本机零点换算后仍是同一天`() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val local = localDay(2026, 10, 16, tz)
        assertEquals(Triple(2026, 10, 16), utcParts(localDayToPickerUtc(local)))
    }

    @Test
    fun `西五区也不会差一天`() {
        val tz = TimeZone.getTimeZone("America/New_York")
        val local = localDay(2026, 10, 16, tz)
        assertEquals(Triple(2026, 10, 16), utcParts(localDayToPickerUtc(local)))
    }

    @Test
    fun `往返换算在多个时区都一致`() {
        for (id in listOf("Asia/Shanghai", "America/New_York", "UTC", "Asia/Kolkata", "Pacific/Auckland")) {
            val tz = TimeZone.getTimeZone(id)
            val local = localDay(2026, 3, 1, tz)
            assertEquals(id, local, pickerUtcToLocalDay(localDayToPickerUtc(local)))
        }
    }
}

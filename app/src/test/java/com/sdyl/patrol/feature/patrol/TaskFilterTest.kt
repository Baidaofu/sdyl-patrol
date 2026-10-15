package com.sdyl.patrol.feature.patrol

import com.sdyl.patrol.feature.patrol.model.PatrolTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「我的」+「分类▾」的分类匹配（用户 m15074）。
 *
 * 原版任务中心的下拉一共这些项，所以每档**只认自己的状态码**、不许互相串
 * （尤其「超期完成」「已取消」不能落进别的分类，否则用户点哪个都一样）。
 */
class TaskFilterTest {

    @Test
    fun `mine ignores the state and follows the mine set`() {
        assertTrue(taskMatchesFilter(PatrolTaskState.COMPLETED, TaskFilter.MINE, mineHit = true))
        assertFalse(taskMatchesFilter(PatrolTaskState.COMPLETED, TaskFilter.MINE, mineHit = false))
    }

    @Test
    fun `unlimited accepts every state`() {
        listOf(
            PatrolTaskState.NOT_ISSUED,
            PatrolTaskState.ISSUED,
            PatrolTaskState.RUNNING,
            PatrolTaskState.COMPLETED,
            PatrolTaskState.OVERDUE,
            PatrolTaskState.OVERDUE_COMPLETED,
            PatrolTaskState.CANCELLED,
            null,
        ).forEach { assertTrue("$it 应该被状态不限放行", taskMatchesFilter(it, TaskFilter.ALL, false)) }
    }

    @Test
    fun `each category matches exactly one server state`() {
        val pairs = listOf(
            TaskFilter.NOT_CLAIMED to PatrolTaskState.NOT_ISSUED,
            TaskFilter.ISSUED to PatrolTaskState.ISSUED,
            TaskFilter.RUNNING to PatrolTaskState.RUNNING,
            TaskFilter.DONE to PatrolTaskState.COMPLETED,
            TaskFilter.OVERDUE to PatrolTaskState.OVERDUE,
            TaskFilter.OVERDUE_DONE to PatrolTaskState.OVERDUE_COMPLETED,
            TaskFilter.CANCELLED to PatrolTaskState.CANCELLED,
        )
        val allStates = pairs.map { it.second }
        pairs.forEach { (filter, state) ->
            assertTrue("${filter.label} 应命中 $state", taskMatchesFilter(state, filter, false))
            allStates.filter { it != state }.forEach { other ->
                assertFalse(
                    "${filter.label} 不该命中 $other",
                    taskMatchesFilter(other, filter, false),
                )
            }
            assertFalse("${filter.label} 不该命中空状态", taskMatchesFilter(null, filter, false))
        }
    }

    @Test
    fun `category list follows the original dropdown order and excludes mine`() {
        assertFalse(TaskFilter.categories.contains(TaskFilter.MINE))
        assertEquals(
            listOf("状态不限", "未领取", "已领取", "执行中", "已完成", "已超期", "超期完成", "已取消"),
            TaskFilter.categories.map { it.label },
        )
    }

    @Test
    fun `status labels follow the server dictionary`() {
        assertEquals("未领取", PatrolTaskState.label(PatrolTaskState.NOT_ISSUED))
        assertEquals("已领取", PatrolTaskState.label(PatrolTaskState.ISSUED))
        assertEquals("执行中", PatrolTaskState.label(PatrolTaskState.RUNNING))
        assertEquals("已完成", PatrolTaskState.label(PatrolTaskState.COMPLETED))
        assertEquals("已超期", PatrolTaskState.label(PatrolTaskState.OVERDUE))
        assertEquals("超期完成", PatrolTaskState.label(PatrolTaskState.OVERDUE_COMPLETED))
        assertEquals("已取消", PatrolTaskState.label(PatrolTaskState.CANCELLED))
    }
}

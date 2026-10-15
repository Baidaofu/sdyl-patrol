package com.sdyl.patrol.feature.patrol

/**
 * 时间规则（用户 m12704）——开始任务、巡检点签到、提交任务三个界面共用同一套判据：
 *
 * ```
 * 计划开始 < 实际开始 < 最早巡检点 < 最晚巡检点 < 实际结束 < 计划结束
 * 任意两个巡检点之间间隔 ≥ 2 分钟
 * ```
 *
 * 传 0 的项表示「这一次不校验它」：开始任务界面只有实际开始时间，签到界面只有那一个点位，
 * 提交任务界面才有完整的一条链。纯函数，便于单测。
 */
object TimeRules {

    /** 巡检点之间至少 2 分钟（用户 m12704：120 秒以上） */
    const val MIN_GAP_MILLIS = 2 * 60_000L

    /**
     * @param points 巡检点 =（时间, 名称），名称只用于提示文案（一般取区域名）
     * @param fmt 把毫秒格式化成提示里显示的时间
     * @return 全部问题；空列表 = 通过
     */
    fun check(
        planStart: Long,
        planEnd: Long,
        start: Long,
        end: Long,
        points: List<Pair<Long, String>>,
        fmt: (Long) -> String,
    ): List<String> {
        val issues = mutableListOf<String>()

        if (start > 0L) {
            if (planStart > 0L && start <= planStart) {
                issues += "实际开始时间（${fmt(start)}）必须晚于计划开始时间（${fmt(planStart)}）"
            }
            if (planEnd > 0L && start >= planEnd) {
                issues += "实际开始时间（${fmt(start)}）必须早于计划结束时间（${fmt(planEnd)}）"
            }
            if (end > 0L && end <= start) {
                issues += "实际结束时间（${fmt(end)}）必须晚于实际开始时间（${fmt(start)}）"
            }
        }

        if (end > 0L) {
            if (planStart > 0L && end <= planStart) {
                issues += "实际结束时间（${fmt(end)}）必须晚于计划开始时间（${fmt(planStart)}）"
            }
            if (planEnd > 0L && end > planEnd) {
                issues += "实际结束时间（${fmt(end)}）不能晚于计划结束时间（${fmt(planEnd)}）"
            }
        }

        val sorted = points.filter { it.first > 0L }.sortedBy { it.first }
        if (sorted.isNotEmpty()) {
            val (earliest, earliestName) = sorted.first()
            val (latest, latestName) = sorted.last()

            if (planStart > 0L && earliest <= planStart) {
                issues += "巡检点「$earliestName」（${fmt(earliest)}）必须晚于计划开始时间（${fmt(planStart)}）"
            }
            if (planEnd > 0L && latest >= planEnd) {
                issues += "巡检点「$latestName」（${fmt(latest)}）必须早于计划结束时间（${fmt(planEnd)}）"
            }
            if (start > 0L && earliest <= start) {
                issues += "巡检点「$earliestName」（${fmt(earliest)}）必须晚于实际开始时间（${fmt(start)}）"
            }
            if (end > 0L && latest >= end) {
                issues += "巡检点「$latestName」（${fmt(latest)}）必须早于实际结束时间（${fmt(end)}）"
            }

            if (sorted.size >= 2) {
                sorted.zipWithNext().forEach { (a, b) ->
                    if (b.first - a.first < MIN_GAP_MILLIS) {
                        issues += "巡检点间隔不足 2 分钟：${a.second} ${fmt(a.first)} → " +
                            "${b.second} ${fmt(b.first)}"
                    }
                }
            }
        }
        return issues
    }
}

package com.sdyl.patrol.feature.patrol

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object PatrolRoutes {
    const val TASKS = "tasks"
    const val TASK = "task/{tableNo}"
    const val AREA = "area/{tableNo}/{areaId}"
    const val SIMPLIFIED = "simplified/{tableNo}"

    fun task(tableNo: String) = "task/${Uri.encode(tableNo)}"
    fun area(tableNo: String, areaId: String) = "area/${Uri.encode(tableNo)}/${Uri.encode(areaId)}"
    fun simplified(tableNo: String) = "simplified/${Uri.encode(tableNo)}"
}

@Composable
fun PatrolNav(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = PatrolRoutes.TASKS) {
        composable(PatrolRoutes.TASKS) {
            TaskListScreen(
                onOpenTask = { task ->
                    task.tableNo?.takeIf { it.isNotBlank() }?.let { tableNo ->
                        // 用户 m08985 第 3 条：开了「简化巡检」就直接进一页填时间的表单
                        if (com.sdyl.patrol.Graph.session.simplifiedPatrol) {
                            navController.navigate(PatrolRoutes.simplified(tableNo))
                        } else {
                            navController.navigate(PatrolRoutes.task(tableNo))
                        }
                    }
                },
            )
        }
        composable(PatrolRoutes.SIMPLIFIED) { entry ->
            val tableNo = Uri.decode(entry.arguments?.getString("tableNo").orEmpty())
            SimplifiedPatrolScreen(tableNo = tableNo, onBack = { navController.popBackStack() })
        }
        composable(PatrolRoutes.TASK) { entry ->
            val tableNo = Uri.decode(entry.arguments?.getString("tableNo").orEmpty())
            TaskDetailScreen(
                tableNo = tableNo,
                onBack = { navController.popBackStack() },
                onOpenArea = { areaId ->
                    navController.navigate(PatrolRoutes.area(tableNo, areaId))
                },
            )
        }
        composable(PatrolRoutes.AREA) { entry ->
            val tableNo = Uri.decode(entry.arguments?.getString("tableNo").orEmpty())
            val areaId = Uri.decode(entry.arguments?.getString("areaId").orEmpty())
            AreaWorkScreen(
                tableNo = tableNo,
                areaId = areaId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

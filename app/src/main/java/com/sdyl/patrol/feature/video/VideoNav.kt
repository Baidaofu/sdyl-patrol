package com.sdyl.patrol.feature.video

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object VideoRoutes {
    const val LIST = "video/list"
    const val PLAYER = "video/player/{channel}/{title}"

    fun player(channel: String, title: String): String =
        "video/player/$channel/${Uri.encode(title)}"
}

@Composable
fun VideoNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = VideoRoutes.LIST) {
        composable(VideoRoutes.LIST) {
            VideoScreen(onOpenChannel = { ch ->
                nav.navigate(VideoRoutes.player(ch.channel, ch.name))
            })
        }
        composable(
            route = VideoRoutes.PLAYER,
            arguments = listOf(
                navArgument("channel") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { entry ->
            VideoPlayerScreen(
                channel = entry.arguments?.getString("channel").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

package com.xdo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xdo.app.AppEvents
import com.xdo.app.ui.home.HomeScreen
import com.xdo.app.ui.home.HomeViewModel
import com.xdo.app.ui.player.PlayerScreen
import com.xdo.app.ui.resolve.ResolveScreen
import com.xdo.app.ui.resolve.ResolveViewModel
import com.xdo.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RESOLVE = "resolve"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
}

@Composable
fun AppRoot(onOpenSettings: () -> Unit = {}) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()

    // 分享/粘贴链接进来 → 解析并跳解析页
    LaunchedEffect(Unit) {
        // 冷启动兜底：订阅前收到的分享
        AppEvents.takeLastShare()?.let { homeViewModel.onShare(it) }
        AppEvents.shares.collect { text ->
            homeViewModel.onShare(text)
        }
    }
    LaunchedEffect(Unit) {
        homeViewModel.pendingResolve.collect { recordId ->
            if (recordId != null && recordId > 0) {
                navController.navigate("${Routes.RESOLVE}/$recordId") {
                    launchSingleTop = true
                }
                homeViewModel.clearPending()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenResolve = { id ->
                    navController.navigate("${Routes.RESOLVE}/$id") { launchSingleTop = true }
                },
                onOpenPlayer = { id ->
                    navController.navigate("${Routes.PLAYER}/$id") { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = "${Routes.RESOLVE}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val vm: ResolveViewModel = viewModel(key = "resolve_$id")
            ResolveScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onDone = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
        composable(
            route = "${Routes.PLAYER}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            PlayerScreen(
                recordId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
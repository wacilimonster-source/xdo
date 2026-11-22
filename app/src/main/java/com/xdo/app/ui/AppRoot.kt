package com.xdo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.xdo.app.ui.settings.UpdateDialog
import com.xdo.app.update.UpdateManager
import com.xdo.app.update.UpdateState

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

    // 启动时自动检查更新（6 小时节流）；CheckState 全局可见
    LaunchedEffect(Unit) {
        UpdateManager.autoCheck()
    }
    // P-06：更新弹窗单独放进 UpdateHost——APK 下载进度的高频更新只重组弹窗，
    // 不再让 AppRoot（以及它下面的 NavHost）跟着每拍重组
    UpdateHost()

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

    AppNavHost(navController, homeViewModel)
}

/** 全局更新弹窗 + 「安装未知应用」权限引导闭环 */
@Composable
private fun UpdateHost() {
    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    // B07：持有最近一次「发现新版本」的 info，Downloading/DownloadFailed 状态弹窗不消失
    val lastUpdateInfo by UpdateManager.lastAvailable.collectAsStateWithLifecycle()
    val updateInfo = (updateState as? UpdateState.Available)?.info ?: lastUpdateInfo
    val showUpdateDialog = updateState is UpdateState.Available ||
        updateState is UpdateState.Downloading ||
        updateState is UpdateState.DownloadFailed ||
        updateState is UpdateState.InstallBlocked
    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            info = updateInfo,
            state = updateState,
            onDownload = { UpdateManager.downloadAndInstall(updateInfo) },
            onCancelDownload = { UpdateManager.cancelDownload() },
            onOpenInstallSettings = { UpdateManager.openInstallPermissionSettings() },
            onDismiss = { UpdateManager.reset() },
        )
    }

    // 安装权限引导闭环：从系统设置回到应用时（ON_RESUME），权限已开启则自动继续安装已下载的 APK
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    // P-06：不再以 updateState 为 key（下载进度每变一次就重新注册观察者）；
    // continueInstall() 内部自己判断是否处于 InstallBlocked，其余状态直接返回
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                UpdateManager.continueInstall()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    homeViewModel: HomeViewModel,
) {
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
                recordId = id,
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
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

package com.example.adb_connection.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.capturetoolplaceholdercaptures.CaptureToolPlaceholderCapturesScreen
import com.example.adb_connection.ui.home.HomeScreen
import com.example.adb_connection.ui.home.HomeViewModel
import com.example.adb_connection.ui.home.HomeViewModelFactory
import com.example.adb_connection.ui.settings.SettingsScreen
import com.example.adb_connection.ui.usbcopy.UsbCopyScreen
import com.example.adb_connection.ui.usbfiles.UsbFilesScreen
import com.example.adb_connection.ui.wifi.WifiSetupScreen

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object WifiSetup : Screen("wifi_setup", "WiFi", Icons.Default.Wifi)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object UsbCopy : Screen("usb_copy", "USB Copy", Icons.Default.Home)
    object UsbFiles : Screen("usb_files", "USB Files", Icons.Default.Home)
    object CaptureToolPlaceholderCaptures : Screen("capture_tool_placeholder_captures", "CAPTURE_TOOL_PLACEHOLDER Captures", Icons.Default.Home)
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val isAutoNavEnabled by settingsRepository.isAutoNavigationEnabled.collectAsState(initial = false)
    val isDebugMode by settingsRepository.isDebugModeEnabled.collectAsState(initial = false)

    val navController = rememberNavController()
    val items = listOf(
        Screen.Home,
        Screen.WifiSetup,
        Screen.Settings,
    )

    Scaffold(
        bottomBar = {
            if (!isAutoNavEnabled) {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                val debugTargets = if (isDebugMode) listOf(
                    DebugNavTarget("WiFi Setup") { navController.navigate(Screen.WifiSetup.route) },
                    DebugNavTarget("Settings") { navController.navigate(Screen.Settings.route) },
                    DebugNavTarget("USB Copy") { navController.navigate(Screen.UsbCopy.route) },
                    DebugNavTarget("USB Files") { navController.navigate(Screen.UsbFiles.route) },
                ) else emptyList()
                HomeScreen(
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToWifi = { navController.navigate(Screen.WifiSetup.route) },
                    onNavigateToUsbCopy = { navController.navigate(Screen.UsbCopy.route) },
                    onNavigateToCaptureToolPlaceholderCaptures = { navController.navigate(Screen.CaptureToolPlaceholderCaptures.route) },
                    onNavigateToUsbFiles = { navController.navigate(Screen.UsbFiles.route) },
                    debugNavTargets = debugTargets
                )
            }
            composable(Screen.UsbFiles.route) {
                val debugTargets = if (isDebugMode) listOf(
                    DebugNavTarget("Home") { navController.navigate(Screen.Home.route) },
                    DebugNavTarget("WiFi Setup") { navController.navigate(Screen.WifiSetup.route) },
                    DebugNavTarget("Settings") { navController.navigate(Screen.Settings.route) },
                    DebugNavTarget("USB Copy") { navController.navigate(Screen.UsbCopy.route) },
                ) else emptyList()
                UsbFilesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    debugNavTargets = debugTargets
                )
            }
            composable(Screen.UsbCopy.route) {
                val debugTargets = if (isDebugMode) listOf(
                    DebugNavTarget("Home") { navController.navigate(Screen.Home.route) },
                    DebugNavTarget("WiFi Setup") { navController.navigate(Screen.WifiSetup.route) },
                    DebugNavTarget("Settings") { navController.navigate(Screen.Settings.route) },
                    DebugNavTarget("USB Files") { navController.navigate(Screen.UsbFiles.route) },
                ) else emptyList()
                UsbCopyScreen(
                    onNavigateBack = { navController.popBackStack() },
                    debugNavTargets = debugTargets
                )
            }
            composable(Screen.CaptureToolPlaceholderCaptures.route) {
                val debugTargets = if (isDebugMode) listOf(
                    DebugNavTarget("Home") { navController.navigate(Screen.Home.route) },
                    DebugNavTarget("WiFi Setup") { navController.navigate(Screen.WifiSetup.route) },
                    DebugNavTarget("Settings") { navController.navigate(Screen.Settings.route) },
                    DebugNavTarget("USB Copy") { navController.navigate(Screen.UsbCopy.route) },
                    DebugNavTarget("USB Files") { navController.navigate(Screen.UsbFiles.route) },
                ) else emptyList()
                CaptureToolPlaceholderCapturesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    debugNavTargets = debugTargets
                )
            }
            composable(Screen.WifiSetup.route) {
                val homeBackStackEntry = remember(navController) {
                    navController.getBackStackEntry(Screen.Home.route)
                }
                val homeViewModel: HomeViewModel = viewModel(
                    viewModelStoreOwner = homeBackStackEntry,
                    factory = HomeViewModelFactory(context)
                )
                val eRelease by homeViewModel.eReleaseValue.collectAsState()
                val debugTargets = if (isDebugMode) listOf(
                    DebugNavTarget("Home") { navController.navigate(Screen.Home.route) },
                    DebugNavTarget("Settings") { navController.navigate(Screen.Settings.route) },
                    DebugNavTarget("USB Copy") { navController.navigate(Screen.UsbCopy.route) },
                    DebugNavTarget("USB Files") { navController.navigate(Screen.UsbFiles.route) },
                ) else emptyList()
                WifiSetupScreen(
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToHome = { navController.navigate(Screen.Home.route) },
                    debugNavTargets = debugTargets,
                    eRelease = eRelease
                )
            }
            composable(Screen.Settings.route) {
                val debugTargets = if (isDebugMode) listOf(
                    DebugNavTarget("Home") { navController.navigate(Screen.Home.route) },
                    DebugNavTarget("WiFi Setup") { navController.navigate(Screen.WifiSetup.route) },
                    DebugNavTarget("USB Copy") { navController.navigate(Screen.UsbCopy.route) },
                    DebugNavTarget("USB Files") { navController.navigate(Screen.UsbFiles.route) },
                ) else emptyList()
                SettingsScreen(
                    onNavigateToHome = { navController.navigate(Screen.Home.route) },
                    onNavigateToWifi = { navController.navigate(Screen.WifiSetup.route) },
                    debugNavTargets = debugTargets
                )
            }
        }
    }
}

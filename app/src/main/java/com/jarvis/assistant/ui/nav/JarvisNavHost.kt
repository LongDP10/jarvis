package com.jarvis.assistant.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.ui.chat.ChatScreen
import com.jarvis.assistant.ui.debug.DebugConsoleScreen
import com.jarvis.assistant.ui.history.HistoryScreen
import com.jarvis.assistant.ui.home.HomeScreen
import com.jarvis.assistant.ui.onboarding.OnboardingScreen
import com.jarvis.assistant.ui.settings.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val DEBUG = "debug"
}

@Composable
fun JarvisNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        // Onboarding is a one-time gate; leaving it on the back
                        // stack would let the user reverse into it forever.
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { navController.navigate(Routes.CHAT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenDebug = { navController.navigate(Routes.DEBUG) },
            )
        }

        composable(Routes.CHAT) {
            ChatScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDebug = { navController.navigate(Routes.DEBUG) },
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DEBUG) {
            DebugConsoleScreen(onBack = { navController.popBackStack() })
        }
    }
}

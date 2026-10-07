package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.repository.DarkTalkRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.AuthViewModel
import com.example.ui.chat.ChatRoomScreen
import com.example.ui.chat.ChatRoomViewModel
import com.example.ui.chats.ChatsScreen
import com.example.ui.chats.ChatsViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkTalkTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as DarkTalkApplication).repository

        setContent {
            var currentTheme by remember {
                mutableStateOf(repository.preferencesManager.appTheme)
            }

            LaunchedEffect(Unit) {
                while (true) {
                    val saved = repository.preferencesManager.appTheme
                    if (saved != currentTheme) {
                        currentTheme = saved
                    }
                    delay(300)
                }
            }

            DarkTalkTheme(themeName = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    DarkTalkNavGraph(repository = repository)
                }
            }
        }
    }
}

class CustomViewModelFactory<T : ViewModel>(
    private val creator: () -> T
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
        return creator() as VM
    }
}

@Composable
fun DarkTalkNavGraph(repository: DarkTalkRepository) {
    val navController = rememberNavController()
    val startDestination = if (repository.preferencesManager.isLoggedIn) "chats" else "auth"

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("auth") {
            val authViewModel: AuthViewModel = viewModel(
                factory = CustomViewModelFactory { AuthViewModel(repository) }
            )
            AuthScreen(
                viewModel = authViewModel,
                serverDomain = repository.preferencesManager.serverDomain,
                onNavigateToSettings = { navController.navigate("settings") },
                onAuthSuccess = {
                    navController.navigate("chats") {
                        popUpTo("auth") { inclusive = true }
                    }
                }
            )
        }

        composable("chats") {
            val chatsViewModel: ChatsViewModel = viewModel(
                factory = CustomViewModelFactory { ChatsViewModel(repository) }
            )
            ChatsScreen(
                viewModel = chatsViewModel,
                currentUserId = repository.preferencesManager.userId,
                onChatClick = { chatId, title ->
                    val encodedTitle = Uri.encode(title)
                    navController.navigate("chat_room/$chatId/$encodedTitle")
                },
                onNavigateToProfile = { navController.navigate("profile") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }

        composable(
            route = "chat_room/{chatId}/{chatTitle}",
            arguments = listOf(
                navArgument("chatId") { type = NavType.LongType },
                navArgument("chatTitle") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong("chatId") ?: 0L
            val rawTitle = backStackEntry.arguments?.getString("chatTitle") ?: "Chat"
            val chatTitle = Uri.decode(rawTitle)

            val chatRoomViewModel: ChatRoomViewModel = viewModel(
                key = "chat_room_$chatId",
                factory = CustomViewModelFactory { ChatRoomViewModel(chatId, repository) }
            )

            ChatRoomScreen(
                chatTitle = chatTitle,
                viewModel = chatRoomViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("profile") {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = CustomViewModelFactory { SettingsViewModel(repository) }
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                initialTab = 0,
                onBackClick = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate("auth") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("settings") {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = CustomViewModelFactory { SettingsViewModel(repository) }
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                initialTab = 0,
                onBackClick = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate("auth") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}

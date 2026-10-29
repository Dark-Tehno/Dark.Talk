package com.example

import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.call.CallNotifications
import com.example.call.CallPhase
import com.example.data.repository.DarkTalkRepository
import com.example.ui.call.CallHost
import com.example.ui.call.rememberCallPermissionRequester
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.AuthViewModel
import com.example.ui.chat.ChatRoomScreen
import com.example.ui.chat.ChatRoomViewModel
import com.example.ui.chats.ChatsScreen
import com.example.ui.chats.ChatsViewModel
import com.example.ui.components.GlassBackdrop
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.DarkTalkTheme
import com.example.ui.theme.TextPrimary
import com.example.util.LocalAppStrings
import com.example.util.getTranslations

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Экран входящего звонка поверх блокировки
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        pendingAnswer = intent?.action == CallNotifications.ACTION_ANSWER

        val app = application as DarkTalkApplication
        val repository = app.repository

        setContent {
            // Реактивная тема и реактивный язык приложения
            val themeName by repository.preferencesManager.appThemeFlow.collectAsState()
            val userLanguage by repository.preferencesManager.userLanguageFlow.collectAsState()
            val translations = remember(userLanguage) { getTranslations(userLanguage) }

            CompositionLocalProvider(
                LocalAppStrings provides translations
            ) {
                DarkTalkTheme(themeName = themeName) {
                    GlassBackdrop {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent,
                            contentColor = TextPrimary
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                DarkTalkNavGraph(repository = repository)
                                CallHost(manager = app.callManager)
                                AnswerFromNotification(app)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CallNotifications.ACTION_ANSWER) pendingAnswer = true
    }

    companion object {
        /** Пользователь нажал «Принять» в уведомлении: принимаем после запроса разрешений. */
        var pendingAnswerState by androidx.compose.runtime.mutableStateOf(false)
    }

    private var pendingAnswer: Boolean
        get() = pendingAnswerState
        set(value) { pendingAnswerState = value }
}

@Composable
private fun AnswerFromNotification(app: DarkTalkApplication) {
    val state by app.callManager.state.collectAsState()
    val requestPermissions = rememberCallPermissionRequester()
    val wanted = MainActivity.pendingAnswerState
    LaunchedEffect(wanted, state.phase) {
        if (!wanted) return@LaunchedEffect
        if (state.phase == CallPhase.INCOMING) {
            MainActivity.pendingAnswerState = false
            requestPermissions(state.isVideoCall) { app.callManager.accept() }
        } else if (state.phase != CallPhase.IDLE || state.callId == null) {
            // звонок уже завершён или ещё не пришёл: не держим флаг дольше нескольких секунд
            kotlinx.coroutines.delay(5000)
            MainActivity.pendingAnswerState = false
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

    // Токен отозван (401) / устройство удалено -> возвращаем на экран входа
    val loggedIn by repository.preferencesManager.isLoggedInFlow.collectAsState()
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(loggedIn, route) {
        if (!loggedIn && route != null && route != "auth" && route != "settings") {
            repository.logout()
            navController.navigate("auth") { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("auth") {
            val authViewModel: AuthViewModel = viewModel(
                factory = CustomViewModelFactory { AuthViewModel(repository) }
            )
            AuthScreen(
                viewModel = authViewModel,
                serverDomain = repository.preferencesManager.serverDomain,
                onNavigateToSettings = { navController.navigate("settings") },
                onAuthSuccess = {
                    navController.navigate("chats") { popUpTo("auth") { inclusive = true } }
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
                    navController.navigate("chat_room/$chatId/${Uri.encode(title)}")
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
            val chatTitle = Uri.decode(backStackEntry.arguments?.getString("chatTitle") ?: "Chat")

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
                onLoggedOut = { navController.navigate("auth") { popUpTo(0) { inclusive = true } } }
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
                onLoggedOut = { navController.navigate("auth") { popUpTo(0) { inclusive = true } } }
            )
        }
    }
}

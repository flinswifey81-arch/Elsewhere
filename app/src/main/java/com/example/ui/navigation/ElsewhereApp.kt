package com.example.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.di.AppContainer
import com.example.ui.screens.*

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : Screen("home", "Home", Icons.Filled.Home)
    object Characters : Screen("characters", "Characters", Icons.Filled.Person)
    object Personas : Screen("personas", "Personas", Icons.Filled.Face)
    object Chats : Screen("chats", "Chats", Icons.AutoMirrored.Filled.Chat)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Characters,
    Screen.Personas,
    Screen.Chats,
    Screen.Settings
)

@Composable
fun ElsewhereApp(container: AppContainer) {
    val navController = rememberNavController()
    
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selected,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent
                        ),
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { 
                HomeScreen(
                    chatRepository = container.chatRepository,
                    onNavigateToChat = { chatId -> navController.navigate("chat_detail/$chatId") },
                    onNavigateToLibrary = { navController.navigate(Screen.Characters.route) },
                    onNewSoloChat = { navController.navigate("create_solo_chat") },
                    onNewGroupChat = { navController.navigate("create_group_chat") }
                ) 
            }
            composable(Screen.Characters.route) { 
                CharactersScreen(
                    repository = container.characterRepository,
                    onCharacterSelected = { /* TODO */ }
                ) 
            }
            composable(Screen.Personas.route) { 
                PersonasScreen(
                    repository = container.personaRepository,
                    onPersonaSelected = { /* TODO */ }
                ) 
            }
            composable(Screen.Chats.route) { 
                ChatsScreen(
                    chatRepository = container.chatRepository,
                    onNavigateToChat = { chatId -> navController.navigate("chat_detail/$chatId") },
                    onNewSoloChat = { navController.navigate("create_solo_chat") },
                    onNewGroupChat = { navController.navigate("create_group_chat") }
                ) 
            }
            composable(Screen.Settings.route) { 
                SettingsScreen(
                    settingsRepository = container.settingsRepository,
                    appearanceRepository = container.appearanceRepository
                ) 
            }
            
            composable("create_solo_chat") {
                CreateSoloChatScreen(
                    chatRepository = container.chatRepository,
                    personaRepository = container.personaRepository,
                    characterRepository = container.characterRepository,
                    onChatCreated = { chatId -> 
                        navController.popBackStack()
                        navController.navigate("chat_detail/$chatId")
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            
            composable(
                "chat_settings/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                ChatSettingsScreen(
                    chatId = chatId,
                    chatRepository = container.chatRepository,
                    modelProvider = container.modelProvider,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("create_group_chat") {
                CreateGroupChatScreen(
                    chatRepository = container.chatRepository,
                    personaRepository = container.personaRepository,
                    characterRepository = container.characterRepository,
                    onChatCreated = { chatId -> 
                        navController.popBackStack()
                        navController.navigate("chat_detail/$chatId")
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            
            composable(
                "chat_detail/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                ChatDetailScreen(
                    chatId = chatId,
                    appContainer = container,
                    onNavigateBack = { navController.popBackStack() },
                    onChatDeleted = {
                        navController.navigate(Screen.Chats.route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                    onNavigateToInspector = { navController.navigate("context_inspector/$chatId") },
                    onNavigateToSettings = { navController.navigate("chat_settings/$chatId") },
                )
            }
            composable(
                "context_inspector/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                ContextInspectorScreen(
                    chatId = chatId,
                    appContainer = container,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

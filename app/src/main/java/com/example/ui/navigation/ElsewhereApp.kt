package com.example.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
    object Scenarios : Screen("scenarios", "Scenarios", Icons.Filled.AutoStories)
    object Personas : Screen("personas", "Personas", Icons.Filled.Face)
    object Lorebooks : Screen("lorebooks", "Lorebooks", Icons.Filled.MenuBook)
    object Chats : Screen("chats", "Chats", Icons.AutoMirrored.Filled.Chat)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Characters,
    Screen.Personas,
    Screen.Scenarios,
    Screen.Lorebooks,
    Screen.Chats,
    Screen.Settings
)

@Composable
fun ElsewhereApp(container: AppContainer) {
    val navController = rememberNavController()
    val isLightPalette = MaterialTheme.colorScheme.background.luminance() > 0.5f
    fun navigateTo(screen: Screen) {
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    
    Scaffold(
        bottomBar = {
            BoxWithConstraints {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                val fontScale = LocalDensity.current.fontScale
                val compact = maxWidth < 520.dp || fontScale > 1.15f
                val highScale = fontScale > 1.3f
                val visibleItems = when {
                    !compact -> bottomNavItems
                    highScale -> listOf(Screen.Home, Screen.Lorebooks, Screen.Chats)
                    else -> listOf(Screen.Home, Screen.Characters, Screen.Personas, Screen.Lorebooks)
                }
                val overflowItems = bottomNavItems - visibleItems.toSet()
                var showMore by remember { mutableStateOf(false) }
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = if (isLightPalette) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary,
                    selectedTextColor = if (isLightPalette) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = if (isLightPalette) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    tonalElevation = 3.dp
                ) {
                    visibleItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title, maxLines = 1, softWrap = false) },
                            selected = selected,
                            colors = itemColors,
                            onClick = { navigateTo(screen) }
                        )
                    }
                    if (overflowItems.isNotEmpty()) {
                        val overflowSelected = overflowItems.any { screen ->
                            currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        }
                        NavigationBarItem(
                            icon = {
                                Box {
                                    Icon(Icons.Filled.MoreHoriz, contentDescription = "More navigation")
                                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                        overflowItems.forEach { screen ->
                                            DropdownMenuItem(
                                                text = { Text(screen.title, maxLines = 1, softWrap = false) },
                                                leadingIcon = { Icon(screen.icon, contentDescription = null) },
                                                onClick = {
                                                    showMore = false
                                                    navigateTo(screen)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            label = { Text("More", maxLines = 1, softWrap = false) },
                            selected = overflowSelected,
                            colors = itemColors,
                            onClick = { showMore = true }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Screen.Home.route) { 
                HomeScreen(
                    chatRepository = container.chatRepository,
                    onNavigateToChat = { chatId -> navController.navigate("chat_detail/$chatId") },
                    onNavigateToLibrary = { navController.navigate(Screen.Characters.route) },
                    onNavigateToLorebooks = { navController.navigate(Screen.Lorebooks.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToChats = { navController.navigate(Screen.Chats.route) },
                    onNewSoloChat = { navController.navigate("create_solo_chat") },
                    onNewGroupChat = { navController.navigate("create_group_chat") },
                    onScenarios = { navController.navigate(Screen.Scenarios.route) }
                ) 
            }
            composable(Screen.Characters.route) { 
                CharactersScreen(
                    repository = container.characterRepository,
                    profileImageRepository = container.profileImageRepository,
                    onCharacterSelected = { /* TODO */ }
                ) 
            }
            composable(Screen.Scenarios.route) {
                ScenariosScreen(container, onChatCreated = { navController.navigate("chat_detail/$it") },
                    onPersonas = { navController.navigate(Screen.Personas.route) })
            }
            composable(Screen.Personas.route) { 
                PersonasScreen(
                    repository = container.personaRepository,
                    profileImageRepository = container.profileImageRepository,
                    onPersonaSelected = { /* TODO */ }
                ) 
            }
            composable(Screen.Lorebooks.route) {
                val repository = container.lorebookRepository
                if (repository == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("Lorebooks are unavailable in this environment.")
                    }
                } else {
                    LorebooksScreen(repository = repository)
                }
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
                    appearanceRepository = container.appearanceRepository,
                    backupRepository = container.backupRepository
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
                    onNavigateToInspector = { characterId ->
                        navController.navigate("context_inspector/$chatId?characterId=${Uri.encode(characterId.orEmpty())}")
                    },
                    onNavigateToLorebooks = { navController.navigate("chat_lorebooks/$chatId") },
                    onNavigateToNpcBank = { navController.navigate("chat_npc_bank/$chatId") },
                    onNavigateToSettings = { navController.navigate("chat_settings/$chatId") },
                )
            }
            composable(
                "chat_npc_bank/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                val repository = container.npcRepository
                if (repository == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("NPC Bank is unavailable in this environment.")
                    }
                } else {
                    NpcBankScreen(
                        chatId = chatId,
                        repository = repository,
                        chatRepository = container.chatRepository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
            // Keep the former destination as a non-interactive redirect so restored navigation
            // state from an older build cannot expose controls or strand the user. This route
            // never reads the co-op manager and therefore cannot host, join, or resume a room.
            composable(
                "chat_coop/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId").orEmpty()
                LaunchedEffect(chatId) {
                    if (chatId.isNotBlank()) {
                        navController.navigate("chat_detail/$chatId") {
                            popUpTo(backStackEntry.destination.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            composable(
                "chat_lorebooks/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                val repository = container.lorebookRepository
                if (repository == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("Lorebooks are unavailable in this environment.")
                    }
                } else {
                    ChatLorebooksScreen(
                        chatId = chatId,
                        repository = repository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                "context_inspector/{chatId}?characterId={characterId}",
                arguments = listOf(
                    navArgument("chatId") { type = NavType.StringType },
                    navArgument("characterId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                val characterId = backStackEntry.arguments?.getString("characterId")?.takeIf(String::isNotBlank)
                ContextInspectorScreen(
                    chatId = chatId,
                    characterId = characterId,
                    appContainer = container,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

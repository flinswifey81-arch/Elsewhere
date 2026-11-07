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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.example.domain.repository.AppearanceSettings
import androidx.compose.runtime.collectAsState
import com.example.ui.screens.*

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : Screen("home", "Home", Icons.Filled.Home)
    object Chats : Screen("chats", "Chats", Icons.AutoMirrored.Filled.Chat)
    object Create : Screen("create_hub", "Create", Icons.Filled.AddCircle)
    object Worlds : Screen("worlds", "Worlds", Icons.Filled.Public)
    object Library : Screen("library_hub", "Library", Icons.Filled.Bookmarks)
    object Characters : Screen("characters", "Characters", Icons.Filled.Person)
    object Personas : Screen("personas", "Personas", Icons.Filled.Face)
    object Lorebooks : Screen("lorebooks", "Lorebooks", Icons.Filled.MenuBook)
    object Scenarios : Screen("scenarios", "Scenarios", Icons.Filled.AutoStories)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Chats,
    Screen.Create,
    Screen.Worlds,
    Screen.Library
)

@Composable
fun ElsewhereApp(
    container: AppContainer,
    requestedChatId: String? = null,
    onRequestedChatHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    LaunchedEffect(requestedChatId) {
        val target = requestedChatId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        navController.navigate("chat_detail/${Uri.encode(target)}") {
            launchSingleTop = true
        }
        onRequestedChatHandled()
    }
    val isLightPalette = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val appearanceSettings by container.appearanceRepository.appearanceSettings.collectAsState(initial = AppearanceSettings())
    fun navigateTo(screen: Screen) {
        val currentRoute = navController.currentDestination?.route
        // A child page (Characters, Personas, Lorebooks or Settings) belongs to the
        // Library tab. Tapping the tab must return to its hub, not restore the
        // child page as the selected destination.
        val libraryChild = currentRoute in listOf(
            Screen.Characters.route, Screen.Personas.route,
            Screen.Lorebooks.route, Screen.Settings.route, "npc_bank_picker"
        )
        val createChild = currentRoute in listOf(
            Screen.Scenarios.route, "create_solo_chat", "create_group_chat"
        )
        if ((screen == Screen.Library && libraryChild) ||
            (screen == Screen.Create && createChild)
        ) {
            if (navController.popBackStack(screen.route, inclusive = false)) return
        }
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = !(screen == Screen.Library && libraryChild) &&
                !(screen == Screen.Create && createChild)
        }
    }
    
    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val activeRoute = currentDestination?.route
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
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true ||
                        (screen == Screen.Library && activeRoute in listOf(
                            Screen.Characters.route, Screen.Personas.route, Screen.Lorebooks.route, Screen.Settings.route, "npc_bank_picker"
                        )) ||
                        (screen == Screen.Create && activeRoute in listOf(
                            Screen.Scenarios.route, "create_solo_chat", "create_group_chat"
                        ))
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title, maxLines = 1, softWrap = false) },
                        selected = selected,
                        colors = itemColors,
                        onClick = { navigateTo(screen) },
                        modifier = Modifier.testTag("bottom_nav_" + screen.title.lowercase())
                    )
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
                    onNavigateToLibrary = { navController.navigate(Screen.Library.route) },
                    palette = appearanceSettings.colorPalette,
                    onNavigateToLorebooks = { navController.navigate(Screen.Lorebooks.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToChats = { navController.navigate(Screen.Chats.route) },
                    onNewSoloChat = { navController.navigate("create_solo_chat") },
                    onNewGroupChat = { navController.navigate("create_group_chat") },
                    onScenarios = { navController.navigate(Screen.Scenarios.route) }
                ) 
            }
            composable(Screen.Create.route) {
                CreateHubScreen(
                    onSolo = { navController.navigate("create_solo_chat") },
                    onGroup = { navController.navigate("create_group_chat") },
                    onScenario = { navController.navigate(Screen.Scenarios.route) },
                    onWorlds = { navController.navigate(Screen.Worlds.route) }
                )
            }
            composable(Screen.Library.route) {
                LibraryHubScreen(
                    onCharacters = { navController.navigate(Screen.Characters.route) },
                    onPersonas = { navController.navigate(Screen.Personas.route) },
                    onLorebooks = { navController.navigate(Screen.Lorebooks.route) },
                    onNpcChats = { navController.navigate("npc_bank_picker") },
                    onSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable("npc_bank_picker") {
                NpcBankChatPickerScreen(
                    chatRepository = container.chatRepository,
                    onOpenNpcBank = { chatId ->
                        navController.navigate("chat_npc_bank/" + Uri.encode(chatId))
                    }
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
            composable(Screen.Worlds.route) {
                val repository = container.worldRepository
                if (repository == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("Worlds are unavailable in this environment.")
                    }
                } else {
                    WorldsScreen(
                        repository = repository,
                        // Full Worlds archive UI belongs in normal Elsewhere, not a preview APK.
                        archiveTransfer = container.worldArchiveTransfer,
                        chatRepository = container.chatRepository,
                        onOpenChat = { chatId ->
                            navController.navigate("chat_detail/${Uri.encode(chatId)}")
                        },
                        onCreateSoloEpisode = { worldId ->
                            navController.navigate("world_create_solo/${Uri.encode(worldId)}")
                        },
                        onCreateScenarioEpisode = { worldId ->
                            navController.navigate("world_scenarios/${Uri.encode(worldId)}")
                        }
                    )
                }
            }
            composable(
                "world_scenarios/{worldId}",
                arguments = listOf(navArgument("worldId") { type = NavType.StringType })
            ) { backStackEntry ->
                val worldId = backStackEntry.arguments?.getString("worldId").orEmpty()
                ScenariosScreen(
                    container,
                    onChatCreated = { chatId -> navController.navigate("chat_detail/${Uri.encode(chatId)}") },
                    onPersonas = { navController.navigate(Screen.Personas.route) },
                    initialWorldId = worldId
                )
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
                    chatRepository = container.chatRepository,
                    modelProvider = container.modelProvider,
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
            
            // A World episode uses the existing Solo chat creator, then records its
            // membership before opening it. A failed link never deletes the new chat.
            composable(
                "world_create_solo/{worldId}",
                arguments = listOf(navArgument("worldId") { type = NavType.StringType })
            ) { backStackEntry ->
                val worldId = backStackEntry.arguments?.getString("worldId").orEmpty()
                val repository = container.worldRepository
                val scope = rememberCoroutineScope()
                var createdButUnlinked by remember { mutableStateOf<String?>(null) }
                var linkError by remember { mutableStateOf<String?>(null) }
                fun openCreatedChat(chatId: String) {
                    navController.navigate("chat_detail/${Uri.encode(chatId)}") {
                        popUpTo("world_create_solo/{worldId}") { inclusive = true }
                    }
                }
                CreateSoloChatScreen(
                    chatRepository = container.chatRepository,
                    personaRepository = container.personaRepository,
                    characterRepository = container.characterRepository,
                    onChatCreated = { chatId ->
                        scope.launch {
                            try {
                                requireNotNull(repository) { "Worlds repository is unavailable" }
                                    .attach(worldId, com.example.data.model.WorldResourceType.CHAT, chatId)
                                openCreatedChat(chatId)
                            } catch (failure: Exception) {
                                createdButUnlinked = chatId
                                linkError = failure.message ?: "Could not link new episode to this World."
                            }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
                val unlinkedId = createdButUnlinked
                if (unlinkedId != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Episode created but not linked") },
                        text = {
                            Text(
                                (linkError ?: "Linking failed.") +
                                    " The chat was saved. You can retry linking it or open it " +
                                    "and attach it to the World later."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        requireNotNull(repository).attach(
                                            worldId, com.example.data.model.WorldResourceType.CHAT, unlinkedId
                                        )
                                        createdButUnlinked = null
                                        openCreatedChat(unlinkedId)
                                    } catch (failure: Exception) {
                                        linkError = failure.message ?: "Linking still failed."
                                    }
                                }
                            }) { Text("Retry link") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                createdButUnlinked = null
                                openCreatedChat(unlinkedId)
                            }) { Text("Open unlinked chat") }
                        }
                    )
                }
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
                        onNavigateBack = { navController.popBackStack() },
                        profileImageRepository = container.profileImageRepository
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

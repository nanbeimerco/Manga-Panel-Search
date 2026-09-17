package com.mangapanel.search

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangapanel.search.data.api.MangaApiClient
import com.mangapanel.search.data.engine.LocalSearchEngine
import com.mangapanel.search.data.local.LocalArchiveManager
import com.mangapanel.search.ui.screens.LibraryScreen
import com.mangapanel.search.ui.screens.SearchScreen
import com.mangapanel.search.ui.screens.SettingsScreen
import com.mangapanel.search.ui.theme.ColorPresets
import com.mangapanel.search.ui.theme.MangaPanelSearchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("manga_search_prefs", Context.MODE_PRIVATE) }

            val savedThemeId = remember { prefs.getString("theme_preset", ColorPresets.TITANIUM_SLATE.id) ?: ColorPresets.TITANIUM_SLATE.id }
            var currentPreset by remember { mutableStateOf(ColorPresets.fromId(savedThemeId)) }

            val savedServerUrl = remember { prefs.getString("server_url", "http://10.0.2.2:8088") ?: "http://10.0.2.2:8088" }
            val apiClient = remember { MangaApiClient(baseUrl = savedServerUrl) }

            var isLocalMode by remember { mutableStateOf(prefs.getBoolean("is_local_mode", true)) }
            val localArchiveManager = remember { LocalArchiveManager(context) }
            val localSearchEngine = remember { LocalSearchEngine(context) }

            var selectedTab by remember { mutableIntStateOf(0) }

            MangaPanelSearchTheme(preset = currentPreset) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = {
                                Text(
                                    text = "MANGA PANEL SEARCH",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = {}) {
                                    Icon(
                                        Icons.Default.AutoStories,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            actions = {
                                FilterChip(
                                    selected = isLocalMode,
                                    onClick = {
                                        isLocalMode = !isLocalMode
                                        prefs.edit().putBoolean("is_local_mode", isLocalMode).apply()
                                    },
                                    label = {
                                        Text(
                                            text = if (isLocalMode) "📱 端末単体" else "💻 PC連携",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    },
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Crop, contentDescription = "コマ検索") },
                                label = { Text("コマ検索") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.LibraryBooks, contentDescription = "ライブラリ") },
                                label = { Text("ライブラリ") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "設定") },
                                label = { Text("設定") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (selectedTab) {
                        0 -> SearchScreen(
                            isLocalMode = isLocalMode,
                            localArchiveManager = localArchiveManager,
                            localSearchEngine = localSearchEngine,
                            apiClient = apiClient,
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> LibraryScreen(
                            isLocalMode = isLocalMode,
                            localArchiveManager = localArchiveManager,
                            localSearchEngine = localSearchEngine,
                            apiClient = apiClient,
                            modifier = Modifier.padding(innerPadding)
                        )
                        2 -> SettingsScreen(
                            isLocalMode = isLocalMode,
                            onModeChanged = { newMode ->
                                isLocalMode = newMode
                                prefs.edit().putBoolean("is_local_mode", newMode).apply()
                            },
                            localArchiveManager = localArchiveManager,
                            apiClient = apiClient,
                            currentPreset = currentPreset,
                            onPresetSelected = { newPreset ->
                                currentPreset = newPreset
                                prefs.edit().putString("theme_preset", newPreset.id).apply()
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

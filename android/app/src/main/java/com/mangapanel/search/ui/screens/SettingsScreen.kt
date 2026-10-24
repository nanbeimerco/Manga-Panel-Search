package com.mangapanel.search.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangapanel.search.data.api.MangaApiClient
import com.mangapanel.search.data.local.LocalArchiveManager
import com.mangapanel.search.data.local.RegisteredUriItem
import com.mangapanel.search.data.model.TargetPathItem
import com.mangapanel.search.ui.theme.AppThemeMode
import com.mangapanel.search.ui.theme.ColorPresetSpec
import com.mangapanel.search.ui.theme.ColorPresets
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
    isLocalMode: Boolean,
    onModeChanged: (Boolean) -> Unit,
    localArchiveManager: LocalArchiveManager,
    apiClient: MangaApiClient,
    currentPreset: ColorPresetSpec,
    onPresetSelected: (ColorPresetSpec) -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onDynamicColorChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(apiClient.baseUrl) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    // Local registered items
    var localItems by remember { mutableStateOf<List<RegisteredUriItem>>(emptyList()) }
    var localMessage by remember { mutableStateOf<String?>(null) }

    fun refreshLocalItems() {
        scope.launch {
            localItems = localArchiveManager.getRegisteredUris()
            localArchiveManager.getArchivesAsync(forceRescan = true)
        }
    }

    LaunchedEffect(Unit) {
        refreshLocalItems()
    }

    // Remote registered items
    var remotePaths by remember { mutableStateOf<List<TargetPathItem>>(emptyList()) }

    fun refreshRemotePaths() {
        if (!isLocalMode) {
            scope.launch {
                apiClient.getTargetPaths().onSuccess {
                    remotePaths = it.paths
                }
            }
        }
    }

    LaunchedEffect(isLocalMode, apiClient.baseUrl) {
        refreshRemotePaths()
    }

    // SAF Launchers (Folder Tree & Multiple Files)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val success = localArchiveManager.addTreeUri(it)
            if (success) {
                localMessage = "フォルダを追加しました"
                refreshLocalItems()
            } else {
                localMessage = "フォルダの登録に失敗しました"
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            for (u in uris) {
                localArchiveManager.addDocumentUri(u)
            }
            localMessage = "${uris.size} 件のファイルを追加しました"
            refreshLocalItems()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Operation Mode Selection Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "動作モード設定",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Mode 1: Local Standalone
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isLocalMode) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isLocalMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeChanged(true) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = isLocalMode,
                                    onClick = { onModeChanged(true) }
                                )
                                Column {
                                    Text(
                                        text = "📱 端末単体モード (完全ローカル)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "端末内のZIP/CBZ/RAR/フォルダを直接読み込み、端末CPUでSIFT照合を実行します",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Mode 2: Remote PC Server
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (!isLocalMode) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (!isLocalMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeChanged(false) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = !isLocalMode,
                                    onClick = { onModeChanged(false) }
                                )
                                Column {
                                    Text(
                                        text = "💻 PC連携モード (FastAPI サーバー)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "PCで起動中の Manga Panel Search サーバーに接続し、PCの高速GPU/CPUで特定します",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // PC Server Configuration (if remote mode selected)
                    if (!isLocalMode) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = "PCサーバー URL",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = {
                                    serverUrl = it
                                    saveFeedback = null
                                    testResult = null
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("http://192.168.1.xxx:8088") }
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        apiClient.baseUrl = serverUrl
                                        val prefs = context.getSharedPreferences("manga_search_prefs", android.content.Context.MODE_PRIVATE)
                                        prefs.edit().putString("server_url", serverUrl).apply()
                                        saveFeedback = "サーバーURLを保存しました"
                                    },
                                    shape = RoundedCornerShape(9999.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("保存")
                                }

                                OutlinedButton(
                                    onClick = {
                                        isTestingConnection = true
                                        testResult = null
                                        scope.launch {
                                            apiClient.getStatus()
                                                .onSuccess {
                                                    testResult = "接続成功: 登録漫画 ${it.totalArchives} 件"
                                                }
                                                .onFailure {
                                                    testResult = "接続失敗: ${it.localizedMessage}"
                                                }
                                            isTestingConnection = false
                                        }
                                    },
                                    shape = RoundedCornerShape(9999.dp)
                                ) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("接続テスト")
                                }
                            }

                            if (saveFeedback != null) {
                                Text(saveFeedback!!, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            if (testResult != null) {
                                Text(testResult!!, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. Material 3 Theme & Appearance Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "外観 ＆ Material 3 テーマ設定",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Theme Mode Selector (System, Dark, Light)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "テーマ明暗モード",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppThemeMode.entries.forEach { mode ->
                                val isSelected = (themeMode == mode)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onThemeModeChanged(mode) },
                                    label = { Text(mode.displayName, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Dynamic Color Monet Toggle (Android 12+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "壁紙連動ダイナミックカラー (Monet)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "端末の壁紙から自動抽出された Material 3 パレットを適用します",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Switch(
                                    checked = useDynamicColor,
                                    onCheckedChange = { onDynamicColorChanged(it) }
                                )
                            }
                        }
                    }

                    // 5 Material 3 Preset Cards
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "カラープリセット (デスクトップ版共通 5 仕様)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        for (preset in ColorPresets.all) {
                            val isSelected = !useDynamicColor && (currentPreset.id == preset.id)
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) preset.previewDot else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDynamicColorChanged(false)
                                        onPresetSelected(preset)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(preset.previewDot)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = preset.description,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = preset.previewDot,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Storage & Path Management Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = if (isLocalMode) "端末内ストレージ登録管理" else "PCサーバー登録パス管理",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isLocalMode) {
                        Text(
                            text = "端末内の漫画フォルダ（画像フォルダ）またはアーカイブ（.cbz, .zip, .rar, .cbr）を登録します。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { folderPickerLauncher.launch(null) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(9999.dp)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("フォルダ追加", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            FilledTonalButton(
                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(9999.dp)
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("ファイル追加", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (localMessage != null) {
                            Text(localMessage!!, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Local Items List
                        if (localItems.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "登録済みパス一覧 (${localItems.size} 件)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                for (item in localItems) {
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    if (item.isTree) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = item.displayName,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    localArchiveManager.removeUri(item.uriString)
                                                    refreshLocalItems()
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "削除",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Feature Cache Clear Button
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        var cacheClearedMsg by remember { mutableStateOf<String?>(null) }
                        OutlinedButton(
                            onClick = {
                                val cacheDir = File(context.filesDir, "feature_cache")
                                if (cacheDir.exists()) {
                                    val count = cacheDir.listFiles()?.size ?: 0
                                    cacheDir.deleteRecursively()
                                    cacheDir.mkdirs()
                                    cacheClearedMsg = "事前作成キャッシュ ($count 件) を削除しました"
                                    refreshLocalItems()
                                }
                            },
                            shape = RoundedCornerShape(9999.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("事前作成キャッシュをクリア", fontSize = 12.sp)
                        }

                        if (cacheClearedMsg != null) {
                            Text(cacheClearedMsg!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

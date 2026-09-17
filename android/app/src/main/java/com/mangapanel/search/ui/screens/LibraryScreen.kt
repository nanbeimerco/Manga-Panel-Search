package com.mangapanel.search.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mangapanel.search.data.api.MangaApiClient
import com.mangapanel.search.data.engine.IndexingStateManager
import com.mangapanel.search.data.engine.LocalSearchEngine
import com.mangapanel.search.data.local.LocalArchiveManager
import com.mangapanel.search.data.local.LocalMangaArchive
import com.mangapanel.search.data.model.ArchiveItem
import com.mangapanel.search.data.model.SystemStatus
import com.mangapanel.search.service.IndexingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    isLocalMode: Boolean,
    localArchiveManager: LocalArchiveManager,
    localSearchEngine: LocalSearchEngine,
    apiClient: MangaApiClient,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val indexingState by IndexingStateManager.state.collectAsState()

    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        IndexingForegroundService.startService(context)
    }

    fun launchIndexingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        IndexingForegroundService.startService(context)
    }

    // Remote state
    var remoteArchives by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var remoteStatus by remember { mutableStateOf<SystemStatus?>(null) }

    // Local state
    var localArchives by remember { mutableStateOf<List<LocalMangaArchive>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshData(forceRescan: Boolean = false) {
        scope.launch {
            if (forceRescan || localArchives.isEmpty()) {
                isLoading = true
            }
            if (isLocalMode) {
                localArchives = localArchiveManager.getArchivesAsync(forceRescan = forceRescan)
            } else {
                apiClient.getLibrary().onSuccess { remoteArchives = it.archives }
                apiClient.getStatus().onSuccess { remoteStatus = it }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshData(forceRescan = false)
    }

    // Remote Polling while indexing
    LaunchedEffect(remoteStatus?.isIndexing) {
        while (!isLocalMode && remoteStatus?.isIndexing == true) {
            delay(1000)
            apiClient.getStatus().onSuccess { remoteStatus = it }
            apiClient.getLibrary().onSuccess { remoteArchives = it.archives }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        text = if (isLocalMode) "📱 端末内漫画ライブラリ一覧" else "💻 PCサーバー登録漫画一覧",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { refreshData(forceRescan = true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9999.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("再スキャン")
                        }

                        if (isLocalMode && indexingState.isRunning) {
                            Button(
                                onClick = {
                                    IndexingForegroundService.stopService(context)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(9999.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("中止 (一時停止)")
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (isLocalMode) {
                                        launchIndexingService()
                                    } else {
                                        scope.launch {
                                            apiClient.startIndexing()
                                            delay(500)
                                            apiClient.getStatus().onSuccess { remoteStatus = it }
                                        }
                                    }
                                },
                                enabled = if (isLocalMode) localArchives.isNotEmpty() else !(remoteStatus?.isIndexing ?: false),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(9999.dp)
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isLocalMode) "事前作成 (再開)" else "事前作成")
                            }
                        }
                    }

                    // Progress indicators
                    if (isLocalMode && (indexingState.isRunning || indexingState.progressText.isNotEmpty())) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = indexingState.progressText,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${(indexingState.progress * 100).toInt()}%",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { indexingState.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (!isLocalMode && remoteStatus?.isIndexing == true) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "PC側インデックス中: ${remoteStatus?.currentFile ?: ""}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${remoteStatus?.progressPercent?.toInt() ?: 0}%",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (remoteStatus?.progressPercent ?: 0f) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Archive List Cards
        if (isLoading) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (isLocalMode) "端末内ストレージをスキャン中..." else "サーバー情報を取得中...",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else if (isLocalMode) {
            if (localArchives.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(28.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "端末内の漫画が見つかりません。\n「設定」タブの「フォルダを選択」から追加してください。\n（※ 対応形式: .cbz, .zip, .rar, .cbr, 画像フォルダ）",
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            } else {
                item {
                    val totalPages = localArchives.sumOf { it.pageCount }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "検出された作品",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Badge {
                            Text("${localArchives.size} 作品 (${totalPages} P)", modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
                items(localArchives) { arc ->
                    val cachedCount = remember(arc, indexingState.completedPages) {
                        localSearchEngine.getCachedPageCount(arc)
                    }
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = arc.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${arc.type} • ${arc.pageCount} ページ (作成済: ${cachedCount}p)",
                                    fontSize = 12.sp,
                                    color = if (cachedCount >= arc.pageCount && arc.pageCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        if (cachedCount >= arc.pageCount && arc.pageCount > 0) "完了" else arc.type,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Remote archives
            if (remoteArchives.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(28.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "PCサーバー上に漫画が見つかりません。",
                                color = MaterialTheme.colorScheme.outline,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                items(remoteArchives) { arc ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = arc.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${arc.type.uppercase()} • ${arc.pageCount} ページ",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        if (arc.isFullyIndexed) "完了" else "${arc.cachedPages}/${arc.pageCount}P",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (arc.isFullyIndexed) Icons.Default.CheckCircle else Icons.Default.HourglassTop,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (arc.isFullyIndexed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

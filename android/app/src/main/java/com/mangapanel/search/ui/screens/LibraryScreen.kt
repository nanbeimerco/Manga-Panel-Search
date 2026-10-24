package com.mangapanel.search.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mangapanel.search.data.api.MangaApiClient
import com.mangapanel.search.data.engine.IndexingStateManager
import com.mangapanel.search.data.engine.LocalSearchEngine
import com.mangapanel.search.data.local.LibraryGroupManager
import com.mangapanel.search.data.local.LocalArchiveManager
import com.mangapanel.search.data.local.LocalMangaArchive
import com.mangapanel.search.data.model.ArchiveItem
import com.mangapanel.search.data.model.SystemStatus
import com.mangapanel.search.service.IndexingForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    isLocalMode: Boolean,
    localArchiveManager: LocalArchiveManager,
    localLibraryGroupManager: LibraryGroupManager,
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
    var structureVersion by remember { mutableIntStateOf(0) } // trigger re-render on checkbox toggle

    // Dialog state: Create new folder
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    fun refreshData(forceRescan: Boolean = false) {
        scope.launch {
            if (forceRescan || localArchives.isEmpty()) {
                isLoading = true
            }
            if (isLocalMode) {
                val arcs = localArchiveManager.getArchivesAsync(forceRescan = forceRescan)
                localArchives = arcs
                localLibraryGroupManager.autoOrganizeIfEmpty(arcs.map { it.name })
                structureVersion++
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

    val structure = remember(localArchives, structureVersion) {
        localLibraryGroupManager.getStructure(localArchives.map { it.name })
    }

    val totalEnabledVolumes = remember(structure) {
        structure.groups.sumOf { g -> g.archives.count { it.checked } } +
                structure.ungrouped.archives.count { it.checked }
    }

    val totalAllVolumes = remember(structure) {
        structure.groups.sumOf { it.archives.size } + structure.ungrouped.archives.size
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Card: Controls & Actions
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isLocalMode) "登録ライブラリ ＆ フォルダ管理" else "💻 PCサーバー登録漫画一覧",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = if (isLocalMode) "📱 端末単体" else "💻 PC連携",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }

                    // Toolbar Action Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLocalMode) {
                            FilledTonalButton(
                                onClick = {
                                    newFolderName = ""
                                    showCreateFolderDialog = true
                                },
                                shape = RoundedCornerShape(9999.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("新規フォルダ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = { refreshData(forceRescan = true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9999.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("再スキャン", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("中止", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isLocalMode) "事前作成" else "事前作成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Selection Summary & Select/Deselect All Toolbar
                    if (isLocalMode && localArchives.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "検索対象: $totalEnabledVolumes / $totalAllVolumes 巻",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        onClick = {
                                            localLibraryGroupManager.selectAll(localArchives.map { it.name })
                                            structureVersion++
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("すべて選択", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(
                                        onClick = {
                                            localLibraryGroupManager.deselectAll(localArchives.map { it.name })
                                            structureVersion++
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("すべて解除", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
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

        // 2. Archive List
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
                // Render Folder Groups
                items(structure.groups) { group ->
                    val isExpanded = localLibraryGroupManager.isGroupExpanded(group.id)
                    val parentState = when {
                        group.isIndeterminate -> ToggleableState.Indeterminate
                        group.enabled -> ToggleableState.On
                        else -> ToggleableState.Off
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // Folder Header Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        localLibraryGroupManager.toggleGroupExpanded(group.id)
                                        structureVersion++
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = group.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Badge {
                                        Text("${group.archives.size}巻", fontSize = 11.sp)
                                    }
                                }

                                // Parent Checkbox
                                TriStateCheckbox(
                                    state = parentState,
                                    onClick = {
                                        val nextEnabled = (parentState != ToggleableState.On)
                                        localLibraryGroupManager.toggleGroup(group.id, nextEnabled)
                                        structureVersion++
                                    }
                                )
                            }

                            // Expanded Volumes in Folder
                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, end = 14.dp, bottom = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )

                                    for (archiveItem in group.archives) {
                                        val arc = localArchiveManager.getArchiveByName(archiveItem.name)
                                        val cachedCount = if (arc != null) {
                                            localSearchEngine.getCachedPageCount(arc)
                                        } else 0
                                        val pageCount = arc?.pageCount ?: 0

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    localLibraryGroupManager.toggleArchive(archiveItem.name, !archiveItem.checked)
                                                    structureVersion++
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = archiveItem.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (archiveItem.checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                                )
                                                Text(
                                                    text = "${arc?.type ?: ""} • ${pageCount}P (作成済: ${cachedCount}P)",
                                                    fontSize = 11.sp,
                                                    color = if (cachedCount >= pageCount && pageCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                                )
                                            }

                                            Checkbox(
                                                checked = archiveItem.checked,
                                                onCheckedChange = { checked ->
                                                    localLibraryGroupManager.toggleArchive(archiveItem.name, checked)
                                                    structureVersion++
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Render Ungrouped Archives if any
                if (structure.ungrouped.archives.isNotEmpty()) {
                    val uState = when {
                        structure.ungrouped.isIndeterminate -> ToggleableState.Indeterminate
                        structure.ungrouped.enabled -> ToggleableState.On
                        else -> ToggleableState.Off
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = "未分類の単行本",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Badge {
                                            Text("${structure.ungrouped.archives.size}巻", fontSize = 11.sp)
                                        }
                                    }

                                    TriStateCheckbox(
                                        state = uState,
                                        onClick = {
                                            val nextEnabled = (uState != ToggleableState.On)
                                            localLibraryGroupManager.toggleUngrouped(localArchives.map { it.name }, nextEnabled)
                                            structureVersion++
                                        }
                                    )
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                for (archiveItem in structure.ungrouped.archives) {
                                    val arc = localArchiveManager.getArchiveByName(archiveItem.name)
                                    val cachedCount = if (arc != null) {
                                        localSearchEngine.getCachedPageCount(arc)
                                    } else 0
                                    val pageCount = arc?.pageCount ?: 0

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                localLibraryGroupManager.toggleArchive(archiveItem.name, !archiveItem.checked)
                                                structureVersion++
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = archiveItem.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (archiveItem.checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                            )
                                            Text(
                                                text = "${arc?.type ?: ""} • ${pageCount}P (作成済: ${cachedCount}P)",
                                                fontSize = 11.sp,
                                                color = if (cachedCount >= pageCount && pageCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                            )
                                        }

                                        Checkbox(
                                            checked = archiveItem.checked,
                                            onCheckedChange = { checked ->
                                                localLibraryGroupManager.toggleArchive(archiveItem.name, checked)
                                                structureVersion++
                                            }
                                        )
                                    }
                                }
                            }
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

    // Modal Dialog: Create new folder
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("新規作品フォルダの作成", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "単行本を作品ごとに分類するためのフォルダ名を入力してください（例: らんま1/2、犬夜叉など）。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text("作品名・フォルダ名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            localLibraryGroupManager.createGroup(newFolderName)
                            structureVersion++
                        }
                        showCreateFolderDialog = false
                    },
                    shape = RoundedCornerShape(9999.dp)
                ) {
                    Text("作成")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showCreateFolderDialog = false },
                    shape = RoundedCornerShape(9999.dp)
                ) {
                    Text("キャンセル")
                }
            }
        )
    }
}

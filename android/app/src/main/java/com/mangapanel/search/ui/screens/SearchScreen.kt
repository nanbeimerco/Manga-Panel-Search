package com.mangapanel.search.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mangapanel.search.data.api.MangaApiClient
import com.mangapanel.search.data.engine.LocalSearchEngine
import com.mangapanel.search.data.local.LibraryGroupManager
import com.mangapanel.search.data.local.LocalArchiveManager
import com.mangapanel.search.data.model.SearchResultItem
import com.mangapanel.search.ui.components.PanelHighlightViewer
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SearchScreen(
    isLocalMode: Boolean,
    localArchiveManager: LocalArchiveManager,
    localLibraryGroupManager: LibraryGroupManager,
    localSearchEngine: LocalSearchEngine,
    apiClient: MangaApiClient,
    onNavigateToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var normalizePhoto by remember { mutableStateOf(true) }
    var minScore by remember { mutableFloatStateOf(0.20f) }
    var candidatePoolStep by remember { mutableFloatStateOf(0f) } // 0:全探索, 1:網羅(150), 2:標準(80), 3:超高速(40)
    var minPanelAreaPercent by remember { mutableFloatStateOf(1.0f) }

    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Camera Capture temp file & launcher
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            try {
                context.contentResolver.openInputStream(tempCameraUri!!)?.use { stream ->
                    selectedImageBytes = stream.readBytes()
                    selectedImageUri = tempCameraUri
                    searchResults = emptyList()
                    errorMessage = null
                }
            } catch (e: Exception) {
                errorMessage = "カメラ写真の取得に失敗しました: ${e.localizedMessage}"
            }
        }
    }

    fun launchCamera() {
        try {
            val cacheFile = File(context.cacheDir, "camera_query_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            errorMessage = "カメラの起動に失敗しました: ${e.localizedMessage}"
        }
    }

    // Gallery Picker Launcher
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    selectedImageBytes = stream.readBytes()
                }
                searchResults = emptyList()
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = "画像の読み込みに失敗しました: ${e.localizedMessage}"
            }
        }
    }

    // Calculate Search Scope stats
    val allLocalArchives = remember { localArchiveManager.getArchives() }
    val enabledArchiveNames = remember(allLocalArchives) {
        localLibraryGroupManager.getEnabledArchiveNames(allLocalArchives.map { it.name })
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Search Scope Banner
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.FilterAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            val scopeTitle = if (isLocalMode) {
                                if (allLocalArchives.isEmpty()) {
                                    "検索対象: ライブラリ未登録"
                                } else if (enabledArchiveNames.size == allLocalArchives.size) {
                                    "検索対象: 全巻 (${enabledArchiveNames.size}巻)"
                                } else {
                                    "検索対象: 選択された ${enabledArchiveNames.size}/${allLocalArchives.size} 巻"
                                }
                            } else {
                                "検索対象: PCサーバー内ライブラリ"
                            }
                            Text(
                                text = scopeTitle,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = if (isLocalMode) "チェックされた作品のみを対象に照合します" else "PC側の全設定対象から検索します",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    if (isLocalMode) {
                        OutlinedButton(
                            onClick = onNavigateToLibrary,
                            shape = RoundedCornerShape(9999.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.LibraryBooks, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("対象変更", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Upload Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
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
                            text = "探したいコマ画像をアップロード",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedImageBytes != null) {
                            IconButton(
                                onClick = {
                                    selectedImageBytes = null
                                    selectedImageUri = null
                                    searchResults = emptyList()
                                    errorMessage = null
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "画像をクリア",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }

                    // Dropzone / Selected Image Preview Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImageBytes != null) {
                            val bitmap = remember(selectedImageBytes) {
                                BitmapFactory.decodeByteArray(selectedImageBytes, 0, selectedImageBytes!!.size)
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Selected Query Panel",
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(9999.dp))
                                        .background(Color.Black.copy(alpha = 0.70f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${bitmap.width} × ${bitmap.height}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(42.dp)
                                )
                                Text(
                                    text = "タップしてコマ画像を選択",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "写真アルバム・カメラ撮影・クリップボード貼付対応",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Input Action Buttons (Photo Gallery, Camera, Clipboard)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9999.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("アルバム", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        FilledTonalButton(
                            onClick = { launchCamera() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9999.dp)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("カメラ", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                var imageLoaded = false
                                if (clip != null && clip.itemCount > 0) {
                                    val item = clip.getItemAt(0)
                                    val uri = item.uri
                                    if (uri != null) {
                                        try {
                                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                                selectedImageBytes = stream.readBytes()
                                                imageLoaded = true
                                                searchResults = emptyList()
                                                errorMessage = null
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                                if (!imageLoaded) {
                                    errorMessage = "クリップボードに画像が見つかりませんでした。"
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(9999.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("貼付", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    // Photo Illumination / Yellow Paper Normalization Toggle
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = "紙焼け・黄ばみ・撮影影の自動補正",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "撮影写真の背景を自動で白く平坦化（OFFで原画のまま照合）",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Switch(
                                checked = normalizePhoto,
                                onCheckedChange = { normalizePhoto = it }
                            )
                        }
                    }

                    // Slider 1: Coarse Search Mode
                    val candidatePoolSize = when (candidatePoolStep.toInt()) {
                        0 -> 0
                        1 -> 150
                        2 -> 80
                        3 -> 40
                        else -> 0
                    }
                    val candidatePoolLabel = when (candidatePoolStep.toInt()) {
                        0 -> "全探索 (足切りなし・最高精度)"
                        1 -> "網羅 (上位150候補)"
                        2 -> "標準 (上位80候補)"
                        3 -> "超高速 (上位40候補)"
                        else -> "全探索"
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "⚡ 高速照合モード (粗探索サンプリング)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = candidatePoolLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Slider(
                            value = candidatePoolStep,
                            onValueChange = { candidatePoolStep = it },
                            valueRange = 0f..3f,
                            steps = 2
                        )
                    }

                    // Slider 2: Min Panel Area Tolerance
                    val minPanelAreaLabel = when {
                        minPanelAreaPercent <= 0.8f -> "0.5% (極小コマも検出)"
                        minPanelAreaPercent <= 2.0f -> String.format("%.1f%% (標準・推奨)", minPanelAreaPercent)
                        else -> String.format("%.1f%% (大コマ重視/ゴミ排除)", minPanelAreaPercent)
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "📐 コマの小ささ許容度 (最小コマ面積比率)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = minPanelAreaLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = minPanelAreaPercent,
                            onValueChange = { minPanelAreaPercent = it },
                            valueRange = 0.5f..8.0f,
                            steps = 14
                        )
                    }

                    // Slider 3: Min Score Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "許容最小スコア",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.2f", minScore),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = minScore,
                            onValueChange = { minScore = it },
                            valueRange = 0.10f..0.80f,
                            steps = 14
                        )
                    }

                    // Search Button
                    Button(
                        onClick = {
                            selectedImageBytes?.let { bytes ->
                                isSearching = true
                                errorMessage = null
                                searchResults = emptyList()

                                scope.launch {
                                    if (isLocalMode) {
                                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (bmp == null) {
                                            isSearching = false
                                            errorMessage = "画像のデコードに失敗しました"
                                            return@launch
                                        }
                                        val allArcs = localArchiveManager.getArchives()
                                        if (allArcs.isEmpty()) {
                                            isSearching = false
                                            errorMessage = "登録された漫画がありません。「設定」からフォルダを選択してください。"
                                            return@launch
                                        }

                                        val activeEnabledNames = localLibraryGroupManager.getEnabledArchiveNames(allArcs.map { it.name })
                                        val targetArchives = allArcs.filter { it.name in activeEnabledNames }
                                        if (targetArchives.isEmpty()) {
                                            isSearching = false
                                            errorMessage = "検索対象として選択されている漫画がありません。「ライブラリ」画面で作品にチェックを入れてください。"
                                            return@launch
                                        }

                                        val results = localSearchEngine.searchPanel(
                                            queryBitmap = bmp,
                                            archives = targetArchives,
                                            minScore = minScore,
                                            normalizePhoto = normalizePhoto,
                                            candidatePoolSize = candidatePoolSize,
                                            minPanelAreaPercent = minPanelAreaPercent
                                        )
                                        isSearching = false
                                        searchResults = results
                                        if (results.isEmpty()) {
                                            errorMessage = "該当するコマが見つかりませんでした。"
                                        }
                                    } else {
                                        val result = apiClient.searchPanel(
                                            imageBytes = bytes,
                                            minScore = minScore,
                                            normalizePhoto = normalizePhoto,
                                            candidatePoolSize = candidatePoolSize,
                                            minPanelAreaPercent = minPanelAreaPercent
                                        )
                                        isSearching = false
                                        result.onSuccess { resp ->
                                            searchResults = resp.results
                                            if (resp.results.isEmpty()) {
                                                errorMessage = "該当するコマが見つかりませんでした。"
                                            }
                                        }.onFailure { err ->
                                            errorMessage = "検索エラー: ${err.localizedMessage}"
                                        }
                                    }
                                }
                            }
                        },
                        enabled = selectedImageBytes != null && !isSearching,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(9999.dp)
                    ) {
                        Icon(Icons.Default.ImageSearch, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isLocalMode) "端末内で特定する" else "PCサーバーで特定する",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    if (isSearching) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Error message card
        if (errorMessage != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. Results Header Toolbar
        if (searchResults.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "特定結果",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Badge {
                            Text("${searchResults.size} 件検出", modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }

                    TextButton(
                        onClick = { searchResults = emptyList() },
                        shape = RoundedCornerShape(9999.dp)
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("結果をクリア", fontSize = 12.sp)
                    }
                }
            }
        }

        // 4. Results List Cards
        items(searchResults) { res ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = res.archiveName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "PAGE ${res.pageNumber} (${res.pageFilename})",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "インライア一致点: ${res.inliersCount} 点",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "${(res.score * 100).toInt()}% 一致",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }

                    // Page Viewer with Animated Highlight Box & Pinch Zoom
                    val imageModel: Any = if (isLocalMode) {
                        remember(res.archiveName, res.pageIndex) {
                            localArchiveManager.getPageBitmap(res.archiveName, res.pageIndex) ?: ""
                        }
                    } else {
                        apiClient.getFullImageUrl(res.pageImageUrl)
                    }

                    PanelHighlightViewer(
                        imageUrl = imageModel,
                        boundingBox = res.boundingBox,
                        title = res.archiveName,
                        subtitle = "PAGE ${res.pageNumber} (${(res.score * 100).toInt()}% 一致)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

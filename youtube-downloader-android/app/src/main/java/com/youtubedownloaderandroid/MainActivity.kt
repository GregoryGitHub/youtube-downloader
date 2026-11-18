package com.youtubedownloaderandroid

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.youtubedownloaderandroid.ui.theme.MyApplicationTheme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Runtime Diagnostics to inspect library APIs and values
        try {
            val ffmpegClass = Class.forName("com.yausername.ffmpeg.FFmpeg")
            val instanceField = ffmpegClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val ffmpegInstance = instanceField.get(null)

            val binDirField = ffmpegClass.getDeclaredField("binDir")
            binDirField.isAccessible = true
            val binDir = binDirField.get(ffmpegInstance) as? java.io.File

            val initializedField = ffmpegClass.getDeclaredField("initialized")
            initializedField.isAccessible = true
            val initialized = initializedField.get(ffmpegInstance) as? Boolean

            android.util.Log.d("DIAGNOSTIC", "FFmpeg binDir value: ${binDir?.absolutePath}")
            android.util.Log.d("DIAGNOSTIC", "FFmpeg initialized value: $initialized")
        } catch (e: Exception) {
            android.util.Log.e("DIAGNOSTIC", "Failed to get FFmpeg field values", e)
        }

        try {
            val ytdlClass = Class.forName("com.yausername.youtubedl_android.YoutubeDL")
            val instanceField = ytdlClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val ytdlInstance = instanceField.get(null)

            val ffmpegPathField = ytdlClass.getDeclaredField("ffmpegPath")
            ffmpegPathField.isAccessible = true
            val ffmpegPath = ffmpegPathField.get(ytdlInstance) as? java.io.File

            val ffmpegBinNameField = ytdlClass.getDeclaredField("ffmpegBinName")
            ffmpegBinNameField.isAccessible = true
            val ffmpegBinName = ffmpegBinNameField.get(ytdlInstance) as? String

            val ffmpegDirNameField = ytdlClass.getDeclaredField("ffmpegDirName")
            ffmpegDirNameField.isAccessible = true
            val ffmpegDirName = ffmpegDirNameField.get(ytdlInstance) as? String

            android.util.Log.d("DIAGNOSTIC", "YoutubeDL ffmpegPath value: ${ffmpegPath?.absolutePath}")
            android.util.Log.d("DIAGNOSTIC", "YoutubeDL ffmpegBinName value: $ffmpegBinName")
            android.util.Log.d("DIAGNOSTIC", "YoutubeDL ffmpegDirName value: $ffmpegDirName")
        } catch (e: Exception) {
            android.util.Log.e("DIAGNOSTIC", "Failed to get YoutubeDL field values", e)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    YoutubeDownloaderApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeDownloaderApp(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val url by viewModel.url.collectAsState()
    val status by viewModel.status.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val maxConcurrent by viewModel.maxConcurrentDownloads.collectAsState()
    val folderMode by viewModel.folderMode.collectAsState()
    val customFolderUri by viewModel.customFolderUri.collectAsState()
    val isUpdatingYtdl by viewModel.isUpdatingYtdl.collectAsState()
    val ytdlUpdateStatus by viewModel.ytdlUpdateStatus.collectAsState()
    val autoRetry by viewModel.autoRetry.collectAsState()
    val organizeByArtist by viewModel.organizeByArtist.collectAsState()
    val fullAlbumMeta by viewModel.fullAlbumMeta.collectAsState()
    val albumProposal by viewModel.albumProposal.collectAsState()
    val context = LocalContext.current

    // Request notification permission on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                android.widget.Toast.makeText(context, "As notificações são recomendadas para acompanhar downloads em segundo plano.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        
        LaunchedEffect(Unit) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var selectedTab by remember { mutableStateOf(0) }

    // SAF folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            viewModel.setCustomFolderUri(it.toString())
        }
    }

    // YouTube Official Dark Theme Background (#0F0F0F)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Bar - YDownloader
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "YDownloader",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Media Downloader",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFFF0000), // YouTube Red
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tab Row - YouTube Theme Red indicator
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color(0xFFFF0000), // YouTube Red
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color(0xFFFF0000)
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Baixar", color = if (selectedTab == 0) Color(0xFFFF0000) else Color.White, fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fila", color = if (selectedTab == 1) Color(0xFFFF0000) else Color.White, fontWeight = FontWeight.SemiBold)
                            if (queue.any { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED }) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFFFF0000), shape = RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Ajustes", color = if (selectedTab == 2) Color(0xFFFF0000) else Color.White, fontWeight = FontWeight.SemiBold) }
                )
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> DownloaderTab(
                        url = url,
                        status = status,
                        isDownloading = isDownloading,
                        onUrlChange = viewModel::onUrlChange,
                        onQueueClick = { viewModel.queueUrl(context) { selectedTab = 1 } }
                    )
                    1 -> QueueTab(
                        queue = queue,
                        autoRetry = autoRetry,
                        onAutoRetryChange = viewModel::setAutoRetry,
                        onRemove = viewModel::removeItem,
                        onClearCompleted = viewModel::clearCompleted,
                        onRetry = viewModel::retryItem,
                        onRetryAllFailed = viewModel::retryAllFailed
                    )
                    2 -> SettingsTab(
                        organizeByArtist = organizeByArtist,
                        fullAlbumMeta = fullAlbumMeta,
                        onOrganizeByArtistChange = viewModel::setOrganizeByArtist,
                        onFullAlbumMetaChange = viewModel::setFullAlbumMeta,
                        maxConcurrent = maxConcurrent,
                        folderMode = folderMode,
                        customFolderUri = customFolderUri,
                        isUpdatingYtdl = isUpdatingYtdl,
                        ytdlUpdateStatus = ytdlUpdateStatus,
                        onMaxConcurrentChange = viewModel::setMaxConcurrentDownloads,
                        onFolderModeChange = viewModel::setFolderMode,
                        onUpdateClick = { viewModel.updateYtdl(context) },
                        onSelectFolderClick = { folderPickerLauncher.launch(null) }
                    )
                }
            }
        }
    }

    albumProposal?.let { proposal ->
        AlbumConfirmDialog(
            proposal = proposal,
            onSelect = viewModel::selectAlbumCandidate,
            onLyricsChange = viewModel::setIncludeLyrics,
            onConfirm = { viewModel.confirmAlbum(useMeta = true) },
            onWithoutMeta = { viewModel.confirmAlbum(useMeta = false) },
            onCancel = viewModel::cancelAlbum
        )
    }
}

@Composable
fun DownloaderTab(
    url: String,
    status: String,
    isDownloading: Boolean,
    onUrlChange: (String) -> Unit,
    onQueueClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // YouTube Charcoal card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Insira o link do YouTube",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    placeholder = { Text("URL de vídeo ou playlist", color = Color(0xFF8E8E8E)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDownloading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF0000), // YouTube Red
                        unfocusedBorderColor = Color(0xFF2F2F2F),
                        focusedContainerColor = Color(0xFF0F0F0F), // YouTube Black
                        unfocusedContainerColor = Color(0xFF0F0F0F)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Button(
                    onClick = onQueueClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = !isDownloading && url.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF0000), // YouTube Red
                        disabledContainerColor = Color(0xFF2F2F2F)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Adicionar à Fila",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // Status Card
        if (status.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (status.contains("Erro")) Color(0xFFFF4E4E) else Color(0xFFFF0000),
                                shape = RoundedCornerShape(5.dp)
                            )
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE5E5E5)
                    )
                }
            }
        }
    }
}

@Composable
fun QueueTab(
    queue: List<DownloadItem>,
    autoRetry: Boolean,
    onAutoRetryChange: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onClearCompleted: () -> Unit,
    onRetry: (String) -> Unit,
    onRetryAllFailed: () -> Unit = {}
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Toggle Switch for Auto-retry
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Re-tentar falhas automaticamente (até ${DownloadQueueManager.MAX_AUTO_RETRIES}x)",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = autoRetry,
                onCheckedChange = onAutoRetryChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFFFF0000), // YouTube Red
                    uncheckedThumbColor = Color(0xFF8E8E8E),
                    uncheckedTrackColor = Color(0xFF2F2F2F)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF2F2F2F))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${queue.size} itens na fila",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (queue.any { it.status == DownloadStatus.FAILED }) {
                    TextButton(onClick = onRetryAllFailed) {
                        Text("Repetir Falhas", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
                    }
                }
                if (queue.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }) {
                    TextButton(onClick = onClearCompleted) {
                        Text("Limpar Concluídos", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum download ativo.",
                    color = Color(0xFF8E8E8E),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(queue, key = { it.id }) { item ->
                    QueueItemCard(
                        item = item,
                        onOpenFolder = { openDownloadFolder(context, item) },
                        onCancel = { onRemove(item.id) },
                        onRetry = { onRetry(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun QueueItemCard(
    item: DownloadItem,
    onOpenFolder: () -> Unit = {},
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val statusColor = when (item.status) {
        DownloadStatus.COMPLETED -> Color(0xFF2BA040) // YouTube Green
        DownloadStatus.FAILED -> Color(0xFFFF4E4E) // YouTube soft Red
        DownloadStatus.DOWNLOADING -> Color(0xFF3EA6FF) // YouTube Blue
        DownloadStatus.CONVERTING -> Color(0xFFFFC107) // YouTube Gold
        DownloadStatus.FETCHING_INFO -> Color(0xFF3EA6FF)
        DownloadStatus.QUEUED -> Color(0xFF8E8E8E) // YouTube Grey
    }

    val statusText = when (item.status) {
        DownloadStatus.COMPLETED -> "Concluído"
        DownloadStatus.FAILED -> "Falhou"
        DownloadStatus.DOWNLOADING -> "Baixando (${(item.progress * 100).toInt()}%)"
        DownloadStatus.CONVERTING -> "Convertendo..."
        DownloadStatus.FETCHING_INFO -> "Inicializando..."
        DownloadStatus.QUEUED -> if (item.retryCount > 0) "Retentando" else "Na fila"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpenFolder),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // First Row: Title, Status Badge, and Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.playlistTitle != null) {
                        Text(
                            text = "Playlist: ${item.playlistTitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8BE39B), // Light green
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                // Status Badge
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Retry Button (only if failed)
                if (item.status == DownloadStatus.FAILED) {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = "Repetir",
                            color = Color(0xFFFF0000), // YouTube Red
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Remove Button
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = "Remover",
                        color = Color(0xFFFF4E4E), // YouTube soft Red
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Second Row: Status details / ETA / Error
            val showDetail = item.statusDetail != null && item.statusDetail.isNotBlank() && item.status != DownloadStatus.COMPLETED && item.status != DownloadStatus.FAILED
            val showEta = item.status == DownloadStatus.DOWNLOADING && item.eta != "N/A"
            val showError = item.status == DownloadStatus.FAILED && item.errorMessage != null

            if (showDetail || showEta || showError) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showDetail) {
                        Text(
                            text = item.statusDetail!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE5E5E5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (showEta) {
                        Text(
                            text = "Restante: ${item.eta}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE5E5E5)
                        )
                    }
                    if (showError) {
                        Text(
                            text = item.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF4E4E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Third Row: Progress Bar (only when active)
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.CONVERTING || item.status == DownloadStatus.FETCHING_INFO) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFFF0000), // YouTube Red
                    trackColor = Color(0xFF2F2F2F) // YouTube Dark Grey
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = Color(0xFF8E8E8E), style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFFFF0000),
                uncheckedThumbColor = Color(0xFF8E8E8E),
                uncheckedTrackColor = Color(0xFF2F2F2F)
            )
        )
    }
}

@Composable
fun SettingsTab(
    organizeByArtist: Boolean,
    fullAlbumMeta: Boolean,
    onOrganizeByArtistChange: (Boolean) -> Unit,
    onFullAlbumMetaChange: (Boolean) -> Unit,
    maxConcurrent: Int,
    folderMode: String,
    customFolderUri: String,
    isUpdatingYtdl: Boolean,
    ytdlUpdateStatus: String,
    onMaxConcurrentChange: (Int) -> Unit,
    onFolderModeChange: (String) -> Unit,
    onUpdateClick: () -> Unit,
    onSelectFolderClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Configurações",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        // Concurrent downloads card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Downloads Simultâneos: $maxConcurrent",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Slider(
                    value = maxConcurrent.toFloat(),
                    onValueChange = { onMaxConcurrentChange(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF0000), // YouTube Red
                        activeTrackColor = Color(0xFFFF0000),
                        inactiveTrackColor = Color(0xFF2F2F2F)
                    )
                )
                
                Text(
                    text = "Controla quantos vídeos serão processados ao mesmo tempo. Acima de 3 o YouTube tende a limitar a conexão; as falhas são re-tentadas automaticamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E8E)
                )
            }
        }

        BackgroundCard()

        // Library organisation + metadata card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Biblioteca e Metadados",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                SettingSwitch(
                    title = "Organizar por artista",
                    description = "Salva em Artista/Álbum (ano)/, juntando todos os álbuns do mesmo artista na mesma pasta.",
                    checked = organizeByArtist,
                    onChange = onOrganizeByArtistChange
                )
                SettingSwitch(
                    title = "Metadados completos em álbuns",
                    description = "Ao baixar um álbum, busca no iTunes/Deezer título, artista, ano, gênero, nº das faixas, capa em alta e letras, e pede sua confirmação antes.",
                    checked = fullAlbumMeta,
                    onChange = onFullAlbumMetaChange
                )
            }
        }

        // Folder Mode selection card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Destino dos Downloads",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val isCustom = folderMode == "custom"
                    
                    Surface(
                        onClick = { onFolderModeChange("public") },
                        modifier = Modifier.weight(1f),
                        color = if (!isCustom) Color(0xFFFF0000).copy(alpha = 0.15f) else Color(0xFF0F0F0F),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (!isCustom) Color(0xFFFF0000) else Color(0xFF2F2F2F)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Pasta Música",
                                color = if (!isCustom) Color(0xFFFF0000) else Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Pública do sistema",
                                color = Color(0xFF8E8E8E),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Surface(
                        onClick = { onSelectFolderClick() },
                        modifier = Modifier.weight(1f),
                        color = if (isCustom) Color(0xFFFF0000).copy(alpha = 0.15f) else Color(0xFF0F0F0F),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isCustom) Color(0xFFFF0000) else Color(0xFF2F2F2F)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Escolher Pasta",
                                color = if (isCustom) Color(0xFFFF0000) else Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isCustom && customFolderUri.isNotBlank()) getReadableUriPath(customFolderUri) else "Escolher do sistema",
                                color = Color(0xFF8E8E8E),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Text(
                    text = "Downloads de playlist são agrupados em uma subpasta com o nome da playlist (ou do álbum).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E8E)
                )
            }
        }

        // Component updates card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Atualização de Componentes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "Caso ocorram erros nos downloads (como mudança de assinaturas do YouTube), clique para atualizar o yt-dlp.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E8E)
                )

                Button(
                    onClick = onUpdateClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !isUpdatingYtdl,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF0000), // YouTube Red
                        disabledContainerColor = Color(0xFF2F2F2F)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isUpdatingYtdl) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Atualizar yt-dlp",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (ytdlUpdateStatus.isNotBlank()) {
                    Text(
                        text = ytdlUpdateStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ytdlUpdateStatus.contains("Erro")) Color(0xFFFF4E4E) else Color(0xFF2BA040),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun getReadableUriPath(uriString: String): String {
    if (uriString.isBlank()) return "Nenhuma pasta"
    return try {
        val decoded = android.net.Uri.decode(uriString)
        val parts = decoded.split(":")
        if (parts.size > 1) {
            parts.last()
        } else {
            decoded.substringAfterLast("/")
        }
    } catch (e: Exception) {
        "Pasta personalizada"
    }
}

/**
 * Lets the user exempt the app from battery optimisation so downloads keep
 * running with the screen locked on aggressive OEM ROMs.
 */
@android.annotation.SuppressLint("BatteryLife")
@Composable
fun BackgroundCard() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
    var exempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    // Re-check when returning from the system dialog.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2F2F2F), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Downloads em segundo plano",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (exempt) {
                    "Liberado: os downloads continuam com a tela bloqueada."
                } else {
                    "Para continuar baixando com a tela bloqueada, permita que o app ignore a otimização de bateria."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (exempt) Color(0xFF2BA040) else Color(0xFF8E8E8E)
            )
            if (!exempt) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        } catch (e: Exception) {
                            context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000))
                ) {
                    Text("Permitir em segundo plano", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Opens the folder of a finished download in the device's file manager.
 * Tries the folder view first (Files / My Files), then falls back to the
 * system document browser positioned on that folder.
 */
fun openDownloadFolder(context: android.content.Context, item: DownloadItem) {
    val folder = item.folderUri
    if (item.status != DownloadStatus.COMPLETED || folder == null) {
        android.widget.Toast.makeText(context, "A música ainda não foi salva.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = Uri.parse(folder)
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR),
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
    )
    for (intent in attempts) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    android.widget.Toast.makeText(context, "Nenhum gerenciador de arquivos encontrado.", android.widget.Toast.LENGTH_SHORT).show()
}

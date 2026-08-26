package com.youtubedownloaderandroid

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadQueueManager {
    private const val TAG = "DownloadQueueManager"
    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(2)
    val maxConcurrentDownloads = _maxConcurrentDownloads.asStateFlow()

    private val _folderMode = MutableStateFlow("public")
    val folderMode = _folderMode.asStateFlow()

    private val _customFolderUri = MutableStateFlow("")
    val customFolderUri = _customFolderUri.asStateFlow()

    private val _autoRetry = MutableStateFlow(false)
    val autoRetry = _autoRetry.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun init(appContext: Context) {
        context = appContext.applicationContext
        prefs = context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)

        _maxConcurrentDownloads.value = prefs.getInt("max_concurrent", 2)
        _folderMode.value = prefs.getString("folder_mode", "public") ?: "public"
        _customFolderUri.value = prefs.getString("custom_folder_uri", "") ?: ""
        _autoRetry.value = prefs.getBoolean("auto_retry", false)

        // Start observing queue for processing
        scope.launch {
            _queue.collect {
                processQueue()
            }
        }
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        _maxConcurrentDownloads.value = limit
        prefs.edit().putInt("max_concurrent", limit).apply()
    }

    fun setFolderMode(mode: String) {
        _folderMode.value = mode
        prefs.edit().putString("folder_mode", mode).apply()
    }

    fun setCustomFolderUri(uriString: String) {
        _customFolderUri.value = uriString
        prefs.edit().putString("custom_folder_uri", uriString).apply()
        setFolderMode("custom")
    }

    fun setAutoRetry(enabled: Boolean) {
        _autoRetry.value = enabled
        prefs.edit().putBoolean("auto_retry", enabled).apply()
    }

    fun addItems(items: List<DownloadItem>) {
        _queue.value = _queue.value + items
    }

    fun removeItem(id: String) {
        _queue.value = _queue.value.filter { it.id != id }
    }

    fun clearCompleted() {
        _queue.value = _queue.value.filter {
            it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED
        }
    }

    fun retryItem(id: String) {
        _queue.value = _queue.value.map {
            if (it.id == id) {
                it.copy(
                    status = DownloadStatus.QUEUED,
                    progress = 0f,
                    eta = "N/A",
                    statusDetail = "Aguardando...",
                    errorMessage = null,
                    retryCount = 0 // Reset retry count when manually retried
                )
            } else it
        }
    }

    private fun processQueue() {
        val items = _queue.value
        val queuedItems = items.filter { it.status == DownloadStatus.QUEUED }
        val runningCount = items.count {
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.FETCHING_INFO ||
            it.status == DownloadStatus.CONVERTING
        }

        // Start or stop the Foreground Service depending on queue activity
        val hasActivity = runningCount > 0 || queuedItems.isNotEmpty()
        updateServiceState(hasActivity)

        val limit = _maxConcurrentDownloads.value
        if (runningCount < limit && queuedItems.isNotEmpty()) {
            val toStart = limit - runningCount
            for (i in 0 until minOf(toStart, queuedItems.size)) {
                val item = queuedItems[i]
                startItemDownload(item)
            }
        }
    }

    private fun startItemDownload(item: DownloadItem) {
        updateItemStatus(item.id, DownloadStatus.FETCHING_INFO, "Conectando ao YouTube...")

        scope.launch {
            if (item.retryCount > 0) {
                delay(2000) // Aguarda 2 segundos antes de tentar novamente para ficar visível ao usuário e evitar sobrecarga
            }
            try {
                val downloadManager = DownloadManager(context)
                val currentFolderMode = _folderMode.value
                val currentCustomFolderUri = _customFolderUri.value

                val result = downloadManager.downloadAudio(
                    url = item.url,
                    folderMode = currentFolderMode,
                    customFolderUri = currentCustomFolderUri,
                    subfolderName = item.playlistTitle,
                    trackNumber = item.trackNumber
                ) { progress, eta, line ->
                    updateItemProgress(item.id, progress / 100f, eta, line)
                }

                result.onSuccess {
                    updateItemStatus(item.id, DownloadStatus.COMPLETED, "Concluído")
                } .onFailure {
                    updateItemFailure(item.id, it.message ?: "Falha na conversão ou download")
                }
            } catch (e: Exception) {
                updateItemFailure(item.id, e.message ?: "Erro desconhecido")
            }
        }
    }

    private fun updateItemStatus(id: String, status: DownloadStatus, statusDetail: String? = null) {
        _queue.value = _queue.value.map {
            if (it.id == id) it.copy(status = status, statusDetail = statusDetail ?: it.statusDetail, errorMessage = null) else it
        }
    }

    private fun updateItemProgress(id: String, progress: Float, eta: String, line: String) {
        _queue.value = _queue.value.map {
            if (it.id == id) {
                val cleanLine = cleanProgressLine(line)
                val currentStatus = when {
                    progress >= 1.0f || line.contains("[ExtractAudio]") || line.contains("[ffmpeg]") -> DownloadStatus.CONVERTING
                    progress > 0f -> DownloadStatus.DOWNLOADING
                    else -> it.status
                }
                it.copy(progress = progress, eta = eta, status = currentStatus, statusDetail = cleanLine)
            } else it
        }
    }

    private fun updateItemFailure(id: String, errorMessage: String) {
        val autoRetryEnabled = _autoRetry.value
        _queue.value = _queue.value.map {
            if (it.id == id) {
                if (autoRetryEnabled && it.retryCount < 3) {
                    Log.i(TAG, "Auto-retry active. Retrying download for ID $id (attempt ${it.retryCount + 1}/3)")
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        progress = 0f,
                        eta = "N/A",
                        statusDetail = "Tentando novamente (${it.retryCount + 1}/3)...",
                        errorMessage = null,
                        retryCount = it.retryCount + 1
                    )
                } else {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        statusDetail = "Erro no download",
                        errorMessage = errorMessage
                    )
                }
            } else it
        }
    }

    private fun updateServiceState(active: Boolean) {
        if (!::context.isInitialized) return
        val intent = Intent(context, DownloadService::class.java)
        try {
            if (active) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating service state: ", e)
        }
    }

    private fun cleanProgressLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("[download]")) {
            val atIndex = trimmed.indexOf(" at ")
            val etaIndex = trimmed.indexOf(" ETA ")
            if (atIndex != -1 && etaIndex != -1 && etaIndex > atIndex + 4) {
                val speed = trimmed.substring(atIndex + 4, etaIndex).trim()
                return "Baixando a $speed"
            }
            if (trimmed.contains("Destination:")) {
                return "Preparando gravação..."
            }
            return "Baixando..."
        }
        if (trimmed.startsWith("[ExtractAudio]")) {
            return "Convertendo para MP3..."
        }
        if (trimmed.startsWith("[youtube]")) {
            val content = trimmed.substring(9).trim()
            val colonIndex = content.indexOf(":")
            val msg = if (colonIndex != -1 && colonIndex < 15) {
                content.substring(colonIndex + 1).trim()
            } else {
                content
            }
            return when {
                msg.contains("Downloading webpage", ignoreCase = true) -> "Carregando página do vídeo..."
                msg.contains("Downloading android player API JSON", ignoreCase = true) -> "Obtendo dados do player..."
                msg.contains("Extracting URL", ignoreCase = true) -> "Extraindo endereço..."
                else -> msg
            }
        }
        if (trimmed.startsWith("[ffmpeg]")) {
            return "Processando áudio com FFmpeg..."
        }
        return trimmed
    }
}

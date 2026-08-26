package com.youtubedownloaderandroid

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

enum class DownloadStatus {
    QUEUED,
    FETCHING_INFO,
    DOWNLOADING,
    CONVERTING,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val playlistTitle: String? = null,
    val trackNumber: Int? = null,
    val progress: Float = 0f,
    val eta: String = "N/A",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val statusDetail: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)

    private val _url = MutableStateFlow("")
    val url = _url.asStateFlow()

    private val _status = MutableStateFlow("Pronto")
    val status = _status.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(prefs.getInt("max_concurrent", 2))
    val maxConcurrentDownloads = _maxConcurrentDownloads.asStateFlow()

    private val _folderMode = MutableStateFlow(prefs.getString("folder_mode", "public") ?: "public")
    val folderMode = _folderMode.asStateFlow()

    private val _customFolderUri = MutableStateFlow(prefs.getString("custom_folder_uri", "") ?: "")
    val customFolderUri = _customFolderUri.asStateFlow()

    private val _isUpdatingYtdl = MutableStateFlow(false)
    val isUpdatingYtdl = _isUpdatingYtdl.asStateFlow()

    private val _ytdlUpdateStatus = MutableStateFlow("")
    val ytdlUpdateStatus = _ytdlUpdateStatus.asStateFlow()

    private val _autoRetry = MutableStateFlow(prefs.getBoolean("auto_retry", false))
    val autoRetry = _autoRetry.asStateFlow()

    init {
        // Silent startup update check in the background
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(application.applicationContext)
                Log.i("MainViewModel", "Silent update check completed successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Silent update check failed on startup", e)
            }
        }
        processQueue(application.applicationContext)
    }

    fun onUrlChange(newUrl: String) {
        _url.value = newUrl
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
        DownloadQueueManager.setAutoRetry(enabled)
    }

    fun updateYtdl(context: Context) {
        if (_isUpdatingYtdl.value) return
        viewModelScope.launch {
            _isUpdatingYtdl.value = true
            _ytdlUpdateStatus.value = "Atualizando componentes..."
            try {
                withContext(Dispatchers.IO) {
                    com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(context)
                }
                _ytdlUpdateStatus.value = "Sucesso! Componentes atualizados."
            } catch (e: Exception) {
                _ytdlUpdateStatus.value = "Erro: ${e.message}"
            } finally {
                _isUpdatingYtdl.value = false
            }
        }
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
                    errorMessage = null
                )
            } else it
        }
    }

    fun queueUrl(context: Context) {
        val inputUrl = _url.value.trim()
        if (inputUrl.isBlank()) {
            _status.value = "Por favor, insira uma URL."
            return
        }

        viewModelScope.launch {
            _isDownloading.value = true
            _status.value = "Analisando link..."
            try {
                val downloadManager = DownloadManager(context)
                val info = downloadManager.fetchPlaylistOrVideoInfo(inputUrl)
                
                val newItems = info.entries.mapIndexed { index, entry ->
                    DownloadItem(
                        id = entry.id,
                        url = entry.url,
                        title = entry.title,
                        playlistTitle = if (info.isPlaylist) info.title else null,
                        trackNumber = if (info.isPlaylist) index + 1 else null,
                        status = DownloadStatus.QUEUED
                    )
                }
                
                _queue.value = _queue.value + newItems
                _status.value = "Adicionado à fila: ${newItems.size} item(ns)"
                _url.value = ""
            } catch (e: Exception) {
                _status.value = "Erro ao analisar: ${e.message}"
            } finally {
                _isDownloading.value = false
            }
        }
    }

    private fun processQueue(context: Context) {
        viewModelScope.launch {
            _queue.collect { items ->
                val queuedItems = items.filter { it.status == DownloadStatus.QUEUED }
                val runningCount = items.count { 
                    it.status == DownloadStatus.DOWNLOADING || 
                    it.status == DownloadStatus.FETCHING_INFO || 
                    it.status == DownloadStatus.CONVERTING 
                }
                
                val limit = _maxConcurrentDownloads.value
                if (runningCount < limit && queuedItems.isNotEmpty()) {
                    val toStart = limit - runningCount
                    for (i in 0 until minOf(toStart, queuedItems.size)) {
                        val item = queuedItems[i]
                        startItemDownload(context, item)
                    }
                }
            }
        }
    }

    private fun startItemDownload(context: Context, item: DownloadItem) {
        updateItemStatus(item.id, DownloadStatus.FETCHING_INFO, "Conectando ao YouTube...")
        
        viewModelScope.launch {
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
        _queue.value = _queue.value.map {
            if (it.id == id) it.copy(status = DownloadStatus.FAILED, statusDetail = "Erro no download", errorMessage = errorMessage) else it
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

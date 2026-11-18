package com.youtubedownloaderandroid

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val retryCount: Int = 0,
    /** Item belongs to a YouTube Music album (shared year/genre, "Album (year)" folder). */
    val isAlbum: Boolean = false,
    /** Album year reported by YouTube; fallback when iTunes/Deezer have nothing. */
    val albumYear: String? = null,
    /** Document URI of the folder holding the finished MP3. */
    val folderUri: String? = null,
    /** Epoch millis before which an auto-retry must not start (backoff). */
    val nextAttemptAt: Long = 0L
)

/**
 * UI state holder. The queue, settings and download processing live in
 * [DownloadQueueManager] so they survive the activity and keep running in
 * the background; this ViewModel only forwards to it.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _url = MutableStateFlow("")
    val url = _url.asStateFlow()

    private val _status = MutableStateFlow("Pronto")
    val status = _status.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    val queue: StateFlow<List<DownloadItem>> = DownloadQueueManager.queue
    val maxConcurrentDownloads: StateFlow<Int> = DownloadQueueManager.maxConcurrentDownloads
    val folderMode: StateFlow<String> = DownloadQueueManager.folderMode
    val customFolderUri: StateFlow<String> = DownloadQueueManager.customFolderUri
    val autoRetry: StateFlow<Boolean> = DownloadQueueManager.autoRetry

    private val _isUpdatingYtdl = MutableStateFlow(false)
    val isUpdatingYtdl = _isUpdatingYtdl.asStateFlow()

    private val _ytdlUpdateStatus = MutableStateFlow("")
    val ytdlUpdateStatus = _ytdlUpdateStatus.asStateFlow()

    init {
        DownloadQueueManager.init(application.applicationContext)
        // Silent startup update check in the background
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(application.applicationContext)
                Log.i("MainViewModel", "Silent update check completed successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Silent update check failed on startup", e)
            }
        }
    }

    fun onUrlChange(newUrl: String) {
        _url.value = newUrl
    }

    fun setMaxConcurrentDownloads(limit: Int) = DownloadQueueManager.setMaxConcurrentDownloads(limit)

    fun setFolderMode(mode: String) = DownloadQueueManager.setFolderMode(mode)

    fun setCustomFolderUri(uriString: String) = DownloadQueueManager.setCustomFolderUri(uriString)

    fun setAutoRetry(enabled: Boolean) = DownloadQueueManager.setAutoRetry(enabled)

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

    fun removeItem(id: String) = DownloadQueueManager.removeItem(id)

    fun clearCompleted() = DownloadQueueManager.clearCompleted()

    fun retryItem(id: String) = DownloadQueueManager.retryItem(id)

    fun retryAllFailed() = DownloadQueueManager.retryAllFailed()

    fun queueUrl(context: Context, onQueued: () -> Unit = {}) {
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
                        isAlbum = info.isAlbum,
                        albumYear = info.year,
                        status = DownloadStatus.QUEUED
                    )
                }

                DownloadQueueManager.addItems(newItems)
                _status.value = "Adicionado à fila: ${newItems.size} item(ns)"
                _url.value = ""
                onQueued()
            } catch (e: Exception) {
                _status.value = "Erro ao analisar: ${e.message}"
            } finally {
                _isDownloading.value = false
            }
        }
    }
}

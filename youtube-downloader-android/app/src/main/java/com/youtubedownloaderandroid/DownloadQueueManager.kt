package com.youtubedownloaderandroid

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for the download queue. Lives in the Application
 * process (not in the ViewModel), so downloads keep running with the screen
 * locked or the activity closed while [DownloadService] keeps the process in
 * the foreground.
 */
object DownloadQueueManager {
    private const val TAG = "DownloadQueueManager"

    /** Automatic attempts after the first one fails. */
    const val MAX_AUTO_RETRIES = 5

    /** Minimum gap between two yt-dlp starts, avoids bursts that YouTube rejects. */
    private const val START_SPACING_MS = 1500L

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private lateinit var queueFile: File

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(3)
    val maxConcurrentDownloads = _maxConcurrentDownloads.asStateFlow()

    private val _folderMode = MutableStateFlow("public")
    val folderMode = _folderMode.asStateFlow()

    private val _customFolderUri = MutableStateFlow("")
    val customFolderUri = _customFolderUri.asStateFlow()

    private val _autoRetry = MutableStateFlow(true)
    val autoRetry = _autoRetry.asStateFlow()

    /** "<Artist>/<Album (year)>/" folders, like the desktop app. */
    private val _organizeByArtist = MutableStateFlow(true)
    val organizeByArtist = _organizeByArtist.asStateFlow()

    /** Offer full iTunes/Deezer metadata (with lyrics) when an album is queued. */
    private val _fullAlbumMeta = MutableStateFlow(true)
    val fullAlbumMeta = _fullAlbumMeta.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Running download jobs by item id. Guarded by [lock]. */
    private val activeJobs = mutableMapOf<String, Job>()
    private val lock = Any()

    private val startGate = Mutex()
    private var lastStartAt = 0L

    private var initialized = false

    fun init(appContext: Context) {
        if (initialized) return
        initialized = true
        context = appContext.applicationContext
        prefs = context.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
        queueFile = File(context.filesDir, "queue.json")

        _maxConcurrentDownloads.value = prefs.getInt("max_concurrent", 3)
        _folderMode.value = prefs.getString("folder_mode", "public") ?: "public"
        _customFolderUri.value = prefs.getString("custom_folder_uri", "") ?: ""
        _autoRetry.value = prefs.getBoolean("auto_retry_v2", true)
        _organizeByArtist.value = prefs.getBoolean("organize_by_artist", true)
        _fullAlbumMeta.value = prefs.getBoolean("full_album_meta", true)

        restoreQueue()

        scope.launch {
            _queue.collect {
                processQueue()
            }
        }
        // Persist the queue (debounced) so a process kill doesn't lose it.
        scope.launch {
            var last: List<DownloadItem>? = null
            while (isActive) {
                delay(2000)
                val current = _queue.value
                if (current !== last) {
                    last = current
                    saveQueue(current)
                }
            }
        }
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        _maxConcurrentDownloads.value = limit
        prefs.edit().putInt("max_concurrent", limit).apply()
        processQueue()
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
        prefs.edit().putBoolean("auto_retry_v2", enabled).apply()
    }

    fun setOrganizeByArtist(enabled: Boolean) {
        _organizeByArtist.value = enabled
        prefs.edit().putBoolean("organize_by_artist", enabled).apply()
    }

    fun setFullAlbumMeta(enabled: Boolean) {
        _fullAlbumMeta.value = enabled
        prefs.edit().putBoolean("full_album_meta", enabled).apply()
    }

    fun addItems(items: List<DownloadItem>) {
        _queue.update { current ->
            // Skip items already pending/running; re-queue the ones that ended.
            val pendingIds = current.filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED }
                .map { it.id }.toSet()
            val fresh = items.filter { it.id !in pendingIds }
            val freshIds = fresh.map { it.id }.toSet()
            current.filter { it.id !in freshIds } + fresh
        }
    }

    fun removeItem(id: String) {
        val job = synchronized(lock) { activeJobs.remove(id) }
        if (job != null) {
            try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) {}
            job.cancel()
        }
        _queue.update { list -> list.filter { it.id != id } }
        scope.launch(Dispatchers.IO) {
            DownloadManager.workDirFor(context, id).deleteRecursively()
        }
    }

    fun clearCompleted() {
        val removed = _queue.value.filter { it.status == DownloadStatus.FAILED }.map { it.id }
        _queue.update { list ->
            list.filter { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED }
        }
        scope.launch(Dispatchers.IO) {
            removed.forEach { DownloadManager.workDirFor(context, it).deleteRecursively() }
        }
    }

    fun retryItem(id: String) {
        _queue.update { list ->
            list.map {
                if (it.id == id && it.status == DownloadStatus.FAILED) {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        progress = 0f,
                        eta = "N/A",
                        statusDetail = "Aguardando...",
                        errorMessage = null,
                        retryCount = 0,
                        nextAttemptAt = 0L
                    )
                } else it
            }
        }
    }

    fun retryAllFailed() {
        _queue.value.filter { it.status == DownloadStatus.FAILED }.forEach { retryItem(it.id) }
    }

    private fun isRunning(status: DownloadStatus) =
        status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.FETCHING_INFO ||
            status == DownloadStatus.CONVERTING

    private fun processQueue() {
        if (!initialized) return
        val toStart = mutableListOf<DownloadItem>()
        var nextWake = Long.MAX_VALUE
        var hasActivity: Boolean

        synchronized(lock) {
            val items = _queue.value
            val now = System.currentTimeMillis()
            val queued = items.filter { it.status == DownloadStatus.QUEUED && it.id !in activeJobs }
            hasActivity = activeJobs.isNotEmpty() || queued.isNotEmpty()

            val free = _maxConcurrentDownloads.value - activeJobs.size
            val ready = queued.filter { it.nextAttemptAt <= now }
            queued.filter { it.nextAttemptAt > now }.forEach { nextWake = minOf(nextWake, it.nextAttemptAt) }

            ready.take(maxOf(0, free)).forEach { item ->
                toStart += item
                // Reserve the slot now so a concurrent processQueue() can't double-start it.
                activeJobs[item.id] = Job()
            }
        }

        updateServiceState(hasActivity)

        toStart.forEach { startItemDownload(it) }

        if (nextWake != Long.MAX_VALUE) scheduleWake(nextWake)
    }

    private var wakeJob: Job? = null
    private var wakeAt = Long.MAX_VALUE

    /** One pending timer for the earliest backed-off retry (not one per queue update). */
    private fun scheduleWake(at: Long) {
        synchronized(lock) {
            if (wakeJob?.isActive == true && wakeAt <= at) return
            wakeJob?.cancel()
            wakeAt = at
            wakeJob = scope.launch {
                delay(maxOf(0L, at - System.currentTimeMillis()))
                synchronized(lock) { wakeAt = Long.MAX_VALUE }
                processQueue()
            }
        }
    }

    private fun startItemDownload(item: DownloadItem) {
        val statusText = if (item.retryCount > 0) {
            "Tentativa ${item.retryCount + 1}/${MAX_AUTO_RETRIES + 1}..."
        } else "Aguardando vez..."
        updateItem(item.id) { it.copy(status = DownloadStatus.FETCHING_INFO, statusDetail = statusText, errorMessage = null) }

        val job = scope.launch {
            try {
                // Space the starts so parallel downloads don't hammer YouTube at once.
                startGate.withLock {
                    val wait = lastStartAt + START_SPACING_MS - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    lastStartAt = System.currentTimeMillis()
                }
                updateItem(item.id) { it.copy(statusDetail = "Conectando ao YouTube...") }

                val isLastAttempt = !_autoRetry.value || item.retryCount >= MAX_AUTO_RETRIES
                val result = DownloadManager(context).downloadAudio(
                    itemId = item.id,
                    url = item.url,
                    folderMode = _folderMode.value,
                    customFolderUri = _customFolderUri.value,
                    subfolderName = item.playlistTitle,
                    trackNumber = item.trackNumber,
                    isAlbum = item.isAlbum,
                    albumYear = item.albumYear,
                    organizeByArtist = _organizeByArtist.value,
                    trackMeta = item.trackMeta,
                    isLastAttempt = isLastAttempt
                ) { progress, eta, line ->
                    updateItemProgress(item.id, progress / 100f, eta, line)
                }

                result.onSuccess { saved ->
                    updateItem(item.id) {
                        it.copy(
                            status = DownloadStatus.COMPLETED, progress = 1f, statusDetail = "Concluído",
                            errorMessage = null, folderUri = saved.folderUri, fileUri = saved.fileUri
                        )
                    }
                }.onFailure {
                    if (it is CancellationException || it is YoutubeDL.CanceledException) return@onFailure
                    updateItemFailure(item.id, it.message ?: "Falha na conversão ou download")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateItemFailure(item.id, e.message ?: "Erro desconhecido")
            } finally {
                synchronized(lock) { activeJobs.remove(item.id) }
                processQueue()
            }
        }
        synchronized(lock) {
            // Only register if the item wasn't removed meanwhile.
            if (activeJobs.containsKey(item.id)) activeJobs[item.id] = job else job.cancel()
        }
    }

    private inline fun updateItem(id: String, crossinline transform: (DownloadItem) -> DownloadItem) {
        _queue.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun updateItemProgress(id: String, progress: Float, eta: String, line: String) {
        updateItem(id) {
            if (it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED) return@updateItem it
            val cleanLine = cleanProgressLine(line)
            val currentStatus = when {
                progress >= 1.0f || line.contains("[ExtractAudio]") || line.contains("[ffmpeg]") ||
                    line.contains("[EmbedThumbnail]") || line.contains("[Metadata]") || line.startsWith("[move]") -> DownloadStatus.CONVERTING
                progress > 0f -> DownloadStatus.DOWNLOADING
                else -> it.status
            }
            it.copy(progress = progress, eta = eta, status = currentStatus, statusDetail = cleanLine)
        }
    }

    private fun updateItemFailure(id: String, errorMessage: String) {
        val autoRetryEnabled = _autoRetry.value
        updateItem(id) {
            if (autoRetryEnabled && it.retryCount < MAX_AUTO_RETRIES) {
                val attempt = it.retryCount + 1
                // 10s, 20s, 40s, 80s, 160s - gives YouTube time to stop throttling.
                val backoffMs = 10_000L * (1L shl (attempt - 1))
                Log.i(TAG, "Auto-retry $id ($attempt/$MAX_AUTO_RETRIES) em ${backoffMs / 1000}s: $errorMessage")
                it.copy(
                    status = DownloadStatus.QUEUED,
                    eta = "N/A",
                    statusDetail = "Falhou, nova tentativa em ${backoffMs / 1000}s ($attempt/$MAX_AUTO_RETRIES)",
                    errorMessage = errorMessage,
                    retryCount = attempt,
                    nextAttemptAt = System.currentTimeMillis() + backoffMs
                )
            } else {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusDetail = "Erro no download",
                    errorMessage = errorMessage
                )
            }
        }
    }

    private fun updateServiceState(active: Boolean) {
        if (!::context.isInitialized) return
        val intent = Intent(context, DownloadService::class.java)
        try {
            if (active) {
                if (!DownloadService.isRunning) {
                    ContextCompat.startForegroundService(context, intent)
                }
            } else if (DownloadService.isRunning) {
                context.stopService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating service state: ", e)
        }
    }

    // ---- persistence ------------------------------------------------------

    private fun saveQueue(items: List<DownloadItem>) {
        try {
            val arr = JSONArray()
            items.forEach { item ->
                arr.put(JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    item.playlistTitle?.let { put("playlistTitle", it) }
                    item.trackNumber?.let { put("trackNumber", it) }
                    put("status", item.status.name)
                    put("retryCount", item.retryCount)
                    put("isAlbum", item.isAlbum)
                    item.folderUri?.let { put("folderUri", it) }
                    item.fileUri?.let { put("fileUri", it) }
                    item.albumYear?.let { put("albumYear", it) }
                    item.errorMessage?.let { put("errorMessage", it) }
                    item.trackMeta?.let { put("trackMeta", it.toJson()) }
                })
            }
            val tmp = File(queueFile.parentFile, "queue.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(queueFile)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar a fila", e)
        }
    }

    private fun restoreQueue() {
        try {
            if (!queueFile.exists()) return
            val arr = JSONArray(queueFile.readText())
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val saved = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.QUEUED)
                // Anything that was running when the process died goes back to the queue.
                val status = if (saved == DownloadStatus.COMPLETED || saved == DownloadStatus.FAILED) saved else DownloadStatus.QUEUED
                DownloadItem(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    title = o.optString("title", o.getString("id")),
                    playlistTitle = if (o.has("playlistTitle")) o.getString("playlistTitle") else null,
                    trackNumber = if (o.has("trackNumber")) o.getInt("trackNumber") else null,
                    status = status,
                    progress = if (status == DownloadStatus.COMPLETED) 1f else 0f,
                    statusDetail = when (status) {
                        DownloadStatus.COMPLETED -> "Concluído"
                        DownloadStatus.FAILED -> "Erro no download"
                        else -> "Aguardando..."
                    },
                    errorMessage = if (status == DownloadStatus.FAILED && o.has("errorMessage")) o.getString("errorMessage") else null,
                    retryCount = o.optInt("retryCount", 0),
                    isAlbum = o.optBoolean("isAlbum", false),
                    folderUri = if (o.has("folderUri")) o.getString("folderUri") else null,
                    fileUri = if (o.has("fileUri")) o.getString("fileUri") else null,
                    albumYear = if (o.has("albumYear")) o.getString("albumYear") else null,
                    trackMeta = o.optJSONObject("trackMeta")?.let { runCatching { TrackMeta.fromJson(it) }.getOrNull() }
                )
            }
            _queue.value = items
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao restaurar a fila", e)
        }
    }

    private fun cleanProgressLine(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("[download]")) {
            if (trimmed.contains("has already been downloaded")) {
                return "Arquivo já baixado, reaproveitando..."
            }
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
        if (trimmed.startsWith("[EmbedThumbnail]") || trimmed.startsWith("[ThumbnailsConvertor]")) {
            return "Aplicando capa..."
        }
        if (trimmed.startsWith("[Metadata]")) {
            return trimmed.removePrefix("[Metadata]").trim().takeIf { it.endsWith("...") } ?: "Gravando metadados..."
        }
        if (trimmed.startsWith("[meta]")) {
            return trimmed.removePrefix("[meta]").trim().ifEmpty { "Buscando metadados..." }
        }
        if (trimmed.startsWith("[move]")) {
            return "Copiando para a pasta de destino..."
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
                msg.contains("player API JSON", ignoreCase = true) -> "Obtendo dados do player..."
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

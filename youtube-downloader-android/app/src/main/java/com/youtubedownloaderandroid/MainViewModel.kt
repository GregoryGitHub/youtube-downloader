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
    /** Content URI of the finished MP3 (MediaStore or SAF document), opened in the player. */
    val fileUri: String? = null,
    /** Epoch millis before which an auto-retry must not start (backoff). */
    val nextAttemptAt: Long = 0L,
    /** Full catalogue metadata the user accepted for this album track. */
    val trackMeta: TrackMeta? = null
)

/** Album found in iTunes/Deezer for a queued YouTube Music album, waiting for the user's OK. */
data class AlbumProposal(
    val info: PlaylistInfo,
    val candidates: List<CatalogAlbum>,
    val selected: Int,
    val details: CatalogAlbumDetails?,
    /** playlist entry id -> index in details.tracks */
    val matches: Map<String, Int>,
    val loading: Boolean = false,
    val error: String? = null,
    val includeLyrics: Boolean = true,
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
    val organizeByArtist: StateFlow<Boolean> = DownloadQueueManager.organizeByArtist
    val fullAlbumMeta: StateFlow<Boolean> = DownloadQueueManager.fullAlbumMeta

    private val _albumProposal = MutableStateFlow<AlbumProposal?>(null)
    val albumProposal = _albumProposal.asStateFlow()
    private var onAlbumQueued: () -> Unit = {}

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

    fun setOrganizeByArtist(enabled: Boolean) = DownloadQueueManager.setOrganizeByArtist(enabled)

    fun setFullAlbumMeta(enabled: Boolean) = DownloadQueueManager.setFullAlbumMeta(enabled)

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

                if (info.isAlbum && info.entries.isNotEmpty() && fullAlbumMeta.value) {
                    _status.value = "Buscando o álbum no iTunes/Deezer..."
                    val proposal = runCatching { findAlbum(downloadManager, info) }
                        .onFailure { Log.w("MainViewModel", "album lookup", it) }
                        .getOrNull()
                    if (proposal != null) {
                        onAlbumQueued = onQueued
                        _albumProposal.value = proposal
                        _status.value = "Confirme os dados do álbum."
                        return@launch
                    }
                    enqueue(info, null)
                    _status.value = "Álbum não encontrado no iTunes/Deezer; ${info.entries.size} faixa(s) na fila com os dados do YouTube."
                    onQueued()
                    return@launch
                }

                enqueue(info, null)
                _status.value = "Adicionado à fila: ${info.entries.size} item(ns)"
                onQueued()
            } catch (e: Exception) {
                _status.value = "Erro ao analisar: ${e.message}"
            } finally {
                _isDownloading.value = false
            }
        }
    }

    private fun enqueue(info: PlaylistInfo, proposal: AlbumProposal?) {
        val d = proposal?.details
        val newItems = info.entries.mapIndexed { index, entry ->
            val meta = d?.let { trackMetaFor(it, entry, index, info.entries.size, proposal.matches[entry.id], proposal.includeLyrics) }
            DownloadItem(
                id = entry.id,
                url = entry.url,
                title = meta?.title ?: entry.title,
                playlistTitle = if (info.isPlaylist) info.title else null,
                trackNumber = if (info.isPlaylist) index + 1 else null,
                isAlbum = info.isAlbum,
                albumYear = info.year,
                status = DownloadStatus.QUEUED,
                trackMeta = meta
            )
        }
        DownloadQueueManager.addItems(newItems)
        _url.value = ""
    }

    private fun trackMetaFor(
        d: CatalogAlbumDetails,
        entry: PlaylistEntry,
        index: Int,
        entryCount: Int,
        match: Int?,
        lyrics: Boolean
    ): TrackMeta {
        val t = match?.let { d.tracks.getOrNull(it) }
        return TrackMeta(
            // Tracks the catalogue doesn't list keep the YouTube title but still
            // land in the same album folder.
            title = t?.title ?: entry.title,
            artist = t?.artist?.takeIf { it.isNotBlank() } ?: d.album.artist,
            album = d.album.title,
            albumArtist = d.album.artist,
            year = d.year,
            genre = d.genre,
            track = t?.track ?: (index + 1),
            trackTotal = t?.let { d.trackTotal(it.disc) } ?: maxOf(entryCount, d.tracks.size),
            disc = t?.disc ?: 1,
            discTotal = d.discTotal,
            durationSec = t?.durationSec?.takeIf { it > 0 } ?: entry.durationSec,
            coverUrl = d.album.coverUrl,
            lyrics = lyrics
        )
    }

    private suspend fun findAlbum(dm: DownloadManager, info: PlaylistInfo): AlbumProposal? {
        val (artist, probedAlbum) = dm.probeAlbum(info.entries.first().url)
        // YouTube Music names the playlist "Album - <name>".
        val album = probedAlbum.ifBlank { info.title.orEmpty().removePrefix("Album - ").trim() }
        if (album.isBlank()) return null
        val candidates = withContext(Dispatchers.IO) { AlbumCatalog.search(artist, album, info.entries.size) }
        if (candidates.isEmpty()) return null
        return withDetails(AlbumProposal(info, candidates, 0, null, emptyMap()), 0)
    }

    private suspend fun withDetails(p: AlbumProposal, index: Int): AlbumProposal {
        val d = withContext(Dispatchers.IO) { AlbumCatalog.details(p.candidates[index]) }
        val entries = p.info.entries.mapIndexed { i, e -> TitleMatch.Entry(e.id, e.title, e.durationSec, i) }
        return p.copy(selected = index, details = d, matches = TitleMatch.match(entries, d.tracks), loading = false, error = null)
    }

    fun selectAlbumCandidate(index: Int) {
        val p = _albumProposal.value ?: return
        if (index == p.selected && p.details != null) return
        _albumProposal.value = p.copy(selected = index, loading = true, error = null)
        viewModelScope.launch {
            _albumProposal.value = try {
                withDetails(p, index)
            } catch (e: Exception) {
                p.copy(selected = index, details = null, loading = false, error = "Erro ao carregar o álbum: ${e.message}")
            }
        }
    }

    fun setIncludeLyrics(enabled: Boolean) {
        _albumProposal.value = _albumProposal.value?.copy(includeLyrics = enabled)
    }

    /** Queue the album with the catalogue data ([useMeta]) or with YouTube's own tags. */
    fun confirmAlbum(useMeta: Boolean) {
        val p = _albumProposal.value ?: return
        _albumProposal.value = null
        val withMeta = useMeta && p.details != null
        enqueue(p.info, if (withMeta) p else null)
        _status.value = if (withMeta) {
            "Adicionado à fila: ${p.info.entries.size} faixa(s) com metadados completos"
        } else {
            "Adicionado à fila: ${p.info.entries.size} item(ns)"
        }
        onAlbumQueued()
    }

    fun cancelAlbum() {
        _albumProposal.value = null
        _status.value = "Cancelado."
    }
}

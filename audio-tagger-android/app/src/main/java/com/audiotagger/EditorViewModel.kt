package com.audiotagger

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class Screen { HOME, EDITOR, SEARCH, MATCH }

data class Progress(val label: String, val done: Int, val total: Int)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var screen by mutableStateOf(Screen.HOME)
        private set
    var title by mutableStateOf("")
        private set
    var tracks by mutableStateOf(listOf<Track>())
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    /** One-shot message for the snackbar; the UI clears it once shown. */
    var message by mutableStateOf<String?>(null)

    // search
    var searchArtist by mutableStateOf("")
    var searchAlbum by mutableStateOf("")
    var source by mutableStateOf(Source.entries.getOrElse(prefs.getInt("source", 0)) { Source.ITUNES })
        private set
    var results by mutableStateOf(listOf<AlbumResult>())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    // match
    var details by mutableStateOf<AlbumDetails?>(null)
        private set
    /** local track id -> index in details.tracks, or null to leave the file alone. */
    var assignment by mutableStateOf(mapOf<Int, Int?>())
        private set
    var options by mutableStateOf(ApplyOption.entries.toSet())
        private set

    var preferSynced by mutableStateOf(prefs.getBoolean("preferSynced", false))
        private set

    private val workDir get() = File(ctx.cacheDir, "work")

    val dirtyCount get() = tracks.count { it.dirty }

    // ---------- opening ----------

    fun openFolder(treeUri: Uri) {
        keepPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        load(TagIO.folderName(ctx, treeUri)) { TagIO.listFolder(ctx, treeUri) }
    }

    fun openFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { keepPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        load(if (uris.size == 1) TagIO.displayName(ctx, uris[0]) else "${uris.size} arquivos") {
            uris.map { AudioDoc(it, TagIO.displayName(ctx, it), "") }.filter { TagIO.isAudio(it.name) }
        }
    }

    private fun keepPermission(uri: Uri, flags: Int) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun load(name: String, list: () -> List<AudioDoc>) {
        viewModelScope.launch {
            progress = Progress("Lendo arquivos…", 0, 0)
            val failed = mutableListOf<String>()
            val loaded = withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                val docs = list()
                docs.mapIndexedNotNull { i, doc ->
                    withContext(Dispatchers.Main) { progress = Progress("Lendo arquivos…", i, docs.size) }
                    try {
                        TagIO.load(ctx, doc, i, workDir)
                    } catch (e: Exception) {
                        Log.w(TAG, "load ${doc.name}", e)
                        failed += doc.name
                        null
                    }
                }
            }
            progress = null
            if (loaded.isEmpty()) {
                message = if (failed.isEmpty()) "Nenhum arquivo de áudio compatível encontrado." else "Não foi possível ler: ${failed.joinToString()}"
                return@launch
            }
            title = name
            tracks = loaded
            screen = Screen.EDITOR
            if (failed.isNotEmpty()) message = "Ignorados (não foi possível ler): ${failed.joinToString()}"
        }
    }

    fun close() {
        tracks = emptyList()
        details = null
        results = emptyList()
        screen = Screen.HOME
        viewModelScope.launch(Dispatchers.IO) { workDir.deleteRecursively() }
    }

    // ---------- editing ----------

    /** Value shared by every track, or null when the tracks disagree. */
    fun commonValue(field: TagField): String? = tracks.map { it[field] }.distinct().singleOrNull()

    fun setForAll(field: TagField, value: String) {
        tracks = tracks.map { it.with(field, value) }
    }

    fun setField(trackId: Int, field: TagField, value: String) {
        tracks = tracks.map { if (it.id == trackId) it.with(field, value) else it }
    }

    fun commonCover(): Cover? = tracks.map { it.cover }.distinct().singleOrNull()
    val coversDiffer get() = tracks.map { it.cover }.distinct().size > 1

    fun setCoverForAll(cover: Cover?) {
        tracks = tracks.map { it.copy(cover = cover) }
    }

    fun pickCover(uri: Uri, trackId: Int? = null) {
        viewModelScope.launch {
            val cover = withContext(Dispatchers.IO) { runCatching { coverFromImage(uri) }.getOrNull() }
            if (cover == null) {
                message = "Não foi possível abrir a imagem."
                return@launch
            }
            tracks = tracks.map { if (trackId == null || it.id == trackId) it.copy(cover = cover) else it }
        }
    }

    fun setTrackCover(trackId: Int, cover: Cover?) {
        tracks = tracks.map { if (it.id == trackId) it.copy(cover = cover) else it }
    }

    fun revertAll() {
        tracks = tracks.map { it.copy(values = it.original, cover = it.originalCover) }
    }

    fun sortByNumber() {
        tracks = tracks.sortedWith(compareBy<Track>({ it.discNo ?: 1 }, { it.trackNo ?: Int.MAX_VALUE }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
    }

    fun sortByFileName() {
        tracks = tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::folder).thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
    }

    /** 1..n in the current list order, restarting on each disc; also fills the totals. */
    fun numberInListOrder() {
        val perDisc = tracks.groupBy { it.discNo ?: 1 }
        val numbers = perDisc.values.flatMap { list -> list.mapIndexed { i, t -> t.id to (i + 1) } }.toMap()
        val totals = perDisc.mapValues { it.value.size }
        tracks = tracks.map { t ->
            t.with(TagField.TRACK, numbers.getValue(t.id).toString())
                .with(TagField.TRACK_TOTAL, totals.getValue(t.discNo ?: 1).toString())
        }
    }

    fun move(trackId: Int, delta: Int) {
        val i = tracks.indexOfFirst { it.id == trackId }
        val j = i + delta
        if (i < 0 || j !in tracks.indices) return
        tracks = tracks.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** "Song" from "03 - Song.mp3" for tracks without a title. */
    fun titlesFromFileNames() {
        tracks = tracks.map { t ->
            if (t[TagField.TITLE].isBlank()) t.with(TagField.TITLE, TrackMatcher.cleanFileName(t.fileName)) else t
        }
    }

    // ---------- saving ----------

    fun save() {
        val toSave = tracks.filter { it.dirty }
        if (toSave.isEmpty()) {
            message = "Nada para salvar."
            return
        }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            toSave.forEachIndexed { i, t ->
                progress = Progress("Salvando…", i, toSave.size)
                val saved = withContext(Dispatchers.IO) {
                    try {
                        TagIO.save(ctx, t)
                    } catch (e: Exception) {
                        Log.e(TAG, "save ${t.fileName}", e)
                        failed += "${t.fileName} (${e.message ?: e.javaClass.simpleName})"
                        null
                    }
                }
                if (saved != null) tracks = tracks.map { if (it.id == saved.id) saved else it }
            }
            progress = null
            message = if (failed.isEmpty()) "${toSave.size} arquivo(s) salvo(s)." else "Falha ao salvar: ${failed.joinToString()}"
        }
    }

    // ---------- online search ----------

    fun openSearch() {
        searchAlbum = commonValue(TagField.ALBUM)?.takeIf { it.isNotBlank() } ?: title
        searchArtist = commonValue(TagField.ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: tracks.map { it[TagField.ARTIST] }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: ""
        screen = Screen.SEARCH
        if (searchAlbum.isNotBlank() || searchArtist.isNotBlank()) search()
    }

    fun changeSource(s: Source) {
        source = s
        prefs.edit().putInt("source", s.ordinal).apply()
        search()
    }

    fun search() {
        if (searchAlbum.isBlank() && searchArtist.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            searchError = null
            try {
                val artist = searchArtist.trim()
                val album = searchAlbum.trim()
                val src = source
                results = withContext(Dispatchers.IO) { OnlineCatalog.searchAlbums(src, artist, album) }
                if (results.isEmpty()) searchError = "Nenhum álbum encontrado no ${src.label}."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "search", e)
                results = emptyList()
                searchError = "Erro na busca: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                searching = false
            }
        }
    }

    fun chooseAlbum(album: AlbumResult) {
        viewModelScope.launch {
            progress = Progress("Carregando faixas do álbum…", 0, 0)
            try {
                val d = withContext(Dispatchers.IO) { OnlineCatalog.albumDetails(album) }
                if (d.tracks.isEmpty()) {
                    message = "O ${album.source.label} não listou as faixas deste álbum."
                    return@launch
                }
                details = d
                val locals = tracks.map {
                    TrackMatcher.Local(it.id, it[TagField.TITLE], it.fileName, it.durationSec, it.trackNo, it.discNo)
                }
                val matched = TrackMatcher.match(locals, d.tracks)
                assignment = tracks.associate { it.id to matched[it.id] }
                screen = Screen.MATCH
            } catch (e: Exception) {
                Log.w(TAG, "details", e)
                message = "Erro ao carregar o álbum: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                progress = null
            }
        }
    }

    fun assign(trackId: Int, remoteIndex: Int?) {
        assignment = assignment + (trackId to remoteIndex)
    }

    fun toggleOption(o: ApplyOption) {
        options = if (o in options) options - o else options + o
    }

    fun applyAlbum() {
        val d = details ?: return
        val opts = options
        viewModelScope.launch {
            var cover: Cover? = null
            if (ApplyOption.COVER in opts && d.album.coverUrl != null) {
                progress = Progress("Baixando capa…", 0, 0)
                cover = withContext(Dispatchers.IO) {
                    runCatching { OnlineCatalog.download(d.album.coverUrl) }
                        .recoverCatching { OnlineCatalog.download(d.album.thumbUrl!!) }
                        .getOrNull()
                        ?.let { Cover(it, TagIO.sniffMime(it)) }
                }
                progress = null
                if (cover == null) message = "Não foi possível baixar a capa; o resto foi aplicado."
            }
            tracks = tracks.map { t ->
                var u = t
                if (ApplyOption.ALBUM in opts) {
                    u = u.with(TagField.ALBUM, d.album.title).with(TagField.ALBUM_ARTIST, d.album.artist)
                }
                if (ApplyOption.YEAR in opts && d.year != null) u = u.with(TagField.YEAR, d.year)
                if (ApplyOption.GENRE in opts && d.genre != null) u = u.with(TagField.GENRE, d.genre)
                assignment[t.id]?.let { d.tracks.getOrNull(it) }?.let { r ->
                    if (ApplyOption.TITLE in opts) u = u.with(TagField.TITLE, r.title)
                    if (ApplyOption.ARTIST in opts && r.artist.isNotBlank()) u = u.with(TagField.ARTIST, r.artist)
                    if (ApplyOption.NUMBERS in opts) {
                        u = u.with(TagField.TRACK, r.track.toString())
                            .with(TagField.TRACK_TOTAL, d.trackTotal(r.disc).toString())
                            .with(TagField.DISC, r.disc.toString())
                            .with(TagField.DISC_TOTAL, d.discTotal.toString())
                    }
                }
                if (cover != null) u = u.copy(cover = cover)
                u
            }
            if (ApplyOption.NUMBERS in opts) sortByNumber()
            screen = Screen.EDITOR
            if (ApplyOption.LYRICS in opts) {
                // Uses the catalogue titles just applied, which find lyrics far more often.
                val matched = assignment.filterValues { it != null }.keys
                fetchLyrics(matched.ifEmpty { null }, quietIfNone = true)
            } else if (message == null) {
                message = "Dados aplicados. Revise e toque em Salvar."
            }
        }
    }

    fun back() {
        screen = when (screen) {
            Screen.MATCH -> Screen.SEARCH
            Screen.SEARCH -> Screen.EDITOR
            else -> screen
        }
    }

    // ---------- lyrics ----------

    fun togglePreferSynced() {
        preferSynced = !preferSynced
        prefs.edit().putBoolean("preferSynced", preferSynced).apply()
    }

    /** Fetches lyrics from LRCLIB for the given tracks (all when null). */
    fun fetchLyrics(ids: Set<Int>? = null, overwrite: Boolean = false, quietIfNone: Boolean = false) {
        val targets = tracks.filter { (ids == null || it.id in ids) && (overwrite || it[TagField.LYRICS].isBlank()) }
        if (targets.isEmpty()) {
            message = if (quietIfNone) "Dados aplicados. Revise e toque em Salvar." else "Todas as faixas já têm letra."
            return
        }
        viewModelScope.launch {
            var found = 0
            var error: String? = null
            targets.forEachIndexed { i, t ->
                progress = Progress("Buscando letras…", i, targets.size)
                val artist = t[TagField.ARTIST].ifBlank { t[TagField.ALBUM_ARTIST] }
                val result = withContext(Dispatchers.IO) {
                    try {
                        OnlineCatalog.lyrics(artist, t.displayTitle, t[TagField.ALBUM], t.durationSec)
                    } catch (e: Exception) {
                        Log.w(TAG, "lyrics", e)
                        error = e.message
                        null
                    }
                }
                val text = result?.let { if (preferSynced) it.synced ?: it.plain else it.plain ?: it.synced }
                if (text != null) {
                    found++
                    setField(t.id, TagField.LYRICS, text)
                }
            }
            progress = null
            message = (if (quietIfNone) "Dados aplicados. " else "") + when {
                targets.size == 1 && found == 1 -> "Letra encontrada."
                targets.size == 1 -> error?.let { "Erro ao buscar letra: $it" } ?: "Letra não encontrada no LRCLIB."
                else -> "Letras encontradas: $found de ${targets.size}." + (error?.let { " (alguns erros de rede)" } ?: "")
            }
        }
    }

    // ---------- images ----------

    /** Square, center-cropped JPEG of at most 1200 px, the shape players expect. */
    private fun coverFromImage(uri: Uri): Cover {
        val cr = ctx.contentResolver
        val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("imagem inválida")

        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val side = minOf(bmp.width, bmp.height)
        val scale = minOf(1f, 1200f / side)
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, m, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Cover(out.toByteArray(), "image/jpeg")
    }

    companion object {
        private const val TAG = "EditorViewModel"
    }
}

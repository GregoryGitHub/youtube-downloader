package com.youtubedownloaderandroid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

data class PlaylistInfo(
    val title: String?,
    val entries: List<PlaylistEntry>,
    val isPlaylist: Boolean,
    /** YouTube Music album (OLAK5uy_ playlist): all tracks share year/genre/folder. */
    val isAlbum: Boolean = false,
    /** Year YouTube reports for the album, used only if the catalogues have nothing. */
    val year: String? = null
)

data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String
)

private fun JSONObject.optStringOrEmpty(key: String): String =
    if (isNull(key)) "" else optString(key, "").takeUnless { it == "null" } ?: ""

class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"

        /** Marker written once yt-dlp finished (download + conversion + tags). */
        private const val DONE_MARKER = ".ytdl_done"

        /**
         * Square cover crop. YouTube thumbnails come in two shapes:
         *  - 16:9 (maxres/hq720): crop the centre `ih x ih` square;
         *  - 4:3 (hqdefault/sddefault): the 16:9 image is letterboxed with black
         *    bars, so the usable height is `iw*9/16` - crop that square instead.
         * Images that are already square (or portrait) use their smallest side.
         * Single quotes protect the commas from ffmpeg's filtergraph parser and
         * the double quotes keep them intact through yt-dlp's shlex split.
         */
        private const val SQUARE_SIDE =
            "if(gte(iw/ih,1.7),ih,if(gte(iw/ih,1.3),trunc(iw*9/32)*2,min(iw,ih)))"
        private const val SQUARE_CROP = "crop='$SQUARE_SIDE':'$SQUARE_SIDE'"

        /** Serialises folder lookup/creation so parallel items never create "Album (1)". */
        private val folderLock = Any()

        /** Directory holding the per-item work folders (kept between retries). */
        fun workRoot(context: Context) = File(context.cacheDir, "work")

        fun workDirFor(context: Context, itemId: String): File =
            File(workRoot(context), itemId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

        private const val META_FILE = "album_meta.json"

        /** Album-level metadata, so every track of an album gets the same year/genre/folder. */
        private val albumCache = ConcurrentHashMap<String, AlbumMeta>()
        private val albumLocks = ConcurrentHashMap<String, Mutex>()

        private fun isYear(s: String?) = s != null && s.length == 4 && s.all { it.isDigit() }

        /** Value usable as a literal in --parse-metadata: no ':' (separator) nor template chars. */
        private fun literal(value: String): String =
            "%(n_o_n_e|" + value.replace(Regex("[:%()|]"), " ").trim() + ")s"
    }

    suspend fun fetchPlaylistOrVideoInfo(url: String): PlaylistInfo = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")

        val response = YoutubeDL.getInstance().execute(request)
        val json = response.out ?: throw Exception("Nenhum dado retornado do link.")

        val root = JSONObject(json)
        val title = root.optString("title", root.optString("fulltitle", "Download"))

        val playlistId = root.optString("id", "")
        val isAlbum = playlistId.startsWith("OLAK5uy_")
        var albumYear: String? = null
        if (isAlbum) {
            // optString() turns JSON null into the text "null" - treat it as missing.
            val releaseYear = root.optStringOrEmpty("release_year")
            val releaseDate = root.optStringOrEmpty("release_date")
            val year = if (releaseYear.isNotEmpty()) {
                releaseYear
            } else if (releaseDate.length >= 4) {
                releaseDate.substring(0, 4)
            } else {
                ""
            }
            if (isYear(year)) albumYear = year
        }

        val entries = mutableListOf<PlaylistEntry>()

        val type = root.optString("_type")
        if (type == "playlist") {
            val entriesArray = root.optJSONArray("entries")
            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val entry = entriesArray.getJSONObject(i)
                    val id = entry.optString("id", "")
                    val entryTitle = entry.optString("title", "Faixa ${i + 1}")
                    val entryUrl = entry.optString("url", "https://www.youtube.com/watch?v=$id")
                    if (id.isNotEmpty()) {
                        entries.add(PlaylistEntry(id, entryTitle, entryUrl))
                    }
                }
            }
        } else {
            val id = root.optString("id", "")
            val entryTitle = root.optString("title", "Música")
            val entryUrl = root.optString("webpage_url", url)
            entries.add(PlaylistEntry(id, entryTitle, entryUrl))
        }

        PlaylistInfo(title = title, entries = entries, isPlaylist = type == "playlist", isAlbum = isAlbum, year = albumYear)
    }

    /**
     * Downloads one item as MP3 into a work folder that is unique per item and
     * survives failures, then copies the result to the destination.
     *
     * Because the work folder is kept, a retry only redoes the step that failed:
     *  - finished MP3 + done marker  -> only the copy to the destination runs;
     *  - source audio already there  -> yt-dlp skips the download and converts;
     *  - partial `.part` download    -> yt-dlp resumes it.
     */
    suspend fun downloadAudio(
        itemId: String,
        url: String,
        folderMode: String = "public", // "public" ou "custom"
        customFolderUri: String? = null,
        subfolderName: String? = null,
        trackNumber: Int? = null,
        isAlbum: Boolean = false,
        albumYear: String? = null,
        isLastAttempt: Boolean = true,
        onProgress: (Float, String, String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val workDir = workDirFor(context, itemId)
        try {
            if (!workDir.exists()) workDir.mkdirs()
            val marker = File(workDir, DONE_MARKER)

            // Real release year / genre (iTunes, Deezer), resolved once per album.
            val meta = resolveMeta(itemId, url, workDir, if (isAlbum) subfolderName else null, isLastAttempt, onProgress)
            val folderYear = meta.year ?: albumYear
            val folderName = subfolderName?.let { if (isAlbum && isYear(folderYear)) "$it ($folderYear)" else it }

            if (marker.exists() && findMp3(workDir) != null) {
                Log.i(TAG, "[$itemId] MP3 já pronto de tentativa anterior, apenas copiando.")
                onProgress(100f, "N/A", "[move] Copiando para a pasta de destino...")
            } else {
                cleanupInterruptedConversion(workDir)
                runYtDlp(itemId, url, workDir, trackNumber, meta, isLastAttempt, onProgress)
                marker.createNewFile()
            }

            val mp3 = findMp3(workDir) ?: throw Exception("Nenhum arquivo MP3 foi gerado.")
            onProgress(100f, "N/A", "[move] Copiando para a pasta de destino...")
            val folderUri = if (folderMode == "custom" && !customFolderUri.isNullOrEmpty()) {
                moveToCustomFolder(mp3, customFolderUri, folderName)
            } else {
                moveToPublicMusicFolder(mp3, folderName)
            }

            workDir.deleteRecursively()
            Result.success(folderUri)
        } catch (e: Exception) {
            // Work folder is intentionally kept so the retry can reuse it.
            Log.e(TAG, "[$itemId] Download failed", e)
            Result.failure(e)
        }
    }

    /**
     * Year/genre for this item. Order: value saved by a previous attempt,
     * album cache (other track of the same album), then a lookup.
     */
    private suspend fun resolveMeta(
        itemId: String,
        url: String,
        workDir: File,
        albumKey: String?,
        isLastAttempt: Boolean,
        onProgress: (Float, String, String) -> Unit
    ): AlbumMeta {
        val saved = File(workDir, META_FILE)
        if (saved.exists()) {
            runCatching { AlbumMeta.fromJson(JSONObject(saved.readText())) }.getOrNull()?.let { return it }
        }

        val meta = if (albumKey != null) {
            albumCache[albumKey] ?: albumLocks.getOrPut(albumKey) { Mutex() }.withLock {
                albumCache[albumKey] ?: lookupFor(itemId, url, isLastAttempt, onProgress).also {
                    albumCache[albumKey] = it
                }
            }
        } else {
            lookupFor(itemId, url, isLastAttempt, onProgress)
        }

        runCatching { saved.writeText(meta.toJson().toString()) }
        return meta
    }

    private suspend fun lookupFor(
        itemId: String,
        url: String,
        isLastAttempt: Boolean,
        onProgress: (Float, String, String) -> Unit
    ): AlbumMeta {
        onProgress(0f, "N/A", "[meta] Buscando ano e genero...")
        val probe = withContext(Dispatchers.IO) { probeTrack(itemId, url) }
        // Year from YouTube's own release date when the catalogues have nothing.
        val ytYear = probe.releaseDate.take(4).takeIf { isYear(it) }
        if (probe.artist.isBlank() || probe.album.isBlank()) {
            return AlbumMeta(ytYear, null, "youtube")
        }
        val found = try {
            MetadataLookup.lookup(probe.artist, probe.album)
        } catch (e: IOException) {
            // Catalogues unreachable: retry later, unless this is the last chance.
            if (!isLastAttempt) throw Exception("Sem conexão com iTunes/Deezer para buscar ano e gênero", e)
            AlbumMeta.NONE
        }
        return if (found.year != null) found else AlbumMeta(ytYear, found.genre, "youtube")
    }

    private data class TrackProbe(val artist: String, val album: String, val releaseDate: String)

    /** Reads artist / album / release date of a track without downloading it. */
    private fun probeTrack(itemId: String, url: String): TrackProbe {
        val request = YoutubeDLRequest(url)
        request.addOption("--skip-download")
        request.addOption("--no-warnings")
        request.addOption("--no-playlist")
        request.addOption("--extractor-retries", "5")
        request.addOption("--print", "%(artist,creator)s|||%(album)s|||%(release_date)s")
        val line = try {
            YoutubeDL.getInstance().execute(request, "$itemId-probe").out.trim().lines().lastOrNull().orEmpty()
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Exception) {
            throw Exception(shortError(e), e)
        }
        val parts = line.split("|||").map { it.trim().takeUnless { v -> v == "NA" }.orEmpty() }
        // Main artist only: "A, B, C" -> "A".
        val artist = parts.getOrElse(0) { "" }.substringBefore(",").trim()
        return TrackProbe(artist, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    private fun runYtDlp(
        itemId: String,
        url: String,
        workDir: File,
        trackNumber: Int?,
        meta: AlbumMeta,
        isLastAttempt: Boolean,
        onProgress: (Float, String, String) -> Unit
    ) {
        val request = YoutubeDLRequest(url)

        request.addOption("--extract-audio")
        request.addOption("--audio-format", "mp3")
        request.addOption("--audio-quality", "320k")
        request.addOption("--add-metadata")
        request.addOption("--embed-thumbnail")
        request.addOption("--convert-thumbnails", "jpg")

        // Parse album/release year with fallback to upload date year
        request.addOption("--parse-metadata", "%(release_date,upload_date>%Y)s:%(release_year)s")

        if (trackNumber != null) {
            request.addOption("--parse-metadata", "$trackNumber:%(track_number)s")
        }

        // Real release year / genre from iTunes/Deezer override YouTube's values.
        meta.year?.let {
            request.addOption("--parse-metadata", "${literal(it)}:%(release_year)s")
            request.addOption("--parse-metadata", "${literal(it)}:%(meta_date)s")
        }
        meta.genre?.let {
            request.addOption("--parse-metadata", "${literal(it)}:%(meta_genre)s")
        }

        // Keep only the main artist: "A, B, C" -> "A" (same rule as desktop).
        request.addCommands(listOf("--replace-in-metadata", "artist,album_artist", "\\s*,.*", ""))

        // Square cover: crop when the thumbnail is converted to JPG and again
        // when it is embedded (no-op if already square).
        request.addOption("--ppa", "ThumbnailsConvertor+ffmpeg_o:-vf \"$SQUARE_CROP\"")
        request.addOption("--ppa", "EmbedThumbnail+ffmpeg_o:-c:v mjpeg -vf \"$SQUARE_CROP\"")

        // Network resilience: YouTube throttles/resets connections when several
        // downloads run at once, so retry inside yt-dlp with backoff first.
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        request.addOption("--extractor-retries", "5")
        request.addOption("--retry-sleep", "http:exp=1:20")
        request.addOption("--retry-sleep", "fragment:exp=1:20")
        request.addOption("--retry-sleep", "extractor:exp=1:20")
        request.addOption("--socket-timeout", "30")

        // Reuse what a previous attempt already produced.
        request.addOption("--continue")
        request.addOption("--no-post-overwrites")

        request.addOption("--no-mtime")
        request.addOption("--no-warnings")
        request.addOption("--no-playlist")
        request.addOption("-o", "${workDir.absolutePath}/%(title).150B.%(ext)s")

        try {
            YoutubeDL.getInstance().execute(request, itemId) { progress: Float, etaInSeconds: Long, line: String ->
                val etaString = if (etaInSeconds > 0) "${etaInSeconds}s" else "N/A"
                onProgress(progress, etaString, line)
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Exception) {
            val mp3 = findMp3(workDir)
            if (mp3 != null && !hasSourceAudio(workDir) && isLastAttempt) {
                // Audio is fine, only a secondary step (e.g. cover) failed and
                // there are no attempts left: keep the file instead of losing it.
                Log.w(TAG, "[$itemId] Erro secundário no yt-dlp, mantendo MP3 gerado.", e)
                return
            }
            throw Exception(shortError(e), e)
        }
    }

    private fun findMp3(dir: File): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.extension.equals("mp3", true) && it.length() > 0 }

    private val sourceExtensions = setOf("webm", "m4a", "opus", "ogg", "mp4", "aac", "mka", "weba")

    private fun hasSourceAudio(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.extension.lowercase() in sourceExtensions } == true

    /**
     * If the source audio is still present next to an MP3, the conversion was
     * interrupted: the MP3 may be truncated, so drop it and convert again
     * (the source is reused, nothing is downloaded twice).
     */
    private fun cleanupInterruptedConversion(dir: File) {
        if (!hasSourceAudio(dir)) return
        dir.listFiles()?.filter {
            it.isFile && (it.extension.equals("mp3", true) || it.name.contains(".temp."))
        }?.forEach { it.delete() }
    }

    /** Last meaningful "ERROR:" line from yt-dlp instead of the whole stderr dump. */
    private fun shortError(e: Exception): String {
        val msg = e.message ?: return "Falha na conversão ou download"
        val errorLine = msg.lines().lastOrNull { it.contains("ERROR:") } ?: msg.lines().firstOrNull { it.isNotBlank() }
        return errorLine?.substringAfter("ERROR:")?.trim()?.take(300) ?: "Falha na conversão ou download"
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /** @return document URI of the folder that received the file. */
    private fun moveToCustomFolder(file: File, treeUriString: String, subfolderName: String?): String {
        val treeUri = Uri.parse(treeUriString)
        val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw Exception("Não foi possível acessar a pasta selecionada.")

        val pickedDir = synchronized(folderLock) {
            if (subfolderName != null) {
                val sanitized = sanitizeFilename(subfolderName)
                val existingDir = rootDir.findFile(sanitized)
                if (existingDir != null && existingDir.isDirectory) {
                    existingDir
                } else {
                    rootDir.createDirectory(sanitized) ?: rootDir
                }
            } else rootDir
        }

        pickedDir.findFile(file.name)?.delete()

        val newFile = pickedDir.createFile("audio/mpeg", file.name) ?: throw Exception("Não foi possível criar o arquivo de áudio na pasta destino.")
        try {
            val out = context.contentResolver.openOutputStream(newFile.uri)
                ?: throw Exception("Não foi possível gravar na pasta destino.")
            out.use { outputStream ->
                FileInputStream(file).use { it.copyTo(outputStream) }
            }
        } catch (e: Exception) {
            newFile.delete()
            throw e
        }
        return pickedDir.uri.toString()
    }

    /** @return document URI (external storage provider) of the folder that received the file. */
    private fun moveToPublicMusicFolder(file: File, subfolderName: String?): String {
        val resolver = context.contentResolver
        val sanitizedSubfolder = subfolderName?.let { sanitizeFilename(it) }
        val relativePath = if (sanitizedSubfolder != null) "Music/$sanitizedSubfolder/" else "Music/"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // A previous attempt may have already written this file: replace it
        // instead of creating "Música (1).mp3".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                resolver.delete(
                    collection,
                    "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?",
                    arrayOf(relativePath, file.name)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível remover arquivo anterior ${file.name}", e)
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, contentValues)
            ?: throw Exception("Não foi possível criar o arquivo na pasta Música.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw Exception("Não foi possível gravar na pasta Música.")
            out.use { outputStream ->
                FileInputStream(file).use { it.copyTo(outputStream) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:" + relativePath.trimEnd('/')
        ).toString()
    }

    suspend fun getVideoInfo(url: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().getInfo(url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get info", e)
            null
        }
    }
}

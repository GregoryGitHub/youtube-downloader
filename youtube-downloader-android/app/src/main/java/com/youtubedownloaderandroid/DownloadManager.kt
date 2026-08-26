package com.youtubedownloaderandroid

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import android.os.Environment
import org.json.JSONObject

data class PlaylistInfo(
    val title: String?,
    val entries: List<PlaylistEntry>,
    val isPlaylist: Boolean
)

data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String
)

class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
    }

    suspend fun fetchPlaylistOrVideoInfo(url: String): PlaylistInfo = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        
        val response = YoutubeDL.getInstance().execute(request)
        val json = response.out ?: throw Exception("Nenhum dado retornado do link.")
        
        val root = JSONObject(json)
        var title = root.optString("title", root.optString("fulltitle", "Download"))
        
        val playlistId = root.optString("id", "")
        if (playlistId.startsWith("OLAK5uy_")) {
            val releaseYear = root.optString("release_year", "")
            val releaseDate = root.optString("release_date", "")
            val year = if (releaseYear.isNotEmpty()) {
                releaseYear
            } else if (releaseDate.length >= 4) {
                releaseDate.substring(0, 4)
            } else {
                ""
            }
            if (year.isNotEmpty()) {
                title = "$title ($year)"
            }
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
        
        PlaylistInfo(title = title, entries = entries, isPlaylist = type == "playlist")
    }

    suspend fun downloadAudio(
        url: String,
        folderMode: String = "public", // "public" ou "custom"
        customFolderUri: String? = null,
        subfolderName: String? = null,
        trackNumber: Int? = null,
        onProgress: (Float, String, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "downloads_${System.currentTimeMillis()}")
        try {
            // 1. Criar diretório temporário dentro da área privada do app
            if (!tempDir.exists()) tempDir.mkdirs()

            val request = YoutubeDLRequest(url)
            
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "320k")
            request.addOption("--add-metadata")
            request.addOption("--embed-thumbnail")
            
            // Parse album/release year with fallback to upload date year
            request.addOption("--parse-metadata", "%(release_date,upload_date>%Y)s:%(release_year)s")
            
            if (trackNumber != null) {
                request.addOption("--parse-metadata", "$trackNumber:%(track_number)s")
            }
            request.addOption("--ppa", "EmbedThumbnail+ffmpeg_o:-c:v mjpeg -vf crop=ih:ih")
            request.addOption("--no-mtime")
            request.addOption("--no-warnings")
            request.addOption("--ignore-errors")
            request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")

            // 2. Executar o download (capturando erros secundários de modificação de data/tempo ou metadados)
            try {
                YoutubeDL.getInstance().execute(request) { progress: Float, etaInSeconds: Long, line: String ->
                    val etaString = if (etaInSeconds > 0) "${etaInSeconds}s" else "N/A"
                    onProgress(progress, etaString, line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro secundário na execução do YoutubeDL, verificando se os arquivos foram criados...", e)
            }

            // 3. Mover os arquivos .mp3 resultantes para o destino selecionado
            val files = tempDir.listFiles() ?: emptyArray()
            var movedAtLeastOne = false
            
            for (file in files) {
                if (file.extension.lowercase() == "mp3") {
                    if (folderMode == "custom" && !customFolderUri.isNullOrEmpty()) {
                        moveToCustomFolder(file, customFolderUri, subfolderName)
                    } else {
                        moveToPublicMusicFolder(file, subfolderName)
                    }
                    movedAtLeastOne = true
                }
            }

            // Limpar diretório temporário
            tempDir.deleteRecursively()

            if (movedAtLeastOne) {
                Result.success(Unit) 
            } else {
                Result.failure(Exception("Nenhum arquivo MP3 foi gerado."))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            tempDir.deleteRecursively()
            Result.failure(e)
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun moveToCustomFolder(file: File, treeUriString: String, subfolderName: String?) {
        val treeUri = Uri.parse(treeUriString)
        var pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw Exception("Não foi possível acessar a pasta selecionada.")
        
        if (subfolderName != null) {
            val sanitized = sanitizeFilename(subfolderName)
            val existingDir = pickedDir.findFile(sanitized)
            pickedDir = if (existingDir != null && existingDir.isDirectory) {
                existingDir
            } else {
                pickedDir.createDirectory(sanitized) ?: pickedDir
            }
        }
        
        val existingFile = pickedDir.findFile(file.name)
        if (existingFile != null) {
            existingFile.delete()
        }
        
        val newFile = pickedDir.createFile("audio/mpeg", file.name) ?: throw Exception("Não foi possível criar o arquivo de áudio na pasta destino.")
        context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
            FileInputStream(file).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    private fun moveToPublicMusicFolder(file: File, subfolderName: String?) {
        val resolver = context.contentResolver
        val sanitizedSubfolder = subfolderName?.let { sanitizeFilename(it) }
        val relativePath = if (sanitizedSubfolder != null) "Music/$sanitizedSubfolder/" else "Music/"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                FileInputStream(file).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        }
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

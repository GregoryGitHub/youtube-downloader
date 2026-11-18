package com.audiotagger

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/** A file found in the folder the user opened, before its tags are read. */
data class AudioDoc(val uri: Uri, val name: String, val folder: String)

/**
 * Reads and writes tags with jaudiotagger. Files come from the Storage Access
 * Framework (content:// URIs), so each one is copied into the app cache, edited
 * there, checked, and then written back over the original.
 */
object TagIO {
    val EXTENSIONS = setOf("mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "wav", "aif", "aiff", "aifc", "wma", "dsf")

    init {
        TagOptionSingleton.getInstance().apply {
            isAndroid = true
            // v2.3 is what Windows, car stereos and most players read best.
            iD3V2Version = ID3V2Version.ID3_V23
        }
        // jaudiotagger is very chatty on java.util.logging.
        Logger.getLogger("org.jaudiotagger").level = Level.WARNING
    }

    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Audio files in a folder and up to two levels of sub-folders (CD1, CD2...). */
    fun listFolder(context: Context, treeUri: Uri): List<AudioDoc> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<AudioDoc>()
        fun walk(dir: DocumentFile, path: String, depth: Int) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) {
                    if (depth < 2) walk(f, if (path.isEmpty()) name else "$path/$name", depth + 1)
                } else if (isAudio(name)) {
                    out += AudioDoc(f.uri, name, path)
                }
            }
        }
        walk(root, "", 0)
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AudioDoc::folder).thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun folderName(context: Context, treeUri: Uri): String =
        DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Pasta"

    fun displayName(context: Context, uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"

    fun load(context: Context, doc: AudioDoc, id: Int, workDir: File): Track {
        val ext = doc.name.substringAfterLast('.', "mp3").lowercase()
        val cache = File(workDir, "$id.$ext")
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            cache.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Não foi possível abrir ${doc.name}")

        val af = AudioFileIO.read(cache)
        val header = af.audioHeader
        val tag: Tag? = af.tag
        val values = TagField.entries.associateWith { f -> tag?.read(f.key).orEmpty() }
        val cover = tag?.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() }?.let { bytes ->
            Cover(bytes, tag.firstArtwork.mimeType?.takeIf { it.isNotBlank() } ?: sniffMime(bytes))
        }
        val format = buildString {
            append(ext.uppercase())
            header.bitRate?.takeIf { it.isNotBlank() && it != "-1" }?.let { append(" · ").append(it.removePrefix("~")).append(" kbps") }
        }
        return Track(
            id = id,
            uri = doc.uri,
            fileName = doc.name,
            folder = doc.folder,
            cache = cache,
            durationSec = header.trackLength,
            format = format,
            original = values,
            values = values,
            originalCover = cover,
            cover = cover,
        )
    }

    private fun Tag.read(key: FieldKey): String = try {
        val v = getFirst(key).orEmpty().trim()
        // ID3 can hold "03" or a leftover "0" in number fields.
        if (key in NUMBER_KEYS) v.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: "" else v
    } catch (_: Exception) {
        ""
    }

    private val NUMBER_KEYS = setOf(FieldKey.TRACK, FieldKey.TRACK_TOTAL, FieldKey.DISC_NO, FieldKey.DISC_TOTAL)

    /**
     * Writes the changed fields into the cached copy, re-reads it to make sure
     * the result is a valid file, then replaces the original.
     * @return the track with the saved values as its new baseline.
     */
    fun save(context: Context, track: Track): Track {
        val af = AudioFileIO.read(track.cache)
        val tag = af.tagOrCreateAndSetDefault
        val changed = track.changedFields.toMutableSet()

        // Number and total share a frame in ID3/MP4: rewrite them together.
        if (TagField.TRACK in changed || TagField.TRACK_TOTAL in changed) changed += listOf(TagField.TRACK, TagField.TRACK_TOTAL)
        if (TagField.DISC in changed || TagField.DISC_TOTAL in changed) changed += listOf(TagField.DISC, TagField.DISC_TOTAL)

        for (f in changed.sortedBy { it.ordinal }) {
            runCatching { tag.deleteField(f.key) }
        }
        for (f in changed.sortedBy { it.ordinal }) {
            val v = track[f].trim()
            if (v.isNotEmpty()) tag.setField(f.key, v)
        }

        if (track.coverChanged) {
            runCatching { tag.deleteArtworkField() }
            track.cover?.let { setCover(tag, it) }
        }
        af.commit()

        // Never put back a file we can no longer read.
        AudioFileIO.read(track.cache)
        writeBack(context, track.cache, track.uri)
        scan(context, track.uri)
        return track.copy(original = track.values, originalCover = track.cover)
    }

    private fun setCover(tag: Tag, cover: Cover) {
        if (tag is FlacTag) {
            // FlacTag.createField(Artwork) needs java.awt on Android; build the block directly.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(cover.bytes, 0, cover.bytes.size, bounds)
            tag.setField(
                MetadataBlockDataPicture(
                    cover.bytes, PictureTypes.DEFAULT_ID, cover.mime, "",
                    bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0), 24, 0,
                )
            )
        } else {
            val art = AndroidArtwork().apply {
                binaryData = cover.bytes
                mimeType = cover.mime
                pictureType = PictureTypes.DEFAULT_ID
                description = ""
            }
            tag.setField(art)
        }
    }

    private fun writeBack(context: Context, src: File, uri: Uri) {
        val cr = context.contentResolver
        // "rwt" truncates reliably; plain "w" did not on some Android 10 builds.
        val pfd = runCatching { cr.openFileDescriptor(uri, "rwt") }.getOrNull()
        if (pfd != null) {
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                    out.flush()
                    out.channel.truncate(src.length())
                }
            }
            return
        }
        cr.openOutputStream(uri, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("Sem permissão de escrita")
    }

    /** Tells the media library about the new tags, when the file lives on shared storage. */
    private fun scan(context: Context, uri: Uri) {
        val path = runCatching {
            val id = DocumentsContract.getDocumentId(uri)
            val (volume, rel) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (volume.equals("primary", true)) "${Environment.getExternalStorageDirectory()}/$rel"
            else "/storage/$volume/$rel"
        }.getOrNull() ?: return
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    fun sniffMime(bytes: ByteArray): String =
        if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
}

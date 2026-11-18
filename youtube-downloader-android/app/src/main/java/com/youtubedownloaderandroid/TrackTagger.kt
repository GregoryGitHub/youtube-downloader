package com.youtubedownloaderandroid

import android.content.Context
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.reference.PictureTypes
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

/** Full catalogue metadata for one track, chosen by the user when queuing an album. */
data class TrackMeta(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String?,
    val genre: String?,
    val track: Int,
    val trackTotal: Int,
    val disc: Int,
    val discTotal: Int,
    val durationSec: Int,
    val coverUrl: String?,
    val lyrics: Boolean,
) {
    /** "Album (1999)" */
    val albumFolder get() = if (year != null) "$album ($year)" else album

    /** "03 - Title" (or "1-03 - Title" on multi-disc albums). */
    val fileStem get() = (if (discTotal > 1) "$disc-" else "") + track.toString().padStart(2, '0') + " - " + title

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title); put("artist", artist); put("album", album); put("albumArtist", albumArtist)
        year?.let { put("year", it) }; genre?.let { put("genre", it) }
        put("track", track); put("trackTotal", trackTotal); put("disc", disc); put("discTotal", discTotal)
        put("durationSec", durationSec); coverUrl?.let { put("coverUrl", it) }; put("lyrics", lyrics)
    }

    companion object {
        fun fromJson(o: JSONObject) = TrackMeta(
            title = o.getString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            albumArtist = o.optString("albumArtist"),
            year = o.optString("year").takeIf { it.isNotEmpty() },
            genre = o.optString("genre").takeIf { it.isNotEmpty() },
            track = o.optInt("track"),
            trackTotal = o.optInt("trackTotal"),
            disc = o.optInt("disc", 1),
            discTotal = o.optInt("discTotal", 1),
            durationSec = o.optInt("durationSec"),
            coverUrl = o.optString("coverUrl").takeIf { it.isNotEmpty() },
            lyrics = o.optBoolean("lyrics", true),
        )
    }
}

/** Writes tags into the finished MP3 with jaudiotagger. */
object TrackTagger {
    private const val TAG = "TrackTagger"

    init {
        TagOptionSingleton.getInstance().apply {
            isAndroid = true
            iD3V2Version = ID3V2Version.ID3_V23
        }
        Logger.getLogger("org.jaudiotagger").level = Level.WARNING
    }

    /** Main artist (album artist, else artist) from the tags yt-dlp wrote, for the artist folder. */
    fun mainArtist(mp3: File): String? = runCatching {
        val tag = AudioFileIO.read(mp3).tag ?: return null
        val raw = tag.getFirst(FieldKey.ALBUM_ARTIST).ifBlank { tag.getFirst(FieldKey.ARTIST) }
        raw.substringBefore(",").trim().takeIf { it.isNotEmpty() && it != "NA" }
    }.getOrNull()

    /**
     * Replaces the tags with the catalogue data. Cover and lyrics are best
     * effort: a network failure there never fails the download.
     */
    fun apply(context: Context, mp3: File, meta: TrackMeta, onStatus: (String) -> Unit) {
        val cover = meta.coverUrl?.let { url ->
            onStatus("[meta] Baixando capa do álbum...")
            runCatching { coverBytes(context, url) }.onFailure { Log.w(TAG, "capa", it) }.getOrNull()
        }
        val lyrics = if (meta.lyrics) {
            onStatus("[meta] Buscando letra...")
            runCatching { AlbumCatalog.lyrics(meta.artist, meta.title, meta.album, meta.durationSec) }
                .onFailure { Log.w(TAG, "letra", it) }.getOrNull()
        } else null

        onStatus("[Metadata] Gravando metadados completos...")
        writeTags(mp3, meta, cover, lyrics)
    }

    /** Pure tag writing, separated from the network so it can be unit tested. */
    fun writeTags(mp3: File, meta: TrackMeta, cover: ByteArray?, lyrics: String?) {
        val af = AudioFileIO.read(mp3)
        val tag = af.tagOrCreateAndSetDefault
        fun set(key: FieldKey, value: String?) {
            runCatching { tag.deleteField(key) }
            if (!value.isNullOrBlank()) tag.setField(key, value)
        }
        set(FieldKey.TITLE, meta.title)
        set(FieldKey.ARTIST, meta.artist)
        set(FieldKey.ALBUM, meta.album)
        set(FieldKey.ALBUM_ARTIST, meta.albumArtist)
        set(FieldKey.YEAR, meta.year)
        set(FieldKey.GENRE, meta.genre)
        runCatching { tag.deleteField(FieldKey.TRACK) }
        runCatching { tag.deleteField(FieldKey.DISC_NO) }
        tag.setField(FieldKey.TRACK, meta.track.toString())
        tag.setField(FieldKey.TRACK_TOTAL, meta.trackTotal.toString())
        tag.setField(FieldKey.DISC_NO, meta.disc.toString())
        tag.setField(FieldKey.DISC_TOTAL, meta.discTotal.toString())
        if (lyrics != null) set(FieldKey.LYRICS, lyrics)
        if (cover != null) {
            runCatching { tag.deleteArtworkField() }
            tag.setField(AndroidArtwork().apply {
                binaryData = cover
                mimeType = if (cover.size > 3 && cover[0] == 0x89.toByte()) "image/png" else "image/jpeg"
                pictureType = PictureTypes.DEFAULT_ID
                description = ""
            })
        }
        af.commit()
    }

    /** Album covers are shared by every track: download each one once. */
    @Synchronized
    private fun coverBytes(context: Context, url: String): ByteArray {
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, name)
        if (file.length() > 0) return file.readBytes()
        val bytes = AlbumCatalog.download(url)
        file.writeBytes(bytes)
        return bytes
    }
}

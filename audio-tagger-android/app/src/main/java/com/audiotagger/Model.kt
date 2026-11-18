package com.audiotagger

import android.net.Uri
import org.jaudiotagger.tag.FieldKey
import java.io.File

/** The tag fields the app edits, in display order. */
enum class TagField(
    val label: String,
    val key: FieldKey,
    /** Shown in the album card and applied to every track at once. */
    val albumLevel: Boolean,
    val numeric: Boolean = false,
    val multiline: Boolean = false,
) {
    TITLE("Título", FieldKey.TITLE, albumLevel = false),
    ARTIST("Artista", FieldKey.ARTIST, albumLevel = false),
    ALBUM("Álbum", FieldKey.ALBUM, albumLevel = true),
    ALBUM_ARTIST("Artista do álbum", FieldKey.ALBUM_ARTIST, albumLevel = true),
    YEAR("Ano", FieldKey.YEAR, albumLevel = true, numeric = true),
    GENRE("Gênero", FieldKey.GENRE, albumLevel = true),
    TRACK("Faixa", FieldKey.TRACK, albumLevel = false, numeric = true),
    TRACK_TOTAL("Total de faixas", FieldKey.TRACK_TOTAL, albumLevel = true, numeric = true),
    DISC("Disco", FieldKey.DISC_NO, albumLevel = false, numeric = true),
    DISC_TOTAL("Total de discos", FieldKey.DISC_TOTAL, albumLevel = true, numeric = true),
    COMPOSER("Compositor", FieldKey.COMPOSER, albumLevel = false),
    COMMENT("Comentário", FieldKey.COMMENT, albumLevel = false, multiline = true),
    LYRICS("Letra", FieldKey.LYRICS, albumLevel = false, multiline = true);

    companion object {
        val ALBUM_FIELDS = entries.filter { it.albumLevel }
        val TRACK_FIELDS = entries.filter { !it.albumLevel }
    }
}

typealias TagValues = Map<TagField, String>

class Cover(val bytes: ByteArray, val mime: String) {
    override fun equals(other: Any?) = other is Cover && mime == other.mime && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
}

/** One audio file opened in the editor: what is on disk and what the user has changed. */
data class Track(
    val id: Int,
    val uri: Uri,
    val fileName: String,
    /** Sub-folder inside the opened folder ("CD1"), empty at the top level. */
    val folder: String,
    /** Working copy in the app cache; jaudiotagger only works on real files. */
    val cache: File,
    val durationSec: Int,
    val format: String,
    val original: TagValues,
    val values: TagValues,
    val originalCover: Cover?,
    val cover: Cover?,
) {
    operator fun get(field: TagField): String = values[field].orEmpty()

    val coverChanged get() = cover != originalCover
    val changedFields get() = TagField.entries.filter { values[it].orEmpty() != original[it].orEmpty() }
    val dirty get() = coverChanged || changedFields.isNotEmpty()

    val displayTitle get() = this[TagField.TITLE].ifBlank { fileName.substringBeforeLast('.') }
    val trackNo get() = this[TagField.TRACK].toIntOrNull()
    val discNo get() = this[TagField.DISC].toIntOrNull()

    fun with(field: TagField, value: String) = copy(values = values + (field to value))
}

enum class Source(val label: String) { ITUNES("iTunes"), DEEZER("Deezer") }

data class AlbumResult(
    val source: Source,
    val id: String,
    val title: String,
    val artist: String,
    val year: String?,
    val trackCount: Int,
    val thumbUrl: String?,
    val coverUrl: String?,
    val kind: String?,
)

data class RemoteTrack(
    val title: String,
    val artist: String,
    val track: Int,
    val disc: Int,
    val durationSec: Int,
)

data class AlbumDetails(
    val album: AlbumResult,
    val year: String?,
    val genre: String?,
    val tracks: List<RemoteTrack>,
) {
    val discTotal get() = tracks.maxOfOrNull { it.disc }?.coerceAtLeast(1) ?: 1
    fun trackTotal(disc: Int) = tracks.count { it.disc == disc }
}

/** Which parts of an online album get copied into the files. */
enum class ApplyOption(val label: String) {
    TITLE("Títulos"),
    ARTIST("Artista das faixas"),
    ALBUM("Álbum e artista do álbum"),
    YEAR("Ano"),
    GENRE("Gênero"),
    NUMBERS("Nº da faixa e disco"),
    COVER("Capa"),
    LYRICS("Letras (faixas sem letra)"),
}

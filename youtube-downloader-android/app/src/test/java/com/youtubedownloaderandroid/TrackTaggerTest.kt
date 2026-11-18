package com.youtubedownloaderandroid

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TrackTaggerTest {
    private val meta = TrackMeta(
        title = "Canção",
        artist = "Artista feat. Outro",
        album = "Álbum Certo",
        albumArtist = "Artista",
        year = "1998",
        genre = "MPB",
        track = 3,
        trackTotal = 12,
        disc = 1,
        discTotal = 2,
        durationSec = 3,
        coverUrl = null,
        lyrics = true,
    )

    private fun copyFixture(): File {
        val src = File(javaClass.classLoader!!.getResource("sample.mp3").toURI())
        return File.createTempFile("tagger", ".mp3").also { src.copyTo(it, overwrite = true) }
    }

    @Test
    fun writesEveryField() {
        val mp3 = copyFixture()
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        TrackTagger.writeTags(mp3, meta, cover, "linha 1\nlinha 2")

        val tag = AudioFileIO.read(mp3).tag
        assertEquals("Canção", tag.getFirst(FieldKey.TITLE))
        assertEquals("Artista feat. Outro", tag.getFirst(FieldKey.ARTIST))
        assertEquals("Álbum Certo", tag.getFirst(FieldKey.ALBUM))
        assertEquals("Artista", tag.getFirst(FieldKey.ALBUM_ARTIST))
        assertEquals("1998", tag.getFirst(FieldKey.YEAR))
        assertEquals("MPB", tag.getFirst(FieldKey.GENRE))
        assertEquals("3", tag.getFirst(FieldKey.TRACK))
        assertEquals("12", tag.getFirst(FieldKey.TRACK_TOTAL))
        assertEquals("1", tag.getFirst(FieldKey.DISC_NO))
        assertEquals("2", tag.getFirst(FieldKey.DISC_TOTAL))
        assertEquals("linha 1\nlinha 2", tag.getFirst(FieldKey.LYRICS))
        assertArrayEquals(cover, tag.firstArtwork.binaryData)
        mp3.delete()
    }

    @Test
    fun rewritingKeepsASingleValue() {
        val mp3 = copyFixture()
        TrackTagger.writeTags(mp3, meta, null, null)
        TrackTagger.writeTags(mp3, meta.copy(track = 4), null, null)
        val tag = AudioFileIO.read(mp3).tag
        assertEquals("4", tag.getFirst(FieldKey.TRACK))
        assertEquals("12", tag.getFirst(FieldKey.TRACK_TOTAL))
        assertEquals(1, tag.getAll(FieldKey.TITLE).size)
        mp3.delete()
    }

    @Test
    fun matchesPlaylistEntriesToAlbumTracks() {
        val tracks = listOf(
            CatalogTrack("Intro", "A", 1, 1, 60),
            CatalogTrack("Noite Fria", "A", 2, 1, 180),
            CatalogTrack("Canção do Mar", "A", 3, 1, 200),
        )
        val entries = listOf(
            TitleMatch.Entry("x", "Cancao do Mar (Official Audio)", 201, 0),
            TitleMatch.Entry("y", "Intro", 60, 1),
            TitleMatch.Entry("z", "Noite Fria", 179, 2),
        )
        assertEquals(mapOf("x" to 2, "y" to 0, "z" to 1), TitleMatch.match(entries, tracks))
    }
}

package com.audiotagger

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMatcherTest {
    private val remote = listOf(
        RemoteTrack("Intro", "A", 1, 1, 60),
        RemoteTrack("Canção do Mar (Remastered)", "A", 2, 1, 200),
        RemoteTrack("Noite Fria", "A", 3, 1, 180),
    )

    private fun local(id: Int, title: String, file: String, dur: Int, track: Int?) =
        TrackMatcher.Local(id, title, file, dur, track, 1)

    @Test
    fun matchesByTitleEvenWithWrongNumbers() {
        val l = listOf(
            local(10, "Noite fria", "a.mp3", 181, 1),
            local(11, "Cancao do mar", "b.mp3", 199, 3),
            local(12, "Intro", "c.mp3", 60, 2),
        )
        assertEquals(mapOf(10 to 2, 11 to 1, 12 to 0), TrackMatcher.match(l, remote))
    }

    @Test
    fun matchesByFileName() {
        val l = listOf(
            local(1, "", "03 - Noite Fria.flac", 0, null),
            local(2, "", "01 - Intro.flac", 0, null),
            local(3, "", "02 - Canção do Mar.flac", 0, null),
        )
        assertEquals(mapOf(1 to 2, 2 to 0, 3 to 1), TrackMatcher.match(l, remote))
    }

    @Test
    fun fallsBackToOrder() {
        val l = listOf(
            local(1, "", "Track 02.mp3", 0, null),
            local(2, "", "Track 01.mp3", 0, null),
            local(3, "", "Track 03.mp3", 0, null),
        )
        assertEquals(mapOf(2 to 0, 1 to 1, 3 to 2), TrackMatcher.match(l, remote))
    }

    @Test
    fun cleansFileNames() {
        assertEquals("Song", TrackMatcher.cleanFileName("03 - Artist - Song.mp3"))
        assertEquals("Song", TrackMatcher.cleanFileName("1-03 Song.flac"))
        assertEquals("Song Name", TrackMatcher.cleanFileName("07. Song_Name.m4a"))
    }
}

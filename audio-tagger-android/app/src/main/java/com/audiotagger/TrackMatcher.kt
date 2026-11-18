package com.audiotagger

import java.text.Normalizer
import kotlin.math.abs

/**
 * Pairs the local files with the tracks of an online album.
 *
 * Each pair gets a score from the title (tag or file name), the duration and
 * the current track number; the best pairs are taken first. Whatever is left
 * is paired in order, so a CD ripped as "Track 01.mp3"... still lines up.
 */
object TrackMatcher {
    data class Local(
        val id: Int,
        val title: String,
        val fileName: String,
        val durationSec: Int,
        val track: Int?,
        val disc: Int?,
    )

    /** @return local id -> index in [remote]. Unmatched files are absent. */
    fun match(local: List<Local>, remote: List<RemoteTrack>): Map<Int, Int> {
        data class Pair(val l: Int, val r: Int, val score: Double)

        val pairs = buildList {
            for ((li, l) in local.withIndex()) for ((ri, r) in remote.withIndex()) {
                val s = score(l, r)
                if (s >= 1.0) add(Pair(li, ri, s))
            }
        }.sortedByDescending { it.score }

        val result = mutableMapOf<Int, Int>()
        val usedRemote = mutableSetOf<Int>()
        for (p in pairs) {
            val id = local[p.l].id
            if (id in result || p.r in usedRemote) continue
            result[id] = p.r
            usedRemote += p.r
        }

        // Leftovers: pair them in order (current disc/track, then file name).
        val freeLocal = local.filter { it.id !in result }
            .sortedWith(compareBy<Local>({ it.disc ?: 1 }, { it.track ?: Int.MAX_VALUE }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
        val freeRemote = remote.indices.filter { it !in usedRemote }
        if (freeLocal.isNotEmpty() && freeLocal.size == freeRemote.size) {
            freeLocal.zip(freeRemote).forEach { (l, r) -> result[l.id] = r }
        }
        return result
    }

    fun score(l: Local, r: RemoteTrack): Double {
        val titleSim = maxOf(similarity(l.title, r.title), similarity(cleanFileName(l.fileName), r.title))
        var s = titleSim * 3
        if (l.durationSec > 0 && r.durationSec > 0) {
            val diff = abs(l.durationSec - r.durationSec)
            s += when {
                diff <= 2 -> 1.0
                diff <= 6 -> 0.6
                diff <= 15 -> 0.2
                else -> -0.5
            }
        }
        if (l.track == r.track && (l.disc ?: 1) == r.disc) s += 0.4
        return s
    }

    /** 0..1 — 1 for the same title, ignoring accents, case, "(feat. x)", "- Remastered"... */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.startsWith("$y ") || y.startsWith("$x ")) return 0.85
        val tx = x.split(' ').toSet()
        val ty = y.split(' ').toSet()
        return tx.intersect(ty).size.toDouble() / tx.union(ty).size * 0.9
    }

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase()
            .replace(Regex("[(\\[].*?[)\\]]"), " ")
            .replace(Regex("\\s-\\s.*(remaster|ao vivo|live|version|versao|mix|edit).*$"), " ")
            .replace(Regex("\\b(feat|ft)\\.?\\s.*$"), " ")
            .replace("&", " e ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    /** "03 - Artist - Song.mp3" -> "Song"; "1-03 Song.flac" -> "Song". */
    fun cleanFileName(name: String): String {
        var s = name.substringBeforeLast('.')
        s = s.replace(Regex("^\\s*(\\d+[-.])?\\d+\\s*[-._)]*\\s*"), "")
        // "Artist - Song": keep the last part.
        if (" - " in s) s = s.substringAfterLast(" - ")
        return s.replace('_', ' ').trim()
    }
}

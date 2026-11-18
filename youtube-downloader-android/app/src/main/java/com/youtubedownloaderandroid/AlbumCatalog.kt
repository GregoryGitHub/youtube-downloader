package com.youtubedownloaderandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

/** An album found in a public catalogue (iTunes or Deezer). */
data class CatalogAlbum(
    val source: String, // "iTunes" | "Deezer"
    val id: String,
    val title: String,
    val artist: String,
    val year: String?,
    val trackCount: Int,
    val thumbUrl: String?,
    val coverUrl: String?,
)

data class CatalogTrack(
    val title: String,
    val artist: String,
    val track: Int,
    val disc: Int,
    val durationSec: Int,
)

data class CatalogAlbumDetails(
    val album: CatalogAlbum,
    val year: String?,
    val genre: String?,
    val tracks: List<CatalogTrack>,
) {
    val discTotal get() = tracks.maxOfOrNull { it.disc }?.coerceAtLeast(1) ?: 1
    fun trackTotal(disc: Int) = tracks.count { it.disc == disc }
}

/**
 * Album data from the iTunes Search API and the Deezer API, lyrics from LRCLIB.
 * All public and keyless. Calls block: use Dispatchers.IO.
 */
object AlbumCatalog {
    private const val USER_AGENT = "YDownloader-Android/1.0"

    /** Candidates from both catalogues, best match for [artist] / [album] / [trackCount] first. */
    fun search(artist: String, album: String, trackCount: Int): List<CatalogAlbum> {
        val all = mutableListOf<CatalogAlbum>()
        var lastError: IOException? = null
        try { all += itunesSearch(artist, album) } catch (e: IOException) { lastError = e }
        try { all += deezerSearch(artist, album) } catch (e: IOException) { lastError = e }
        if (all.isEmpty() && lastError != null) throw lastError
        return all
            .map { it to score(it, artist, album, trackCount) }
            .filter { it.second > 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { "${it.source}|${it.id}" }
            .take(12)
    }

    private fun score(c: CatalogAlbum, artist: String, album: String, trackCount: Int): Double {
        var s = TitleMatch.similarity(c.title, album) * 2 + TitleMatch.similarity(c.artist, artist)
        if (trackCount > 0 && c.trackCount > 0) {
            s += when (abs(c.trackCount - trackCount)) {
                0 -> 1.0
                1, 2 -> 0.4
                else -> 0.0
            }
        }
        if (c.source == "iTunes") s += 0.05 // Portuguese genres, slightly better covers
        return s
    }

    fun details(album: CatalogAlbum): CatalogAlbumDetails =
        if (album.source == "Deezer") deezerAlbum(album) else itunesAlbum(album)

    private fun itunesSearch(artist: String, album: String): List<CatalogAlbum> {
        val term = enc("$artist $album".trim())
        for (country in listOf("BR", "US")) {
            val list = JSONObject(get("https://itunes.apple.com/search?term=$term&entity=album&country=$country&limit=25"))
                .optJSONArray("results").objects()
                .map { r ->
                    val art = r.optString("artworkUrl100").takeIf { it.isNotBlank() }
                    CatalogAlbum(
                        source = "iTunes",
                        id = "${r.optLong("collectionId")}@$country",
                        title = r.optString("collectionName"),
                        artist = r.optString("artistName"),
                        year = r.optString("releaseDate").take(4).takeIf { it.length == 4 },
                        trackCount = r.optInt("trackCount"),
                        thumbUrl = art,
                        coverUrl = art?.replace("100x100bb", "1200x1200bb"),
                    )
                }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun itunesAlbum(album: CatalogAlbum): CatalogAlbumDetails {
        val (id, country) = album.id.split('@').let { it[0] to it.getOrElse(1) { "US" } }
        val results = JSONObject(get("https://itunes.apple.com/lookup?id=$id&entity=song&country=$country&limit=300"))
            .optJSONArray("results").objects()
        val collection = results.firstOrNull { it.optString("wrapperType") == "collection" }
        val tracks = results.filter { it.optString("wrapperType") == "track" && it.optString("kind") == "song" }
            .map {
                CatalogTrack(
                    title = it.optString("trackName"),
                    artist = it.optString("artistName"),
                    track = it.optInt("trackNumber"),
                    disc = it.optInt("discNumber", 1).coerceAtLeast(1),
                    durationSec = it.optInt("trackTimeMillis") / 1000,
                )
            }
            .sortedWith(compareBy({ it.disc }, { it.track }))
        return CatalogAlbumDetails(
            album = album,
            year = collection?.optString("releaseDate")?.take(4)?.takeIf { it.length == 4 } ?: album.year,
            genre = collection?.optString("primaryGenreName")?.takeIf { it.isNotBlank() },
            tracks = tracks,
        )
    }

    private fun deezerSearch(artist: String, album: String): List<CatalogAlbum> {
        val q = if (artist.isNotBlank() && album.isNotBlank()) "artist:\"$artist\" album:\"$album\"" else "$artist $album".trim()
        var data = JSONObject(get("https://api.deezer.com/search/album?q=${enc(q)}&limit=25")).optJSONArray("data").objects()
        if (data.isEmpty()) {
            data = JSONObject(get("https://api.deezer.com/search/album?q=${enc("$artist $album".trim())}&limit=25"))
                .optJSONArray("data").objects()
        }
        return data.map { r ->
            CatalogAlbum(
                source = "Deezer",
                id = r.optLong("id").toString(),
                title = r.optString("title"),
                artist = r.optJSONObject("artist")?.optString("name").orEmpty(),
                year = null,
                trackCount = r.optInt("nb_tracks"),
                thumbUrl = r.optString("cover_medium").takeIf { it.isNotBlank() },
                coverUrl = r.optString("cover_xl").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun deezerAlbum(album: CatalogAlbum): CatalogAlbumDetails {
        val details = JSONObject(get("https://api.deezer.com/album/${album.id}"))
        details.optJSONObject("error")?.let { throw IOException(it.optString("message", "Erro do Deezer")) }
        val tracks = JSONObject(get("https://api.deezer.com/album/${album.id}/tracks?limit=300"))
            .optJSONArray("data").objects()
            .map {
                CatalogTrack(
                    title = it.optString("title"),
                    artist = it.optJSONObject("artist")?.optString("name").orEmpty(),
                    track = it.optInt("track_position"),
                    disc = it.optInt("disk_number", 1).coerceAtLeast(1),
                    durationSec = it.optInt("duration"),
                )
            }
            .sortedWith(compareBy({ it.disc }, { it.track }))
        val year = details.optString("release_date").take(4).takeIf { it.length == 4 }
        return CatalogAlbumDetails(
            album = album.copy(year = year),
            year = year,
            genre = details.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)
                ?.optString("name")?.takeIf { it.isNotBlank() },
            tracks = tracks,
        )
    }

    /**
     * Plain-text lyrics from LRCLIB (timestamps removed when only synced lyrics
     * exist), or null. Plain text shows correctly in every player.
     */
    fun lyrics(artist: String, title: String, album: String, durationSec: Int): String? {
        if (title.isBlank()) return null
        val params = buildString {
            append("artist_name=").append(enc(artist))
            append("&track_name=").append(enc(title))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        get("https://lrclib.net/api/get?$params").takeIf { it.startsWith("{") }?.let { body ->
            parseLyrics(JSONObject(body))?.let { return it }
        }
        val search = get("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (!search.startsWith("[")) return null
        return JSONArray(search).objects()
            .filter { durationSec <= 0 || it.optDouble("duration", 0.0).let { d -> d <= 0 || abs(d - durationSec) <= 5 } }
            .firstNotNullOfOrNull { parseLyrics(it) }
    }

    private fun parseLyrics(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return null
        o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it.trim() }
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return synced.lines()
            .map { it.replace(Regex("^(\\[\\d+:\\d+(?:[.:]\\d+)?])+\\s?"), "") }
            .joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    fun download(url: String): ByteArray {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
    }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 429 || code >= 500) throw IOException("${URL(url).host} respondeu HTTP $code")
            if (code != 200) return "{}"
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

/** Pairs YouTube playlist entries with catalogue tracks by title and duration. */
object TitleMatch {
    data class Entry(val key: String, val title: String, val durationSec: Int, val position: Int)

    /** @return entry key -> index in [tracks]. */
    fun match(entries: List<Entry>, tracks: List<CatalogTrack>): Map<String, Int> {
        data class P(val e: Int, val t: Int, val s: Double)
        val pairs = buildList {
            for ((ei, e) in entries.withIndex()) for ((ti, t) in tracks.withIndex()) {
                var s = similarity(e.title, t.title) * 3
                if (e.durationSec > 0 && t.durationSec > 0) {
                    val d = abs(e.durationSec - t.durationSec)
                    s += when { d <= 2 -> 1.0; d <= 6 -> 0.6; d <= 15 -> 0.2; else -> -0.5 }
                }
                if (ti == e.position) s += 0.3
                if (s >= 1.0) add(P(ei, ti, s))
            }
        }.sortedByDescending { it.s }
        val out = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        for (p in pairs) {
            val key = entries[p.e].key
            if (key in out || p.t in used) continue
            out[key] = p.t
            used += p.t
        }
        // Same number of leftovers on both sides: pair them in playlist order.
        val freeE = entries.filter { it.key !in out }.sortedBy { it.position }
        val freeT = tracks.indices.filter { it !in used }
        if (freeE.isNotEmpty() && freeE.size == freeT.size) freeE.zip(freeT).forEach { (e, t) -> out[e.key] = t }
        return out
    }

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
            .replace(Regex("\\s-\\s.*(remaster|ao vivo|live|version|versao|mix|edit|official|audio|video).*$"), " ")
            .replace(Regex("\\b(feat|ft)\\.?\\s.*$"), " ")
            .replace("&", " e ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }
}

package com.youtubedownloaderandroid

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Release year / genre of an album, taken from a public music catalogue. */
data class AlbumMeta(val year: String?, val genre: String?, val source: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        year?.let { put("year", it) }
        genre?.let { put("genre", it) }
        put("source", source)
    }

    companion object {
        val NONE = AlbumMeta(null, null, "none")

        fun fromJson(o: JSONObject) = AlbumMeta(
            year = o.optString("year").takeIf { it.isNotEmpty() && it != "null" },
            genre = o.optString("genre").takeIf { it.isNotEmpty() && it != "null" },
            source = o.optString("source", "none")
        )
    }
}

/**
 * Looks up the real release year and genre of an album. YouTube only knows
 * when the album was uploaded, which is often decades after the release.
 *
 * Sources: iTunes Search API (Brazilian store, genres in Portuguese) and,
 * when it finds nothing, the Deezer API. Both are public and keyless.
 * A result is only accepted when album and artist names match, and the
 * earliest matching release wins (re-releases carry later dates).
 */
object MetadataLookup {
    private const val TAG = "MetadataLookup"
    private const val USER_AGENT = "YDownloader-Android/1.0"

    private val cache = ConcurrentHashMap<String, AlbumMeta>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * @return the metadata, or [AlbumMeta.NONE] when no catalogue has the album.
     * @throws IOException when both catalogues were unreachable (worth retrying).
     */
    suspend fun lookup(artist: String, album: String): AlbumMeta {
        val key = "${normalize(artist)}|${normalize(album)}"
        cache[key]?.let { return it }
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            cache[key]?.let { return@withLock it }
            val result = withContext(Dispatchers.IO) { fetch(artist, album) }
            cache[key] = result
            Log.i(TAG, "$artist / $album -> $result")
            result
        }
    }

    private fun fetch(artist: String, album: String): AlbumMeta {
        var networkError: IOException? = null

        try {
            itunes(artist, album)?.let { return it }
        } catch (e: IOException) {
            Log.w(TAG, "iTunes indisponível", e)
            networkError = e
        }
        try {
            deezer(artist, album)?.let { return it }
            // Deezer answered: a "not found" from it is a real answer.
            return AlbumMeta.NONE
        } catch (e: IOException) {
            Log.w(TAG, "Deezer indisponível", e)
            if (networkError != null) throw e
        }
        return AlbumMeta.NONE
    }

    private fun itunes(artist: String, album: String): AlbumMeta? {
        val term = URLEncoder.encode("$artist $album", "UTF-8")
        val json = httpGet("https://itunes.apple.com/search?term=$term&entity=album&country=BR&lang=pt_br&limit=25")
        val results = JSONObject(json).optJSONArray("results") ?: return null

        var best: AlbumMeta? = null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (!albumMatches(r.optString("collectionName"), album)) continue
            if (!artistMatches(r.optString("artistName"), artist)) continue
            val year = r.optString("releaseDate").take(4).takeIf { isYear(it) } ?: continue
            val genre = r.optString("primaryGenreName").takeIf { it.isNotBlank() }
            if (best == null || year < best.year!!) best = AlbumMeta(year, genre, "iTunes")
        }
        return best
    }

    private fun deezer(artist: String, album: String): AlbumMeta? {
        val q = URLEncoder.encode("artist:\"$artist\" album:\"$album\"", "UTF-8")
        val json = httpGet("https://api.deezer.com/search/album?q=$q&limit=10")
        val data = JSONObject(json).optJSONArray("data") ?: return null

        var best: AlbumMeta? = null
        var checked = 0
        for (i in 0 until data.length()) {
            val r = data.getJSONObject(i)
            if (!albumMatches(r.optString("title"), album)) continue
            if (!artistMatches(r.optJSONObject("artist")?.optString("name").orEmpty(), artist)) continue
            if (checked++ >= 3) break
            val details = JSONObject(httpGet("https://api.deezer.com/album/${r.getLong("id")}"))
            val year = details.optString("release_date").take(4).takeIf { isYear(it) } ?: continue
            val genre = details.optJSONObject("genres")?.optJSONArray("data")
                ?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
            if (best == null || year < best.year!!) best = AlbumMeta(year, genre, "Deezer")
        }
        return best
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            val code = conn.responseCode
            if (code == 429 || code >= 500) throw IOException("HTTP $code em ${URL(url).host}")
            if (code != 200) return "{}"
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun isYear(s: String) = s.length == 4 && s.all { it.isDigit() } && s >= "1900"

    /** Lowercase, no accents, no "(...)" / "[...]" suffixes, no EP/Single tags. */
    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase()
            .replace(Regex("[(\\[].*?[)\\]]"), " ")
            .replace(Regex("\\s-\\s(ep|single)\\b.*$"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun albumMatches(candidate: String, album: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(album)
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.startsWith("$b ") || b.startsWith("$a "))
    }

    private fun artistMatches(candidate: String, artist: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(artist)
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
    }
}

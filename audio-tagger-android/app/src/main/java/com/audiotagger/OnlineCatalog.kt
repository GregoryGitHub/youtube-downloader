package com.audiotagger

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Public, keyless music catalogues: the iTunes Search API and the Deezer API
 * for album data and covers, LRCLIB for lyrics. All calls block; run them on
 * Dispatchers.IO.
 */
object OnlineCatalog {
    private const val USER_AGENT = "AudioTagger-Android/1.0"

    // ---------- album search ----------

    fun searchAlbums(source: Source, artist: String, album: String): List<AlbumResult> = when (source) {
        Source.ITUNES -> itunesSearch(artist, album)
        Source.DEEZER -> deezerSearch(artist, album)
    }

    fun albumDetails(album: AlbumResult): AlbumDetails = when (album.source) {
        Source.ITUNES -> itunesAlbum(album)
        Source.DEEZER -> deezerAlbum(album)
    }

    /** The Brazilian store has Portuguese genres; fall back to the US store, which has more albums. */
    private fun itunesSearch(artist: String, album: String): List<AlbumResult> {
        val term = enc(listOf(artist, album).filter { it.isNotBlank() }.joinToString(" "))
        for (country in listOf("BR", "US")) {
            val json = JSONObject(get("https://itunes.apple.com/search?term=$term&entity=album&country=$country&limit=30"))
            val list = json.optJSONArray("results").objects().map { r ->
                val art100 = r.optString("artworkUrl100").takeIf { it.isNotBlank() }
                AlbumResult(
                    source = Source.ITUNES,
                    id = "${r.optLong("collectionId")}@$country",
                    title = r.optString("collectionName"),
                    artist = r.optString("artistName"),
                    year = r.optString("releaseDate").take(4).takeIf { it.length == 4 },
                    trackCount = r.optInt("trackCount"),
                    thumbUrl = art100,
                    coverUrl = art100?.replace("100x100bb", "1200x1200bb"),
                    kind = r.optString("collectionType").takeIf { it.isNotBlank() },
                )
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun itunesAlbum(album: AlbumResult): AlbumDetails {
        val (id, country) = album.id.split('@').let { it[0] to it.getOrElse(1) { "US" } }
        val results = JSONObject(get("https://itunes.apple.com/lookup?id=$id&entity=song&country=$country&limit=300"))
            .optJSONArray("results").objects()
        val collection = results.firstOrNull { it.optString("wrapperType") == "collection" }
        val tracks = results.filter { it.optString("wrapperType") == "track" && it.optString("kind") == "song" }
            .map {
                RemoteTrack(
                    title = it.optString("trackName"),
                    artist = it.optString("artistName"),
                    track = it.optInt("trackNumber"),
                    disc = it.optInt("discNumber", 1).coerceAtLeast(1),
                    durationSec = it.optInt("trackTimeMillis") / 1000,
                )
            }
            .sortedWith(compareBy({ it.disc }, { it.track }))
        return AlbumDetails(
            album = album,
            year = collection?.optString("releaseDate")?.take(4)?.takeIf { it.length == 4 } ?: album.year,
            genre = collection?.optString("primaryGenreName")?.takeIf { it.isNotBlank() },
            tracks = tracks,
        )
    }

    private fun deezerSearch(artist: String, album: String): List<AlbumResult> {
        val q = when {
            artist.isNotBlank() && album.isNotBlank() -> "artist:\"$artist\" album:\"$album\""
            else -> "$artist $album".trim()
        }
        var data = JSONObject(get("https://api.deezer.com/search/album?q=${enc(q)}&limit=30")).optJSONArray("data").objects()
        if (data.isEmpty() && artist.isNotBlank() && album.isNotBlank()) {
            // The strict query misses titles with punctuation; retry loosely.
            data = JSONObject(get("https://api.deezer.com/search/album?q=${enc("$artist $album")}&limit=30"))
                .optJSONArray("data").objects()
        }
        return data.map { r ->
            AlbumResult(
                source = Source.DEEZER,
                id = r.optLong("id").toString(),
                title = r.optString("title"),
                artist = r.optJSONObject("artist")?.optString("name").orEmpty(),
                year = null, // only in the album details
                trackCount = r.optInt("nb_tracks"),
                thumbUrl = r.optString("cover_medium").takeIf { it.isNotBlank() },
                coverUrl = r.optString("cover_xl").takeIf { it.isNotBlank() },
                kind = r.optString("record_type").takeIf { it.isNotBlank() },
            )
        }
    }

    private fun deezerAlbum(album: AlbumResult): AlbumDetails {
        val details = JSONObject(get("https://api.deezer.com/album/${album.id}"))
        details.optJSONObject("error")?.let { throw IOException(it.optString("message", "Erro do Deezer")) }
        val tracks = JSONObject(get("https://api.deezer.com/album/${album.id}/tracks?limit=300"))
            .optJSONArray("data").objects()
            .map {
                RemoteTrack(
                    title = it.optString("title"),
                    artist = it.optJSONObject("artist")?.optString("name").orEmpty(),
                    track = it.optInt("track_position"),
                    disc = it.optInt("disk_number", 1).coerceAtLeast(1),
                    durationSec = it.optInt("duration"),
                )
            }
            .sortedWith(compareBy({ it.disc }, { it.track }))
        return AlbumDetails(
            album = album,
            year = details.optString("release_date").take(4).takeIf { it.length == 4 },
            genre = details.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)
                ?.optString("name")?.takeIf { it.isNotBlank() },
            tracks = tracks,
        )
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

    // ---------- lyrics ----------

    data class Lyrics(val plain: String?, val synced: String?)

    /**
     * LRCLIB: exact lookup by artist/title/album/duration first, then a search.
     * @return null when nothing was found.
     */
    fun lyrics(artist: String, title: String, album: String, durationSec: Int): Lyrics? {
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
        val candidates = JSONArray(search).objects()
            .filter { durationSec <= 0 || it.optDouble("duration", 0.0).let { d -> d <= 0 || kotlin.math.abs(d - durationSec) <= 5 } }
        return candidates.firstNotNullOfOrNull { parseLyrics(it) }
    }

    private fun parseLyrics(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental")) return Lyrics("[Instrumental]", null)
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        return if (plain == null && synced == null) null else Lyrics(plain, synced)
    }

    // ---------- http ----------

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
    }

    /** Body of a GET; "{}" for 4xx answers (not found), exception for network/5xx errors. */
    private fun get(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 429 || code >= 500) throw IOException("O serviço ${URL(url).host} respondeu HTTP $code")
            if (code != 200) return "{}"
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

//! Full album metadata for YouTube Music album downloads: album search on the
//! iTunes Search API and the Deezer API, lyrics from LRCLIB (all public and
//! keyless), matching of playlist entries to album tracks, and tag writing.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::time::Duration;

use lofty::config::WriteOptions;
use lofty::picture::{MimeType, Picture, PictureType};
use lofty::prelude::*;
use lofty::tag::{ItemKey, Tag, TagType};
use serde_json::Value;

const USER_AGENT: &str = "YDownloader-Desktop/0.2";

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct CatalogAlbum {
    pub source: String, // "iTunes" | "Deezer"
    pub id: String,
    pub title: String,
    pub artist: String,
    pub year: Option<String>,
    pub track_count: u32,
    pub thumb_url: Option<String>,
    pub cover_url: Option<String>,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct CatalogTrack {
    pub title: String,
    pub artist: String,
    pub track: u32,
    pub disc: u32,
    pub duration_sec: u32,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct AlbumDetails {
    pub album: CatalogAlbum,
    pub year: Option<String>,
    pub genre: Option<String>,
    pub tracks: Vec<CatalogTrack>,
    pub disc_total: u32,
}

/// A playlist entry as the frontend knows it.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct PlaylistEntry {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub duration: u32,
}

/// Album details plus "entry id -> index in details.tracks".
#[derive(Clone, Debug, serde::Serialize)]
pub struct AlbumMatch {
    pub details: AlbumDetails,
    pub matches: HashMap<String, usize>,
}

/// Full metadata accepted by the user for one track.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, PartialEq)]
pub struct TrackMeta {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_artist: String,
    pub year: Option<String>,
    pub genre: Option<String>,
    pub track: u32,
    pub track_total: u32,
    pub disc: u32,
    pub disc_total: u32,
    pub duration_sec: u32,
    pub cover_url: Option<String>,
    #[serde(default = "yes")]
    pub lyrics: bool,
}

fn yes() -> bool {
    true
}

impl TrackMeta {
    /// "Album (1999)"
    pub fn album_folder(&self) -> String {
        match &self.year {
            Some(y) if !y.is_empty() => format!("{} ({})", self.album, y),
            _ => self.album.clone(),
        }
    }

    /// "03 - Title" or "1-03 - Title" on multi-disc albums.
    pub fn file_stem(&self) -> String {
        let n = format!("{:02}", self.track);
        if self.disc_total > 1 {
            format!("{}-{} - {}", self.disc, n, self.title)
        } else {
            format!("{} - {}", n, self.title)
        }
    }
}

// ---------------------------------------------------------------------------
// HTTP

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(20))
        .user_agent(USER_AGENT)
        .build()
}

/// JSON body; `Null` for "not found" answers, Err for network / 5xx errors.
fn get_json(url: &str) -> Result<Value, String> {
    match agent().get(url).set("Accept-Language", "pt-BR,pt;q=0.9").call() {
        Ok(resp) => {
            let body = resp.into_string().map_err(|e| e.to_string())?;
            serde_json::from_str(&body).map_err(|e| e.to_string())
        }
        Err(ureq::Error::Status(code, _)) if code < 500 && code != 429 => Ok(Value::Null),
        Err(e) => Err(format!("Falha ao acessar o catálogo: {}", e)),
    }
}

fn enc(s: &str) -> String {
    let mut out = String::new();
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(*b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn s(v: &Value, key: &str) -> String {
    v.get(key).and_then(|x| x.as_str()).unwrap_or_default().to_string()
}

fn year_of(date: &str) -> Option<String> {
    let y: String = date.chars().take(4).collect();
    (y.len() == 4 && y.chars().all(|c| c.is_ascii_digit())).then_some(y)
}

fn arr(v: &Value, key: &str) -> Vec<Value> {
    v.get(key).and_then(|x| x.as_array()).cloned().unwrap_or_default()
}

// ---------------------------------------------------------------------------
// Search

/// Candidates from iTunes and Deezer, best match for artist / album / track count first.
pub fn search(artist: &str, album: &str, track_count: u32) -> Result<Vec<CatalogAlbum>, String> {
    let mut all = Vec::new();
    let mut last_err = None;
    match itunes_search(artist, album) {
        Ok(v) => all.extend(v),
        Err(e) => last_err = Some(e),
    }
    match deezer_search(artist, album) {
        Ok(v) => all.extend(v),
        Err(e) => last_err = Some(e),
    }
    if all.is_empty() {
        if let Some(e) = last_err {
            return Err(e);
        }
    }
    let mut scored: Vec<(f64, CatalogAlbum)> = all
        .into_iter()
        .map(|c| (score(&c, artist, album, track_count), c))
        .filter(|(sc, _)| *sc > 0.5)
        .collect();
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    let mut seen = HashSet::new();
    Ok(scored
        .into_iter()
        .map(|(_, c)| c)
        .filter(|c| seen.insert(format!("{}|{}", c.source, c.id)))
        .take(12)
        .collect())
}

fn score(c: &CatalogAlbum, artist: &str, album: &str, track_count: u32) -> f64 {
    let mut sc = similarity(&c.title, album) * 2.0 + similarity(&c.artist, artist);
    if track_count > 0 && c.track_count > 0 {
        sc += match (c.track_count as i64 - track_count as i64).abs() {
            0 => 1.0,
            1 | 2 => 0.4,
            _ => 0.0,
        };
    }
    if c.source == "iTunes" {
        sc += 0.05;
    }
    sc
}

fn itunes_search(artist: &str, album: &str) -> Result<Vec<CatalogAlbum>, String> {
    let term = enc(format!("{} {}", artist, album).trim());
    for country in ["BR", "US"] {
        let v = get_json(&format!(
            "https://itunes.apple.com/search?term={}&entity=album&country={}&limit=25",
            term, country
        ))?;
        let list: Vec<CatalogAlbum> = arr(&v, "results")
            .iter()
            .map(|r| {
                let art = Some(s(r, "artworkUrl100")).filter(|x| !x.is_empty());
                CatalogAlbum {
                    source: "iTunes".into(),
                    id: format!("{}@{}", r.get("collectionId").and_then(|x| x.as_u64()).unwrap_or(0), country),
                    title: s(r, "collectionName"),
                    artist: s(r, "artistName"),
                    year: year_of(&s(r, "releaseDate")),
                    track_count: r.get("trackCount").and_then(|x| x.as_u64()).unwrap_or(0) as u32,
                    cover_url: art.as_ref().map(|a| a.replace("100x100bb", "1200x1200bb")),
                    thumb_url: art,
                }
            })
            .collect();
        if !list.is_empty() {
            return Ok(list);
        }
    }
    Ok(Vec::new())
}

fn deezer_search(artist: &str, album: &str) -> Result<Vec<CatalogAlbum>, String> {
    let strict = if !artist.is_empty() && !album.is_empty() {
        format!("artist:\"{}\" album:\"{}\"", artist, album)
    } else {
        format!("{} {}", artist, album).trim().to_string()
    };
    let mut data = arr(&get_json(&format!("https://api.deezer.com/search/album?q={}&limit=25", enc(&strict)))?, "data");
    if data.is_empty() {
        let loose = format!("{} {}", artist, album);
        data = arr(&get_json(&format!("https://api.deezer.com/search/album?q={}&limit=25", enc(loose.trim())))?, "data");
    }
    Ok(data
        .iter()
        .map(|r| CatalogAlbum {
            source: "Deezer".into(),
            id: r.get("id").and_then(|x| x.as_u64()).unwrap_or(0).to_string(),
            title: s(r, "title"),
            artist: r.pointer("/artist/name").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
            year: None,
            track_count: r.get("nb_tracks").and_then(|x| x.as_u64()).unwrap_or(0) as u32,
            thumb_url: Some(s(r, "cover_medium")).filter(|x| !x.is_empty()),
            cover_url: Some(s(r, "cover_xl")).filter(|x| !x.is_empty()),
        })
        .collect())
}

pub fn details(album: &CatalogAlbum) -> Result<AlbumDetails, String> {
    let (year, genre, mut tracks, album) = if album.source == "Deezer" {
        let d = get_json(&format!("https://api.deezer.com/album/{}", album.id))?;
        if d.get("error").is_some() || d.is_null() {
            return Err("O Deezer não encontrou este álbum.".into());
        }
        let t = get_json(&format!("https://api.deezer.com/album/{}/tracks?limit=300", album.id))?;
        let tracks: Vec<CatalogTrack> = arr(&t, "data")
            .iter()
            .map(|x| CatalogTrack {
                title: s(x, "title"),
                artist: x.pointer("/artist/name").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                track: x.get("track_position").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
                disc: (x.get("disk_number").and_then(|v| v.as_u64()).unwrap_or(1) as u32).max(1),
                duration_sec: x.get("duration").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
            })
            .collect();
        let year = year_of(&s(&d, "release_date"));
        let genre = d.pointer("/genres/data/0/name").and_then(|v| v.as_str()).map(String::from).filter(|g| !g.is_empty());
        let mut a = album.clone();
        a.year = year.clone();
        (year, genre, tracks, a)
    } else {
        let mut parts = album.id.split('@');
        let id = parts.next().unwrap_or_default();
        let country = parts.next().unwrap_or("US");
        let v = get_json(&format!(
            "https://itunes.apple.com/lookup?id={}&entity=song&country={}&limit=300",
            id, country
        ))?;
        let results = arr(&v, "results");
        let collection = results.iter().find(|r| s(r, "wrapperType") == "collection");
        let tracks: Vec<CatalogTrack> = results
            .iter()
            .filter(|r| s(r, "wrapperType") == "track" && s(r, "kind") == "song")
            .map(|x| CatalogTrack {
                title: s(x, "trackName"),
                artist: s(x, "artistName"),
                track: x.get("trackNumber").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
                disc: (x.get("discNumber").and_then(|v| v.as_u64()).unwrap_or(1) as u32).max(1),
                duration_sec: (x.get("trackTimeMillis").and_then(|v| v.as_u64()).unwrap_or(0) / 1000) as u32,
            })
            .collect();
        let year = collection.and_then(|c| year_of(&s(c, "releaseDate"))).or_else(|| album.year.clone());
        let genre = collection.map(|c| s(c, "primaryGenreName")).filter(|g| !g.is_empty());
        (year, genre, tracks, album.clone())
    };
    tracks.sort_by_key(|t| (t.disc, t.track));
    let disc_total = tracks.iter().map(|t| t.disc).max().unwrap_or(1).max(1);
    Ok(AlbumDetails { album, year, genre, tracks, disc_total })
}

// ---------------------------------------------------------------------------
// Matching

pub fn normalize(text: &str) -> String {
    // Strip accents for the common Latin letters.
    let mut base = String::new();
    for c in text.to_lowercase().chars() {
        let m = match c {
            'á' | 'à' | 'â' | 'ã' | 'ä' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'í' | 'ì' | 'î' | 'ï' => 'i',
            'ó' | 'ò' | 'ô' | 'õ' | 'ö' => 'o',
            'ú' | 'ù' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            'ñ' => 'n',
            other => other,
        };
        base.push(m);
    }
    // Drop "(...)" / "[...]".
    let mut out = String::new();
    let mut depth = 0;
    for c in base.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    // "- Remastered 2011", "- Ao Vivo", " feat. X"...
    if let Some(i) = out.find(" - ") {
        let tail = &out[i..];
        if ["remaster", "ao vivo", "live", "version", "versao", "mix", "edit", "official", "audio", "video"]
            .iter()
            .any(|w| tail.contains(w))
        {
            out.truncate(i);
        }
    }
    for marker in [" feat. ", " feat ", " ft. ", " ft "] {
        if let Some(i) = out.find(marker) {
            out.truncate(i);
        }
    }
    let out = out.replace('&', " e ");
    out.split(|c: char| !c.is_ascii_alphanumeric())
        .filter(|w| !w.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn similarity(a: &str, b: &str) -> f64 {
    let x = normalize(a);
    let y = normalize(b);
    if x.is_empty() || y.is_empty() {
        return 0.0;
    }
    if x == y {
        return 1.0;
    }
    if x.starts_with(&format!("{} ", y)) || y.starts_with(&format!("{} ", x)) {
        return 0.85;
    }
    let tx: HashSet<&str> = x.split(' ').collect();
    let ty: HashSet<&str> = y.split(' ').collect();
    tx.intersection(&ty).count() as f64 / tx.union(&ty).count() as f64 * 0.9
}

/// Pairs playlist entries with album tracks by title and duration; leftovers
/// are paired in order when both sides have the same number left.
pub fn match_tracks(entries: &[PlaylistEntry], tracks: &[CatalogTrack]) -> HashMap<String, usize> {
    let mut pairs = Vec::new();
    for (ei, e) in entries.iter().enumerate() {
        for (ti, t) in tracks.iter().enumerate() {
            let mut sc = similarity(&e.title, &t.title) * 3.0;
            if e.duration > 0 && t.duration_sec > 0 {
                let d = (e.duration as i64 - t.duration_sec as i64).abs();
                sc += if d <= 2 { 1.0 } else if d <= 6 { 0.6 } else if d <= 15 { 0.2 } else { -0.5 };
            }
            if ti == ei {
                sc += 0.3;
            }
            if sc >= 1.0 {
                pairs.push((sc, ei, ti));
            }
        }
    }
    pairs.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    let mut out = HashMap::new();
    let mut used = HashSet::new();
    for (_, ei, ti) in pairs {
        let key = &entries[ei].id;
        if out.contains_key(key) || used.contains(&ti) {
            continue;
        }
        out.insert(key.clone(), ti);
        used.insert(ti);
    }
    let free_e: Vec<&PlaylistEntry> = entries.iter().filter(|e| !out.contains_key(&e.id)).collect();
    let free_t: Vec<usize> = (0..tracks.len()).filter(|t| !used.contains(t)).collect();
    if !free_e.is_empty() && free_e.len() == free_t.len() {
        for (e, t) in free_e.into_iter().zip(free_t) {
            out.insert(e.id.clone(), t);
        }
    }
    out
}

pub fn load_match(album: &CatalogAlbum, entries: &[PlaylistEntry]) -> Result<AlbumMatch, String> {
    let details = details(album)?;
    let matches = match_tracks(entries, &details.tracks);
    Ok(AlbumMatch { details, matches })
}

// ---------------------------------------------------------------------------
// Lyrics, cover, tags

/// Plain-text lyrics from LRCLIB (timestamps stripped when only synced lyrics exist).
pub fn lyrics(artist: &str, title: &str, album: &str, duration: u32) -> Option<String> {
    if title.is_empty() {
        return None;
    }
    let mut q = format!("artist_name={}&track_name={}", enc(artist), enc(title));
    if !album.is_empty() {
        q.push_str(&format!("&album_name={}", enc(album)));
    }
    if duration > 0 {
        q.push_str(&format!("&duration={}", duration));
    }
    if let Ok(v) = get_json(&format!("https://lrclib.net/api/get?{}", q)) {
        if let Some(l) = parse_lyrics(&v) {
            return Some(l);
        }
    }
    let v = get_json(&format!(
        "https://lrclib.net/api/search?track_name={}&artist_name={}",
        enc(title),
        enc(artist)
    ))
    .ok()?;
    v.as_array()?
        .iter()
        .filter(|x| {
            let d = x.get("duration").and_then(|d| d.as_f64()).unwrap_or(0.0);
            duration == 0 || d <= 0.0 || (d - duration as f64).abs() <= 5.0
        })
        .find_map(parse_lyrics)
}

fn parse_lyrics(v: &Value) -> Option<String> {
    if v.is_null() || v.get("instrumental").and_then(|x| x.as_bool()).unwrap_or(false) {
        return None;
    }
    let plain = s(v, "plainLyrics");
    if !plain.trim().is_empty() {
        return Some(plain.trim().to_string());
    }
    let synced = s(v, "syncedLyrics");
    let text = strip_timestamps(&synced);
    (!text.is_empty()).then_some(text)
}

pub fn strip_timestamps(lrc: &str) -> String {
    lrc.lines()
        .map(|line| {
            let mut rest = line;
            while rest.starts_with('[') {
                match rest.find(']') {
                    Some(i) if rest[1..i].contains(':') => rest = &rest[i + 1..],
                    _ => break,
                }
            }
            rest.trim_start()
        })
        .collect::<Vec<_>>()
        .join("\n")
        .trim()
        .to_string()
}

/// Album cover, downloaded once per album into `cache_dir`.
pub fn cover_bytes(url: &str, cache_dir: &Path) -> Option<Vec<u8>> {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    url.hash(&mut h);
    let file: PathBuf = cache_dir.join(format!("{:016x}.img", h.finish()));
    if let Ok(b) = std::fs::read(&file) {
        if !b.is_empty() {
            return Some(b);
        }
    }
    let resp = agent().get(url).call().ok()?;
    let mut bytes = Vec::new();
    std::io::Read::read_to_end(&mut resp.into_reader(), &mut bytes).ok()?;
    if bytes.is_empty() {
        return None;
    }
    std::fs::create_dir_all(cache_dir).ok();
    std::fs::write(&file, &bytes).ok();
    Some(bytes)
}

/// Replaces the MP3's tags with the catalogue data. ID3v2.4, like the rest of
/// the file written by yt-dlp/ffmpeg (lofty drops the year when converting to v2.3).
pub fn write_tags(path: &Path, meta: &TrackMeta, cover: Option<Vec<u8>>, lyrics: Option<String>) -> Result<(), String> {
    let mut file = lofty::read_from_path(path).map_err(|e| format!("Falha ao ler as tags: {}", e))?;
    if file.tag(TagType::Id3v2).is_none() {
        file.insert_tag(Tag::new(TagType::Id3v2));
    }
    let tag = file.tag_mut(TagType::Id3v2).ok_or("Sem tag ID3v2")?;

    tag.set_title(meta.title.clone());
    tag.set_artist(meta.artist.clone());
    tag.set_album(meta.album.clone());
    tag.insert_text(ItemKey::AlbumArtist, meta.album_artist.clone());
    if let Some(y) = &meta.year {
        tag.insert_text(ItemKey::RecordingDate, y.clone());
    }
    if let Some(g) = &meta.genre {
        tag.set_genre(g.clone());
    }
    tag.set_track(meta.track);
    tag.set_track_total(meta.track_total);
    tag.set_disk(meta.disc);
    tag.set_disk_total(meta.disc_total);
    if let Some(l) = lyrics {
        tag.insert_text(ItemKey::Lyrics, l);
    }
    if let Some(bytes) = cover {
        let mime = if bytes.starts_with(&[0x89, b'P', b'N', b'G']) { MimeType::Png } else { MimeType::Jpeg };
        tag.remove_picture_type(PictureType::CoverFront);
        tag.remove_picture_type(PictureType::Other);
        // Explicit empty description: lofty's ID3v2.3 writer breaks APIC frames
        // without one, so stay safe if the version ever changes.
        tag.push_picture(Picture::new_unchecked(PictureType::CoverFront, Some(mime), Some(String::new()), bytes));
    }

    tag.save_to_path(path, WriteOptions::default())
        .map_err(|e| format!("Falha ao gravar as tags: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_normalize_and_similarity() {
        assert_eq!(normalize("Canção do Mar (Remastered)"), "cancao do mar");
        assert_eq!(normalize("Song - Remastered 2011"), "song");
        assert_eq!(normalize("Song feat. Someone"), "song");
        assert_eq!(similarity("Noite Fria", "noite fria"), 1.0);
    }

    #[test]
    fn test_match_tracks() {
        let tracks = vec![
            CatalogTrack { title: "Intro".into(), artist: "A".into(), track: 1, disc: 1, duration_sec: 60 },
            CatalogTrack { title: "Noite Fria".into(), artist: "A".into(), track: 2, disc: 1, duration_sec: 180 },
            CatalogTrack { title: "Canção do Mar".into(), artist: "A".into(), track: 3, disc: 1, duration_sec: 200 },
        ];
        let entries = vec![
            PlaylistEntry { id: "x".into(), title: "Cancao do Mar (Official Audio)".into(), duration: 201 },
            PlaylistEntry { id: "y".into(), title: "Intro".into(), duration: 60 },
            PlaylistEntry { id: "z".into(), title: "Noite Fria".into(), duration: 179 },
        ];
        let m = match_tracks(&entries, &tracks);
        assert_eq!(m["x"], 2);
        assert_eq!(m["y"], 0);
        assert_eq!(m["z"], 1);
    }

    #[test]
    fn test_strip_timestamps() {
        assert_eq!(strip_timestamps("[00:01.00]Linha 1\n[00:05.20][00:09.00] Linha 2"), "Linha 1\nLinha 2");
    }

    #[test]
    fn test_file_stem() {
        let mut m = TrackMeta {
            title: "Song".into(), artist: "A".into(), album: "Al".into(), album_artist: "A".into(),
            year: Some("1999".into()), genre: None, track: 3, track_total: 10, disc: 1, disc_total: 1,
            duration_sec: 0, cover_url: None, lyrics: true,
        };
        assert_eq!(m.file_stem(), "03 - Song");
        assert_eq!(m.album_folder(), "Al (1999)");
        m.disc_total = 2;
        m.disc = 2;
        assert_eq!(m.file_stem(), "2-03 - Song");
    }

    #[test]
    fn test_write_tags() {
        let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("test-data/sample.mp3");
        let dst = std::env::temp_dir().join("ydl_tag_test.mp3");
        std::fs::copy(&src, &dst).unwrap();
        let meta = TrackMeta {
            title: "Canção".into(), artist: "Artista feat. B".into(), album: "Álbum".into(),
            album_artist: "Artista".into(), year: Some("1998".into()), genre: Some("MPB".into()),
            track: 3, track_total: 12, disc: 1, disc_total: 2, duration_sec: 3, cover_url: None, lyrics: true,
        };
        let cover = vec![0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3];
        write_tags(&dst, &meta, Some(cover.clone()), Some("linha 1
linha 2".into())).unwrap();
        // Writing twice must not duplicate anything.
        write_tags(&dst, &meta, Some(cover.clone()), Some("linha 1
linha 2".into())).unwrap();

        let file = lofty::read_from_path(&dst).unwrap();
        let tag = file.tag(TagType::Id3v2).unwrap();
        assert_eq!(tag.title().as_deref(), Some("Canção"));
        assert_eq!(tag.artist().as_deref(), Some("Artista feat. B"));
        assert_eq!(tag.album().as_deref(), Some("Álbum"));
        assert_eq!(tag.get_string(&ItemKey::AlbumArtist), Some("Artista"));
        assert_eq!(tag.genre().as_deref(), Some("MPB"));
        assert_eq!(tag.track(), Some(3));
        assert_eq!(tag.track_total(), Some(12));
        assert_eq!(tag.disk(), Some(1));
        assert_eq!(tag.disk_total(), Some(2));
        assert_eq!(tag.get_string(&ItemKey::Lyrics), Some("linha 1
linha 2"));
        assert_eq!(tag.pictures().len(), 1);
        assert_eq!(tag.pictures()[0].data(), &cover[..]);
        assert_eq!(tag.get_string(&ItemKey::RecordingDate), Some("1998"));
        let head = std::fs::read(&dst).unwrap();
        assert_eq!(&head[..4], b"ID3");
        std::fs::remove_file(&dst).ok();
    }

    /// Hits the real catalogues: `cargo test -- --ignored live_`.
    #[test]
    #[ignore]
    fn live_search_and_lyrics() {
        let c = search("Legião Urbana", "Dois", 12).unwrap();
        assert!(!c.is_empty());
        println!("{:?}", c.iter().take(3).map(|a| (&a.source, &a.title, &a.artist, a.track_count)).collect::<Vec<_>>());
        let d = details(&c[0]).unwrap();
        println!("{:?} {:?} {} faixas", d.year, d.genre, d.tracks.len());
        assert!(d.tracks.len() >= 10);
        let l = lyrics(&d.album.artist, &d.tracks[0].title, &d.album.title, d.tracks[0].duration_sec);
        println!("letra: {:?}", l.as_deref().map(|x| &x[..x.len().min(60)]));
        let deezer = c.iter().find(|a| a.source == "Deezer").expect("deezer result");
        let dd = details(deezer).unwrap();
        println!("deezer {:?} {:?} {}", dd.year, dd.genre, dd.tracks.len());
    }
}

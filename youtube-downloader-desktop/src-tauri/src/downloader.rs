use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::{Mutex, Notify};
use tauri::{AppHandle, Emitter};

use crate::settings::{AppMode, MediaType, PersistedSettings, DEFAULT_DEEZER_QUALITY};

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DownloadStatus {
    Queued,
    FetchingInfo,
    Downloading,
    Converting,
    Completed,
    Failed,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct DownloadItem {
    pub id: String,
    pub url: String,
    pub title: String,
    pub playlist_title: Option<String>,
    #[serde(default)]
    pub track_number: Option<u32>,
    pub progress: f32,
    pub eta: String,
    pub status: DownloadStatus,
    pub status_detail: Option<String>,
    pub error_message: Option<String>,
    #[serde(default)]
    pub retry_count: u32,
    #[serde(default)]
    pub is_album: Option<bool>,
    /// Fixed at enqueue time so toggling the selector doesn't affect queued
    /// items. `None` means audio.
    #[serde(default)]
    pub media_type: Option<MediaType>,
}

pub struct DownloadState {
    pub queue: Vec<DownloadItem>,
    pub max_concurrent: usize,
    pub destination_folder: PathBuf,
    pub video_folder: PathBuf,
    pub media_type: MediaType,
    pub yt_dlp_path: String,
    pub ffmpeg_path: String,
    pub auto_retry: bool,
    pub organize_by_artist: bool,
    // Deezer mode
    pub config_dir: PathBuf,
    pub mode: AppMode,
    pub deezer_arl: String,
    pub deezer_quality: String,
    pub python_path: String,
    pub deezload_path: String,
    // Store abort handles to cancel downloads
    pub active_downloads: HashMap<String, tauri::async_runtime::JoinHandle<()>>,
}

impl DownloadState {
    /// Write the restart-persistent subset of the state to disk.
    pub fn persist(&self) -> Result<(), String> {
        PersistedSettings {
            mode: self.mode,
            deezer_arl: self.deezer_arl.clone(),
            deezer_quality: self.deezer_quality.clone(),
            media_type: self.media_type,
            audio_folder: Some(self.destination_folder.clone()),
            video_folder: Some(self.video_folder.clone()),
        }
        .save(&self.config_dir)
    }
}

pub struct DownloadManager {
    pub state: Arc<Mutex<DownloadState>>,
    pub notify: Arc<Notify>,
}

pub struct DeezerRuntime {
    pub config_dir: PathBuf,
    pub settings: PersistedSettings,
    pub python_path: String,
    pub deezload_path: String,
}

/// Running tally while parsing deezload's stdout for a single queued URL.
struct DeezerParse {
    tracks_total: f32,
    tracks_done: f32,
    current_fraction: f32,
    any_success: bool,
}

impl Default for DeezerParse {
    fn default() -> Self {
        Self {
            tracks_total: 1.0,
            tracks_done: 0.0,
            current_fraction: 0.0,
            any_success: false,
        }
    }
}

impl DownloadManager {
    pub fn new(
        yt_dlp: String,
        ffmpeg: String,
        default_dest: PathBuf,
        default_video_dest: PathBuf,
        deezer: DeezerRuntime,
    ) -> Self {
        // Prefer the persisted folders, as long as they still exist on disk.
        let pick = |saved: &Option<PathBuf>, fallback: PathBuf| {
            saved.clone().filter(|p| p.is_dir()).unwrap_or(fallback)
        };
        let destination_folder = pick(&deezer.settings.audio_folder, default_dest);
        let video_folder = pick(&deezer.settings.video_folder, default_video_dest);

        Self {
            state: Arc::new(Mutex::new(DownloadState {
                queue: Vec::new(),
                max_concurrent: 10,
                destination_folder,
                video_folder,
                media_type: deezer.settings.media_type,
                yt_dlp_path: yt_dlp,
                ffmpeg_path: ffmpeg,
                auto_retry: false,
                organize_by_artist: true,
                config_dir: deezer.config_dir,
                mode: deezer.settings.mode,
                deezer_arl: deezer.settings.deezer_arl,
                deezer_quality: deezer.settings.deezer_quality,
                python_path: deezer.python_path,
                deezload_path: deezer.deezload_path,
                active_downloads: HashMap::new(),
            })),
            notify: Arc::new(Notify::new()),
        }
    }

    pub fn start_queue_processor(self: &Arc<Self>, app_handle: AppHandle) {
        let manager = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            loop {
                // Wait for a notification that queue has changed
                manager.notify.notified().await;

                let mut state = manager.state.lock().await;
                let running_count = state.queue.iter().filter(|item| {
                    item.status == DownloadStatus::Downloading
                        || item.status == DownloadStatus::FetchingInfo
                        || item.status == DownloadStatus::Converting
                }).count();

                if running_count < state.max_concurrent {
                    let to_start = state.max_concurrent - running_count;
                    let mut started = 0;

                    let mut items_to_start = Vec::new();
                    for item in state.queue.iter() {
                        if item.status == DownloadStatus::Queued {
                            items_to_start.push(item.clone());
                            started += 1;
                            if started >= to_start {
                                break;
                            }
                        }
                    }

                    for item in items_to_start {
                        let manager_clone = Arc::clone(&manager);
                        let app_handle_clone = app_handle.clone();
                        let item_id = item.id.clone();
                        let item_id_thread = item_id.clone();

                        let handle = tauri::async_runtime::spawn(async move {
                            if let Err(e) = manager_clone.run_download(app_handle_clone.clone(), item).await {
                                let mut state = manager_clone.state.lock().await;
                                let auto_retry = state.auto_retry;
                                if let Some(q_item) = state.queue.iter_mut().find(|i| i.id == item_id_thread) {
                                    if auto_retry && q_item.retry_count < 3 {
                                        q_item.retry_count += 1;
                                        q_item.status = DownloadStatus::Queued;
                                        q_item.status_detail = Some(format!("Tentando novamente ({}/3)...", q_item.retry_count));
                                        q_item.progress = 0.0;
                                        q_item.eta = "N/A".to_string();
                                    } else {
                                        q_item.status = DownloadStatus::Failed;
                                        q_item.error_message = Some(e);
                                        q_item.status_detail = Some("Erro no download".to_string());
                                    }
                                }
                                manager_clone.emit_queue_update(&state, &app_handle_clone);
                            }
                            // Cleanup active download handle
                            {
                                let mut state = manager_clone.state.lock().await;
                                state.active_downloads.remove(&item_id_thread);
                            }
                            // Trigger processor for next items
                            manager_clone.notify.notify_one();
                        });

                        state.active_downloads.insert(item_id, handle);
                    }
                }
            }
        });
    }

    async fn run_download(&self, app_handle: AppHandle, item: DownloadItem) -> Result<(), String> {
        let mode = { self.state.lock().await.mode };
        if mode == AppMode::Deezer {
            return self.run_deezer_download(app_handle, item).await;
        }

        let is_video = item.media_type == Some(MediaType::Video);

        let (yt_dlp, ffmpeg_dir, dest_folder, organize_by_artist) = {
            let state = self.state.lock().await;

            // Resolve ffmpeg parent dir for --ffmpeg-location
            let ffmpeg_dir = if !state.ffmpeg_path.is_empty() && state.ffmpeg_path != "ffmpeg" {
                Path::new(&state.ffmpeg_path).parent().map(|p| p.to_string_lossy().to_string())
            } else {
                None
            };

            (
                state.yt_dlp_path.clone(),
                ffmpeg_dir,
                if is_video {
                    state.video_folder.clone()
                } else {
                    state.destination_folder.clone()
                },
                state.organize_by_artist,
            )
        };

        if yt_dlp.is_empty() {
            return Err("yt-dlp não está instalado ou não foi encontrado.".to_string());
        }

        // 1. Fetch info and update state to DOWNLOADING
        {
            let mut state = self.state.lock().await;
            if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                i.status = DownloadStatus::Downloading;
                i.status_detail = Some("Iniciando download...".to_string());
            }
            self.emit_queue_update(&state, &app_handle);
        }

        // Build yt-dlp arguments
        let mut args = if is_video {
            video_args(&item, &dest_folder)
        } else {
            audio_args(&item, &dest_folder, organize_by_artist)
        };

        if let Some(ref f_dir) = ffmpeg_dir {
            args.push("--ffmpeg-location".to_string());
            args.push(f_dir.clone());
        }

        args.push(item.url.clone());

        // Spawn process
        let mut cmd = Command::new(&yt_dlp);
        cmd.args(&args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        // On Windows, hide command window
        #[cfg(windows)]
        {
            const CREATE_NO_WINDOW: u32 = 0x08000000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = cmd.spawn().map_err(|e| format!("Falha ao executar yt-dlp: {}", e))?;
        let stdout = child.stdout.take().ok_or("Falha ao capturar stdout do processo.")?;
        let mut reader = BufReader::new(stdout).lines();
        let converting_text = if is_video {
            "Mesclando vídeo e áudio..."
        } else {
            "Convertendo para MP3..."
        };

        while let Ok(Some(line)) = reader.next_line().await {
            if let Some((progress, speed, eta)) = parse_progress(&line) {
                let mut state = self.state.lock().await;
                if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                    i.progress = progress;
                    i.eta = eta;

                    let clean_line = if is_postprocess_line(&line) {
                        i.status = DownloadStatus::Converting;
                        converting_text.to_string()
                    } else {
                        i.status = DownloadStatus::Downloading;
                        format!("Baixando a {}", speed)
                    };
                    i.status_detail = Some(clean_line);
                }
                self.emit_queue_update(&state, &app_handle);
            } else if is_postprocess_line(&line) {
                let mut state = self.state.lock().await;
                if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                    i.status = DownloadStatus::Converting;
                    i.status_detail = Some(converting_text.to_string());
                }
                self.emit_queue_update(&state, &app_handle);
            }
        }

        let status = child.wait().await.map_err(|e| format!("Erro ao aguardar processo: {}", e))?;

        let mut state = self.state.lock().await;
        if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
            if status.success() {
                i.status = DownloadStatus::Completed;
                i.progress = 1.0;
                i.eta = "00:00".to_string();
                i.status_detail = Some("Concluído".to_string());
            } else {
                return Err("Processo yt-dlp retornou um código de erro.".to_string());
            }
        }
        self.emit_queue_update(&state, &app_handle);

        Ok(())
    }

    /// Deezer-mode download: drives the bundled `deezload.py` (one process per
    /// queued URL) and translates its stdout into queue progress updates.
    async fn run_deezer_download(
        &self,
        app_handle: AppHandle,
        item: DownloadItem,
    ) -> Result<(), String> {
        let (python, script, arl, quality, dest, organize_by_artist) = {
            let state = self.state.lock().await;
            (
                state.python_path.clone(),
                state.deezload_path.clone(),
                state.deezer_arl.trim().to_string(),
                if state.deezer_quality.is_empty() {
                    DEFAULT_DEEZER_QUALITY.to_string()
                } else {
                    state.deezer_quality.clone()
                },
                state.destination_folder.clone(),
                state.organize_by_artist,
            )
        };

        if python.is_empty() || script.is_empty() {
            return Err(
                "Componente deezload não instalado. Vá em Ajustes › Componentes do Sistema."
                    .to_string(),
            );
        }
        if arl.is_empty() {
            return Err(
                "Cookie ARL do Deezer não configurado. Vá em Ajustes › Conta Deezer.".to_string(),
            );
        }

        // Private dependency folder created during installation.
        let libs_dir = Path::new(&script).parent().map(|p| p.join("libs"));

        // deezload downloads into its own layout ("Artist - Album/"), so we send
        // it to a per-item staging folder and move the files into the canonical
        // "<Artist>/<Album> (<year>)/" tree afterwards, matching YouTube mode.
        let staging = Path::new(&script)
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| dest.clone())
            .join("staging")
            .join(sanitize_filename(&item.id));
        fs::remove_dir_all(&staging).ok();
        fs::create_dir_all(&staging)
            .map_err(|e| format!("Falha ao criar pasta temporária: {}", e))?;

        {
            let mut state = self.state.lock().await;
            if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                i.status = DownloadStatus::Downloading;
                i.status_detail = Some("Autenticando no Deezer...".to_string());
                i.progress = 0.0;
            }
            self.emit_queue_update(&state, &app_handle);
        }

        // Resolve container metadata (artist / album / year) from Deezer's
        // public API so the folder names match YouTube mode exactly.
        let meta = {
            let url = item.url.clone();
            tauri::async_runtime::spawn_blocking(move || fetch_deezer_meta(&url))
                .await
                .unwrap_or(DeezerContainer::Unknown)
        };

        let args = vec![
            script.clone(),
            "--arl".to_string(),
            arl,
            "--url".to_string(),
            item.url.clone(),
            "--quality".to_string(),
            quality,
            "--output".to_string(),
            staging.to_string_lossy().to_string(),
            "--concurrency".to_string(),
            "1".to_string(),
        ];

        let mut cmd = Command::new(&python);
        cmd.args(&args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .env("PYTHONIOENCODING", "utf-8")
            .env("PYTHONUNBUFFERED", "1");

        if let Some(ref libs) = libs_dir {
            cmd.env("PYTHONPATH", libs);
        }

        #[cfg(windows)]
        {
            const CREATE_NO_WINDOW: u32 = 0x08000000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = cmd
            .spawn()
            .map_err(|e| format!("Falha ao executar o Python/deezload: {}", e))?;

        let stdout = child.stdout.take().ok_or("Falha ao capturar stdout do processo.")?;
        let stderr = child.stderr.take().ok_or("Falha ao capturar stderr do processo.")?;

        // Collect stderr in the background for a useful error message on failure.
        let stderr_task = tauri::async_runtime::spawn(async move {
            let mut buf = String::new();
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                buf.push_str(&line);
                buf.push('\n');
            }
            buf
        });

        // deezload writes its progress bar with '\r' and no newline, so a plain
        // line reader would only see it once the track finishes. Split the byte
        // stream on '\r' *and* '\n' so the bar animates live.
        let mut stdout_reader = stdout;
        let mut acc: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 4096];
        let mut parse = DeezerParse::default();

        loop {
            let read = match stdout_reader.read(&mut chunk).await {
                Ok(0) => break,
                Ok(n) => n,
                Err(_) => break,
            };
            acc.extend_from_slice(&chunk[..read]);

            while let Some(pos) = acc.iter().position(|&b| b == b'\n' || b == b'\r') {
                let segment: Vec<u8> = acc.drain(..=pos).collect();
                let line = String::from_utf8_lossy(&segment);
                let line = line.trim();
                if !line.is_empty() {
                    self.apply_deezer_line(&app_handle, &item.id, line, &mut parse)
                        .await;
                }
            }
        }
        if !acc.is_empty() {
            let line = String::from_utf8_lossy(&acc);
            let line = line.trim();
            if !line.is_empty() {
                self.apply_deezer_line(&app_handle, &item.id, line, &mut parse)
                    .await;
            }
        }
        let any_success = parse.any_success;

        let status = child
            .wait()
            .await
            .map_err(|e| format!("Erro ao aguardar processo: {}", e))?;
        let stderr_output = stderr_task.await.unwrap_or_default();

        if status.success() && any_success {
            {
                let mut state = self.state.lock().await;
                if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                    i.status = DownloadStatus::Converting;
                    i.status_detail = Some("Organizando arquivos...".to_string());
                }
                self.emit_queue_update(&state, &app_handle);
            }

            // Move the staged files into "<Artist>/<Album> (<year>)/".
            let reorg = {
                let staging = staging.clone();
                let dest = dest.clone();
                let meta = meta.clone();
                tauri::async_runtime::spawn_blocking(move || {
                    reorganize_deezer_output(&staging, &dest, organize_by_artist, &meta)
                })
                .await
            };

            // Drop the staging folder if everything was moved out.
            if fs::read_dir(&staging)
                .map(|mut d| d.next().is_none())
                .unwrap_or(false)
            {
                fs::remove_dir_all(&staging).ok();
            }

            let organized = matches!(reorg, Ok(Ok(())));
            let mut state = self.state.lock().await;
            if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                i.status = DownloadStatus::Completed;
                i.progress = 1.0;
                i.eta = "00:00".to_string();
                i.status_detail = Some(if organized {
                    "Concluído".to_string()
                } else {
                    "Concluído (verifique a pasta 'staging')".to_string()
                });
            }
            self.emit_queue_update(&state, &app_handle);
            Ok(())
        } else {
            fs::remove_dir_all(&staging).ok();
            let trimmed = stderr_output.trim();
            let msg = if !trimmed.is_empty() {
                trimmed
                    .lines()
                    .last()
                    .unwrap_or("Erro no deezload")
                    .to_string()
            } else if !any_success {
                "Nenhuma faixa foi baixada. Verifique o cookie ARL e o link do Deezer.".to_string()
            } else {
                "O deezload retornou um código de erro.".to_string()
            };
            Err(msg)
        }
    }

    /// Interpret one line of deezload stdout and push the resulting progress /
    /// status onto the matching queue item.
    async fn apply_deezer_line(
        &self,
        app_handle: &AppHandle,
        item_id: &str,
        line: &str,
        parse: &mut DeezerParse,
    ) {
        if let Some(rest) = line.strip_prefix('[') {
            // "[i/total] Track title" — start of a new track in a list.
            if let Some(close) = rest.find(']') {
                if let Some((idx, total)) = rest[..close].split_once('/') {
                    let (idx, total) = (idx.trim(), total.trim());
                    if let Ok(total_n) = total.parse::<f32>() {
                        parse.tracks_total = total_n.max(1.0);
                        parse.current_fraction = 0.0;
                        let title = rest[close + 1..].trim();
                        let mut state = self.state.lock().await;
                        if let Some(i) = state.queue.iter_mut().find(|x| x.id == item_id) {
                            i.status_detail = Some(format!("Faixa {}/{}: {}", idx, total, title));
                        }
                        self.emit_queue_update(&state, app_handle);
                    }
                }
            }
        } else if let Some(pct) = parse_deezer_pct(line) {
            parse.current_fraction = pct;
        } else if line.contains("\u{2713} Downloaded:") || line.contains("\u{2713} [") {
            // "✓ Downloaded: file" (single) or "✓ [i/total] title" (concurrent)
            parse.tracks_done += 1.0;
            parse.current_fraction = 0.0;
            parse.any_success = true;
        } else if let Some(found) = line.strip_prefix("Found ") {
            if let Some(n) = found
                .split_whitespace()
                .next()
                .and_then(|w| w.parse::<f32>().ok())
            {
                parse.tracks_total = n.max(1.0);
            }
        } else if let Some(album) = line.strip_prefix("Album: ") {
            let name = album.trim().to_string();
            if !name.is_empty() {
                let mut state = self.state.lock().await;
                if let Some(i) = state.queue.iter_mut().find(|x| x.id == item_id) {
                    i.title = name;
                }
                self.emit_queue_update(&state, app_handle);
            }
        }

        let progress = ((parse.tracks_done + parse.current_fraction.clamp(0.0, 1.0))
            / parse.tracks_total)
            .clamp(0.0, 0.999);
        let mut state = self.state.lock().await;
        if let Some(i) = state.queue.iter_mut().find(|x| x.id == item_id) {
            i.progress = progress;
            i.status = DownloadStatus::Downloading;
        }
        self.emit_queue_update(&state, app_handle);
    }

    pub fn emit_queue_update(&self, state: &DownloadState, app_handle: &AppHandle) {
        app_handle.emit("queue-updated", state.queue.clone()).ok();
    }
}

/// yt-dlp lines emitted by post-processors (MP3 extraction, stream merge, ...).
fn is_postprocess_line(line: &str) -> bool {
    line.contains("[ExtractAudio]") || line.contains("[ffmpeg]") || line.contains("[Merger]")
}

/// Output template shared by both media types: playlists get their own
/// subfolder (albums also carry the release year).
fn output_template(item: &DownloadItem, base_folder: &Path) -> String {
    let template = if let Some(ref playlist) = item.playlist_title {
        let sanitized_playlist = sanitize_filename(playlist);
        if item.is_album.unwrap_or(false) {
            base_folder
                .join(format!("{} (%(release_date,upload_date>%Y)s)", sanitized_playlist))
                .join("%(title)s.%(ext)s")
        } else {
            base_folder.join(sanitized_playlist).join("%(title)s.%(ext)s")
        }
    } else {
        base_folder.join("%(title)s.%(ext)s")
    };
    template.to_string_lossy().to_string()
}

/// yt-dlp arguments for an MP3 320k download with music metadata.
fn audio_args(item: &DownloadItem, dest_folder: &Path, organize_by_artist: bool) -> Vec<String> {
    // When "organize by artist" is on, everything is nested under an artist
    // folder. `primary_artist` is derived below via --parse-metadata so that
    // multi-artist tracks ("A, B, C") still land in a single "A" folder;
    // it falls back to the raw artist/uploader when parsing yields nothing.
    let base_folder = if organize_by_artist {
        dest_folder.join("%(primary_artist,album_artist,artist,uploader)s")
    } else {
        dest_folder.to_path_buf()
    };

    let mut args = vec![
        "--newline".to_string(),
        "--extract-audio".to_string(),
        "--audio-format".to_string(),
        "mp3".to_string(),
        "--audio-quality".to_string(),
        "320k".to_string(),
        "--add-metadata".to_string(),
        "--embed-thumbnail".to_string(),
    ];

    // Parse album/release year with fallback to upload date year
    args.push("--parse-metadata".to_string());
    args.push("%(release_date,upload_date>%Y)s:%(release_year)s".to_string());

    if let Some(track) = item.track_number {
        args.push("--parse-metadata".to_string());
        args.push(format!("{}:%(track_number)s", track));
    }

    if organize_by_artist {
        // Keep only the text before the first comma so "A, B, C" -> "A".
        // This drives the artist folder name.
        args.push("--parse-metadata".to_string());
        args.push(
            "%(album_artist,artist,uploader)s:^\\s*(?P<primary_artist>[^,]+?)\\s*(,|$)"
                .to_string(),
        );
        // Apply the same rule to the tags embedded in the file so players
        // like iTunes don't show the full "A, B, C" list as the artist.
        args.push("--replace-in-metadata".to_string());
        args.push("artist,album_artist".to_string());
        args.push("\\s*,.*".to_string());
        args.push(String::new());
    }

    args.extend(vec![
        "--ppa".to_string(),
        "EmbedThumbnail+ffmpeg_o:-c:v mjpeg -vf crop=ih:ih".to_string(),
        "--no-mtime".to_string(),
        "--no-warnings".to_string(),
        "--ignore-errors".to_string(),
        "-o".to_string(),
        output_template(item, &base_folder),
    ]);
    args
}

/// yt-dlp arguments for a highest-resolution MP4 download. Resolution and fps
/// win the format sort; mp4/m4a streams are only preferred on a tie, which
/// keeps the merged MP4 as compatible as possible without losing quality.
fn video_args(item: &DownloadItem, dest_folder: &Path) -> Vec<String> {
    vec![
        "--newline".to_string(),
        "-f".to_string(),
        "bv*+ba/b".to_string(),
        "-S".to_string(),
        "res,fps,ext:mp4:m4a".to_string(),
        "--merge-output-format".to_string(),
        "mp4".to_string(),
        "--add-metadata".to_string(),
        "--embed-thumbnail".to_string(),
        "--no-mtime".to_string(),
        "--no-warnings".to_string(),
        "--ignore-errors".to_string(),
        "-o".to_string(),
        output_template(item, dest_folder),
    ]
}

pub fn sanitize_filename(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| match c {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => c,
        })
        .collect();
    // Windows rejects names ending in a space or dot.
    cleaned.trim().trim_end_matches('.').trim_end().to_string()
}

// ===========================================================================
// Deezer output reorganisation
//
// deezload saves as "<output>/<Artist> - <Album>/<NN> - <Artist> - <Title>.ext"
// (albums) or flat files (tracks / playlists). YouTube mode saves as
// "<dest>/<Artist>/<Album> (<year>)/<title>.mp3". These helpers move deezload's
// staged output into that same tree.
// ===========================================================================

#[derive(Debug, Clone)]
pub enum DeezerContainer {
    Album {
        artist: String,
        title: String,
        year: Option<String>,
    },
    Track {
        artist: String,
    },
    Playlist {
        title: String,
    },
    /// Artist discography: map of sanitised album title -> release year.
    ArtistDiscography {
        years: HashMap<String, String>,
    },
    Unknown,
}

/// Keep only the text before the first comma ("A, B, C" -> "A"), matching the
/// `primary_artist` rule used for YouTube downloads, then sanitise it.
fn primary_artist(name: &str) -> String {
    let first = name.split(',').next().unwrap_or(name).trim();
    sanitize_filename(first)
}

/// Split a deezload folder / file stem of the form "Artist - Album".
fn split_artist_album(stem: &str) -> (String, String) {
    match stem.split_once(" - ") {
        Some((a, b)) => (a.trim().to_string(), b.trim().to_string()),
        None => (String::new(), stem.trim().to_string()),
    }
}

fn file_stem(name: &str) -> &str {
    match name.rsplit_once('.') {
        Some((stem, _ext)) => stem,
        None => name,
    }
}

/// Resolve a Deezer share/redirect link to its canonical URL.
fn resolve_deezer_url(url: &str) -> String {
    let lower = url.to_lowercase();
    let is_share = lower.contains("link.deezer.com")
        || lower.contains("dzr.page.link")
        || lower.contains("deezer.page.link")
        || lower.contains("/s/");
    if !is_share {
        return url.to_string();
    }
    match ureq::get(url).call() {
        Ok(resp) => resp.get_url().to_string(),
        Err(ureq::Error::Status(_, resp)) => resp.get_url().to_string(),
        Err(_) => url.to_string(),
    }
}

/// Extract `(kind, id)` from a Deezer URL, e.g. ("album", "12345").
fn parse_deezer_url(url: &str) -> Option<(String, String)> {
    let resolved = resolve_deezer_url(url);
    let after_scheme = resolved.splitn(2, "://").nth(1).unwrap_or(&resolved);
    let path = after_scheme.splitn(2, '/').nth(1).unwrap_or("");
    let segs: Vec<&str> = path
        .split(['/', '?', '#'])
        .filter(|s| !s.is_empty())
        .collect();
    for (i, seg) in segs.iter().enumerate() {
        let kind = seg.to_lowercase();
        if matches!(kind.as_str(), "track" | "album" | "playlist" | "artist") {
            if let Some(next) = segs.get(i + 1) {
                let id: String = next.chars().take_while(|c| c.is_ascii_digit()).collect();
                if !id.is_empty() {
                    return Some((kind, id));
                }
            }
        }
    }
    None
}

fn deezer_api(path: &str) -> Option<serde_json::Value> {
    let url = format!("https://api.deezer.com/{}", path);
    let body = ureq::get(&url).call().ok()?.into_string().ok()?;
    let value: serde_json::Value = serde_json::from_str(&body).ok()?;
    if value.get("error").is_some() {
        None
    } else {
        Some(value)
    }
}

fn year_from_release_date(v: &serde_json::Value) -> Option<String> {
    v.get("release_date")
        .and_then(|x| x.as_str())
        .filter(|s| s.len() >= 4 && s[..4].chars().all(|c| c.is_ascii_digit()))
        .map(|s| s[..4].to_string())
}

/// Look up artist / album / year for a Deezer URL via the public API. Any
/// failure degrades to `Unknown`, in which case names are parsed from the
/// staged folder names instead.
pub fn fetch_deezer_meta(url: &str) -> DeezerContainer {
    let Some((kind, id)) = parse_deezer_url(url) else {
        return DeezerContainer::Unknown;
    };

    match kind.as_str() {
        "album" => {
            if let Some(v) = deezer_api(&format!("album/{}", id)) {
                let title = v.get("title").and_then(|x| x.as_str()).unwrap_or_default();
                let artist = v
                    .pointer("/artist/name")
                    .and_then(|x| x.as_str())
                    .unwrap_or_default();
                if !title.is_empty() {
                    return DeezerContainer::Album {
                        artist: artist.to_string(),
                        title: title.to_string(),
                        year: year_from_release_date(&v),
                    };
                }
            }
            DeezerContainer::Unknown
        }
        "track" => {
            if let Some(v) = deezer_api(&format!("track/{}", id)) {
                if let Some(artist) = v.pointer("/artist/name").and_then(|x| x.as_str()) {
                    if !artist.is_empty() {
                        return DeezerContainer::Track {
                            artist: artist.to_string(),
                        };
                    }
                }
            }
            DeezerContainer::Unknown
        }
        "playlist" => {
            if let Some(v) = deezer_api(&format!("playlist/{}", id)) {
                if let Some(title) = v.get("title").and_then(|x| x.as_str()) {
                    if !title.is_empty() {
                        return DeezerContainer::Playlist {
                            title: title.to_string(),
                        };
                    }
                }
            }
            DeezerContainer::Unknown
        }
        "artist" => {
            let mut years = HashMap::new();
            if let Some(v) = deezer_api(&format!("artist/{}/albums?limit=300", id)) {
                if let Some(arr) = v.get("data").and_then(|d| d.as_array()) {
                    for album in arr {
                        let title = album.get("title").and_then(|x| x.as_str()).unwrap_or_default();
                        if let (false, Some(year)) =
                            (title.is_empty(), year_from_release_date(album))
                        {
                            years.insert(sanitize_filename(title), year);
                        }
                    }
                }
            }
            DeezerContainer::ArtistDiscography { years }
        }
        _ => DeezerContainer::Unknown,
    }
}

/// Move a single file, creating parent dirs and falling back to copy+delete
/// when `rename` cannot cross a filesystem boundary.
fn move_file(src: &Path, dst: &Path) -> std::io::Result<()> {
    if let Some(parent) = dst.parent() {
        fs::create_dir_all(parent)?;
    }
    if dst.exists() {
        fs::remove_file(dst).ok();
    }
    if fs::rename(src, dst).is_ok() {
        return Ok(());
    }
    fs::copy(src, dst)?;
    fs::remove_file(src)?;
    Ok(())
}

fn move_dir_contents(src_dir: &Path, dst_dir: &Path) {
    let Ok(entries) = fs::read_dir(src_dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if let Some(name) = path.file_name() {
                move_dir_contents(&path, &dst_dir.join(name));
            }
            fs::remove_dir_all(&path).ok();
        } else if let Some(name) = path.file_name() {
            move_file(&path, &dst_dir.join(name)).ok();
        }
    }
}

/// Move everything deezload staged under `staging` into the canonical
/// "<dest>/<Artist>/<Album> (<year>)/" layout (or without the artist level when
/// `organize_by_artist` is off).
pub fn reorganize_deezer_output(
    staging: &Path,
    dest: &Path,
    organize_by_artist: bool,
    meta: &DeezerContainer,
) -> Result<(), String> {
    let entries =
        fs::read_dir(staging).map_err(|e| format!("Falha ao ler a pasta temporária: {}", e))?;

    let mut dirs = Vec::new();
    let mut files = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            dirs.push(path);
        } else {
            files.push(path);
        }
    }

    // Album folders ("Artist - Album/").
    for dir in dirs {
        let folder = dir
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_string();

        let (raw_artist, raw_album, year) = match meta {
            DeezerContainer::Album {
                artist,
                title,
                year,
            } => (artist.clone(), title.clone(), year.clone()),
            DeezerContainer::ArtistDiscography { years } => {
                let (a, b) = split_artist_album(&folder);
                let y = years.get(&sanitize_filename(&b)).cloned();
                (a, b, y)
            }
            _ => {
                let (a, b) = split_artist_album(&folder);
                (a, b, None)
            }
        };

        let album_folder = match year.as_deref() {
            Some(y) if !y.is_empty() => format!("{} ({})", sanitize_filename(&raw_album), y),
            _ => sanitize_filename(&raw_album),
        };

        let mut target = dest.to_path_buf();
        if organize_by_artist {
            let pa = primary_artist(&raw_artist);
            if !pa.is_empty() {
                target.push(pa);
            }
        }
        target.push(album_folder);

        move_dir_contents(&dir, &target);
        fs::remove_dir_all(&dir).ok();
    }

    // Loose files: a single track, or a playlist's tracks.
    for file in files {
        let name = file
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_string();
        if name.is_empty() {
            continue;
        }

        let mut target = dest.to_path_buf();
        match meta {
            DeezerContainer::Playlist { title } => {
                if organize_by_artist {
                    let (artist, _) = split_artist_album(file_stem(&name));
                    let pa = primary_artist(&artist);
                    if !pa.is_empty() {
                        target.push(pa);
                    }
                }
                target.push(sanitize_filename(title));
            }
            DeezerContainer::Track { artist } => {
                if organize_by_artist {
                    let pa = primary_artist(artist);
                    if !pa.is_empty() {
                        target.push(pa);
                    }
                }
            }
            _ => {
                if organize_by_artist {
                    let (artist, _) = split_artist_album(file_stem(&name));
                    let pa = primary_artist(&artist);
                    if !pa.is_empty() {
                        target.push(pa);
                    }
                }
            }
        }
        target.push(&name);
        move_file(&file, &target).ok();
    }

    Ok(())
}

pub fn parse_progress(line: &str) -> Option<(f32, String, String)> {
    if !line.contains("[download]") {
        return None;
    }

    // Example line: [download]  12.5% of 10.00MiB at  1.23MiB/s ETA 00:04
    // or: [download]  12.5% of ~10.00MiB at  1.23MiB/s ETA 00:04
    let percent_idx = line.find('%')?;
    
    // Find the whitespace before the percent value
    let search_str = &line[..percent_idx];
    let start_idx = search_str.rfind(|c: char| c.is_whitespace())?;
    let percent_str = line[start_idx..percent_idx].trim();
    let percent = percent_str.parse::<f32>().ok()? / 100.0;

    let at_idx = line.find(" at ")?;
    let eta_idx = line.find(" ETA ")?;
    
    let speed = if eta_idx > at_idx + 4 {
        line[at_idx + 4..eta_idx].trim().to_string()
    } else {
        "N/A".to_string()
    };

    let eta = if eta_idx + 5 < line.len() {
        line[eta_idx + 5..].trim().to_string()
    } else {
        "N/A".to_string()
    };

    Some((percent, speed, eta))
}

/// Extract a 0.0–1.0 fraction from a deezload progress-bar line such as
/// ` [█████░░░░░]  45.5%`. Returns `None` when the line has no percentage.
pub fn parse_deezer_pct(line: &str) -> Option<f32> {
    let percent_idx = line.find('%')?;
    let head = &line[..percent_idx];
    let start = head
        .rfind(|c: char| c.is_whitespace() || c == '[' || c == ']')
        .map(|i| i + 1)
        .unwrap_or(0);
    let value: f32 = head[start..].trim().parse().ok()?;
    Some((value / 100.0).clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_deezer_url() {
        assert_eq!(
            parse_deezer_url("https://www.deezer.com/track/3135556"),
            Some(("track".to_string(), "3135556".to_string()))
        );
        assert_eq!(
            parse_deezer_url("https://www.deezer.com/en/album/302127?utm=x"),
            Some(("album".to_string(), "302127".to_string()))
        );
        assert_eq!(
            parse_deezer_url("http://deezer.com/us/playlist/908622995/"),
            Some(("playlist".to_string(), "908622995".to_string()))
        );
        assert_eq!(parse_deezer_url("https://example.com/foo/bar"), None);
    }

    #[test]
    fn test_split_and_primary_artist() {
        assert_eq!(
            split_artist_album("Daft Punk - Discovery"),
            ("Daft Punk".to_string(), "Discovery".to_string())
        );
        assert_eq!(primary_artist("Jay-Z, Kanye West, Frank Ocean"), "Jay-Z");
        assert_eq!(primary_artist("Adele"), "Adele");
    }

    #[test]
    fn test_reorganize_album_layout() {
        let tmp = std::env::temp_dir().join(format!("dztest_{}", std::process::id()));
        let staging = tmp.join("staging");
        let dest = tmp.join("Music");
        let album_dir = staging.join("Kleber Lucas - Deus Cuida de Mim");
        fs::create_dir_all(&album_dir).unwrap();
        fs::write(album_dir.join("01 - Kleber Lucas - Faixa.mp3"), b"x").unwrap();
        fs::write(album_dir.join("Cover.jpg"), b"x").unwrap();

        let meta = DeezerContainer::Album {
            artist: "Kleber Lucas".to_string(),
            title: "Deus Cuida de Mim".to_string(),
            year: Some("2007".to_string()),
        };
        reorganize_deezer_output(&staging, &dest, true, &meta).unwrap();

        let expected = dest
            .join("Kleber Lucas")
            .join("Deus Cuida de Mim (2007)");
        assert!(expected.join("01 - Kleber Lucas - Faixa.mp3").is_file());
        assert!(expected.join("Cover.jpg").is_file());
        fs::remove_dir_all(&tmp).ok();
    }

    #[test]
    fn test_parse_deezer_pct() {
        assert_eq!(parse_deezer_pct(" [█████░░░░░]  45.5%"), Some(0.455));
        assert_eq!(parse_deezer_pct("[██████████] 100.0%"), Some(1.0));
        assert_eq!(parse_deezer_pct("Downloading track 12345..."), None);
        assert_eq!(parse_deezer_pct(" ✓ Downloaded: 01 - Song.flac"), None);
    }

    #[test]
    fn test_parse_progress() {
        let line = "[download]  12.5% of 10.00MiB at  1.23MiB/s ETA 00:04";
        let parsed = parse_progress(line);
        assert!(parsed.is_some());
        let (percent, speed, eta) = parsed.unwrap();
        assert_eq!(percent, 0.125);
        assert_eq!(speed, "1.23MiB/s");
        assert_eq!(eta, "00:04");

        let line2 = "[download]  99.9% of ~45.2KiB at Unknown speed ETA Unknown";
        let parsed2 = parse_progress(line2);
        assert!(parsed2.is_some());
        let (percent2, speed2, eta2) = parsed2.unwrap();
        assert_eq!(percent2, 0.999);
        assert_eq!(speed2, "Unknown speed");
        assert_eq!(eta2, "Unknown");
    }
}

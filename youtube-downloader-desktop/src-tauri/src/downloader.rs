use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::{Mutex, Notify};
use tauri::{AppHandle, Emitter};

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
}

pub struct DownloadState {
    pub queue: Vec<DownloadItem>,
    pub max_concurrent: usize,
    pub destination_folder: PathBuf,
    pub yt_dlp_path: String,
    pub ffmpeg_path: String,
    pub auto_retry: bool,
    // Store abort handles to cancel downloads
    pub active_downloads: HashMap<String, tauri::async_runtime::JoinHandle<()>>,
}

pub struct DownloadManager {
    pub state: Arc<Mutex<DownloadState>>,
    pub notify: Arc<Notify>,
}

impl DownloadManager {
    pub fn new(yt_dlp: String, ffmpeg: String, default_dest: PathBuf) -> Self {
        Self {
            state: Arc::new(Mutex::new(DownloadState {
                queue: Vec::new(),
                max_concurrent: 2,
                destination_folder: default_dest,
                yt_dlp_path: yt_dlp,
                ffmpeg_path: ffmpeg,
                auto_retry: false,
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
        let (yt_dlp, ffmpeg_dir, dest_folder) = {
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
                state.destination_folder.clone(),
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

        // Determine destination template
        let output_template = if let Some(ref playlist) = item.playlist_title {
            let sanitized_playlist = sanitize_filename(playlist);
            if item.is_album.unwrap_or(false) {
                dest_folder
                    .join(format!("{} (%(release_date,upload_date>%Y)s)", sanitized_playlist))
                    .join("%(title)s.%(ext)s")
            } else {
                dest_folder.join(sanitized_playlist).join("%(title)s.%(ext)s")
            }
        } else {
            dest_folder.join("%(title)s.%(ext)s")
        };

        // Build yt-dlp arguments
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

        args.extend(vec![
            "--ppa".to_string(),
            "EmbedThumbnail+ffmpeg_o:-c:v mjpeg -vf crop=ih:ih".to_string(),
            "--no-mtime".to_string(),
            "--no-warnings".to_string(),
            "--ignore-errors".to_string(),
            "-o".to_string(),
            output_template.to_string_lossy().to_string(),
        ]);

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

        while let Ok(Some(line)) = reader.next_line().await {
            if let Some((progress, speed, eta)) = parse_progress(&line) {
                let mut state = self.state.lock().await;
                if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                    i.progress = progress;
                    i.eta = eta;
                    
                    let clean_line = if line.contains("[ExtractAudio]") || line.contains("[ffmpeg]") {
                        i.status = DownloadStatus::Converting;
                        "Convertendo para MP3...".to_string()
                    } else {
                        i.status = DownloadStatus::Downloading;
                        format!("Baixando a {}", speed)
                    };
                    i.status_detail = Some(clean_line);
                }
                self.emit_queue_update(&state, &app_handle);
            } else if line.contains("[ExtractAudio]") || line.contains("[ffmpeg]") {
                let mut state = self.state.lock().await;
                if let Some(i) = state.queue.iter_mut().find(|x| x.id == item.id) {
                    i.status = DownloadStatus::Converting;
                    i.status_detail = Some("Convertendo para MP3...".to_string());
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

    pub fn emit_queue_update(&self, state: &DownloadState, app_handle: &AppHandle) {
        app_handle.emit("queue-updated", state.queue.clone()).ok();
    }
}

pub fn sanitize_filename(name: &str) -> String {
    name.chars()
        .map(|c| match c {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => c,
        })
        .collect()
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

#[cfg(test)]
mod tests {
    use super::*;

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

mod catalog;
mod dependency;
mod downloader;
mod settings;

use std::sync::Arc;
use tauri::{AppHandle, Manager, State};
use dependency::{DeezerDependencies, Dependencies};
use downloader::{DeezerRuntime, DownloadItem, DownloadManager, DownloadStatus};
use settings::{AppMode, MediaType, PersistedSettings, DEEZER_QUALITIES};

/// Album proposal shown to the user before an album download.
#[derive(serde::Serialize)]
struct AlbumProposal {
    candidates: Vec<catalog::CatalogAlbum>,
    selected: usize,
    #[serde(flatten)]
    found: catalog::AlbumMatch,
}

/// Reads artist / album of the first track of a YouTube Music album.
async fn probe_album(yt_dlp: &str, url: &str) -> Result<(String, String), String> {
    let mut cmd = tokio::process::Command::new(yt_dlp);
    cmd.args([
        "--skip-download",
        "--no-warnings",
        "--no-playlist",
        "--print",
        "%(artist,creator)s|||%(album)s",
        url,
    ])
    .stdout(std::process::Stdio::piped())
    .stderr(std::process::Stdio::piped());
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let out = cmd.output().await.map_err(|e| format!("Falha ao executar yt-dlp: {}", e))?;
    let text = String::from_utf8_lossy(&out.stdout);
    let line = text.lines().filter(|l| !l.trim().is_empty()).last().unwrap_or_default();
    let mut parts = line.split("|||").map(|p| {
        let p = p.trim();
        if p == "NA" { String::new() } else { p.to_string() }
    });
    let artist = parts.next().unwrap_or_default();
    let artist = artist.split(',').next().unwrap_or_default().trim().to_string();
    Ok((artist, parts.next().unwrap_or_default()))
}

/// Finds the album of a queued YouTube Music playlist in iTunes/Deezer and
/// matches its tracks. `None` when no catalogue has it.
#[tauri::command]
async fn find_album(
    first_url: String,
    playlist_title: String,
    entries: Vec<catalog::PlaylistEntry>,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<Option<AlbumProposal>, String> {
    let yt_dlp = manager.state.lock().await.yt_dlp_path.clone();
    let (artist, probed) = probe_album(&yt_dlp, &first_url).await.unwrap_or_default();
    // YouTube Music names the playlist "Album - <name>".
    let album = if probed.is_empty() {
        playlist_title.trim_start_matches("Album - ").trim().to_string()
    } else {
        probed
    };
    if album.is_empty() {
        return Ok(None);
    }
    let count = entries.len() as u32;
    tauri::async_runtime::spawn_blocking(move || {
        let candidates = catalog::search(&artist, &album, count)?;
        let Some(first) = candidates.first() else { return Ok(None) };
        let found = catalog::load_match(first, &entries)?;
        Ok(Some(AlbumProposal { candidates, selected: 0, found }))
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Tracks and matches for another candidate picked in the dialog.
#[tauri::command]
async fn load_album(
    album: catalog::CatalogAlbum,
    entries: Vec<catalog::PlaylistEntry>,
) -> Result<catalog::AlbumMatch, String> {
    tauri::async_runtime::spawn_blocking(move || catalog::load_match(&album, &entries))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn get_full_album_meta(manager: State<'_, Arc<DownloadManager>>) -> Result<bool, String> {
    Ok(manager.state.lock().await.full_album_meta)
}

#[tauri::command]
async fn set_full_album_meta(
    enabled: bool,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.full_album_meta = enabled;
    state.persist()
}

#[derive(serde::Serialize)]
struct DeezerSettings {
    arl: String,
    quality: String,
}

#[tauri::command]
fn get_dependencies_status(handle: AppHandle) -> Result<Dependencies, String> {
    dependency::check_dependencies(&handle)
}

#[tauri::command]
async fn install_dependencies(
    handle: AppHandle,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<Dependencies, String> {
    let deps = dependency::download_dependencies(&handle)?;
    // Update the downloader manager path
    let mut state = manager.state.lock().await;
    state.yt_dlp_path = deps.yt_dlp_path.clone();
    state.ffmpeg_path = deps.ffmpeg_path.clone();
    Ok(deps)
}

#[tauri::command]
async fn fetch_video_info(
    url: String,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<serde_json::Value, String> {
    let yt_dlp = {
        let state = manager.state.lock().await;
        state.yt_dlp_path.clone()
    };

    if yt_dlp.is_empty() {
        return Err("yt-dlp não está instalado.".to_string());
    }

    let mut cmd = tokio::process::Command::new(&yt_dlp);
    cmd.args(&["--flat-playlist", "--dump-single-json", &url])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());

    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let output = cmd.output().await.map_err(|e| format!("Falha ao executar yt-dlp: {}", e))?;

    if !output.status.success() {
        let err_msg = String::from_utf8_lossy(&output.stderr).to_string();
        return Err(format!("Erro no yt-dlp: {}", err_msg));
    }

    let out_str = String::from_utf8_lossy(&output.stdout);
    let json: serde_json::Value = serde_json::from_str(&out_str)
        .map_err(|e| format!("Falha ao interpretar resposta JSON: {}", e))?;

    Ok(json)
}

#[tauri::command]
async fn add_to_queue(
    items: Vec<DownloadItem>,
    manager: State<'_, Arc<DownloadManager>>,
    handle: AppHandle,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.queue.extend(items);
    manager.emit_queue_update(&state, &handle);
    manager.notify.notify_one(); // wake up queue processor
    Ok(())
}

#[tauri::command]
async fn set_max_concurrent(
    limit: usize,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.max_concurrent = limit.clamp(1, 10);
    manager.notify.notify_one(); // trigger check
    Ok(())
}

#[tauri::command]
async fn get_destination_folder(manager: State<'_, Arc<DownloadManager>>) -> Result<String, String> {
    let state = manager.state.lock().await;
    Ok(state.destination_folder.to_string_lossy().to_string())
}

#[tauri::command]
async fn get_video_folder(manager: State<'_, Arc<DownloadManager>>) -> Result<String, String> {
    let state = manager.state.lock().await;
    Ok(state.video_folder.to_string_lossy().to_string())
}

/// Pick the destination for `target` ("audio" or "video") and persist it.
#[tauri::command]
async fn select_folder(
    target: String,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<Option<String>, String> {
    let is_video = match target.as_str() {
        "audio" => false,
        "video" => true,
        other => return Err(format!("Destino inválido: {}", other)),
    };

    let default_path = {
        let state = manager.state.lock().await;
        if is_video {
            state.video_folder.clone()
        } else {
            state.destination_folder.clone()
        }
    };

    let folder = rfd::FileDialog::new()
        .set_directory(&default_path)
        .pick_folder();

    if let Some(path) = folder {
        let path_str = path.to_string_lossy().to_string();
        let mut state = manager.state.lock().await;
        if is_video {
            state.video_folder = path;
        } else {
            state.destination_folder = path;
        }
        state.persist()?;
        Ok(Some(path_str))
    } else {
        Ok(None)
    }
}

#[tauri::command]
async fn remove_item(
    id: String,
    manager: State<'_, Arc<DownloadManager>>,
    handle: AppHandle,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    
    // If running, abort the task
    if let Some(join_handle) = state.active_downloads.remove(&id) {
        join_handle.abort();
    }

    state.queue.retain(|item| item.id != id);
    manager.emit_queue_update(&state, &handle);
    manager.notify.notify_one();
    Ok(())
}

#[tauri::command]
async fn clear_completed(
    manager: State<'_, Arc<DownloadManager>>,
    handle: AppHandle,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.queue.retain(|item| {
        item.status != DownloadStatus::Completed && item.status != DownloadStatus::Failed
    });
    manager.emit_queue_update(&state, &handle);
    Ok(())
}

#[tauri::command]
async fn retry_item(
    id: String,
    manager: State<'_, Arc<DownloadManager>>,
    handle: AppHandle,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    if let Some(item) = state.queue.iter_mut().find(|i| i.id == id) {
        item.status = DownloadStatus::Queued;
        item.progress = 0.0;
        item.eta = "N/A".to_string();
        item.status_detail = Some("Aguardando...".to_string());
        item.error_message = None;
        item.retry_count = 0;
    }
    manager.emit_queue_update(&state, &handle);
    manager.notify.notify_one();
    Ok(())
}

#[tauri::command]
async fn get_auto_retry(manager: State<'_, Arc<DownloadManager>>) -> Result<bool, String> {
    let state = manager.state.lock().await;
    Ok(state.auto_retry)
}

#[tauri::command]
async fn set_auto_retry(
    enabled: bool,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.auto_retry = enabled;
    Ok(())
}

#[tauri::command]
async fn get_organize_by_artist(manager: State<'_, Arc<DownloadManager>>) -> Result<bool, String> {
    let state = manager.state.lock().await;
    Ok(state.organize_by_artist)
}

#[tauri::command]
async fn set_organize_by_artist(
    enabled: bool,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.organize_by_artist = enabled;
    Ok(())
}

#[tauri::command]
async fn get_app_mode(manager: State<'_, Arc<DownloadManager>>) -> Result<AppMode, String> {
    let state = manager.state.lock().await;
    Ok(state.mode)
}

#[tauri::command]
async fn set_app_mode(
    mode: String,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let mode = match mode.as_str() {
        "deezer" => AppMode::Deezer,
        "youtube" => AppMode::Youtube,
        other => return Err(format!("Modo inválido: {}", other)),
    };
    let mut state = manager.state.lock().await;
    state.mode = mode;
    state.persist()
}

#[tauri::command]
async fn get_media_type(manager: State<'_, Arc<DownloadManager>>) -> Result<MediaType, String> {
    let state = manager.state.lock().await;
    Ok(state.media_type)
}

#[tauri::command]
async fn set_media_type(
    media_type: String,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let media_type = match media_type.as_str() {
        "audio" => MediaType::Audio,
        "video" => MediaType::Video,
        other => return Err(format!("Tipo de mídia inválido: {}", other)),
    };
    let mut state = manager.state.lock().await;
    state.media_type = media_type;
    state.persist()
}

#[tauri::command]
async fn get_deezer_settings(
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<DeezerSettings, String> {
    let state = manager.state.lock().await;
    Ok(DeezerSettings {
        arl: state.deezer_arl.clone(),
        quality: state.deezer_quality.clone(),
    })
}

#[tauri::command]
async fn set_deezer_arl(
    arl: String,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    let mut state = manager.state.lock().await;
    state.deezer_arl = arl.trim().to_string();
    state.persist()
}

#[tauri::command]
async fn set_deezer_quality(
    quality: String,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<(), String> {
    if !DEEZER_QUALITIES.contains(&quality.as_str()) {
        return Err(format!("Qualidade inválida: {}", quality));
    }
    let mut state = manager.state.lock().await;
    state.deezer_quality = quality;
    state.persist()
}

#[tauri::command]
fn get_deezer_dependencies_status(handle: AppHandle) -> Result<DeezerDependencies, String> {
    dependency::check_deezer_dependencies(&handle)
}

#[tauri::command]
async fn install_deezer_dependencies(
    handle: AppHandle,
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<DeezerDependencies, String> {
    let deps = dependency::download_deezer_dependencies(&handle)?;
    let mut state = manager.state.lock().await;
    state.python_path = deps.python_path.clone();
    state.deezload_path = deps.deezload_path.clone();
    Ok(deps)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            // Find paths
            let path_resolver = app.path();
            let default_dest = path_resolver
                .audio_dir()
                .unwrap_or_else(|_| path_resolver.download_dir().unwrap_or_else(|_| std::env::current_dir().unwrap()));
            let default_video_dest = path_resolver
                .video_dir()
                .unwrap_or_else(|_| path_resolver.download_dir().unwrap_or_else(|_| std::env::current_dir().unwrap()));

            let initial_deps = dependency::check_dependencies(app.handle()).unwrap_or(Dependencies {
                yt_dlp_path: "".to_string(),
                ffmpeg_path: "".to_string(),
            });

            let config_dir = path_resolver
                .app_data_dir()
                .unwrap_or_else(|_| std::env::current_dir().unwrap());
            let persisted = PersistedSettings::load(&config_dir);

            let deezer_deps = dependency::check_deezer_dependencies(app.handle())
                .unwrap_or(DeezerDependencies {
                    python_path: "".to_string(),
                    deezload_path: "".to_string(),
                });

            let manager = Arc::new(DownloadManager::new(
                initial_deps.yt_dlp_path,
                initial_deps.ffmpeg_path,
                default_dest,
                default_video_dest,
                DeezerRuntime {
                    config_dir,
                    settings: persisted,
                    python_path: deezer_deps.python_path,
                    deezload_path: deezer_deps.deezload_path,
                },
            ));

            // Start queue background task
            manager.start_queue_processor(app.handle().clone());

            app.manage(manager);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_dependencies_status,
            install_dependencies,
            fetch_video_info,
            add_to_queue,
            set_max_concurrent,
            get_destination_folder,
            get_video_folder,
            select_folder,
            remove_item,
            clear_completed,
            retry_item,
            get_auto_retry,
            set_auto_retry,
            get_organize_by_artist,
            set_organize_by_artist,
            get_app_mode,
            set_app_mode,
            get_media_type,
            set_media_type,
            get_deezer_settings,
            set_deezer_arl,
            set_deezer_quality,
            get_deezer_dependencies_status,
            install_deezer_dependencies,
            find_album,
            load_album,
            get_full_album_meta,
            set_full_album_meta
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

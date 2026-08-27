mod dependency;
mod downloader;

use std::sync::Arc;
use tauri::{AppHandle, Manager, State};
use dependency::Dependencies;
use downloader::{DownloadItem, DownloadManager, DownloadStatus};

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
async fn select_folder(
    manager: State<'_, Arc<DownloadManager>>,
) -> Result<Option<String>, String> {
    let default_path = {
        let state = manager.state.lock().await;
        state.destination_folder.clone()
    };

    let folder = rfd::FileDialog::new()
        .set_directory(&default_path)
        .pick_folder();

    if let Some(path) = folder {
        let path_str = path.to_string_lossy().to_string();
        let mut state = manager.state.lock().await;
        state.destination_folder = path;
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

            let initial_deps = dependency::check_dependencies(app.handle()).unwrap_or(Dependencies {
                yt_dlp_path: "".to_string(),
                ffmpeg_path: "".to_string(),
            });

            let manager = Arc::new(DownloadManager::new(
                initial_deps.yt_dlp_path,
                initial_deps.ffmpeg_path,
                default_dest,
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
            select_folder,
            remove_item,
            clear_completed,
            retry_item,
            get_auto_retry,
            set_auto_retry,
            get_organize_by_artist,
            set_organize_by_artist
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

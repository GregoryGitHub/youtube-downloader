use std::fs::{self, File};
use std::io::{self, Write};
use std::path::Path;
use tauri::{AppHandle, Manager, Emitter};
use zip::ZipArchive;

#[derive(Clone, serde::Serialize)]
pub struct Dependencies {
    pub yt_dlp_path: String,
    pub ffmpeg_path: String,
}

#[derive(Clone, serde::Serialize)]
struct DownloadProgress {
    component: String,
    progress: f32, // 0.0 to 1.0
    status: String,
}

// Check if commands exist in PATH
fn is_in_path(cmd: &str) -> bool {
    #[cfg(windows)]
    let cmd_name = format!("{}.exe", cmd);
    #[cfg(not(windows))]
    let cmd_name = cmd.to_string();

    if let Ok(path) = std::env::var("PATH") {
        for p in std::env::split_paths(&path) {
            let p_cmd = p.join(&cmd_name);
            if p_cmd.exists() && p_cmd.is_file() {
                return true;
            }
        }
    }
    false
}

pub fn check_dependencies(app: &AppHandle) -> Result<Dependencies, String> {
    let mut yt_dlp_path = if is_in_path("yt-dlp") {
        "yt-dlp".to_string()
    } else {
        "".to_string()
    };

    let mut ffmpeg_path = if is_in_path("ffmpeg") {
        "ffmpeg".to_string()
    } else {
        "".to_string()
    };

    let app_data = app.path().app_data_dir().map_err(|e| e.to_string())?;
    
    // If not in PATH, check app data dir
    if yt_dlp_path.is_empty() {
        #[cfg(windows)]
        let local_yt = app_data.join("yt-dlp.exe");
        #[cfg(not(windows))]
        let local_yt = app_data.join("yt-dlp");

        if local_yt.exists() && local_yt.is_file() {
            yt_dlp_path = local_yt.to_string_lossy().to_string();
        }
    }

    if ffmpeg_path.is_empty() {
        #[cfg(windows)]
        let local_ffmpeg = app_data.join("ffmpeg.exe");
        #[cfg(not(windows))]
        let local_ffmpeg = app_data.join("ffmpeg");

        if local_ffmpeg.exists() && local_ffmpeg.is_file() {
            ffmpeg_path = local_ffmpeg.to_string_lossy().to_string();
        }
    }

    Ok(Dependencies {
        yt_dlp_path,
        ffmpeg_path,
    })
}

pub fn download_dependencies(app: &AppHandle) -> Result<Dependencies, String> {
    let app_data = app.path().app_data_dir().map_err(|e| e.to_string())?;
    if !app_data.exists() {
        fs::create_dir_all(&app_data).map_err(|e| e.to_string())?;
    }

    let deps = check_dependencies(app)?;
    let mut updated_deps = deps.clone();

    // 1. Download yt-dlp if missing
    if deps.yt_dlp_path.is_empty() {
        app.emit("dep-progress", DownloadProgress {
            component: "yt-dlp".to_string(),
            progress: 0.1,
            status: "Iniciando download do yt-dlp...".to_string(),
        }).ok();

        #[cfg(windows)]
        let yt_url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
        #[cfg(not(windows))]
        let yt_url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";

        #[cfg(windows)]
        let yt_dest = app_data.join("yt-dlp.exe");
        #[cfg(not(windows))]
        let yt_dest = app_data.join("yt-dlp");

        download_file(app, "yt-dlp", yt_url, &yt_dest)?;

        // On Unix, make yt-dlp executable
        #[cfg(not(windows))]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(metadata) = fs::metadata(&yt_dest) {
                let mut perms = metadata.permissions();
                perms.set_mode(0o755);
                fs::set_permissions(&yt_dest, perms).ok();
            }
        }

        updated_deps.yt_dlp_path = yt_dest.to_string_lossy().to_string();

        app.emit("dep-progress", DownloadProgress {
            component: "yt-dlp".to_string(),
            progress: 1.0,
            status: "yt-dlp instalado com sucesso!".to_string(),
        }).ok();
    }

    // 2. Download ffmpeg if missing
    if deps.ffmpeg_path.is_empty() {
        app.emit("dep-progress", DownloadProgress {
            component: "ffmpeg".to_string(),
            progress: 0.0,
            status: "Iniciando download do FFmpeg... (este processo pode demorar alguns minutos)".to_string(),
        }).ok();

        #[cfg(windows)]
        {
            // BtbN builds
            let ffmpeg_url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
            let zip_dest = app_data.join("ffmpeg.zip");

            download_file(app, "ffmpeg", ffmpeg_url, &zip_dest)?;

            app.emit("dep-progress", DownloadProgress {
                component: "ffmpeg".to_string(),
                progress: 0.9,
                status: "Extraindo FFmpeg...".to_string(),
            }).ok();

            // Extract ffmpeg.exe and ffprobe.exe
            let zip_file = File::open(&zip_dest).map_err(|e| format!("Falha ao abrir ffmpeg.zip: {}", e))?;
            let mut archive = ZipArchive::new(zip_file).map_err(|e| format!("Falha ao ler zip: {}", e))?;

            let mut extracted_ffmpeg = false;
            let mut extracted_ffprobe = false;

            for i in 0..archive.len() {
                let mut file = archive.by_index(i).map_err(|e| format!("Falha ao ler item zip: {}", e))?;
                let file_path = match file.enclosed_name() {
                    Some(path) => path.to_owned(),
                    None => continue,
                };

                let file_name = file_path.file_name().and_then(|n| n.to_str()).unwrap_or("");
                if file_name == "ffmpeg.exe" {
                    let dest_path = app_data.join("ffmpeg.exe");
                    let mut dest_file = File::create(&dest_path).map_err(|e| format!("Falha ao criar ffmpeg.exe: {}", e))?;
                    io::copy(&mut file, &mut dest_file).map_err(|e| format!("Falha ao extrair ffmpeg.exe: {}", e))?;
                    extracted_ffmpeg = true;
                } else if file_name == "ffprobe.exe" {
                    let dest_path = app_data.join("ffprobe.exe");
                    let mut dest_file = File::create(&dest_path).map_err(|e| format!("Falha ao criar ffprobe.exe: {}", e))?;
                    io::copy(&mut file, &mut dest_file).map_err(|e| format!("Falha ao extrair ffprobe.exe: {}", e))?;
                    extracted_ffprobe = true;
                }

                if extracted_ffmpeg && extracted_ffprobe {
                    break;
                }
            }

            // Cleanup zip
            fs::remove_file(&zip_dest).ok();

            if !extracted_ffmpeg {
                return Err("Não foi possível extrair o ffmpeg.exe do arquivo ZIP.".to_string());
            }

            updated_deps.ffmpeg_path = app_data.join("ffmpeg.exe").to_string_lossy().to_string();
        }

        #[cfg(not(windows))]
        {
            // For macOS / Linux, we recommend using system packages, or you can implement static build download here.
            // To keep things simple and avoid huge complex OS detection, we just return an error asking to install it,
            // but we can try to download from standard releases.
            return Err("Por favor, instale o FFmpeg e adicione-o ao PATH do seu sistema.".to_string());
        }

        app.emit("dep-progress", DownloadProgress {
            component: "ffmpeg".to_string(),
            progress: 1.0,
            status: "FFmpeg instalado com sucesso!".to_string(),
        }).ok();
    }

    Ok(updated_deps)
}

fn download_file(app: &AppHandle, component: &str, url: &str, dest: &Path) -> Result<(), String> {
    let response = ureq::get(url)
        .call()
        .map_err(|e| format!("Falha na requisição de download para {}: {}", component, e))?;

    let total_size = response
        .header("content-length")
        .and_then(|len| len.parse::<u64>().ok())
        .unwrap_or(0);

    let mut reader = response.into_reader();
    let mut dest_file = File::create(dest).map_err(|e| format!("Falha ao criar arquivo de destino: {}", e))?;

    let mut buffer = [0; 8192];
    let mut downloaded = 0u64;
    let mut last_progress_emit = 0.0f32;

    loop {
        let bytes_read = reader.read(&mut buffer).map_err(|e| format!("Erro durante o download: {}", e))?;
        if bytes_read == 0 {
            break;
        }

        dest_file.write_all(&buffer[..bytes_read]).map_err(|e| format!("Erro ao escrever no disco: {}", e))?;
        downloaded += bytes_read as u64;

        if total_size > 0 {
            let progress = downloaded as f32 / total_size as f32;
            // Emit progress updates in 1% steps to avoid flooding events
            if progress - last_progress_emit >= 0.01 || progress >= 1.0 {
                last_progress_emit = progress;
                app.emit("dep-progress", DownloadProgress {
                    component: component.to_string(),
                    progress: 0.1 + (progress * 0.8), // keep it between 10% and 90% for UI animation
                    status: format!("Baixando {}: {:.1}%", component, progress * 100.0),
                }).ok();
            }
        }
    }

    Ok(())
}

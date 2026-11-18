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
pub struct DeezerDependencies {
    /// Command used to launch Python (e.g. "python3", "python", "py"). Empty if none found.
    pub python_path: String,
    /// Absolute path to the downloaded deezload.py. Empty if not installed yet.
    pub deezload_path: String,
}

/// Packages deezload needs at runtime. `textual` (from its requirements.txt) is
/// only used by the TUI, so it is intentionally omitted here.
const DEEZLOAD_PYTHON_DEPS: [&str; 4] = ["requests", "pycryptodome", "mutagen", "deezer-py"];

const DEEZLOAD_SCRIPT_URL: &str =
    "https://raw.githubusercontent.com/Lunatic16/deezload/HEAD/deezload.py";

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

/// Run `<cmd> --version` and confirm it is a real Python 3. This filters out
/// Windows' "App execution alias" stub (`WindowsApps\python.exe`), which is on
/// PATH but only opens the Microsoft Store and fails any actual invocation.
fn probe_python(cmd: &str) -> bool {
    let mut c = std::process::Command::new(cmd);
    c.arg("--version");

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        c.creation_flags(CREATE_NO_WINDOW);
    }

    match c.output() {
        Ok(out) => {
            let text = format!(
                "{}{}",
                String::from_utf8_lossy(&out.stdout),
                String::from_utf8_lossy(&out.stderr)
            );
            out.status.success() && text.contains("Python 3")
        }
        Err(_) => false,
    }
}

/// Locate a usable Python interpreter.
///
/// On Windows we deliberately ignore whatever is on PATH (the Microsoft Store
/// `python.exe` alias is a frequent trap) and only accept the private
/// interpreter this app installs under the deezload folder. On other platforms
/// the system `python3` is used.
fn find_python(app: &AppHandle) -> String {
    let _ = app;

    #[cfg(windows)]
    {
        if let Ok(dir) = deezload_dir(app) {
            let exe = dir.join("python").join("python.exe");
            if exe.is_file() {
                let path = exe.to_string_lossy().to_string();
                if probe_python(&path) {
                    return path;
                }
            }
        }
        String::new()
    }

    #[cfg(not(windows))]
    {
        for candidate in ["python3", "python"] {
            if probe_python(candidate) {
                return candidate.to_string();
            }
        }
        String::new()
    }
}

fn deezload_dir(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("deezload"))
}

pub fn check_deezer_dependencies(app: &AppHandle) -> Result<DeezerDependencies, String> {
    let script = deezload_dir(app)?.join("deezload.py");
    let deezload_path = if script.is_file() {
        script.to_string_lossy().to_string()
    } else {
        String::new()
    };

    Ok(DeezerDependencies {
        python_path: find_python(app),
        deezload_path,
    })
}

pub fn download_deezer_dependencies(app: &AppHandle) -> Result<DeezerDependencies, String> {
    let dir = deezload_dir(app)?;
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;

    // 1. Fetch the single-file deezload script.
    app.emit(
        "dep-progress",
        DownloadProgress {
            component: "deezload".to_string(),
            progress: 0.05,
            status: "Baixando deezload.py...".to_string(),
        },
    )
    .ok();

    let script_dest = dir.join("deezload.py");
    download_file(app, "deezload", DEEZLOAD_SCRIPT_URL, &script_dest)?;

    // 2. Make sure a Python 3 is available. On Windows the app ships its own
    //    (embeddable build), so the user never has to install anything.
    #[cfg(windows)]
    let (python, into_site_packages) = {
        let existing = find_python(app);
        let py = if existing.is_empty() {
            install_embedded_python(app, &dir)?
        } else {
            existing
        };
        (py, true)
    };

    #[cfg(not(windows))]
    let (python, into_site_packages) = {
        let existing = find_python(app);
        if existing.is_empty() {
            return Err(
                "Python 3 não encontrado. Instale o Python 3 pelo gerenciador de pacotes do sistema (ex.: sudo apt install python3 python3-pip)."
                    .to_string(),
            );
        }
        (existing, false)
    };

    // 3. Install deezload's Python dependencies. For our private interpreter we
    //    drop them straight into its site-packages; for a system interpreter we
    //    use an isolated --target folder (added to PYTHONPATH at run time) so we
    //    never touch the user's global packages.
    app.emit(
        "dep-progress",
        DownloadProgress {
            component: "deezload".to_string(),
            progress: 0.8,
            status: "Instalando bibliotecas Python (pode demorar)...".to_string(),
        },
    )
    .ok();

    let libs_dir = dir.join("libs");
    let mut cmd = std::process::Command::new(&python);
    cmd.args(["-m", "pip", "install", "--upgrade", "--no-warn-script-location"]);
    if !into_site_packages {
        cmd.arg("--target").arg(&libs_dir);
    }
    cmd.args(DEEZLOAD_PYTHON_DEPS);

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let output = cmd
        .output()
        .map_err(|e| format!("Falha ao executar pip: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!(
            "pip falhou ao instalar as dependências do deezload: {}",
            stderr.trim()
        ));
    }

    app.emit(
        "dep-progress",
        DownloadProgress {
            component: "deezload".to_string(),
            progress: 1.0,
            status: "deezload instalado com sucesso!".to_string(),
        },
    )
    .ok();

    Ok(DeezerDependencies {
        python_path: python,
        deezload_path: script_dest.to_string_lossy().to_string(),
    })
}

/// Download the official Windows "embeddable package" for CPython into
/// `<dir>/python`, enable `import site` (so pip works) and bootstrap pip.
/// Returns the path to the extracted `python.exe`.
#[cfg(windows)]
fn install_embedded_python(app: &AppHandle, dir: &Path) -> Result<String, String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;
    const PYTHON_EMBED_URL: &str =
        "https://www.python.org/ftp/python/3.12.8/python-3.12.8-embed-amd64.zip";
    const GET_PIP_URL: &str = "https://bootstrap.pypa.io/get-pip.py";

    let py_dir = dir.join("python");
    fs::create_dir_all(&py_dir).map_err(|e| e.to_string())?;

    app.emit(
        "dep-progress",
        DownloadProgress {
            component: "deezload".to_string(),
            progress: 0.2,
            status: "Baixando o Python (uso interno do app)...".to_string(),
        },
    )
    .ok();

    let zip_dest = dir.join("python-embed.zip");
    download_file(app, "deezload", PYTHON_EMBED_URL, &zip_dest)?;

    app.emit(
        "dep-progress",
        DownloadProgress {
            component: "deezload".to_string(),
            progress: 0.5,
            status: "Extraindo o Python...".to_string(),
        },
    )
    .ok();

    {
        let zip_file =
            File::open(&zip_dest).map_err(|e| format!("Falha ao abrir o zip do Python: {}", e))?;
        let mut archive = ZipArchive::new(zip_file)
            .map_err(|e| format!("Falha ao ler o zip do Python: {}", e))?;

        // The embeddable package is a flat list of files (no directories).
        for i in 0..archive.len() {
            let mut file = archive.by_index(i).map_err(|e| e.to_string())?;
            let file_name = match file
                .enclosed_name()
                .and_then(|p| p.file_name().map(|n| n.to_owned()))
            {
                Some(n) => n,
                None => continue,
            };
            let out_path = py_dir.join(&file_name);
            let mut out_file = File::create(&out_path)
                .map_err(|e| format!("Falha ao extrair {}: {}", file_name.to_string_lossy(), e))?;
            io::copy(&mut file, &mut out_file).map_err(|e| e.to_string())?;
        }
    }
    fs::remove_file(&zip_dest).ok();

    // The embeddable build ships with `import site` commented out, which also
    // disables pip / site-packages. Re-enable it and make sure the
    // `Lib\site-packages` directory (where pip installs) is on the path.
    for entry in fs::read_dir(&py_dir).map_err(|e| e.to_string())? {
        let path = entry.map_err(|e| e.to_string())?.path();
        if path.extension().and_then(|e| e.to_str()) == Some("_pth") {
            let content = fs::read_to_string(&path).map_err(|e| e.to_string())?;
            let mut patched = content
                .replace("#import site", "import site")
                .replace("# import site", "import site");
            if !patched.contains("Lib\\site-packages") {
                patched = format!("Lib\\site-packages\n{}", patched);
            }
            fs::write(&path, patched).map_err(|e| e.to_string())?;
        }
    }
    fs::create_dir_all(py_dir.join("Lib").join("site-packages")).ok();

    let python_exe = py_dir.join("python.exe");
    if !python_exe.is_file() {
        return Err("O pacote do Python não continha python.exe.".to_string());
    }

    // Bootstrap pip into the embedded interpreter.
    app.emit(
        "dep-progress",
        DownloadProgress {
            component: "deezload".to_string(),
            progress: 0.65,
            status: "Configurando o pip...".to_string(),
        },
    )
    .ok();

    let get_pip = dir.join("get-pip.py");
    download_file(app, "deezload", GET_PIP_URL, &get_pip)?;

    let output = std::process::Command::new(&python_exe)
        .arg(&get_pip)
        .arg("--no-warn-script-location")
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("Falha ao configurar o pip: {}", e))?;
    fs::remove_file(&get_pip).ok();

    if !output.status.success() {
        return Err(format!(
            "Falha ao configurar o pip: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }

    Ok(python_exe.to_string_lossy().to_string())
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

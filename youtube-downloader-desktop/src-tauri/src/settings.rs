use std::path::{Path, PathBuf};

/// Which service the app is currently operating against.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AppMode {
    Youtube,
    Deezer,
}

impl Default for AppMode {
    fn default() -> Self {
        AppMode::Youtube
    }
}

/// What a YouTube download produces: an MP3 or a full-resolution MP4.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaType {
    Audio,
    Video,
}

impl Default for MediaType {
    fn default() -> Self {
        MediaType::Audio
    }
}

/// Default audio quality requested from deezload when in Deezer mode.
pub const DEFAULT_DEEZER_QUALITY: &str = "MP3_320";

/// Valid `--quality` values accepted by deezload.
pub const DEEZER_QUALITIES: [&str; 3] = ["MP3_128", "MP3_320", "FLAC"];

fn default_quality() -> String {
    DEFAULT_DEEZER_QUALITY.to_string()
}

/// Settings that survive an app restart. Everything else in `DownloadState`
/// is intentionally session-only, matching the app's existing behaviour.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct PersistedSettings {
    #[serde(default)]
    pub mode: AppMode,
    #[serde(default)]
    pub deezer_arl: String,
    #[serde(default = "default_quality")]
    pub deezer_quality: String,
    #[serde(default)]
    pub media_type: MediaType,
    #[serde(default)]
    pub audio_folder: Option<PathBuf>,
    #[serde(default)]
    pub video_folder: Option<PathBuf>,
}

impl Default for PersistedSettings {
    fn default() -> Self {
        Self {
            mode: AppMode::default(),
            deezer_arl: String::new(),
            deezer_quality: default_quality(),
            media_type: MediaType::default(),
            audio_folder: None,
            video_folder: None,
        }
    }
}

impl PersistedSettings {
    fn config_file(dir: &Path) -> PathBuf {
        dir.join("app-config.json")
    }

    /// Read the config file, falling back to defaults on any error so a
    /// corrupt file never blocks startup.
    pub fn load(dir: &Path) -> Self {
        let path = Self::config_file(dir);
        std::fs::read_to_string(path)
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, dir: &Path) -> Result<(), String> {
        if !dir.exists() {
            std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        }
        let json = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(Self::config_file(dir), json).map_err(|e| e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_old_config_without_new_fields() {
        let raw = r#"{"mode":"deezer","deezer_arl":"abc","deezer_quality":"FLAC"}"#;
        let s: PersistedSettings = serde_json::from_str(raw).unwrap();
        assert_eq!(s.mode, AppMode::Deezer);
        assert_eq!(s.media_type, MediaType::Audio);
        assert!(s.audio_folder.is_none());
        assert!(s.video_folder.is_none());
    }
}

// YDownloader Frontend Integration

// Tauri globals injected via withGlobalTauri: true
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

document.addEventListener("DOMContentLoaded", () => {
  // DOM Elements
  const tabs = document.querySelectorAll(".tab-btn");
  const tabContents = document.querySelectorAll(".tab-content");
  
  const urlInput = document.getElementById("url-input");
  const btnEnqueue = document.getElementById("btn-enqueue");
  const downloaderStatusBox = document.getElementById("downloader-status-box");
  const downloaderStatusText = document.getElementById("downloader-status-text");
  
  const queueCountText = document.getElementById("queue-count-text");
  const btnClearCompleted = document.getElementById("btn-clear-completed");
  const queueEmptyState = document.getElementById("queue-empty-state");
  const queueListContainer = document.getElementById("queue-list-container");
  const queueBadge = document.getElementById("queue-badge");
  
  const maxConcurrentSlider = document.getElementById("max-concurrent-slider");
  const maxConcurrentVal = document.getElementById("max-concurrent-val");
  const destFolderInput = document.getElementById("dest-folder-input");
  const btnSelectFolder = document.getElementById("btn-select-folder");
  const autoRetrySwitch = document.getElementById("auto-retry-switch");
  const organizeArtistSwitch = document.getElementById("organize-artist-switch");
  
  const statusYtdl = document.getElementById("status-ytdl");
  const statusFfmpeg = document.getElementById("status-ffmpeg");
  const btnInstallDeps = document.getElementById("btn-install-deps");
  const dependencyStatusBox = document.getElementById("dependency-status-box");
  const dependencyStatusText = document.getElementById("dependency-status-text");

  // Mode switch + Deezer settings
  const modeOptions = document.querySelectorAll(".mode-option");
  const deezerOnlyEls = document.querySelectorAll(".deezer-only");
  const brandTitle = document.getElementById("brand-title");
  const brandSubtitle = document.getElementById("brand-subtitle");
  const cardHeading = document.getElementById("card-heading");
  const cardSubheading = document.getElementById("card-subheading");
  const deezerArlInput = document.getElementById("deezer-arl-input");
  const btnSaveArl = document.getElementById("btn-save-arl");
  const deezerArlStatus = document.getElementById("deezer-arl-status");
  const qualityOptions = document.querySelectorAll("#deezer-quality-options .quality-option");
  const statusPython = document.getElementById("status-python");
  const statusDeezload = document.getElementById("status-deezload");
  const btnInstallDeezer = document.getElementById("btn-install-deezer");

  // YouTube audio/video selector
  const youtubeOnlyEls = document.querySelectorAll(".youtube-only");
  const mediaTypeOptions = document.querySelectorAll("#media-type-options .quality-option");
  const videoFolderInput = document.getElementById("video-folder-input");
  const btnSelectVideoFolder = document.getElementById("btn-select-video-folder");

  let systemDeps = { yt_dlp_path: "", ffmpeg_path: "" };
  let deezerDeps = { python_path: "", deezload_path: "" };
  let appMode = "youtube";
  let mediaType = "audio";

  const YOUTUBE_SUBHEADING = {
    audio: "Suporta links de vídeos individuais ou playlists inteiras. Salva em MP3 320k.",
    video: "Suporta links de vídeos individuais ou playlists inteiras. Salva em MP4 na resolução máxima.",
  };

  const MODE_TEXT = {
    youtube: {
      brand: "YDownloader",
      subtitle: "Media Downloader",
      heading: "Insira o link do YouTube",
      subheading: "Suporta links de vídeos individuais ou playlists inteiras.",
      placeholder: "Insira a URL do vídeo ou playlist...",
    },
    deezer: {
      brand: "DeezLoad",
      subtitle: "Deezer Downloader",
      heading: "Insira o link do Deezer",
      subheading: "Suporta faixas, álbuns, playlists e discografias de artistas.",
      placeholder: "Cole a URL de faixa, álbum ou playlist do Deezer...",
    },
  };

  // ==========================================
  // 1. Navigation Tab Switching
  // ==========================================
  tabs.forEach(tab => {
    tab.addEventListener("click", () => {
      tabs.forEach(t => t.classList.remove("active"));
      tabContents.forEach(c => c.classList.remove("active"));
      
      tab.classList.add("active");
      const targetContent = document.getElementById(tab.getAttribute("data-tab"));
      if (targetContent) targetContent.classList.add("active");
    });
  });

  // ==========================================
  // 1b. Mode Switch (YouTube / Deezer)
  // ==========================================
  function applyMode(mode) {
    appMode = mode === "deezer" ? "deezer" : "youtube";
    const t = MODE_TEXT[appMode];

    document.body.classList.toggle("mode-deezer", appMode === "deezer");
    modeOptions.forEach((btn) =>
      btn.classList.toggle("active", btn.dataset.mode === appMode)
    );
    deezerOnlyEls.forEach((el) => {
      el.hidden = appMode !== "deezer";
    });
    youtubeOnlyEls.forEach((el) => {
      el.hidden = appMode !== "youtube";
    });

    brandTitle.textContent = t.brand;
    brandSubtitle.textContent = t.subtitle;
    cardHeading.textContent = t.heading;
    cardSubheading.textContent =
      appMode === "youtube" ? YOUTUBE_SUBHEADING[mediaType] : t.subheading;
    urlInput.setAttribute("placeholder", t.placeholder);

    if (appMode === "deezer") {
      checkDeezerDependencies();
    }
  }

  function applyMediaType(type) {
    mediaType = type === "video" ? "video" : "audio";
    mediaTypeOptions.forEach((b) =>
      b.classList.toggle("active", b.dataset.media === mediaType)
    );
    if (appMode === "youtube") {
      cardSubheading.textContent = YOUTUBE_SUBHEADING[mediaType];
    }
  }

  mediaTypeOptions.forEach((btn) => {
    btn.addEventListener("click", async () => {
      const type = btn.dataset.media;
      if (type === mediaType) return;
      try {
        await invoke("set_media_type", { mediaType: type });
        applyMediaType(type);
      } catch (e) {
        console.error("Falha ao trocar tipo de mídia:", e);
      }
    });
  });

  modeOptions.forEach((btn) => {
    btn.addEventListener("click", async () => {
      const mode = btn.dataset.mode;
      if (mode === appMode) return;
      try {
        await invoke("set_app_mode", { mode });
        applyMode(mode);
      } catch (e) {
        console.error("Falha ao trocar de modo:", e);
      }
    });
  });

  // ==========================================
  // 2. Downloader URL Enqueuing
  // ==========================================
  urlInput.addEventListener("input", () => {
    btnEnqueue.disabled = urlInput.value.trim() === "";
  });

  function deezerTitleFromUrl(url) {
    if (/\/track\//.test(url)) return "Faixa do Deezer";
    if (/\/album\//.test(url)) return "Álbum do Deezer";
    if (/\/playlist\//.test(url)) return "Playlist do Deezer";
    if (/\/artist\//.test(url)) return "Discografia do Deezer";
    return "Download do Deezer";
  }

  async function enqueueDeezer(url) {
    if (!deezerDeps.python_path || !deezerDeps.deezload_path) {
      showDownloaderStatus(
        "Erro: deezload não instalado. Vá em Ajustes e instale o componente.",
        "error"
      );
      return;
    }
    if (!deezerArlInput.value.trim()) {
      showDownloaderStatus(
        "Erro: configure o cookie ARL do Deezer em Ajustes › Conta Deezer.",
        "error"
      );
      return;
    }

    const item = {
      id: (crypto.randomUUID && crypto.randomUUID()) || `dz_${Date.now()}_${Math.random()}`,
      url,
      title: deezerTitleFromUrl(url),
      playlist_title: null,
      progress: 0.0,
      eta: "N/A",
      status: "QUEUED",
      status_detail: "Aguardando...",
      error_message: null,
    };

    try {
      await invoke("add_to_queue", { items: [item] });
      showDownloaderStatus("Adicionado à fila.", "success");
      urlInput.value = "";
    } catch (e) {
      console.error(e);
      showDownloaderStatus(`Erro: ${e}`, "error");
    }
  }

  btnEnqueue.addEventListener("click", async () => {
    const url = urlInput.value.trim();
    if (!url) return;

    if (appMode === "deezer") {
      await enqueueDeezer(url);
      return;
    }

    // Guard: Check if dependencies are ready
    if (!systemDeps.yt_dlp_path || !systemDeps.ffmpeg_path) {
      showDownloaderStatus("Erro: Componentes em falta. Por favor, vá em Ajustes e instale-os.", "error");
      return;
    }

    // Update UI to loading state
    urlInput.disabled = true;
    btnEnqueue.disabled = true;
    showDownloaderStatus("Analisando link...", "loading");

    try {
      // Fetch metadata from yt-dlp
      const info = await invoke("fetch_video_info", { url });
      
      const newItems = [];
      const isPlaylist = info._type === "playlist";
      const playlistTitle = isPlaylist ? (info.title || info.fulltitle || "Playlist") : null;
      const isAlbum = isPlaylist && !!(info.id && info.id.startsWith("OLAK5uy_"));
      // Snapshot the selector so later toggles don't affect these items.
      const itemMediaType = mediaType;
      const fallbackTitle = itemMediaType === "video" ? "Vídeo" : "Música";
      // Keep queue ids unique when the same video is queued as audio and video.
      const queueId = (id) => (itemMediaType === "video" ? `${id}:video` : id);

      if (isPlaylist && info.entries) {
        info.entries.forEach((entry, index) => {
          if (entry && entry.id) {
            newItems.push({
              id: queueId(entry.id),
              url: entry.url || `https://www.youtube.com/watch?v=${entry.id}`,
              title: entry.title || `${fallbackTitle} ${index + 1}`,
              playlist_title: playlistTitle,
              track_number: index + 1,
              is_album: isAlbum,
              media_type: itemMediaType,
              progress: 0.0,
              eta: "N/A",
              status: "QUEUED",
              status_detail: "Aguardando...",
              error_message: null
            });
          }
        });
      } else {
        const id = info.id || "single_video";
        const title = info.title || info.fulltitle || fallbackTitle;
        newItems.push({
          id: queueId(id),
          url: info.webpage_url || url,
          title: title,
          playlist_title: null,
          is_album: false,
          media_type: itemMediaType,
          progress: 0.0,
          eta: "N/A",
          status: "QUEUED",
          status_detail: "Aguardando...",
          error_message: null
        });
      }

      if (newItems.length > 0) {
        await invoke("add_to_queue", { items: newItems });
        showDownloaderStatus(`Adicionado à fila: ${newItems.length} item(ns).`, "success");
        urlInput.value = "";
      } else {
        showDownloaderStatus("Erro: Nenhum vídeo extraído do link.", "error");
      }
    } catch (e) {
      console.error(e);
      showDownloaderStatus(`Erro: ${e}`, "error");
    } finally {
      urlInput.disabled = false;
      btnEnqueue.disabled = urlInput.value.trim() === "";
    }
  });

  function showDownloaderStatus(message, type = "info") {
    downloaderStatusBox.classList.remove("hidden", "success", "error", "loading");
    downloaderStatusText.textContent = message;
    downloaderStatusBox.classList.remove("hidden");
    
    const indicator = downloaderStatusBox.querySelector(".status-indicator");
    indicator.className = "status-indicator";

    if (type === "error") {
      indicator.style.backgroundColor = "var(--status-danger)";
    } else if (type === "success") {
      indicator.style.backgroundColor = "var(--status-success)";
    } else if (type === "loading") {
      indicator.style.backgroundColor = "var(--status-info)";
      indicator.classList.add("animate-pulse");
    } else {
      indicator.style.backgroundColor = "var(--accent-color)";
    }
  }

  // ==========================================
  // 3. Queue Manager & Events
  // ==========================================
  listen("queue-updated", (event) => {
    const queue = event.payload || [];
    renderQueue(queue);
  });

  btnClearCompleted.addEventListener("click", async () => {
    try {
      await invoke("clear_completed");
    } catch (e) {
      console.error(e);
    }
  });

  function renderQueue(queue) {
    queueListContainer.innerHTML = "";
    
    const activeItems = queue.filter(item => 
      item.status !== "COMPLETED" && item.status !== "FAILED"
    );
    
    // Update badge count
    if (activeItems.length > 0) {
      queueBadge.textContent = activeItems.length;
      queueBadge.classList.remove("hidden");
    } else {
      queueBadge.classList.add("hidden");
    }

    queueCountText.textContent = queue.length > 0 ? queue.length : "Nenhum";

    if (queue.length === 0) {
      queueEmptyState.classList.remove("hidden");
      btnClearCompleted.classList.add("hidden");
      return;
    }

    queueEmptyState.classList.add("hidden");
    
    // Show clear completed if there are done/failed items
    const hasCompletedOrFailed = queue.some(item => 
      item.status === "COMPLETED" || item.status === "FAILED"
    );
    if (hasCompletedOrFailed) {
      btnClearCompleted.classList.remove("hidden");
    } else {
      btnClearCompleted.classList.add("hidden");
    }

    queue.forEach(item => {
      const card = document.createElement("div");
      card.className = "queue-card";
      
      const isDownloading = item.status === "DOWNLOADING";
      const isConverting = item.status === "CONVERTING";
      const isFailed = item.status === "FAILED";
      const isCompleted = item.status === "COMPLETED";
      const isVideo = item.media_type === "video";

      let statusLabel = "Aguardando";
      let badgeClass = "queued";
      if (item.status === "FETCHING_INFO") {
        statusLabel = "Inicializando...";
        badgeClass = "fetching_info";
      } else if (isDownloading) {
        statusLabel = `Baixando (${Math.round(item.progress * 100)}%)`;
        badgeClass = "downloading";
      } else if (isConverting) {
        statusLabel = isVideo ? "Mesclando..." : "Convertendo...";
        badgeClass = "converting";
      } else if (isCompleted) {
        statusLabel = "Concluído";
        badgeClass = "completed";
      } else if (isFailed) {
        statusLabel = "Falhou";
        badgeClass = "failed";
      }

      const playlistTag = item.playlist_title 
        ? `<div class="queue-item-playlist">Playlist: ${item.playlist_title}</div>` 
        : "";

      const actionButtons = isFailed
        ? `<button class="action-link retry" data-id="${item.id}">Repetir</button>
           <button class="action-link cancel" data-id="${item.id}">Remover</button>`
        : `<button class="action-link cancel" data-id="${item.id}">Remover</button>`;

      const detailsText = isDownloading && item.eta !== "N/A"
        ? `<span>${item.status_detail || "Baixando..."}</span><span>Restante: ${item.eta}</span>`
        : isFailed && item.error_message
        ? `<span style="color: var(--status-danger)">${item.error_message}</span>`
        : `<span>${item.status_detail || ""}</span>`;

      const progressBarHtml = isDownloading || isConverting
        ? `<div class="progress-bar-container">
             <div class="progress-bar-fill ${isConverting ? 'converting' : ''}" style="width: ${item.progress * 100}%"></div>
           </div>`
        : "";

      card.innerHTML = `
        <div class="queue-card-header">
          <div class="queue-card-info">
            <h3 class="queue-item-title" title="${item.title}">${item.title}${isVideo ? '<span class="queue-item-media">Vídeo</span>' : ""}</h3>
            ${playlistTag}
          </div>
          <div class="queue-card-actions">
            <span class="status-badge ${badgeClass}">${statusLabel}</span>
            ${actionButtons}
          </div>
        </div>
        
        <div class="queue-card-details">
          ${detailsText}
        </div>
        ${progressBarHtml}
      `;

      // Event Listeners for actions
      card.querySelector(".cancel").addEventListener("click", async () => {
        try {
          await invoke("remove_item", { id: item.id });
        } catch (e) {
          console.error(e);
        }
      });

      if (isFailed) {
        card.querySelector(".retry").addEventListener("click", async () => {
          try {
            await invoke("retry_item", { id: item.id });
          } catch (e) {
            console.error(e);
          }
        });
      }

      queueListContainer.appendChild(card);
    });
  }

  // Auto-retry switch toggle listener
  autoRetrySwitch.addEventListener("change", async () => {
    const enabled = autoRetrySwitch.checked;
    try {
      await invoke("set_auto_retry", { enabled });
    } catch (e) {
      console.error("Falha ao salvar auto-retentativa:", e);
    }
  });

  // Organize-by-artist switch toggle listener
  organizeArtistSwitch.addEventListener("change", async () => {
    const enabled = organizeArtistSwitch.checked;
    try {
      await invoke("set_organize_by_artist", { enabled });
    } catch (e) {
      console.error("Falha ao salvar organização por artista:", e);
    }
  });

  // ==========================================
  // 4. Settings Logic
  // ==========================================
  
  // Concurrency slider
  maxConcurrentSlider.addEventListener("input", async () => {
    const limit = parseInt(maxConcurrentSlider.value);
    maxConcurrentVal.textContent = limit;
    try {
      await invoke("set_max_concurrent", { limit });
    } catch (e) {
      console.error(e);
    }
  });

  // Select Folder dialog
  btnSelectFolder.addEventListener("click", async () => {
    try {
      const selected = await invoke("select_folder", { target: "audio" });
      if (selected) {
        destFolderInput.value = selected;
      }
    } catch (e) {
      console.error(e);
    }
  });

  btnSelectVideoFolder.addEventListener("click", async () => {
    try {
      const selected = await invoke("select_folder", { target: "video" });
      if (selected) {
        videoFolderInput.value = selected;
      }
    } catch (e) {
      console.error(e);
    }
  });

  // ---- Deezer settings ----
  btnSaveArl.addEventListener("click", async () => {
    const arl = deezerArlInput.value.trim();
    try {
      await invoke("set_deezer_arl", { arl });
      deezerArlStatus.textContent = arl
        ? "Cookie ARL salvo neste computador."
        : "Cookie ARL removido.";
    } catch (e) {
      console.error(e);
      deezerArlStatus.textContent = `Erro ao salvar: ${e}`;
    }
  });

  qualityOptions.forEach((btn) => {
    btn.addEventListener("click", async () => {
      const quality = btn.dataset.q;
      try {
        await invoke("set_deezer_quality", { quality });
        qualityOptions.forEach((b) => b.classList.toggle("active", b === btn));
      } catch (e) {
        console.error("Falha ao salvar qualidade:", e);
      }
    });
  });

  async function checkDeezerDependencies() {
    try {
      const deps = await invoke("get_deezer_dependencies_status");
      deezerDeps = deps;

      updateDepBadge(statusPython, deps.python_path);
      updateDepBadge(statusDeezload, deps.deezload_path);

      const ready = deps.python_path && deps.deezload_path;
      btnInstallDeezer.disabled = ready;
      btnInstallDeezer.textContent = ready
        ? "deezload instalado"
        : deps.deezload_path
        ? "Concluir instalação"
        : "Instalar deezload";
    } catch (e) {
      console.error(e);
    }
  }

  btnInstallDeezer.addEventListener("click", async () => {
    btnInstallDeezer.disabled = true;
    dependencyStatusBox.classList.remove("hidden");
    dependencyStatusText.textContent = "Preparando instalação do deezload...";

    try {
      const deps = await invoke("install_deezer_dependencies");
      deezerDeps = deps;
      await checkDeezerDependencies();
      dependencyStatusText.textContent = "deezload instalado com sucesso!";
      setTimeout(() => dependencyStatusBox.classList.add("hidden"), 5000);
    } catch (e) {
      console.error(e);
      dependencyStatusText.textContent = `Erro na instalação: ${e}`;
      btnInstallDeezer.disabled = false;
    }
  });

  // Check initial dependencies and load settings
  async function initSettings() {
    try {
      const path = await invoke("get_destination_folder");
      destFolderInput.value = path;
      videoFolderInput.value = await invoke("get_video_folder");
      applyMediaType(await invoke("get_media_type"));

      const autoRetry = await invoke("get_auto_retry");
      autoRetrySwitch.checked = autoRetry;

      const organizeByArtist = await invoke("get_organize_by_artist");
      organizeArtistSwitch.checked = organizeByArtist;

      const deezerSettings = await invoke("get_deezer_settings");
      deezerArlInput.value = deezerSettings.arl || "";
      qualityOptions.forEach((b) =>
        b.classList.toggle("active", b.dataset.q === deezerSettings.quality)
      );

      const mode = await invoke("get_app_mode");
      applyMode(mode);

      await checkDependencies();
    } catch (e) {
      console.error(e);
    }
  }

  async function checkDependencies() {
    try {
      const deps = await invoke("get_dependencies_status");
      systemDeps = deps;
      
      updateDepBadge(statusYtdl, deps.yt_dlp_path);
      updateDepBadge(statusFfmpeg, deps.ffmpeg_path);

      if (!deps.yt_dlp_path || !deps.ffmpeg_path) {
        btnInstallDeps.disabled = false;
        btnInstallDeps.textContent = "Instalar Componentes";
      } else {
        btnInstallDeps.disabled = true;
        btnInstallDeps.textContent = "Componentes Instalados";
      }
    } catch (e) {
      console.error(e);
    }
  }

  function updateDepBadge(element, path) {
    element.className = "status-badge";
    if (path) {
      element.classList.add("active");
      element.textContent = "Ativo";
    } else {
      element.classList.add("missing");
      element.textContent = "Ausente";
    }
  }

  // Install dependency handler
  btnInstallDeps.addEventListener("click", async () => {
    btnInstallDeps.disabled = true;
    dependencyStatusBox.classList.remove("hidden");
    dependencyStatusText.textContent = "Iniciando instalação de dependências...";
    
    try {
      const deps = await invoke("install_dependencies");
      systemDeps = deps;
      await checkDependencies();
      dependencyStatusText.textContent = "Instalação concluída com sucesso!";
      setTimeout(() => {
        dependencyStatusBox.classList.add("hidden");
      }, 5000);
    } catch (e) {
      console.error(e);
      dependencyStatusText.textContent = `Erro na instalação: ${e}`;
      btnInstallDeps.disabled = false;
    }
  });

  // Listen for dependency install progress
  listen("dep-progress", (event) => {
    const data = event.payload;
    if (data) {
      dependencyStatusBox.classList.remove("hidden");
      dependencyStatusText.textContent = `${data.status}`;
    }
  });

  // Startup Init
  initSettings();
});

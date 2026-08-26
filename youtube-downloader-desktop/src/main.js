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
  
  const statusYtdl = document.getElementById("status-ytdl");
  const statusFfmpeg = document.getElementById("status-ffmpeg");
  const btnInstallDeps = document.getElementById("btn-install-deps");
  const dependencyStatusBox = document.getElementById("dependency-status-box");
  const dependencyStatusText = document.getElementById("dependency-status-text");

  let systemDeps = { yt_dlp_path: "", ffmpeg_path: "" };

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
  // 2. Downloader URL Enqueuing
  // ==========================================
  urlInput.addEventListener("input", () => {
    btnEnqueue.disabled = urlInput.value.trim() === "";
  });

  btnEnqueue.addEventListener("click", async () => {
    const url = urlInput.value.trim();
    if (!url) return;

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

      if (isPlaylist && info.entries) {
        info.entries.forEach((entry, index) => {
          if (entry && entry.id) {
            newItems.push({
              id: entry.id,
              url: entry.url || `https://www.youtube.com/watch?v=${entry.id}`,
              title: entry.title || `Música ${index + 1}`,
              playlist_title: playlistTitle,
              track_number: index + 1,
              is_album: isAlbum,
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
        const title = info.title || info.fulltitle || "Música";
        newItems.push({
          id: id,
          url: info.webpage_url || url,
          title: title,
          playlist_title: null,
          is_album: false,
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

      let statusLabel = "Aguardando";
      let badgeClass = "queued";
      if (item.status === "FETCHING_INFO") {
        statusLabel = "Inicializando...";
        badgeClass = "fetching_info";
      } else if (isDownloading) {
        statusLabel = `Baixando (${Math.round(item.progress * 100)}%)`;
        badgeClass = "downloading";
      } else if (isConverting) {
        statusLabel = "Convertendo...";
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
            <h3 class="queue-item-title" title="${item.title}">${item.title}</h3>
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
      const selected = await invoke("select_folder");
      if (selected) {
        destFolderInput.value = selected;
      }
    } catch (e) {
      console.error(e);
    }
  });

  // Check initial dependencies and load settings
  async function initSettings() {
    try {
      const path = await invoke("get_destination_folder");
      destFolderInput.value = path;

      const autoRetry = await invoke("get_auto_retry");
      autoRetrySwitch.checked = autoRetry;
      
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

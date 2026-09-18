/**
 * Manga Panel Search App
 * Pure Material 3 Dark Theme & 5 Color Presets
 * (Titanium Slate, Material Lavender, Nordic Emerald, Amber Bronze, Sakura Rose)
 */

document.addEventListener('DOMContentLoaded', () => {
  // --- 1. Immediate Electron Window Controls Setup (Highest Priority) ---
  function initElectronTitlebar() {
    if (!window.electronAPI) return;
    document.body.classList.add('is-electron');

    const btnMinimize = document.getElementById('btn-minimize');
    const btnMaximize = document.getElementById('btn-maximize');
    const btnClose = document.getElementById('btn-close');
    const iconMaximize = document.getElementById('icon-maximize');
    const iconRestore = document.getElementById('icon-restore');
    const dragRegion = document.querySelector('.titlebar-drag-region');

    if (btnMinimize) {
      btnMinimize.addEventListener('click', () => {
        window.electronAPI.minimize();
      });
    }

    if (btnMaximize) {
      btnMaximize.addEventListener('click', () => {
        window.electronAPI.toggleMaximize();
      });
    }

    if (dragRegion) {
      dragRegion.addEventListener('dblclick', () => {
        window.electronAPI.toggleMaximize();
      });
    }

    if (btnClose) {
      btnClose.addEventListener('click', () => {
        window.electronAPI.close();
      });
    }

    if (window.electronAPI.onMaximizeChange) {
      window.electronAPI.onMaximizeChange((isMaximized) => {
        if (isMaximized) {
          if (iconMaximize) iconMaximize.style.display = 'none';
          if (iconRestore) iconRestore.style.display = 'inline-block';
          if (btnMaximize) btnMaximize.title = '元のサイズに戻す';
        } else {
          if (iconMaximize) iconMaximize.style.display = 'inline-block';
          if (iconRestore) iconRestore.style.display = 'none';
          if (btnMaximize) btnMaximize.title = '最大化';
        }
      });
    }
  }

  try {
    initElectronTitlebar();
  } catch (err) {
    console.error('Failed to init Electron titlebar:', err);
  }

  // State
  let currentFile = null;
  let minScore = 0.20;
  let indexPollInterval = null;

  // --- Theme Preset Management ---
  const THEME_STORAGE_KEY = 'manga_search_m3_theme';
  const headerThemeSelect = document.getElementById('header-theme-select');
  const presetCards = document.querySelectorAll('.m3-preset-card');

  function applyTheme(themeId) {
    document.documentElement.setAttribute('data-theme', themeId);
    localStorage.setItem(THEME_STORAGE_KEY, themeId);

    if (headerThemeSelect) {
      headerThemeSelect.value = themeId;
    }

    presetCards.forEach(card => {
      if (card.getAttribute('data-preset') === themeId) {
        card.classList.add('active');
      } else {
        card.classList.remove('active');
      }
    });
  }

  // Initial Theme Load
  const savedTheme = localStorage.getItem(THEME_STORAGE_KEY) || 'titanium_slate';
  applyTheme(savedTheme);

  // Header Dropdown Change
  if (headerThemeSelect) {
    headerThemeSelect.addEventListener('change', (e) => {
      applyTheme(e.target.value);
    });
  }

  // Settings Tab Preset Cards Click
  presetCards.forEach(card => {
    card.addEventListener('click', () => {
      const presetId = card.getAttribute('data-preset');
      if (presetId) {
        applyTheme(presetId);
      }
    });
  });

  // --- Navigation ---
  const tabButtons = document.querySelectorAll('.m3-tab-btn');
  const pageViews = document.querySelectorAll('.m3-page-view');

  tabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      tabButtons.forEach(b => b.classList.remove('active'));
      pageViews.forEach(v => v.classList.remove('active'));

      btn.classList.add('active');
      const targetId = btn.getAttribute('data-target');
      const targetView = document.getElementById(targetId);
      targetView.classList.add('active');

      if (targetId === 'tab-library') {
        loadLibraryData();
      } else if (targetId === 'tab-settings') {
        loadTargetPaths();
      }
    });
  });

  // --- DOM Elements - Search ---
  const dropzone = document.getElementById('dropzone');
  const fileInput = document.getElementById('file-input');
  const btnSelectFile = document.getElementById('btn-select-file');
  const searchProgress = document.getElementById('search-progress');
  const searchProgressWrapper = document.getElementById('search-progress-wrapper');
  const searchProgressMsg = document.getElementById('search-progress-msg');
  const searchProgressPercent = document.getElementById('search-progress-percent');
  const searchProgressBar = document.getElementById('search-progress-bar');
  const resultsContainer = document.getElementById('results-container');
  const resultsList = document.getElementById('results-list');
  const resultsCountBadge = document.getElementById('results-count-badge');
  const queryPreviewImg = document.getElementById('query-preview-img');
  const queryMeta = document.getElementById('query-meta');

  // DOM Elements - Library & Status
  const globalStatusBadge = document.getElementById('global-status-badge');
  const libraryDirLabel = document.getElementById('library-dir-label');
  const archivesTbody = document.getElementById('archives-tbody');
  const btnRescan = document.getElementById('btn-rescan');
  const btnStartIndex = document.getElementById('btn-start-index');
  const indexProgressContainer = document.getElementById('index-progress-container');
  const indexProgressBar = document.getElementById('index-progress-bar');
  const indexStatusText = document.getElementById('index-status-text');
  const indexPercentText = document.getElementById('index-percent-text');

  // DOM Elements - Search Scope Banner
  const searchScopeBanner = document.getElementById('search-scope-banner');
  const searchScopeTitle = document.getElementById('search-scope-title');
  const searchScopeSub = document.getElementById('search-scope-sub');
  const btnGotoLibrary = document.getElementById('btn-goto-library');

  // DOM Elements - Folder Groups & Selection
  const librarySelectionSummary = document.getElementById('library-selection-summary');
  const btnCreateFolder = document.getElementById('btn-create-folder');
  const btnSelectAll = document.getElementById('btn-select-all');
  const btnDeselectAll = document.getElementById('btn-deselect-all');
  const libraryGroupsContainer = document.getElementById('library-groups-container');

  // DOM Elements - Create Folder Modal
  const modalCreateFolder = document.getElementById('modal-create-folder');
  const inputFolderName = document.getElementById('input-folder-name');
  const btnCancelFolder = document.getElementById('btn-cancel-folder');
  const btnConfirmCreateFolder = document.getElementById('btn-confirm-create-folder');

  // DOM Elements - Settings & Paths
  const targetPathsList = document.getElementById('target-paths-list');
  const inputNewPath = document.getElementById('input-new-path');
  const btnAddPath = document.getElementById('btn-add-path');
  const settingMinScore = document.getElementById('setting-min-score');
  const labelMinScore = document.getElementById('label-min-score');
  const checkEnableGpuDml = document.getElementById('check-enable-gpu-dml');
  if (checkEnableGpuDml) {
    const savedDml = localStorage.getItem('manga_use_gpu_dml');
    if (savedDml !== null) {
      checkEnableGpuDml.checked = (savedDml === 'true');
    }
    checkEnableGpuDml.addEventListener('change', (e) => {
      localStorage.setItem('manga_use_gpu_dml', e.target.checked);
    });
  }

  const checkEnableCoarseClip = document.getElementById('check-enable-coarse-clip');
  if (checkEnableCoarseClip) {
    const savedClip = localStorage.getItem('manga_use_coarse_clip');
    if (savedClip !== null) {
      checkEnableCoarseClip.checked = (savedClip === 'true');
    }
    checkEnableCoarseClip.addEventListener('change', (e) => {
      localStorage.setItem('manga_use_coarse_clip', e.target.checked);
    });
  }

  // --- File Upload & Drag and Drop ---
  btnSelectFile.addEventListener('click', (e) => {
    e.stopPropagation();
    fileInput.click();
  });

  dropzone.addEventListener('click', () => {
    fileInput.click();
  });

  fileInput.addEventListener('change', (e) => {
    if (e.target.files && e.target.files[0]) {
      handleFile(e.target.files[0]);
    }
  });

  dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('dragover');
  });

  dropzone.addEventListener('dragleave', () => {
    dropzone.classList.remove('dragover');
  });

  dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      handleFile(e.dataTransfer.files[0]);
    }
  });

  // Clipboard Paste Support (Ctrl+V)
  window.addEventListener('paste', (e) => {
    const items = (e.clipboardData || e.originalEvent.clipboardData).items;
    for (let item of items) {
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const file = item.getAsFile();
        handleFile(file);
        break;
      }
    }
  });

  function handleFile(file) {
    if (!file.type.startsWith('image/')) {
      alert('画像ファイル（PNG, JPG, WEBPなど）を選択してください。');
      return;
    }
    currentFile = file;

    // Show preview
    const reader = new FileReader();
    reader.onload = (e) => {
      queryPreviewImg.src = e.target.result;
      queryPreviewImg.onload = () => {
        queryMeta.innerHTML = `
          <strong>ファイル名:</strong> ${file.name}<br>
          <strong>解像度:</strong> ${queryPreviewImg.naturalWidth} &times; ${queryPreviewImg.naturalHeight} px<br>
          <strong>ファイルサイズ:</strong> ${(file.size / 1024).toFixed(1)} KB
        `;
      };
      // Trigger search automatically
      performSearch(file);
    };
    reader.readAsDataURL(file);
  }

  // --- Perform Search API with Real-time Progress Streaming ---
  async function performSearch(file) {
    let progressAnimId = null;
    try {
      resultsContainer.style.display = 'block';

      // 1. Render in-card loading view with rotating spinner and dedicated high-contrast progress bar
      resultsList.innerHTML = `
        <div class="m3-card" style="padding: 32px 24px; text-align: center; border-color: var(--md-sys-color-primary-container);">
          <span class="material-symbols-outlined m3-spin" style="font-size: 46px; color: var(--md-sys-color-primary);">sync</span>
          <p id="in-results-title" style="margin-top: 14px; font-weight: 800; font-size: 1.1rem; color: var(--md-sys-color-on-surface);">
            漫画ライブラリと局所特徴量を照合中...
          </p>
          <div style="max-width: 540px; margin: 20px auto 6px auto; background: var(--md-sys-color-surface-container-high); padding: 18px 22px; border-radius: var(--md-sys-shape-corner-medium); border: 1px solid var(--md-sys-color-outline-variant);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
              <span id="in-results-stage-msg" style="font-size: 0.92rem; font-weight: 700; color: var(--md-sys-color-primary); display: flex; align-items: center; gap: 6px;">
                <span class="material-symbols-outlined" style="font-size: 20px;">manage_search</span>
                特徴量を準備中...
              </span>
              <span id="in-results-percent-badge" class="m3-badge-status" style="font-size: 0.88rem; padding: 3px 14px; font-weight: 800;">
                0.0%
              </span>
            </div>
            <div class="m3-progress-track">
              <div class="m3-progress-fill" id="in-results-progress-bar" style="width: 2%;"></div>
            </div>
            <div id="in-results-detail-msg" style="margin-top: 10px; font-size: 0.8rem; color: var(--md-sys-color-secondary); text-align: left;">
              全ページを対象に高速照合を実行しています
            </div>
          </div>
        </div>
      `;

      // Smooth progress animation state
      let currentDisplayPercent = 0.0;
      let targetPercent = 0.0;
      let targetCurrentCount = 0;
      let targetTotalCount = 0;

      const inResultsProgressBar = document.getElementById('in-results-progress-bar');
      const inResultsPercentBadge = document.getElementById('in-results-percent-badge');
      const inResultsStageMsg = document.getElementById('in-results-stage-msg');
      const inResultsDetailMsg = document.getElementById('in-results-detail-msg');

      function updateProgressUI(pct, cur, tot) {
        const pStr = `${pct.toFixed(1)}%`;
        if (inResultsProgressBar) inResultsProgressBar.style.width = `${Math.max(2.0, pct)}%`;
        if (inResultsPercentBadge) inResultsPercentBadge.textContent = pStr;

        if (inResultsDetailMsg && tot > 0) {
          if (pct < 90.0) {
            inResultsDetailMsg.textContent = `Stage 1: 全件行列積照合中 (${cur.toLocaleString()} / ${tot.toLocaleString()} ページ)`;
          } else {
            inResultsDetailMsg.textContent = `Stage 2: 上位候補の精密幾何検証中 (RANSAC)...`;
          }
        }
      }

      function animationTick() {
        if (currentDisplayPercent < targetPercent) {
          const diff = targetPercent - currentDisplayPercent;
          const stepSize = Math.max(0.04, diff * 0.14);
          currentDisplayPercent = Math.min(targetPercent, currentDisplayPercent + stepSize);
          const currentCount = Math.round((currentDisplayPercent / 100) * (targetTotalCount || 1));
          updateProgressUI(currentDisplayPercent, currentCount, targetTotalCount);
        }
        progressAnimId = requestAnimationFrame(animationTick);
      }
      progressAnimId = requestAnimationFrame(animationTick);

      const checkNormalizePhoto = document.getElementById('check-normalize-photo');
      const normalizePhoto = checkNormalizePhoto ? checkNormalizePhoto.checked : true;

      const sliderMinPanelArea = document.getElementById('slider-min-panel-area');
      const minPanelArea = sliderMinPanelArea ? parseFloat(sliderMinPanelArea.value) : 1.0;
      const enableGpuDml = checkEnableGpuDml ? checkEnableGpuDml.checked : false;
      const enableCoarseClip = checkEnableCoarseClip ? checkEnableCoarseClip.checked : true;

      const formData = new FormData();
      formData.append('file', file);
      formData.append('min_score', minScore);
      formData.append('top_k', 5);
      formData.append('normalize_photo', normalizePhoto);
      formData.append('min_panel_area_percent', minPanelArea);
      formData.append('enable_gpu_dml', enableGpuDml);
      formData.append('enable_coarse_clip', enableCoarseClip);

      let finalData = null;

      try {
        // 1. Try Streaming Search for Real-Time Progress
        const resp = await fetch('/api/search_stream', {
          method: 'POST',
          body: formData
        });

        if (!resp.ok) {
          throw new Error(`Streaming failed with status ${resp.status}`);
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop();

          for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed) continue;
            try {
              const ev = JSON.parse(trimmed);
              if (ev.type === 'progress') {
                targetPercent = Math.max(targetPercent, ev.percent || 0);
                targetCurrentCount = ev.current || 0;
                targetTotalCount = ev.total || 0;

                if (inResultsStageMsg) {
                  if (ev.percent < 90.0) {
                    inResultsStageMsg.innerHTML = '<span class="material-symbols-outlined" style="font-size: 20px;">bolt</span> Stage 1: 全件行列積照合中';
                  } else {
                    inResultsStageMsg.innerHTML = '<span class="material-symbols-outlined" style="font-size: 20px;">verified</span> Stage 2: 精密幾何検証中';
                  }
                }
                if (searchProgressMsg) {
                  searchProgressMsg.textContent = `照合中... ${ev.percent.toFixed(1)}% (${ev.current.toLocaleString()} / ${ev.total.toLocaleString()} ページ)`;
                }
              } else if (ev.type === 'result') {
                finalData = ev;
              } else if (ev.type === 'error') {
                throw new Error(ev.message || '検索処理でエラーが発生しました。');
              }
            } catch (pe) {
              console.warn('NDJSON parse warning:', pe);
            }
          }
        }

        if (buffer.trim()) {
          try {
            const ev = JSON.parse(buffer.trim());
            if (ev.type === 'result') finalData = ev;
          } catch (e) {}
        }
      } catch (streamErr) {
        console.warn('Streaming search failed or interrupted, falling back to sync search:', streamErr);
        if (searchProgressMsg) searchProgressMsg.textContent = '照合中（一括処理）...';
        try {
          const syncResp = await fetch('/api/search', {
            method: 'POST',
            body: formData
          });
          finalData = await syncResp.json();
        } catch (syncErr) {
          if (progressAnimId) cancelAnimationFrame(progressAnimId);
          resultsList.innerHTML = `
            <div class="m3-card" style="border-color: var(--md-sys-color-error);">
              <p style="color: var(--md-sys-color-error); font-weight: 800;">エラーが発生しました: ${syncErr.message}</p>
            </div>
          `;
          return;
        }
      }

      if (progressAnimId) cancelAnimationFrame(progressAnimId);
      updateProgressUI(100.0, targetTotalCount, targetTotalCount);

    if (!finalData || !finalData.success) {
      resultsList.innerHTML = `
        <div class="m3-card" style="border-color: var(--md-sys-color-error);">
          <p style="color: var(--md-sys-color-error); font-weight: 800;">${(finalData && finalData.message) || '検索に失敗しました。'}</p>
        </div>
      `;
      resultsCountBadge.textContent = '0 件';
      return;
    }

    renderSearchResults(finalData.results);
    } catch (err) {
      if (progressAnimId) cancelAnimationFrame(progressAnimId);
      resultsList.innerHTML = `
        <div class="m3-card" style="border-color: var(--md-sys-color-error);">
          <p style="color: var(--md-sys-color-error); font-weight: 800;">エラーが発生しました: ${err.message}</p>
        </div>
      `;
      resultsCountBadge.textContent = '0 件';
    }
  }

  // --- Clear Search Results & Query State ---
  function clearSearchResults() {
    currentFile = null;
    if (fileInput) fileInput.value = '';
    if (resultsContainer) resultsContainer.style.display = 'none';
    if (resultsList) resultsList.innerHTML = '';
    if (queryPreviewImg) queryPreviewImg.src = '';
    if (queryMeta) queryMeta.innerHTML = '';
    if (resultsCountBadge) resultsCountBadge.textContent = '0 件検出';
    if (searchProgressWrapper) {
      searchProgressWrapper.style.display = 'none';
      if (searchProgressBar) searchProgressBar.style.width = '0%';
      if (searchProgressPercent) searchProgressPercent.textContent = '0%';
    }
  }

  const btnClearResults = document.getElementById('btn-clear-results');
  if (btnClearResults) {
    btnClearResults.addEventListener('click', (e) => {
      e.stopPropagation();
      clearSearchResults();
    });
  }

  const btnClearQuery = document.getElementById('btn-clear-query');
  if (btnClearQuery) {
    btnClearQuery.addEventListener('click', (e) => {
      e.stopPropagation();
      clearSearchResults();
    });
  }

  // --- Render Search Results with M3 Highlight Box ---
  function renderSearchResults(results) {
    resultsCountBadge.textContent = `${results.length} 件検出`;

    if (results.length === 0) {
      resultsList.innerHTML = `
        <div class="m3-card">
          <p style="font-size: 1.15rem; font-weight: 800; color: var(--md-sys-color-on-surface);">
            該当するコマが見つかりませんでした。
          </p>
          <p style="font-size: 0.88rem; font-weight: 500; color: var(--md-sys-color-secondary); margin-top: 8px; line-height: 1.6;">
            ・探している漫画が「登録ライブラリ」に含まれているか確認してください。<br>
            ・「設定」タブから許容最小スコア（Min Score）を少し下げて再試行してみてください。
          </p>
        </div>
      `;
      return;
    }

    resultsList.innerHTML = '';

    results.forEach((res, index) => {
      const matchPercent = (res.score * 100).toFixed(1);
      const card = document.createElement('div');
      card.className = 'm3-result-item';

      card.innerHTML = `
        <div class="m3-result-header">
          <div>
            <span class="m3-result-title">${res.archive_name}</span>
            <div style="font-size: 0.98rem; font-weight: 800; color: var(--md-sys-color-primary); margin-top: 6px; font-family: var(--m3-font-brand);">
              ${res.display_page_label || `PAGE ${res.page_number}`} <span style="color: var(--md-sys-color-secondary); font-size: 0.82rem; font-weight: 700; margin-left: 4px;">(${res.page_filename})</span>
            </div>
          </div>
          <div style="display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
            <span class="m3-result-badge">
              <span class="material-symbols-outlined" style="font-size: 18px;">verified</span>
              ${matchPercent}% 一致
            </span>
            <span style="font-size: 0.85rem; font-weight: 800; color: var(--md-sys-color-secondary); font-family: var(--m3-font-brand);">
              ${res.inliers_count} INLIERS
            </span>
          </div>
        </div>

        <div class="m3-viewer-wrapper">
          <div class="m3-page-display" id="page-display-${index}">
            <img src="${res.page_image_url}" alt="Matched Page ${res.page_number}" id="page-img-${index}">
            <div class="m3-highlight-box" id="highlight-${index}"></div>
          </div>
        </div>
      `;

      resultsList.appendChild(card);

      // Calculate position of highlight box after image loads
      const img = document.getElementById(`page-img-${index}`);
      const highlight = document.getElementById(`highlight-${index}`);

      function updateHighlight() {
        const naturalW = img.naturalWidth;
        const naturalH = img.naturalHeight;
        const displayW = img.clientWidth;
        const displayH = img.clientHeight;

        if (!naturalW || !displayW) return;

        const scaleX = displayW / naturalW;
        const scaleY = displayH / naturalH;

        const bbox = res.bounding_box;
        const left = bbox.x * scaleX;
        const top = bbox.y * scaleY;
        const width = bbox.w * scaleX;
        const height = bbox.h * scaleY;

        highlight.style.left = `${left}px`;
        highlight.style.top = `${top}px`;
        highlight.style.width = `${width}px`;
        highlight.style.height = `${height}px`;
      }

      if (img.complete) {
        updateHighlight();
      } else {
        img.onload = updateHighlight;
      }

      window.addEventListener('resize', updateHighlight);
    });
  }

  // --- Library Group Management & Search Filtering ---
  let cachedArchiveMetadata = {};
  let currentGroupStructure = null;

  // Folder Accordion State Persistence (remembers which folders are opened or closed)
  const STORAGE_KEY_EXPANDED_GROUPS = 'manga_expanded_group_ids';
  let expandedGroupIds = new Set();
  try {
    const saved = localStorage.getItem(STORAGE_KEY_EXPANDED_GROUPS);
    if (saved) {
      expandedGroupIds = new Set(JSON.parse(saved));
    }
  } catch (e) {
    console.warn('Failed to parse expanded groups from localStorage:', e);
  }

  function saveExpandedGroupState() {
    try {
      localStorage.setItem(STORAGE_KEY_EXPANDED_GROUPS, JSON.stringify(Array.from(expandedGroupIds)));
    } catch (e) {
      console.warn('Failed to save expanded groups:', e);
    }
  }

  async function loadLibraryData() {
    await loadGroupsAndLibraryData();
  }

  async function loadGroupsAndLibraryData() {
    try {
      // 1. Fetch library archives summary for metadata
      try {
        const libResp = await fetch('/api/library');
        const libData = await libResp.json();
        const archives = (libData && libData.archives) || [];
        cachedArchiveMetadata = {};
        archives.forEach(a => {
          cachedArchiveMetadata[a.name] = a;
        });
        if (libraryDirLabel) {
          libraryDirLabel.textContent = `登録作品数: ${archives.length} 作品 / ディレクトリ: ${libData.data_dir || ''}`;
        }
      } catch (err) {
        console.warn('Failed to fetch library summary:', err);
      }

      // 2. Fetch folder groups structure
      const resp = await fetch('/api/groups');
      const data = await resp.json();
      currentGroupStructure = data.structure;
      renderGroups(data.structure, data.total_archives, data.enabled_archives);
      updateSearchScopeBanner(data.structure, data.total_archives, data.enabled_archives);
    } catch (err) {
      console.error('Failed to load library groups:', err);
      if (libraryGroupsContainer) {
        libraryGroupsContainer.innerHTML = `
          <div style="text-align: center; color: var(--md-sys-color-error); padding: 30px; font-weight: 700;">
            ライブラリグループの読み込みに失敗しました。
          </div>
        `;
      }
    }
  }

  function updateSearchScopeBanner(structure, total, enabled) {
    if (!searchScopeBanner) return;
    searchScopeBanner.style.display = 'flex';

    if (total === 0) {
      searchScopeBanner.style.display = 'none';
      return;
    }

    if (enabled === 0) {
      searchScopeBanner.style.backgroundColor = 'var(--md-sys-color-error-container)';
      searchScopeBanner.style.color = 'var(--md-sys-color-on-error-container)';
      searchScopeBanner.style.borderColor = 'var(--md-sys-color-error)';
      if (searchScopeTitle) searchScopeTitle.textContent = '⚠️ 検索対象の作品が選択されていません';
      if (searchScopeSub) searchScopeSub.textContent = '「作品・巻の選択を変更」から検索対象にチェックを入れてください。';
      return;
    }

    searchScopeBanner.style.backgroundColor = 'var(--md-sys-color-primary-container)';
    searchScopeBanner.style.color = 'var(--md-sys-color-on-primary-container)';
    searchScopeBanner.style.borderColor = 'var(--md-sys-color-primary)';

    if (enabled === total) {
      if (searchScopeTitle) searchScopeTitle.textContent = `🎯 検索対象: 全作品 (全 ${total} 巻)`;
      if (searchScopeSub) searchScopeSub.textContent = 'ライブラリ内のすべての作品・巻から網羅的に検索します。';
    } else {
      const enabledGroupNames = [];
      if (structure && structure.groups) {
        structure.groups.forEach(g => {
          const checkedCount = g.archives.filter(a => a.checked).length;
          if (checkedCount > 0) {
            enabledGroupNames.push(`${g.name} (${checkedCount}巻)`);
          }
        });
      }
      if (structure && structure.ungrouped && structure.ungrouped.archives) {
        const uCount = structure.ungrouped.archives.filter(a => a.checked).length;
        if (uCount > 0) {
          enabledGroupNames.push(`未分類 (${uCount}巻)`);
        }
      }
      const namesSummary = enabledGroupNames.join('、') || '選択された作品';
      if (searchScopeTitle) searchScopeTitle.textContent = `🎯 検索対象: ${namesSummary} (計 ${enabled} / ${total} 巻)`;
      if (searchScopeSub) searchScopeSub.textContent = '選択された作品に絞り込み、対象を限定して効率的かつ高精度に検索します。';
    }
  }

  function renderGroups(structure, total, enabled) {
    if (librarySelectionSummary) {
      librarySelectionSummary.textContent = `検索対象: ${enabled} / ${total} 巻が有効`;
    }

    if (!libraryGroupsContainer) return;
    libraryGroupsContainer.innerHTML = '';

    const allGroups = (structure && structure.groups) || [];
    const ungrouped = (structure && structure.ungrouped) || { archives: [] };

    if (allGroups.length === 0 && ungrouped.archives.length === 0) {
      libraryGroupsContainer.innerHTML = `
        <div style="text-align: center; padding: 36px; color: var(--md-sys-color-outline); font-weight: 700;">
          漫画アーカイブが見つかりません。「設定」タブから漫画フォルダまたはアーカイブファイル（.cbz, .rar等）を登録してください。
        </div>
      `;
      return;
    }

    const groupOptions = allGroups.map(g => ({ id: g.id, name: g.name }));

    allGroups.forEach(g => {
      const card = createGroupCard(g, groupOptions, false);
      libraryGroupsContainer.appendChild(card);
    });

    if (ungrouped.archives && ungrouped.archives.length > 0) {
      const uGroup = {
        id: '_ungrouped',
        name: '未分類のアーカイブ',
        enabled: ungrouped.enabled,
        is_indeterminate: ungrouped.is_indeterminate,
        count: ungrouped.count,
        archives: ungrouped.archives,
      };
      const card = createGroupCard(uGroup, groupOptions, true);
      libraryGroupsContainer.appendChild(card);
    }
  }

  function createGroupCard(group, allGroupOptions, isUngrouped) {
    const card = document.createElement('div');
    card.className = 'm3-group-card';
    card.dataset.groupId = group.id;

    // Group Header
    const header = document.createElement('div');
    header.className = 'm3-group-header';

    const headerLeft = document.createElement('div');
    headerLeft.className = 'm3-group-header-left';

    // Parent Checkbox
    const parentCheck = document.createElement('input');
    parentCheck.type = 'checkbox';
    parentCheck.className = 'm3-checkbox group-parent-checkbox';
    parentCheck.checked = !!group.enabled;
    parentCheck.indeterminate = !!group.is_indeterminate;
    parentCheck.title = 'フォルダ内の全巻を一括選択/解除';

    parentCheck.addEventListener('click', (e) => {
      e.stopPropagation();
    });
    parentCheck.addEventListener('change', async (e) => {
      e.stopPropagation();
      const newEnabled = parentCheck.checked;
      await toggleTarget(isUngrouped ? 'ungrouped' : 'group', isUngrouped ? null : group.id, newEnabled);
    });

    const folderIcon = document.createElement('span');
    folderIcon.className = 'material-symbols-outlined';
    folderIcon.style.color = 'var(--md-sys-color-primary)';
    folderIcon.textContent = isUngrouped ? 'folder_open' : 'folder';

    const titleText = document.createElement('span');
    titleText.className = 'm3-group-title';
    titleText.textContent = group.name;

    const countBadge = document.createElement('span');
    countBadge.className = 'm3-group-badge';
    countBadge.textContent = `${group.count} 巻`;

    headerLeft.appendChild(parentCheck);
    headerLeft.appendChild(folderIcon);
    headerLeft.appendChild(titleText);
    headerLeft.appendChild(countBadge);

    const headerRight = document.createElement('div');
    headerRight.className = 'm3-group-header-right';

    if (!isUngrouped) {
      // Rename button
      const btnRename = document.createElement('button');
      btnRename.className = 'm3-icon-btn';
      btnRename.title = 'フォルダ名を変更';
      btnRename.innerHTML = '<span class="material-symbols-outlined" style="font-size: 19px;">edit</span>';
      btnRename.addEventListener('click', async (e) => {
        e.stopPropagation();
        const newName = prompt('フォルダの新しい名前を入力してください:', group.name);
        if (newName && newName.trim() && newName.trim() !== group.name) {
          await renameGroup(group.id, newName.trim());
        }
      });
      headerRight.appendChild(btnRename);

      // Delete button
      const btnDelete = document.createElement('button');
      btnDelete.className = 'm3-icon-btn';
      btnDelete.title = 'フォルダを削除（中の漫画は未分類に移動します）';
      btnDelete.innerHTML = '<span class="material-symbols-outlined" style="font-size: 19px; color: var(--md-sys-color-error);">delete</span>';
      btnDelete.addEventListener('click', async (e) => {
        e.stopPropagation();
        if (confirm(`フォルダ「${group.name}」を削除しますか？\n（中のアーカイブファイルは削除されず、未分類へ移動します）`)) {
          await deleteGroup(group.id);
        }
      });
      headerRight.appendChild(btnDelete);
    }

    const isExpanded = expandedGroupIds.has(group.id);

    // Accordion Expand/Collapse Icon
    const expandIcon = document.createElement('span');
    expandIcon.className = 'material-symbols-outlined';
    expandIcon.textContent = isExpanded ? 'expand_less' : 'expand_more';
    headerRight.appendChild(expandIcon);

    header.appendChild(headerLeft);
    header.appendChild(headerRight);

    // Group Body (Accordion content)
    const body = document.createElement('div');
    body.className = `m3-group-body ${isExpanded ? 'expanded' : ''}`;

    const table = document.createElement('table');
    table.className = 'm3-group-table';

    table.innerHTML = `
      <thead>
        <tr style="background: var(--md-sys-color-surface-container-highest);">
          <th style="width: 44px; text-align: center;">選択</th>
          <th>アーカイブ / 巻名</th>
          <th style="width: 100px;">ページ数</th>
          <th style="width: 140px;">状態</th>
          <th style="width: 180px;">所属フォルダ</th>
        </tr>
      </thead>
      <tbody></tbody>
    `;

    const tbody = table.querySelector('tbody');

    group.archives.forEach(arc => {
      const tr = document.createElement('tr');
      const meta = cachedArchiveMetadata[arc.name] || {};
      const statusClass = meta.is_fully_indexed ? 'indexed' : 'pending';
      const statusIcon = meta.is_fully_indexed ? 'check_circle' : 'hourglass_top';
      const statusText = meta.is_fully_indexed ? 'キャッシュ済' : `${meta.cached_pages || 0}/${meta.page_count || 0} P`;

      // Build options for folder assignment dropdown
      let selectOptionsHtml = `<option value="">（未分類）</option>`;
      allGroupOptions.forEach(opt => {
        const isCurrent = (!isUngrouped && opt.id === group.id);
        selectOptionsHtml += `<option value="${opt.id}" ${isCurrent ? 'selected' : ''}>${opt.name}</option>`;
      });

      tr.innerHTML = `
        <td style="text-align: center;">
          <input type="checkbox" class="m3-checkbox child-archive-checkbox" data-archive-name="${arc.name}" ${arc.checked ? 'checked' : ''}>
        </td>
        <td>
          <strong style="color: var(--md-sys-color-on-surface);">${arc.name}</strong>
        </td>
        <td>
          <span style="font-family: var(--m3-font-brand); font-weight: 800;">${meta.page_count || '-'}</span> P
        </td>
        <td>
          <span class="m3-badge-status ${statusClass}" style="font-size: 0.78rem; padding: 2px 8px;">
            <span class="material-symbols-outlined" style="font-size: 14px;">${statusIcon}</span>
            ${statusText}
          </span>
        </td>
        <td>
          <select class="m3-move-select" data-archive-name="${arc.name}" 
                  style="width: 100%; padding: 4px 8px; border-radius: 4px; border: 1px solid var(--md-sys-color-outline-variant); background: var(--md-sys-color-surface-container-lowest); color: var(--md-sys-color-on-surface); font-size: 0.82rem; font-weight: 700; cursor: pointer;">
            ${selectOptionsHtml}
          </select>
        </td>
      `;

      // Child checkbox listener
      const childCheck = tr.querySelector('.child-archive-checkbox');
      childCheck.addEventListener('change', async () => {
        await toggleTarget('archive', arc.name, childCheck.checked);
      });

      // Move dropdown listener
      const moveSelect = tr.querySelector('.m3-move-select');
      moveSelect.addEventListener('change', async (e) => {
        const targetGid = e.target.value || null;
        await assignArchiveToGroup(arc.name, targetGid);
      });

      tbody.appendChild(tr);
    });

    body.appendChild(table);

    // Accordion Toggle on header click
    header.addEventListener('click', () => {
      const currentlyExpanded = body.classList.contains('expanded');
      if (currentlyExpanded) {
        body.classList.remove('expanded');
        expandIcon.textContent = 'expand_more';
        expandedGroupIds.delete(group.id);
      } else {
        body.classList.add('expanded');
        expandIcon.textContent = 'expand_less';
        expandedGroupIds.add(group.id);
      }
      saveExpandedGroupState();
    });

    card.appendChild(header);
    card.appendChild(body);
    return card;
  }

  // --- API Action Helpers ---
  async function toggleTarget(targetType, targetId, enabled) {
    try {
      const resp = await fetch('/api/groups/toggle', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          target_type: targetType,
          target_id: targetId,
          enabled: enabled,
        }),
      });
      const data = await resp.json();
      if (data.success) {
        currentGroupStructure = data.structure;
        const scrollY = window.scrollY;
        renderGroups(data.structure, data.total_archives, data.enabled_archives);
        window.scrollTo(0, scrollY);
        updateSearchScopeBanner(data.structure, data.total_archives, data.enabled_archives);
      }
    } catch (err) {
      console.error('Failed to toggle:', err);
    }
  }

  async function assignArchiveToGroup(archiveName, targetGroupId) {
    try {
      const resp = await fetch('/api/groups/assign', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          archive_names: [archiveName],
          target_group_id: targetGroupId,
        }),
      });
      const data = await resp.json();
      if (data.success) {
        await loadGroupsAndLibraryData();
      }
    } catch (err) {
      console.error('Failed to assign group:', err);
    }
  }

  async function renameGroup(groupId, newName) {
    try {
      const resp = await fetch('/api/groups/rename', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          group_id: groupId,
          name: newName,
        }),
      });
      const data = await resp.json();
      if (data.success) {
        await loadGroupsAndLibraryData();
      }
    } catch (err) {
      console.error('Failed to rename group:', err);
    }
  }

  async function deleteGroup(groupId) {
    try {
      const resp = await fetch('/api/groups/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ group_id: groupId }),
      });
      const data = await resp.json();
      if (data.success) {
        await loadGroupsAndLibraryData();
      }
    } catch (err) {
      console.error('Failed to delete group:', err);
    }
  }

  // Toolbar Actions: Select All & Deselect All
  if (btnSelectAll) {
    btnSelectAll.addEventListener('click', async () => {
      await toggleTarget('all', null, true);
    });
  }

  if (btnDeselectAll) {
    btnDeselectAll.addEventListener('click', async () => {
      await toggleTarget('all', null, false);
    });
  }

  // Create Folder Modal Handling
  if (btnCreateFolder && modalCreateFolder) {
    btnCreateFolder.addEventListener('click', () => {
      modalCreateFolder.style.display = 'flex';
      if (inputFolderName) {
        inputFolderName.value = '';
        inputFolderName.focus();
      }
    });
  }

  if (btnCancelFolder && modalCreateFolder) {
    btnCancelFolder.addEventListener('click', () => {
      modalCreateFolder.style.display = 'none';
    });
  }

  if (modalCreateFolder) {
    modalCreateFolder.addEventListener('click', (e) => {
      if (e.target === modalCreateFolder) {
        modalCreateFolder.style.display = 'none';
      }
    });
  }

  async function executeCreateFolder() {
    if (!inputFolderName) return;
    const name = inputFolderName.value.trim();
    if (!name) return;

    try {
      const resp = await fetch('/api/groups/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      });
      const data = await resp.json();
      if (data.success) {
        modalCreateFolder.style.display = 'none';
        await loadGroupsAndLibraryData();
      }
    } catch (err) {
      console.error('Failed to create folder:', err);
    }
  }

  if (btnConfirmCreateFolder) {
    btnConfirmCreateFolder.addEventListener('click', executeCreateFolder);
  }

  if (inputFolderName) {
    inputFolderName.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        executeCreateFolder();
      } else if (e.key === 'Escape' && modalCreateFolder) {
        modalCreateFolder.style.display = 'none';
      }
    });
  }

  // Banner Switch-to-Library Button
  if (btnGotoLibrary) {
    btnGotoLibrary.addEventListener('click', () => {
      const libTabBtn = document.querySelector('.m3-tab-btn[data-target="tab-library"]');
      if (libTabBtn) {
        libTabBtn.click();
      }
    });
  }

  // Rescan button
  btnRescan.addEventListener('click', async () => {
    btnRescan.disabled = true;
    try {
      await fetch('/api/library/scan', { method: 'POST' });
      await loadLibraryData();
      await updateSystemStatus();
    } finally {
      btnRescan.disabled = false;
    }
  });

  // Start background indexing
  btnStartIndex.addEventListener('click', async () => {
    btnStartIndex.disabled = true;
    try {
      const resp = await fetch('/api/index/start', { method: 'POST' });
      const data = await resp.json();
      if (data.started) {
        startIndexPolling();
      }
    } finally {
      btnStartIndex.disabled = false;
    }
  });

  function startIndexPolling() {
    indexProgressContainer.style.display = 'block';
    if (indexPollInterval) clearInterval(indexPollInterval);

    indexPollInterval = setInterval(async () => {
      const status = await fetchSystemStatus();
      if (status) {
        indexProgressBar.style.width = `${status.progress_percent}%`;
        indexPercentText.textContent = `${status.progress_percent}%`;
        indexStatusText.textContent = status.is_indexing 
          ? `インデックス中: ${status.current_file} (${status.indexed_pages}/${status.total_pages})` 
          : 'インデックス完了';

        if (!status.is_indexing) {
          clearInterval(indexPollInterval);
          indexPollInterval = null;
          loadLibraryData();
        }
      }
    }, 1000);
  }

  // --- Status Polling ---
  async function fetchSystemStatus() {
    try {
      const resp = await fetch('/api/status');
      return await resp.json();
    } catch {
      return null;
    }
  }

  let statusRetryTimeout = null;
  async function updateSystemStatus() {
    const status = await fetchSystemStatus();
    if (!status) {
      if (!statusRetryTimeout) {
        statusRetryTimeout = setTimeout(() => {
          statusRetryTimeout = null;
          updateSystemStatus();
        }, 1200);
      }
      return;
    }

    if (!globalStatusBadge) return;

    if (status.is_indexing) {
      globalStatusBadge.className = 'm3-badge-status pending';
      globalStatusBadge.innerHTML = `
        <span class="material-symbols-outlined" style="font-size: 16px;">sync</span>
        インデックス中 (${status.progress_percent || 0}%)
      `;
      if (!indexPollInterval) {
        startIndexPolling();
      }
    } else {
      globalStatusBadge.className = 'm3-badge-status indexed';
      globalStatusBadge.innerHTML = `
        <span class="material-symbols-outlined" style="font-size: 16px;">check_circle</span>
        準備完了 (${status.total_archives || 0}作品 / ${status.total_pages || 0}P)
      `;
    }
  }

  // --- Target Paths Management ---
  async function loadTargetPaths() {
    if (!targetPathsList) return;
    try {
      const resp = await fetch('/api/paths');
      const data = await resp.json();
      const paths = data.paths || [];

      if (paths.length === 0) {
        targetPathsList.innerHTML = `
          <div style="padding: 18px; text-align: center; color: var(--md-sys-color-outline); font-size: 0.88rem; font-weight: 700; background: var(--md-sys-color-surface-container-lowest); border-radius: var(--md-sys-shape-corner-small); border: 1px dashed var(--md-sys-color-outline-variant);">
            登録されたパスがありません。下の入力欄からフォルダーまたは単体ファイルを追加してください。
          </div>
        `;
        return;
      }

      targetPathsList.innerHTML = '';
      paths.forEach(item => {
        const row = document.createElement('div');
        row.style.display = 'flex';
        row.style.alignItems = 'center';
        row.style.justifyContent = 'space-between';
        row.style.padding = '12px 16px';
        row.style.background = 'var(--md-sys-color-surface-container-lowest)';
        row.style.borderRadius = 'var(--md-sys-shape-corner-small)';
        row.style.border = '1px solid var(--md-sys-color-outline-variant)';

        const icon = item.is_dir ? 'folder' : 'description';
        const typeBadge = item.is_dir ? 'フォルダー' : 'ファイル';

        row.innerHTML = `
          <div style="display: flex; align-items: center; gap: 12px; overflow: hidden; margin-right: 12px;">
            <span class="material-symbols-outlined" style="color: var(--md-sys-color-primary); font-size: 22px; flex-shrink: 0;">${icon}</span>
            <div style="overflow: hidden;">
              <div style="font-family: var(--m3-font-body); font-weight: 800; font-size: 0.92rem; color: var(--md-sys-color-on-surface); word-break: break-all;">
                ${item.path}
              </div>
              <div style="font-size: 0.76rem; color: var(--md-sys-color-secondary); margin-top: 2px;">
                ${typeBadge} ${item.exists ? '' : '<span style="color: var(--md-sys-color-error); font-weight: 800;">(存在しません)</span>'}
              </div>
            </div>
          </div>
          <button class="m3-btn-icon btn-remove-path" title="削除" style="color: var(--md-sys-color-error); background: transparent; border: none; cursor: pointer; padding: 8px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0;">
            <span class="material-symbols-outlined" style="font-size: 20px;">delete</span>
          </button>
        `;

        const btnRemove = row.querySelector('.btn-remove-path');
        btnRemove.addEventListener('click', async () => {
          if (!confirm(`次のパスの登録を解除しますか？\n${item.path}`)) return;
          try {
            const res = await fetch('/api/paths/remove', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ path: item.path })
            });
            if (res.ok) {
              await loadTargetPaths();
              await loadLibraryData();
              await updateSystemStatus();
            } else {
              const errData = await res.json();
              alert(`エラー: ${errData.detail || '削除に失敗しました。'}`);
            }
          } catch (err) {
            alert(`削除に失敗しました: ${err.message}`);
          }
        });

        targetPathsList.appendChild(row);
      });

    } catch (err) {
      console.error('Failed to load target paths:', err);
    }
  }

  // --- Path Addition & Explorer Picker Handlers ---
  async function addPathToLibrary(targetPath) {
    if (!targetPath) return false;
    try {
      const resp = await fetch('/api/paths/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: targetPath })
      });
      const data = await resp.json();
      if (resp.ok) {
        await loadTargetPaths();
        await loadLibraryData();
        await updateSystemStatus();
        return true;
      } else {
        alert(`追加エラー: ${data.detail || 'パスの追加に失敗しました。'}`);
        return false;
      }
    } catch (err) {
      alert(`通信エラー: ${err.message}`);
      return false;
    }
  }

  // 1. Browse Folder via Explorer
  const btnBrowseFolder = document.getElementById('btn-browse-folder');
  if (btnBrowseFolder) {
    btnBrowseFolder.addEventListener('click', async () => {
      if (window.electronAPI && window.electronAPI.selectFolder) {
        const folderPath = await window.electronAPI.selectFolder();
        if (folderPath) {
          await addPathToLibrary(folderPath);
        }
      } else {
        // Fallback for browser testing
        const input = document.createElement('input');
        input.type = 'file';
        input.webkitdirectory = true;
        input.onchange = async (e) => {
          if (e.target.files && e.target.files.length > 0) {
            // In browser, full OS path cannot be read for security
            alert('Webブラウザではセキュリティ上OSのフルパスを取得できません。デスクトップアプリ（Manga Panel Search）をご利用いただくか、パスを手入力してください。');
          }
        };
        input.click();
      }
    });
  }

  // 2. Browse Files via Explorer
  const btnBrowseFiles = document.getElementById('btn-browse-files');
  if (btnBrowseFiles) {
    btnBrowseFiles.addEventListener('click', async () => {
      if (window.electronAPI && window.electronAPI.selectFiles) {
        const filePaths = await window.electronAPI.selectFiles();
        if (filePaths && filePaths.length > 0) {
          let count = 0;
          for (const fp of filePaths) {
            const ok = await addPathToLibrary(fp);
            if (ok) count++;
          }
        }
      } else {
        alert('Webブラウザではセキュリティ上OSのフルパスを取得できません。デスクトップアプリをご利用いただくか、パスを手入力してください。');
      }
    });
  }

  // 3. Path Dropzone (Drag & Drop folder or file)
  const pathDropzone = document.getElementById('path-dropzone');
  if (pathDropzone) {
    pathDropzone.addEventListener('dragover', (e) => {
      e.preventDefault();
      pathDropzone.style.borderColor = 'var(--md-sys-color-primary)';
      pathDropzone.style.backgroundColor = 'var(--md-sys-color-surface-container)';
    });

    pathDropzone.addEventListener('dragleave', () => {
      pathDropzone.style.borderColor = 'var(--md-sys-color-outline-variant)';
      pathDropzone.style.backgroundColor = 'var(--md-sys-color-surface-container-lowest)';
    });

    pathDropzone.addEventListener('drop', async (e) => {
      e.preventDefault();
      pathDropzone.style.borderColor = 'var(--md-sys-color-outline-variant)';
      pathDropzone.style.backgroundColor = 'var(--md-sys-color-surface-container-lowest)';

      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        let addedCount = 0;
        for (let i = 0; i < e.dataTransfer.files.length; i++) {
          const file = e.dataTransfer.files[i];
          // In Electron, File objects have a .path property with the exact OS absolute path
          const fullPath = file.path;
          if (fullPath) {
            const ok = await addPathToLibrary(fullPath);
            if (ok) addedCount++;
          }
        }
        if (addedCount === 0 && !window.electronAPI) {
          alert('ブラウザ環境ではドロップされたアイテムの絶対パスを取得できません。デスクトップアプリをご利用いただくか手動で入力してください。');
        }
      }
    });

    pathDropzone.addEventListener('click', () => {
      if (btnBrowseFolder) btnBrowseFolder.click();
    });
  }

  // 4. Manual Add
  if (btnAddPath && inputNewPath) {
    const handleAdd = async () => {
      const newPath = inputNewPath.value.trim();
      if (!newPath) return;

      btnAddPath.disabled = true;
      const ok = await addPathToLibrary(newPath);
      if (ok) {
        inputNewPath.value = '';
      }
      btnAddPath.disabled = false;
    };

    btnAddPath.addEventListener('click', handleAdd);
    inputNewPath.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') handleAdd();
    });
  }

  // --- Score Threshold Slider ---
  if (settingMinScore && labelMinScore) {
    settingMinScore.addEventListener('input', (e) => {
      minScore = parseFloat(e.target.value);
      labelMinScore.textContent = minScore.toFixed(2);
    });
  }

  // --- Advanced Search Slider (Min Panel Area Tolerance) ---
  const sliderMinPanelArea = document.getElementById('slider-min-panel-area');
  const labelMinPanelArea = document.getElementById('label-min-panel-area');

  const STORAGE_KEY_MIN_AREA = 'manga_search_min_panel_area';

  function updateMinPanelAreaLabel(val) {
    if (!labelMinPanelArea) return;
    const num = parseFloat(val);
    const desc = num <= 0.5 ? '極小コマ対応' : num <= 1.5 ? '標準・推奨' : num <= 3.5 ? '中コマ' : '大コマ重視';
    labelMinPanelArea.textContent = `${num.toFixed(1)}% (${desc})`;
  }

  if (sliderMinPanelArea) {
    const savedMinArea = localStorage.getItem(STORAGE_KEY_MIN_AREA) || '1.0';
    sliderMinPanelArea.value = savedMinArea;
    updateMinPanelAreaLabel(savedMinArea);

    sliderMinPanelArea.addEventListener('input', (e) => {
      updateMinPanelAreaLabel(e.target.value);
      localStorage.setItem(STORAGE_KEY_MIN_AREA, e.target.value);
    });
  }
  // Initial Load with Fail-Safe & Auto Refresh
  try {
    updateSystemStatus();
  } catch (e) {
    console.warn('Initial updateSystemStatus error:', e);
  }
  try {
    loadLibraryData();
  } catch (e) {
    console.warn('Initial loadLibraryData error:', e);
  }
  try {
    loadTargetPaths();
  } catch (e) {
    console.warn('Initial loadTargetPaths error:', e);
  }

  // Periodic Status Synchronization (Every 12s)
  setInterval(() => {
    updateSystemStatus();
  }, 12000);
});

/**
 * Manga Panel Search - front-end (Fluent 2 UI)
 */
document.addEventListener('DOMContentLoaded', () => {
  const $ = (id) => document.getElementById(id);
  const icon = (name, size) => window.FluentIcon(name, size);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch {} },
  };

  // ---------- Toast ----------
  function toast(message, kind = 'info') {
    const el = document.createElement('div');
    el.className = `toast ${kind}`;
    const ic = kind === 'success' ? 'checkmark-circle-filled' : kind === 'error' ? 'error-circle' : 'info';
    el.innerHTML = `${icon(ic)}<span class="grow">${esc(message)}</span>`;
    $('toaster').appendChild(el);
    setTimeout(() => el.remove(), 4000);
  }

  // ---------- Electron window controls ----------
  if (window.electronAPI) {
    document.body.classList.add('is-electron');
    $('btn-minimize').addEventListener('click', () => window.electronAPI.minimize());
    $('btn-maximize').addEventListener('click', () => window.electronAPI.toggleMaximize());
    $('btn-close').addEventListener('click', () => window.electronAPI.close());
    document.querySelector('.titlebar').addEventListener('dblclick', (e) => {
      if (!e.target.closest('button')) window.electronAPI.toggleMaximize();
    });
    if (window.electronAPI.onMaximizeChange) {
      window.electronAPI.onMaximizeChange((max) => {
        $('icon-maximize').style.display = max ? 'none' : '';
        $('icon-restore').style.display = max ? '' : 'none';
        $('btn-maximize').title = max ? '元に戻す' : '最大化';
      });
    }
  }

  // ---------- Theme & accent ----------
  function applyTheme(mode) {
    if (mode === 'light' || mode === 'dark') document.documentElement.setAttribute('data-theme', mode);
    else document.documentElement.removeAttribute('data-theme');
    store.set('mps_theme', mode);
    document.querySelectorAll('#theme-segmented button').forEach((b) => b.classList.toggle('active', b.dataset.themeValue === mode));
  }
  function applyAccent(accent) {
    document.documentElement.setAttribute('data-accent', accent);
    store.set('mps_accent', accent);
    document.querySelectorAll('#accent-swatches .swatch').forEach((b) => b.classList.toggle('active', b.dataset.accent === accent));
  }
  applyTheme(store.get('mps_theme', 'system'));
  applyAccent(store.get('mps_accent', 'blue'));
  document.querySelectorAll('#theme-segmented button').forEach((b) => b.addEventListener('click', () => applyTheme(b.dataset.themeValue)));
  document.querySelectorAll('#accent-swatches .swatch').forEach((b) => b.addEventListener('click', () => applyAccent(b.dataset.accent)));

  // ---------- Navigation ----------
  const navItems = document.querySelectorAll('.nav-item');
  function showPage(targetId) {
    navItems.forEach((b) => b.classList.toggle('active', b.dataset.target === targetId));
    document.querySelectorAll('.page').forEach((p) => p.classList.toggle('active', p.id === targetId));
    if (targetId === 'tab-library') loadGroupsAndLibraryData();
    if (targetId === 'tab-settings') loadTargetPaths();
  }
  navItems.forEach((b) => b.addEventListener('click', () => showPage(b.dataset.target)));
  $('btn-goto-library').addEventListener('click', () => showPage('tab-library'));

  // ---------- Sliders (fill + label) ----------
  function bindSlider(input, storageKey, format, onChange) {
    const update = () => {
      const pct = ((input.value - input.min) / (input.max - input.min)) * 100;
      input.style.setProperty('--pct', `${pct}%`);
      format(input.value);
    };
    const saved = storageKey && store.get(storageKey, null);
    if (saved !== null && saved !== undefined) input.value = saved;
    update();
    input.addEventListener('input', () => {
      update();
      if (storageKey) store.set(storageKey, input.value);
      if (onChange) onChange(parseFloat(input.value));
    });
  }

  let minScore = 0.3;
  bindSlider($('setting-min-score'), 'mps_min_score', (v) => {
    minScore = parseFloat(v);
    $('label-min-score').textContent = minScore.toFixed(2);
  });
  bindSlider($('slider-min-panel-area'), 'manga_search_min_panel_area', (v) => {
    $('label-min-panel-area').textContent = `${parseFloat(v).toFixed(1)}%`;
  });

  // Persisted switches
  [['check-normalize-photo', 'manga_normalize_photo'], ['check-enable-coarse-clip', 'manga_use_coarse_clip']].forEach(([id, key]) => {
    const el = $(id);
    const saved = store.get(key, null);
    if (saved !== null) el.checked = saved === 'true';
    el.addEventListener('change', () => store.set(key, el.checked));
  });

  // ---------- Search: file input ----------
  const dropzone = $('dropzone');
  const fileInput = $('file-input');
  $('btn-select-file').addEventListener('click', (e) => { e.stopPropagation(); fileInput.click(); });
  dropzone.addEventListener('click', () => fileInput.click());
  dropzone.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); } });
  fileInput.addEventListener('change', (e) => { if (e.target.files && e.target.files[0]) handleFile(e.target.files[0]); });
  dropzone.addEventListener('dragover', (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
  dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
  dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
    if (e.dataTransfer.files && e.dataTransfer.files[0]) handleFile(e.dataTransfer.files[0]);
  });
  window.addEventListener('paste', (e) => {
    const items = (e.clipboardData || {}).items || [];
    for (const item of items) {
      if (item.kind === 'file' && item.type.startsWith('image/')) { handleFile(item.getAsFile()); break; }
    }
  });

  const resultsContainer = $('results-container');
  const resultsList = $('results-list');
  const resultsCountBadge = $('results-count-badge');
  const queryPreviewImg = $('query-preview-img');
  const queryMeta = $('query-meta');
  let searchSeq = 0;

  function handleFile(file) {
    if (!file || !file.type.startsWith('image/')) {
      toast('画像ファイル (PNG / JPG / WEBP) を選んでください。', 'error');
      return;
    }
    const url = URL.createObjectURL(file);
    queryPreviewImg.onload = () => {
      queryMeta.innerHTML = `
        <dt>ファイル</dt><dd>${esc(file.name || 'クリップボード')}</dd>
        <dt>サイズ</dt><dd>${queryPreviewImg.naturalWidth} × ${queryPreviewImg.naturalHeight} px</dd>
        <dt>容量</dt><dd>${(file.size / 1024).toFixed(1)} KB</dd>`;
    };
    queryPreviewImg.src = url;
    performSearch(file);
  }

  function renderProgress() {
    resultsList.innerHTML = `
      <div class="card progress-card">
        <div class="spinner"></div>
        <div class="t-subtitle2" id="search-stage">特徴量を準備しています…</div>
        <div class="progress thick"><div class="bar" id="search-bar" style="width:2%"></div></div>
        <div class="t-caption1 fg-3" id="search-detail">&nbsp;</div>
      </div>`;
  }

  function setProgress(pct, stage) {
    const bar = $('search-bar');
    if (!bar) return;
    bar.style.width = `${Math.max(2, pct)}%`;
    if (stage) $('search-stage').textContent = stage;
    $('search-detail').textContent = `${pct.toFixed(0)}%`;
  }

  function showSearchError(message) {
    resultsList.innerHTML = `
      <div class="message-bar error">${icon('error-circle')}<div class="grow"><span class="mb-title">検索できませんでした</span>${esc(message)}</div></div>`;
    resultsCountBadge.textContent = '0 件';
  }

  async function performSearch(file) {
    const seq = ++searchSeq;
    resultsContainer.style.display = '';
    resultsCountBadge.textContent = '検索中';
    renderProgress();
    resultsContainer.scrollIntoView({ behavior: 'smooth', block: 'start' });

    const form = new FormData();
    form.append('file', file);
    form.append('min_score', minScore);
    form.append('top_k', 5);
    form.append('normalize_photo', $('check-normalize-photo').checked);
    form.append('min_panel_area_percent', parseFloat($('slider-min-panel-area').value));
    form.append('enable_coarse_clip', $('check-enable-coarse-clip').checked);

    const t0 = performance.now();
    let finalData = null;
    try {
      const resp = await fetch('/api/search_stream', { method: 'POST', body: form });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      const handleLine = (line) => {
        if (!line.trim()) return;
        const ev = JSON.parse(line);
        if (ev.type === 'progress') setProgress(ev.percent || 0, ev.stage);
        else if (ev.type === 'result') finalData = ev;
        else if (ev.type === 'error') finalData = { success: false, message: ev.message };
      };
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop();
        lines.forEach(handleLine);
      }
      handleLine(buffer);
    } catch (err) {
      if (seq === searchSeq) showSearchError(err.message);
      return;
    }
    if (seq !== searchSeq) return; // a newer search replaced this one
    if (!finalData || !finalData.success) {
      showSearchError((finalData && finalData.message) || '不明なエラーです。');
      return;
    }
    renderSearchResults(finalData.results, (performance.now() - t0) / 1000);
  }

  function clearSearchResults() {
    searchSeq++;
    fileInput.value = '';
    resultsContainer.style.display = 'none';
    resultsList.innerHTML = '';
    queryPreviewImg.removeAttribute('src');
    queryMeta.innerHTML = '';
  }
  $('btn-clear-results').addEventListener('click', clearSearchResults);
  $('btn-clear-query').addEventListener('click', clearSearchResults);

  const highlightUpdaters = [];
  window.addEventListener('resize', () => highlightUpdaters.forEach((f) => f()));

  function renderSearchResults(results, seconds) {
    highlightUpdaters.length = 0;
    resultsCountBadge.textContent = `${results.length} 件 · ${seconds.toFixed(1)} 秒`;
    if (results.length === 0) {
      resultsList.innerHTML = `
        <div class="card empty-state">
          ${icon('image-search', 48)}
          <div class="t-subtitle2" style="color: var(--fg-1)">一致するコマが見つかりませんでした</div>
          <p style="margin-top: 8px">探している作品がライブラリの検索対象に入っているか確認してください。<br>設定の「最小スコア」を下げると、弱い候補も表示されます。</p>
        </div>`;
      return;
    }
    resultsList.innerHTML = '';
    results.forEach((res, i) => {
      const pct = Math.round(res.score * 100);
      const confident = res.score >= 0.5;
      const card = document.createElement('div');
      card.className = `card result-card${i === 0 ? ' top' : ''}`;
      card.style.animationDelay = `${i * 50}ms`;
      card.innerHTML = `
        <div class="result-head">
          <span class="result-rank">${i + 1}</span>
          <div class="grow">
            <div class="result-title">${esc(res.display_page_label || `${res.page_number} ページ`)}</div>
            <div class="result-sub">${esc(res.archive_name)} · ${esc(res.page_filename)}</div>
          </div>
          <div class="result-metrics">
            ${confident ? `<span class="badge badge-success">${icon('checkmark-circle-filled')}一致</span>` : `<span class="badge badge-warning">${icon('warning')}低信頼</span>`}
            <div class="score-meter" title="スコア ${res.score.toFixed(3)} / 対応点 ${res.inliers_count}">
              <div class="row-between t-caption1"><span class="fg-3">スコア</span><span class="t-caption1-strong">${pct}%</span></div>
              <div class="progress"><div class="bar" style="width:${pct}%"></div></div>
            </div>
            <span class="badge badge-neutral" title="幾何的に一致した特徴点の数">${res.inliers_count} 点</span>
          </div>
        </div>
        <div class="result-viewer">
          <div class="page-display">
            <img src="${esc(res.page_image_url)}" alt="${esc(res.display_page_label)}" loading="lazy">
            <div class="highlight-box"></div>
          </div>
        </div>`;
      resultsList.appendChild(card);

      const img = card.querySelector('img');
      const box = card.querySelector('.highlight-box');
      const update = () => {
        if (!img.naturalWidth || !img.clientWidth) return;
        const sx = img.clientWidth / img.naturalWidth;
        const sy = img.clientHeight / img.naturalHeight;
        const b = res.bounding_box;
        Object.assign(box.style, { left: `${b.x * sx}px`, top: `${b.y * sy}px`, width: `${b.w * sx}px`, height: `${b.h * sy}px` });
      };
      if (img.complete) update(); else img.addEventListener('load', update);
      highlightUpdaters.push(update);
    });
  }

  // ---------- Status ----------
  const statusBadge = $('global-status-badge');
  let indexPollInterval = null;

  async function fetchStatus() {
    try { return await (await fetch('/api/status')).json(); } catch { return null; }
  }

  async function updateSystemStatus() {
    const s = await fetchStatus();
    if (!s) {
      statusBadge.className = 'badge badge-neutral';
      statusBadge.innerHTML = '<span class="spinner small"></span>接続中';
      setTimeout(updateSystemStatus, 1500);
      return;
    }
    if (s.is_scanning) {
      statusBadge.className = 'badge badge-neutral';
      statusBadge.innerHTML = '<span class="spinner small"></span>ライブラリを読み込み中';
      setTimeout(updateSystemStatus, 1500);
    } else if (s.is_indexing) {
      statusBadge.className = 'badge badge-warning';
      statusBadge.innerHTML = `<span class="spinner small"></span>インデックス作成中 ${s.progress_percent || 0}%`;
      if (!indexPollInterval) startIndexPolling();
    } else {
      statusBadge.className = 'badge badge-success';
      statusBadge.innerHTML = `${icon('checkmark-circle-filled')}準備完了`;
    }
    $('nav-footer-stats').textContent = `${s.total_archives || 0} 冊 · ${(s.total_pages || 0).toLocaleString()} ページ`;
  }

  function startIndexPolling() {
    $('index-progress-container').style.display = '';
    if (indexPollInterval) clearInterval(indexPollInterval);
    indexPollInterval = setInterval(async () => {
      const s = await fetchStatus();
      if (!s) return;
      $('index-progress-bar').style.width = `${s.progress_percent}%`;
      $('index-percent-text').textContent = `${s.progress_percent}%`;
      $('index-status-text').textContent = s.is_indexing
        ? `${s.current_file} (${s.indexed_pages.toLocaleString()} / ${s.total_pages.toLocaleString()})`
        : 'インデックス作成が完了しました';
      if (!s.is_indexing) {
        clearInterval(indexPollInterval);
        indexPollInterval = null;
        setTimeout(() => { $('index-progress-container').style.display = 'none'; }, 2500);
        toast('インデックス作成が完了しました', 'success');
        loadGroupsAndLibraryData();
        updateSystemStatus();
      }
    }, 1000);
  }

  // ---------- Library ----------
  let cachedArchiveMetadata = {};
  let expandedGroupIds = new Set();
  try { expandedGroupIds = new Set(JSON.parse(store.get('manga_expanded_group_ids', '[]'))); } catch {}
  const saveExpanded = () => store.set('manga_expanded_group_ids', JSON.stringify([...expandedGroupIds]));

  async function loadGroupsAndLibraryData() {
    try {
      const lib = await (await fetch('/api/library')).json();
      cachedArchiveMetadata = {};
      (lib.archives || []).forEach((a) => { cachedArchiveMetadata[a.name] = a; });
      const pages = (lib.archives || []).reduce((n, a) => n + (a.page_count || 0), 0);
      $('library-dir-label').textContent = `${(lib.archives || []).length} 冊 · ${pages.toLocaleString()} ページ`;
    } catch (err) {
      console.warn('library summary failed', err);
    }
    try {
      const data = await (await fetch('/api/groups')).json();
      renderGroups(data.structure, data.total_archives, data.enabled_archives);
      updateScopeBanner(data.structure, data.total_archives, data.enabled_archives);
    } catch (err) {
      $('library-groups-container').innerHTML = `<div class="message-bar error">${icon('error-circle')}<div class="grow">ライブラリを読み込めませんでした。</div></div>`;
    }
  }

  function updateScopeBanner(structure, total, enabled) {
    const banner = $('search-scope-banner');
    if (!total) { banner.style.display = 'none'; return; }
    banner.style.display = '';
    if (enabled === 0) {
      banner.className = 'message-bar warning';
      banner.querySelector('.fi').outerHTML = icon('warning');
      $('search-scope-title').textContent = '検索対象が選ばれていません';
      $('search-scope-sub').textContent = 'ライブラリで検索する作品にチェックを入れてください。';
      return;
    }
    banner.className = 'message-bar';
    banner.querySelector('.fi').outerHTML = icon('filter');
    if (enabled === total) {
      $('search-scope-title').textContent = `検索対象: すべて (${total} 冊)`;
      $('search-scope-sub').textContent = '';
      return;
    }
    const names = [];
    ((structure && structure.groups) || []).forEach((g) => {
      const c = g.archives.filter((a) => a.checked).length;
      if (c > 0) names.push(`${g.name} (${c})`);
    });
    const u = structure && structure.ungrouped && structure.ungrouped.archives ? structure.ungrouped.archives.filter((a) => a.checked).length : 0;
    if (u > 0) names.push(`未分類 (${u})`);
    $('search-scope-title').textContent = `検索対象: ${enabled} / ${total} 冊`;
    $('search-scope-sub').textContent = names.join('、');
  }

  function renderGroups(structure, total, enabled) {
    $('library-selection-summary').textContent = `検索対象: ${enabled} / ${total} 冊`;
    const container = $('library-groups-container');
    container.innerHTML = '';
    const groups = (structure && structure.groups) || [];
    const ungrouped = (structure && structure.ungrouped) || { archives: [] };
    if (groups.length === 0 && ungrouped.archives.length === 0) {
      container.innerHTML = `
        <div class="card empty-state">
          ${icon('library', 48)}
          <div class="t-subtitle2" style="color: var(--fg-1)">漫画が登録されていません</div>
          <p style="margin-top: 8px">設定の「ライブラリの場所」からフォルダーやファイルを追加してください。</p>
          <button class="btn btn-primary" style="margin-top: 16px" id="btn-empty-goto-settings">${icon('settings')}設定を開く</button>
        </div>`;
      $('btn-empty-goto-settings').addEventListener('click', () => showPage('tab-settings'));
      return;
    }
    const options = groups.map((g) => ({ id: g.id, name: g.name }));
    groups.forEach((g) => container.appendChild(createGroupCard(g, options, false)));
    if (ungrouped.archives && ungrouped.archives.length > 0) {
      container.appendChild(createGroupCard({
        id: '_ungrouped', name: '未分類', enabled: ungrouped.enabled, is_indeterminate: ungrouped.is_indeterminate,
        count: ungrouped.count, archives: ungrouped.archives,
      }, options, true));
    }
  }

  function createGroupCard(group, options, isUngrouped) {
    const card = document.createElement('div');
    card.className = `accordion${expandedGroupIds.has(group.id) ? ' expanded' : ''}`;

    const header = document.createElement('div');
    header.className = 'accordion-header';
    header.innerHTML = `
      <input type="checkbox" class="checkbox" title="フォルダー内をすべて選択 / 解除">
      ${icon(isUngrouped ? 'folder-open' : 'folder', 20).replace('class="fi"', 'class="fi folder-icon"')}
      <span class="t-body1-strong grow">${esc(group.name)}</span>
      <span class="badge badge-neutral">${group.count} 冊</span>
      ${isUngrouped ? '' : `
        <button class="btn btn-subtle btn-icon act-rename" title="名前を変更">${icon('edit')}</button>
        <button class="btn btn-subtle btn-icon act-delete" title="フォルダーを削除">${icon('delete')}</button>`}
      <span class="chev">${icon('chevron-down')}</span>`;
    const parentCheck = header.querySelector('.checkbox');
    parentCheck.checked = !!group.enabled;
    parentCheck.indeterminate = !!group.is_indeterminate;
    parentCheck.addEventListener('click', (e) => e.stopPropagation());
    parentCheck.addEventListener('change', () => toggleTarget(isUngrouped ? 'ungrouped' : 'group', isUngrouped ? null : group.id, parentCheck.checked));

    if (!isUngrouped) {
      header.querySelector('.act-rename').addEventListener('click', async (e) => {
        e.stopPropagation();
        const name = await openDialog({ title: 'フォルダー名の変更', body: '新しい名前を入力してください。', value: group.name, ok: '変更' });
        if (name && name !== group.name) await postJson('/api/groups/rename', { group_id: group.id, name }).then(loadGroupsAndLibraryData);
      });
      header.querySelector('.act-delete').addEventListener('click', async (e) => {
        e.stopPropagation();
        if (confirm(`フォルダー「${group.name}」を削除しますか？\n中の漫画ファイルは削除されず、未分類に移動します。`)) {
          await postJson('/api/groups/delete', { group_id: group.id });
          loadGroupsAndLibraryData();
        }
      });
    }
    header.addEventListener('click', () => {
      const open = card.classList.toggle('expanded');
      if (open) expandedGroupIds.add(group.id); else expandedGroupIds.delete(group.id);
      saveExpanded();
    });

    const body = document.createElement('div');
    body.className = 'accordion-body';
    const optionHtml = (current) => `<option value="">未分類</option>` +
      options.map((o) => `<option value="${esc(o.id)}"${o.id === current ? ' selected' : ''}>${esc(o.name)}</option>`).join('');
    body.innerHTML = `
      <table class="data-grid">
        <thead><tr><th class="col-check"></th><th>巻 / アーカイブ</th><th class="col-num">ページ</th><th class="col-status">インデックス</th><th class="col-folder">フォルダー</th></tr></thead>
        <tbody></tbody>
      </table>`;
    const tbody = body.querySelector('tbody');
    group.archives.forEach((arc) => {
      const meta = cachedArchiveMetadata[arc.name] || {};
      const done = meta.is_fully_indexed;
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td class="col-check"><input type="checkbox" class="checkbox"${arc.checked ? ' checked' : ''}></td>
        <td><div class="t-body1-strong">${esc(shortName(arc.name))}</div><div class="t-caption1 fg-3 cell-sub">${esc(arc.name)}</div></td>
        <td class="num">${meta.page_count ?? '-'}</td>
        <td>${done ? `<span class="badge badge-success">${icon('checkmark-circle-filled')}作成済み</span>`
                   : `<span class="badge badge-neutral">${icon('hourglass-half')}${meta.cached_pages || 0} / ${meta.page_count || 0}</span>`}</td>
        <td><select class="select select-small" style="width:100%">${optionHtml(isUngrouped ? '' : group.id)}</select></td>`;
      tr.querySelector('.checkbox').addEventListener('change', (e) => toggleTarget('archive', arc.name, e.target.checked));
      tr.querySelector('select').addEventListener('change', async (e) => {
        await postJson('/api/groups/assign', { archive_names: [arc.name], target_group_id: e.target.value || null });
        loadGroupsAndLibraryData();
      });
      tbody.appendChild(tr);
    });

    card.appendChild(header);
    card.appendChild(body);
    return card;
  }

  // "(一般コミック) [作者] らんま1／2 第01巻.rar" -> "第01巻"
  function shortName(name) {
    const m = name.match(/第\s*\d+\s*巻/) || name.match(/vol(?:ume)?[\s._-]*\d+(?:\s*-\s*\d+)?/i);
    return m ? m[0] : name.replace(/\.(zip|cbz|rar|cbr|7z|cb7|tar|cbt)$/i, '');
  }

  async function postJson(url, payload) {
    const resp = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) throw new Error(data.detail || `HTTP ${resp.status}`);
    return data;
  }

  async function toggleTarget(targetType, targetId, enabled) {
    try {
      const data = await postJson('/api/groups/toggle', { target_type: targetType, target_id: targetId, enabled });
      const scroll = document.querySelector('.content').scrollTop;
      renderGroups(data.structure, data.total_archives, data.enabled_archives);
      document.querySelector('.content').scrollTop = scroll;
      updateScopeBanner(data.structure, data.total_archives, data.enabled_archives);
    } catch (err) {
      toast(`更新できませんでした: ${err.message}`, 'error');
    }
  }

  $('btn-select-all').addEventListener('click', () => toggleTarget('all', null, true));
  $('btn-deselect-all').addEventListener('click', () => toggleTarget('all', null, false));

  $('btn-create-folder').addEventListener('click', async () => {
    const name = await openDialog({ title: '新しいフォルダー', body: '作品ごとにまとめるフォルダーの名前を入力してください。', value: '', ok: '作成' });
    if (name) {
      await postJson('/api/groups/create', { name });
      loadGroupsAndLibraryData();
    }
  });

  $('btn-rescan').addEventListener('click', async (e) => {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      await fetch('/api/library/scan', { method: 'POST' });
      await loadGroupsAndLibraryData();
      await updateSystemStatus();
      toast('ライブラリを再スキャンしました', 'success');
    } finally { btn.disabled = false; }
  });

  $('btn-start-index').addEventListener('click', async (e) => {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      const data = await (await fetch('/api/index/start', { method: 'POST' })).json();
      if (data.started) startIndexPolling(); else toast('すでにインデックス作成中です', 'info');
    } finally { btn.disabled = false; }
  });

  // ---------- Dialog (replacement for window.prompt, which Electron does not support) ----------
  function openDialog({ title, body, value, ok }) {
    const dlg = $('dialog');
    const input = $('dialog-input');
    $('dialog-title').textContent = title;
    $('dialog-body').textContent = body;
    $('dialog-ok').textContent = ok || 'OK';
    input.value = value || '';
    dlg.classList.add('open');
    setTimeout(() => { input.focus(); input.select(); }, 30);
    return new Promise((resolve) => {
      const close = (result) => {
        dlg.classList.remove('open');
        $('dialog-ok').onclick = $('dialog-cancel').onclick = dlg.onclick = input.onkeydown = null;
        resolve(result);
      };
      $('dialog-ok').onclick = () => close(input.value.trim() || null);
      $('dialog-cancel').onclick = () => close(null);
      dlg.onclick = (e) => { if (e.target === dlg) close(null); };
      input.onkeydown = (e) => {
        if (e.key === 'Enter') close(input.value.trim() || null);
        if (e.key === 'Escape') close(null);
      };
    });
  }

  // ---------- Settings: library paths ----------
  async function loadTargetPaths() {
    const list = $('target-paths-list');
    try {
      const data = await (await fetch('/api/paths')).json();
      const paths = data.paths || [];
      if (paths.length === 0) {
        list.innerHTML = `<div class="message-bar">${icon('info')}<div class="grow">登録されている場所はありません。</div></div>`;
        return;
      }
      list.innerHTML = '';
      paths.forEach((item) => {
        const row = document.createElement('div');
        row.className = 'path-item';
        row.innerHTML = `
          ${icon(item.is_dir ? 'folder' : 'document')}
          <div class="grow">
            <div class="path-text">${esc(item.path)}</div>
            <div class="t-caption1 fg-3">${item.is_dir ? 'フォルダー' : 'ファイル'}${item.exists ? '' : ' · <span style="color: var(--danger-fg)">見つかりません</span>'}</div>
          </div>
          <button class="btn btn-subtle btn-icon" title="登録を解除">${icon('delete')}</button>`;
        row.querySelector('button').addEventListener('click', async () => {
          if (!confirm(`次の場所の登録を解除しますか？\n${item.path}`)) return;
          try {
            await postJson('/api/paths/remove', { path: item.path });
            await loadTargetPaths();
            loadGroupsAndLibraryData();
            updateSystemStatus();
          } catch (err) { toast(`解除できませんでした: ${err.message}`, 'error'); }
        });
        list.appendChild(row);
      });
    } catch (err) {
      console.error('Failed to load target paths:', err);
    }
  }

  async function addPathToLibrary(path) {
    if (!path) return false;
    try {
      await postJson('/api/paths/add', { path });
      await loadTargetPaths();
      loadGroupsAndLibraryData();
      updateSystemStatus();
      toast('ライブラリに追加しました', 'success');
      return true;
    } catch (err) {
      toast(`追加できませんでした: ${err.message}`, 'error');
      return false;
    }
  }

  const desktopOnly = () => toast('ブラウザーではフォルダーのパスを取得できません。デスクトップ アプリを使うか、パスを直接入力してください。', 'info');
  $('btn-browse-folder').addEventListener('click', async () => {
    if (!window.electronAPI) return desktopOnly();
    const p = await window.electronAPI.selectFolder();
    if (p) addPathToLibrary(p);
  });
  $('btn-browse-files').addEventListener('click', async () => {
    if (!window.electronAPI) return desktopOnly();
    const files = await window.electronAPI.selectFiles();
    for (const f of files || []) await addPathToLibrary(f);
  });

  const pathDrop = $('path-dropzone');
  pathDrop.addEventListener('click', () => $('btn-browse-folder').click());
  pathDrop.addEventListener('dragover', (e) => { e.preventDefault(); pathDrop.classList.add('dragover'); });
  pathDrop.addEventListener('dragleave', () => pathDrop.classList.remove('dragover'));
  pathDrop.addEventListener('drop', async (e) => {
    e.preventDefault();
    pathDrop.classList.remove('dragover');
    const files = [...(e.dataTransfer.files || [])];
    // Electron 32+ removed File.path; the preload exposes webUtils.getPathForFile instead.
    const paths = files.map((f) => (window.electronAPI && window.electronAPI.getPathForFile ? window.electronAPI.getPathForFile(f) : f.path)).filter(Boolean);
    if (paths.length === 0) return desktopOnly();
    for (const p of paths) await addPathToLibrary(p);
  });

  const handleManualAdd = async () => {
    const input = $('input-new-path');
    const v = input.value.trim();
    if (!v) return;
    $('btn-add-path').disabled = true;
    if (await addPathToLibrary(v)) input.value = '';
    $('btn-add-path').disabled = false;
  };
  $('btn-add-path').addEventListener('click', handleManualAdd);
  $('input-new-path').addEventListener('keydown', (e) => { if (e.key === 'Enter') handleManualAdd(); });

  // ---------- Initial load ----------
  updateSystemStatus();
  loadGroupsAndLibraryData();
  setInterval(updateSystemStatus, 12000);
});

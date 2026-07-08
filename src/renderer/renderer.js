'use strict';

const $ = (id) => document.getElementById(id);

let currentAccount = null;

// ---------- tabs ----------
document.querySelectorAll('.nav-item').forEach((btn) => {
  btn.addEventListener('click', () => {
    const tab = btn.dataset.tab;
    document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b === btn));
    document.querySelectorAll('.tab').forEach((s) => s.classList.toggle('hidden', s.dataset.tab !== tab));
  });
});

// ---------- account UI ----------
function typeLabel(acc) {
  return acc && acc.type === 'microsoft' ? t('acc_type_microsoft') : t('acc_type_offline');
}

// Fetch a player's face (via main, to satisfy CSP) and set it as the element's bg.
async function setFace(el, uuid) {
  try {
    const url = await window.iea.skinFace(uuid);
    if (url) { el.style.backgroundImage = `url("${url}")`; el.textContent = ''; }
  } catch (_) { /* keep the letter/placeholder */ }
}

// Full-body skin preview on the Play screen for the active account.
async function updateSkin(acc) {
  const img = $('skinBody');
  if (!acc) {
    img.removeAttribute('src');
    $('skinName').textContent = '—';
    $('skinType').textContent = '';
    return;
  }
  $('skinName').textContent = acc.name;
  $('skinType').textContent = typeLabel(acc);
  // Resolve the arm model first so the body render uses the matching (slim/wide) model.
  let model = 'classic';
  try { model = await window.iea.skinModel(acc.uuid); } catch (_) {}
  $('skinType').textContent = `${typeLabel(acc)} · ${t(model === 'slim' ? 'model_slim' : 'model_wide')}`;
  try {
    const url = await window.iea.skinBody(acc.uuid, model);
    if (url) img.src = url; else img.removeAttribute('src');
  } catch (_) { img.removeAttribute('src'); }
}

function renderAccount(acc) {
  currentAccount = acc;
  const avatar = $('avatar');
  if (acc) {
    $('accountName').textContent = acc.name;
    $('accountType').textContent = typeLabel(acc);
    avatar.textContent = (acc.name || 'P').charAt(0).toUpperCase();
    avatar.style.backgroundImage = '';
    setFace(avatar, acc.uuid);
  } else {
    $('accountName').textContent = t('acc_not_logged_in');
    $('accountType').textContent = '—';
    avatar.textContent = 'P';
    avatar.style.backgroundImage = '';
  }
  updateSkin(acc);
}

// ---------- account switcher list ----------
function applyAccountsResult(res) {
  const active = (res.accounts || []).find((a) => a.id === res.activeId) || null;
  renderAccount(active);
  renderAccountsList();
}

async function renderAccountsList() {
  let data;
  try { data = await window.iea.listAccounts(); } catch (_) { return; }
  const accounts = data.accounts || [];
  const activeId = data.activeId;
  const box = $('accountList');
  box.innerHTML = '';
  if (!accounts.length) {
    box.innerHTML = `<div class="muted small">${escapeHtml(t('acc_none'))}</div>`;
    return;
  }
  for (const a of accounts) {
    const row = document.createElement('div');
    row.className = 'account-row' + (a.id === activeId ? ' active' : '');

    const face = document.createElement('div');
    face.className = 'face';
    setFace(face, a.uuid);

    const info = document.createElement('div');
    info.className = 'info';
    info.innerHTML = `<div class="n"></div><div class="ty"></div>`;
    info.querySelector('.n').textContent = a.name;
    info.querySelector('.ty').textContent = typeLabel(a);

    row.appendChild(face);
    row.appendChild(info);

    if (a.id === activeId) {
      const tag = document.createElement('span');
      tag.className = 'active-tag';
      tag.textContent = t('acc_active');
      row.appendChild(tag);
    }

    const rm = document.createElement('button');
    rm.className = 'remove';
    rm.textContent = '×';
    rm.addEventListener('click', async (e) => {
      e.stopPropagation();
      applyAccountsResult(await window.iea.removeAccount(a.id));
    });
    row.appendChild(rm);

    row.addEventListener('click', async () => {
      applyAccountsResult(await window.iea.selectAccount(a.id));
      setStatus(`${t('status_signed_in')}: ${a.name}`);
    });
    box.appendChild(row);
  }
}

// ---------- resource packs ----------
async function renderPacks() {
  let packs = [];
  try { packs = await window.iea.listPacks(); } catch (_) { return; }
  const box = $('packList');
  box.innerHTML = '';
  if (!packs.length) {
    box.innerHTML = `<div class="muted small">${escapeHtml(t('packs_empty'))}</div>`;
    return;
  }
  for (const p of packs) {
    const row = document.createElement('div');
    row.className = 'account-row';
    const info = document.createElement('div');
    info.className = 'info';
    info.innerHTML = `<div class="n"></div><div class="ty"></div>`;
    info.querySelector('.n').textContent = p.name;
    info.querySelector('.ty').textContent = p.folder ? t('packs_kind_folder') : t('packs_kind_zip');
    row.appendChild(info);
    const rm = document.createElement('button');
    rm.className = 'remove';
    rm.textContent = '×';
    rm.addEventListener('click', async () => {
      await window.iea.removePack(p.name);
      renderPacks();
    });
    row.appendChild(rm);
    box.appendChild(row);
  }
}

const importPackBtn = $('importPackBtn');
if (importPackBtn) {
  importPackBtn.addEventListener('click', async () => {
    const st = $('packStatus');
    importPackBtn.disabled = true;
    st.className = 'pack-status small muted';
    st.textContent = t('packs_converting');
    let res;
    try { res = await window.iea.importPack(); }
    catch (e) { res = { ok: false, error: e.message }; }
    importPackBtn.disabled = false;
    if (!res || res.canceled) { st.textContent = ''; return; }
    if (res.ok) {
      st.className = 'pack-status small ok';
      if (res.kind === 'java-native') {
        st.textContent = `${t('packs_added')}: ${res.name}`;
      } else {
        const fixed = res.fixed ? `, ${res.fixed} ${t('packs_fixed')}` : '';
        st.textContent = `${t('packs_done')} (${res.files} ${t('packs_textures')}${fixed}): ${res.name}`;
      }
      renderPacks();
    } else {
      st.className = 'pack-status small err';
      if (res.kind === 'bedrock') st.textContent = t('packs_bedrock');
      else if (res.kind === 'unknown') st.textContent = t('packs_unknown');
      else st.textContent = `${t('packs_error')}: ${res.error || ''}`;
    }
  });
  $('openPacksBtn').addEventListener('click', () => window.iea.openPacksDir());
}

// ---------- what's-new ----------
function formatDate(iso) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleDateString(); } catch (_) { return ''; }
}
// Trim GitHub release notes for the compact panel (drop the Claude/co-author footer).
function cleanBody(s) {
  if (!s) return '';
  let out = String(s).replace(/\r/g, '')
    .replace(/^\s*🤖.*$/gm, '')
    .replace(/^\s*Co-Authored-By:.*$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  if (out.length > 400) out = out.slice(0, 400).trim() + '…';
  return out;
}

async function loadNews() {
  const box = $('newsList');
  let ver = '', releases = [];
  try { ver = await window.iea.appVersion(); } catch (_) {}
  try { releases = await window.iea.getNews(); } catch (_) {}
  if (ver) $('appVersion').textContent = 'v' + ver;
  if (!releases || !releases.length) {
    box.innerHTML = `<div class="muted small">${escapeHtml(t('news_empty'))}</div>`;
    return;
  }
  box.innerHTML = '';
  for (const r of releases) {
    const item = document.createElement('div');
    item.className = 'news-item';
    const isCurrent = ver && (r.tag === 'v' + ver || r.tag === ver);
    const title = document.createElement('div');
    title.className = 'news-title';
    title.innerHTML =
      `<span class="tag">${escapeHtml(r.tag)}</span>` +
      `<span class="news-date">${escapeHtml(formatDate(r.date))}</span>` +
      (isCurrent ? ` <span class="muted">${escapeHtml(t('news_current'))}</span>` : '');
    const body = document.createElement('div');
    body.className = 'news-body';
    body.textContent = cleanBody(r.body);
    item.appendChild(title);
    item.appendChild(body);
    box.appendChild(item);
  }
}

// ---------- init from settings ----------
async function init() {
  const s = await window.iea.getSettings();
  setLang(s.language || 'ja');
  $('langSelect').value = s.language || 'ja';
  $('usernameInput').value = s.lastUsername || 'Player';
  $('javaPath').value = s.javaPath || '';
  $('gameDir').value = s.gameDir || '';
  $('hypixelKey').value = s.hypixelKey || '';
  $('injectClient').checked = s.injectClient !== false;
  $('capLog').checked = s.capLog !== false;
  capLogEnabled = s.capLog !== false;
  $('discordRpc').checked = s.discordRpc !== false;
  $('ramSlider').value = s.maxRamMB || 2048;
  $('ramValue').textContent = s.maxRamMB || 2048;
  $('versionBadge').textContent = s.versionId || '1.8.9';
  renderAccount(s.account || null);
  renderAccountsList();
  loadNews();
  renderPacks();
  setStatus(t('status_ready'));
}
init();

// ---------- language ----------
$('langSelect').addEventListener('change', async () => {
  const lang = $('langSelect').value;
  setLang(lang);
  renderAccount(currentAccount); // re-render non-i18n-tagged account text
  renderAccountsList();          // localized labels in the switcher
  loadNews();                    // localized "current" / empty text
  await window.iea.saveSettings({ language: lang });
});

// ---------- auth ----------
$('offlineBtn').addEventListener('click', async () => {
  const name = $('usernameInput').value.trim() || 'Player';
  const acc = await window.iea.loginOffline(name);
  renderAccount(acc);
  renderAccountsList();
  setStatus(`${t('status_offline_ready')}: ${acc.name}`);
});

$('msBtn').addEventListener('click', async () => {
  $('msBtn').disabled = true;
  try {
    const acc = await window.iea.loginMicrosoft();
    renderAccount(acc);
    renderAccountsList();
    $('msPrompt').classList.add('hidden');
    setStatus(`${t('status_signed_in')}: ${acc.name}`);
  } catch (e) {
    $('msPrompt').classList.remove('hidden');
    $('msPrompt').innerHTML = `<b style="color:var(--danger)">${t('acc_login_failed')}:</b> ${escapeHtml(e.message)}`;
  } finally {
    $('msBtn').disabled = false;
  }
});

window.iea.onAuthPrompt((p) => {
  $('msPrompt').classList.remove('hidden');
  $('msPrompt').innerHTML =
    `Open <b>${escapeHtml(p.verification_uri)}</b> · code: <code>${escapeHtml(p.user_code)}</code>`;
});

$('logoutBtn').addEventListener('click', async () => {
  await window.iea.logout();
  renderAccount(null);
  renderAccountsList();
  setStatus(t('status_logged_out'));
});

// ---------- settings (auto-saved so they always apply on launch) ----------
$('ramSlider').addEventListener('input', () => { $('ramValue').textContent = $('ramSlider').value; });
$('ramSlider').addEventListener('change', () => window.iea.saveSettings({ maxRamMB: parseInt($('ramSlider').value, 10) }));

$('javaPath').addEventListener('change', () => window.iea.saveSettings({ javaPath: $('javaPath').value.trim() }));
$('gameDir').addEventListener('change', () => window.iea.saveSettings({ gameDir: $('gameDir').value.trim() }));
$('hypixelKey').addEventListener('change', () => window.iea.saveSettings({ hypixelKey: $('hypixelKey').value.trim() }));
$('injectClient').addEventListener('change', () => window.iea.saveSettings({ injectClient: $('injectClient').checked }));
$('capLog').addEventListener('change', () => {
  capLogEnabled = $('capLog').checked;
  window.iea.saveSettings({ capLog: capLogEnabled });
  if (capLogEnabled) trimLog(); // applying the cap now drops any current backlog over the limit
});
$('discordRpc').addEventListener('change', () => {
  window.iea.saveSettings({ discordRpc: $('discordRpc').checked });
  window.iea.setDiscord($('discordRpc').checked);
});

$('pickJavaBtn').addEventListener('click', async () => {
  const p = await window.iea.pickJava();
  if (p) { $('javaPath').value = p; await window.iea.saveSettings({ javaPath: p }); }
});

$('pickDirBtn').addEventListener('click', async () => {
  const p = await window.iea.pickDir();
  if (p) { $('gameDir').value = p; await window.iea.saveSettings({ gameDir: p }); }
});

$('openDirBtn').addEventListener('click', () => {
  window.iea.openGameDir($('gameDir').value.trim());
});

$('saveSettingsBtn').addEventListener('click', async () => {
  await window.iea.saveSettings({
    javaPath: $('javaPath').value.trim(),
    gameDir: $('gameDir').value.trim(),
    hypixelKey: $('hypixelKey').value.trim(),
    maxRamMB: parseInt($('ramSlider').value, 10),
    language: $('langSelect').value,
    injectClient: $('injectClient').checked,
  });
  const hint = $('savedHint');
  hint.classList.remove('hidden');
  setTimeout(() => hint.classList.add('hidden'), 1500);
});

// ---------- launch ----------
function setStatus(text) { $('status').textContent = text; }
function setProgress(done, total) {
  const pct = total ? Math.round((done / total) * 100) : 0;
  $('progressBar').style.width = pct + '%';
  $('progressText').textContent = total ? `${done} / ${total} files (${pct}%)` : '';
}
// Cap the console so a long game session can't grow renderer memory without bound.
// Trim in batches (not every line) so chatty startup logging stays cheap. Toggleable:
// when off, the full log is kept (uses more memory). Default set from saved settings.
const LOG_MAX = 600, LOG_BATCH = 200;
let logLines = [];
let capLogEnabled = true;
function trimLog() {
  if (logLines.length <= LOG_MAX) return;
  logLines = logLines.slice(logLines.length - LOG_MAX);
  $('logConsole').textContent = logLines.join('\n') + '\n';
}
function appendLog(line) {
  const el = $('logConsole');
  logLines.push(line);
  if (capLogEnabled && logLines.length > LOG_MAX + LOG_BATCH) {
    logLines = logLines.slice(logLines.length - LOG_MAX);
    el.textContent = logLines.join('\n') + '\n';
  } else {
    el.textContent += line + '\n';
  }
  el.scrollTop = el.scrollHeight;
}
function escapeHtml(s) { return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

let gameRunning = false;
function setLaunchButton(running) {
  gameRunning = running;
  const btn = $('launchBtn');
  btn.textContent = running ? t('btn_stop') : t('btn_launch');
  btn.classList.toggle('stop', running);
}

window.iea.on('status', setStatus);
window.iea.on('progress', (p) => setProgress(p.done, p.total));
window.iea.on('log', appendLog);
window.iea.on('exit', () => { setLaunchButton(false); setProgress(0, 0); });

$('launchBtn').addEventListener('click', async () => {
  // While the game is running this button acts as STOP.
  if (gameRunning) {
    setStatus(t('status_stopping'));
    await window.iea.stop();
    return;
  }
  if (!currentAccount) {
    setStatus(t('status_login_first'));
    return;
  }
  setLaunchButton(true);
  setStatus(t('status_preparing'));
  const res = await window.iea.launch({ account: currentAccount });
  if (!res.ok) {
    setStatus(`${t('status_error')}: ${res.error}`);
    setLaunchButton(false);
    setProgress(0, 0);
  }
});

$('clearLogBtn').addEventListener('click', () => { logLines = []; $('logConsole').textContent = ''; });

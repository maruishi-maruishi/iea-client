'use strict';

const { app, dialog } = require('electron');

// Update via electron-updater + GitHub Releases. The whole launcher (with the bundled
// iea-agent.jar) is replaced, so one release ships both the UI and the game agent.
// Behaviour: NOTIFY when a newer version exists and let the user choose whether to download
// and install it — no silent background download, no forced install. Runs only in the
// packaged app; every failure is non-fatal so a broken/offline check never blocks the app.
function initAutoUpdate(getWindow) {
  if (!app.isPackaged) {
    console.log('[update] dev build — auto-update disabled');
    return;
  }

  let autoUpdater;
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch (e) {
    console.error('[update] electron-updater unavailable:', e && e.message);
    return;
  }

  autoUpdater.autoDownload = false;          // ask before downloading (user-initiated)
  autoUpdater.autoInstallOnAppQuit = false;  // don't force-install on quit

  const win = () => { const w = getWindow && getWindow(); return (w && !w.isDestroyed()) ? w : null; };

  autoUpdater.on('error', (err) =>
    console.error('[update] error:', err ? (err.stack || err).toString() : 'unknown'));
  autoUpdater.on('checking-for-update', () => console.log('[update] checking…'));
  autoUpdater.on('update-not-available', () => console.log('[update] up to date'));

  // A newer version exists: notify and let the user opt in to downloading it.
  autoUpdater.on('update-available', async (info) => {
    const v = info && info.version ? info.version : '';
    console.log('[update] available:', v);
    try {
      const res = await dialog.showMessageBox(win(), {
        type: 'info',
        buttons: ['今すぐ更新', '後で'],
        defaultId: 0,
        cancelId: 1,
        noLink: true,
        title: 'IEA Client の更新',
        message: `新しいバージョン ${v} が利用可能です。`,
        detail: '「今すぐ更新」でダウンロードし、再起動して適用します。「後で」を選んでも次回起動時にまた確認できます。',
      });
      if (res.response === 0) {
        autoUpdater.downloadUpdate().catch((e) => console.error('[update] download failed:', e && e.message));
      }
    } catch (e) {
      console.error('[update] prompt failed:', e && e.message);
    }
  });

  autoUpdater.on('download-progress', (p) => {
    const w = win();
    if (w) { try { w.setProgressBar((p.percent || 0) / 100); } catch (_) {} }
  });

  autoUpdater.on('update-downloaded', async (info) => {
    const w = win();
    if (w) { try { w.setProgressBar(-1); } catch (_) {} }
    try {
      const res = await dialog.showMessageBox(w, {
        type: 'info',
        buttons: ['再起動して更新', '後で'],
        defaultId: 0,
        cancelId: 1,
        noLink: true,
        title: 'IEA Client の更新',
        message: `バージョン ${info && info.version ? info.version : ''} の準備ができました。`,
        detail: '再起動すると更新が適用されます。「後で」を選ぶと、次に終了したときに適用されます。',
      });
      if (res.response === 0) setImmediate(() => autoUpdater.quitAndInstall());
      else autoUpdater.autoInstallOnAppQuit = true; // they waited — apply on the next quit
    } catch (e) {
      console.error('[update] prompt failed:', e && e.message);
    }
  });

  // check shortly after startup so the window can paint first
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch((e) => console.error('[update] check failed:', e && e.message));
  }, 3000);
}

module.exports = { initAutoUpdate };

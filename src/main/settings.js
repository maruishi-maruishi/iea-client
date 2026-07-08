'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  versionId: '1.8.9',
  language: 'ja',      // UI language: 'ja' | 'en'
  javaPath: '',        // empty = use "java" from PATH
  gameDir: '',         // empty = default location under userData
  maxRamMB: 2048,
  minRamMB: 512,
  injectClient: true, // inject the IEA Java agent (the modified-client features)
  capLog: true,       // cap the launcher log console to recent lines (saves memory)
  hypixelKey: '',     // Hypixel API key -> written to iea-hypixel-key.txt for LevelHead
  optimizeJvm: true,  // apply G1GC performance flags + first-run perf options.txt
  useOptifine: false, // OptiFine integration disabled (IEA + OptiFine was too buggy); vanilla + IEA only
  lastUsername: 'Player',
  account: null,       // the ACTIVE account (offline or microsoft) used to launch
  accounts: [],        // all saved accounts, for the account switcher
  discordRpc: true,    // show Discord Rich Presence while the launcher/game is open
};

// Stable id for an account (one entry per type+uuid).
function accountId(acc) {
  return acc ? `${acc.type}:${acc.uuid}` : null;
}

function settingsFile(userDataDir) {
  return path.join(userDataDir, 'launcher-settings.json');
}

function loadSettings(userDataDir) {
  const file = settingsFile(userDataDir);
  try {
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    return Object.assign({}, DEFAULTS, data);
  } catch (_) {
    return Object.assign({}, DEFAULTS);
  }
}

function saveSettings(userDataDir, settings) {
  const file = settingsFile(userDataDir);
  const merged = Object.assign(loadSettings(userDataDir), settings);
  fs.writeFileSync(file, JSON.stringify(merged, null, 2));
  return merged;
}

// ---- account switcher helpers ----------------------------------------------

/** All saved accounts (each with an `id`) plus the id of the active one. */
function listAccounts(userDataDir) {
  const s = loadSettings(userDataDir);
  const accounts = (s.accounts || []).map((a) => Object.assign({ id: accountId(a) }, a));
  return { accounts, activeId: accountId(s.account) };
}

/** Add or update an account and make it active. Returns the new account list. */
function upsertAccount(userDataDir, acc) {
  const s = loadSettings(userDataDir);
  const withId = Object.assign({ id: accountId(acc) }, acc);
  const accounts = (s.accounts || []).slice();
  const idx = accounts.findIndex((a) => accountId(a) === withId.id);
  if (idx >= 0) accounts[idx] = withId; else accounts.push(withId);
  saveSettings(userDataDir, { accounts, account: withId, lastUsername: withId.name });
  return listAccounts(userDataDir);
}

/** Make a saved account (by id) the active one. */
function selectAccount(userDataDir, id) {
  const s = loadSettings(userDataDir);
  const acc = (s.accounts || []).find((a) => accountId(a) === id);
  if (acc) saveSettings(userDataDir, { account: acc, lastUsername: acc.name });
  return listAccounts(userDataDir);
}

/** Remove a saved account; if it was active, fall back to the first remaining one. */
function removeAccount(userDataDir, id) {
  const s = loadSettings(userDataDir);
  const accounts = (s.accounts || []).filter((a) => accountId(a) !== id);
  let account = s.account;
  if (accountId(account) === id) account = accounts[0] || null;
  saveSettings(userDataDir, { accounts, account });
  return listAccounts(userDataDir);
}

/** One-time migration: seed the accounts array from a pre-existing single account. */
function migrateAccounts(userDataDir) {
  const s = loadSettings(userDataDir);
  if ((!s.accounts || s.accounts.length === 0) && s.account) {
    upsertAccount(userDataDir, s.account);
  }
}

module.exports = {
  loadSettings, saveSettings, DEFAULTS, accountId,
  listAccounts, upsertAccount, selectAccount, removeAccount, migrateAccounts,
};

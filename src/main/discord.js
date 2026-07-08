'use strict';

// Discord Rich Presence — shows "IEA Client" while the launcher is open and the
// player's name while in-game. Needs a free Discord Application "Client ID"
// (create one at https://discord.com/developers/applications, then paste the
// Application ID below). Everything is best-effort: if the `discord-rpc` package
// or a running Discord desktop client is missing, this silently does nothing, so
// the launcher never breaks because of it.

// >>> Paste your Discord Application (Client) ID here to enable Rich Presence. <<<
const CLIENT_ID = '';

// Upload a "logo" art asset in the Discord app's Rich Presence → Art Assets to
// show the icon; otherwise the presence still shows the text lines.
const LARGE_IMAGE = 'logo';

let RPC = null;
try { RPC = require('discord-rpc'); } catch (_) { RPC = null; }

let client = null;
let ready = false;
let want = false;     // whether the user wants RPC on
let current = null;   // last activity we set (re-applied once the client is ready)
const launchedAt = Date.now();

function apply(activity) {
  current = activity;
  if (client && ready) client.setActivity(activity).catch(() => {});
}

function idleActivity() {
  return {
    details: 'IEA Client',
    state: 'In the launcher',
    startTimestamp: launchedAt,
    largeImageKey: LARGE_IMAGE,
    largeImageText: 'IEA Client · Minecraft 1.8.9',
    instance: false,
  };
}

function start() {
  want = true;
  if (!RPC || !CLIENT_ID) return; // no package or no app id -> no-op
  if (client) { apply(current || idleActivity()); return; }
  try {
    RPC.register(CLIENT_ID);
    client = new RPC.Client({ transport: 'ipc' });
    client.on('ready', () => { ready = true; apply(current || idleActivity()); });
    client.login({ clientId: CLIENT_ID }).catch(() => { client = null; ready = false; });
  } catch (_) { client = null; ready = false; }
}

function setPlaying(name) {
  if (!want) return;
  apply({
    details: 'Playing Minecraft 1.8.9',
    state: name ? `as ${name}` : undefined,
    startTimestamp: Date.now(),
    largeImageKey: LARGE_IMAGE,
    largeImageText: 'IEA Client',
    instance: false,
  });
}

function setIdle() {
  if (!want) return;
  apply(idleActivity());
}

function stop() {
  want = false;
  current = null;
  if (client) {
    try { client.clearActivity().catch(() => {}); } catch (_) {}
    try { client.destroy().catch(() => {}); } catch (_) {}
  }
  client = null;
  ready = false;
}

module.exports = { start, setPlaying, setIdle, stop };

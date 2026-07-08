'use strict';

// Player skin images for the launcher UI. The renderer's CSP forbids remote
// images, so we fetch them here in the main process and hand the renderer a
// self-contained data: URL. Results are cached in memory for the session.
//
// mc-heads.net renders a face avatar and a full-body model. For a registered
// player it uses their real skin (and its slim/classic model automatically). For
// a player with no custom skin (offline / default), we substitute the matching
// default account — MHF_Alex (slim) or MHF_Steve (classic) — so the previewed arm
// model still matches the model we resolved from the UUID.

const { getBuffer } = require('./minecraft/http');

const cache = new Map();     // key `${kind}:${id}` -> data URL
const infoCache = new Map(); // id -> { model: 'slim'|'classic', hasSkin: bool }

function undash(uuid) {
  return String(uuid || '').replace(/-/g, '');
}

// Minecraft's default model for a player with no custom skin: derived from the
// UUID exactly like vanilla (DefaultPlayerSkin) — (uuid.hashCode() & 1) == 1 -> slim.
function defaultModel(uuid) {
  const hex = undash(uuid);
  if (hex.length !== 32) return 'classic';
  try {
    const msb = BigInt('0x' + hex.slice(0, 16));
    const lsb = BigInt('0x' + hex.slice(16, 32));
    const hilo = msb ^ lsb;
    const asInt = BigInt.asIntN(32, (hilo >> 32n) ^ hilo); // Java UUID.hashCode()
    return (asInt & 1n) === 1n ? 'slim' : 'classic';
  } catch (_) {
    return 'classic';
  }
}

// Resolve { model, hasSkin } for a UUID. Registered accounts: read the skin's
// metadata.model from the Mojang profile. Offline / unregistered / no custom
// skin: hasSkin=false and the model falls back to the UUID-derived default.
async function skinInfo(uuid) {
  const id = undash(uuid) || 'default';
  if (infoCache.has(id)) return infoCache.get(id);
  let model = null;
  let hasSkin = false;
  try {
    const buf = await getBuffer(
      `https://sessionserver.mojang.com/session/minecraft/profile/${id}`,
      { 'User-Agent': 'iea-client' }
    );
    const json = JSON.parse(buf.toString('utf8'));
    const prop = (json.properties || []).find((p) => p.name === 'textures');
    if (prop) {
      const tex = JSON.parse(Buffer.from(prop.value, 'base64').toString('utf8'));
      const skin = tex.textures && tex.textures.SKIN;
      if (skin && skin.url) {
        hasSkin = true;
        model = (skin.metadata && skin.metadata.model === 'slim') ? 'slim' : 'classic';
      }
    }
  } catch (_) { /* offline / unregistered / rate-limited */ }
  if (!model) model = defaultModel(uuid);
  const info = { model, hasSkin };
  infoCache.set(id, info);
  return info;
}

async function modelOf(uuid) {
  return (await skinInfo(uuid)).model;
}

async function fetchDataUrl(key, url) {
  if (cache.has(key)) return cache.get(key);
  try {
    const buf = await getBuffer(url, { 'User-Agent': 'iea-client' });
    if (!buf || buf.length < 100) return null; // not a real image
    const dataUrl = 'data:image/png;base64,' + buf.toString('base64');
    cache.set(key, dataUrl);
    return dataUrl;
  } catch (_) {
    return null; // don't cache failures, so it can retry later
  }
}

async function faceUrl(uuid) {
  const info = await skinInfo(uuid);
  // The face (head only) is model-independent; for a skinless player use the
  // default account matching the model so the face isn't a random Steve.
  const id = info.hasSkin ? (undash(uuid) || 'MHF_Steve')
    : (info.model === 'slim' ? 'MHF_Alex' : 'MHF_Steve');
  return fetchDataUrl(`avatar:${id}`, `https://mc-heads.net/avatar/${id}/64`);
}

// Full-body render honouring the arm model (slim/wide). We pass the model we
// resolved EXPLICITLY (?model=) — NMSR then renders slim (3px) vs wide (4px) arms
// correctly, which mc-heads did not always auto-detect. NMSR renders a registered
// player's real skin, or a default skin for a skinless/offline UUID; either way the
// forced model makes the arms match our label. mc-heads is a fallback if NMSR is down.
async function bodyUrl(uuid, model) {
  const info = await skinInfo(uuid);
  if (!model) model = info.model;
  const id = undash(uuid);
  if (!id) return null;
  const arm = model === 'slim' ? 'slim' : 'wide';
  const nmsr = await fetchDataUrl(`body:${id}:${arm}`,
    `https://nmsr.nickac.dev/fullbody/${id}?model=${arm}`);
  if (nmsr) return nmsr;
  const fb = info.hasSkin ? id : (arm === 'slim' ? 'MHF_Alex' : 'MHF_Steve');
  return fetchDataUrl(`bodyfb:${fb}`, `https://mc-heads.net/body/${fb}/128`);
}

module.exports = { faceUrl, bodyUrl, modelOf };

'use strict';

// Convert a modern Java resource pack (1.9–1.21) into a 1.8.9-compatible pack.
//
// 1.8.9 refuses packs whose pack.mcmeta pack_format isn't 1, and 1.13's
// "flattening" moved/renamed hundreds of block/item textures. We rewrite the
// mcmeta to pack_format 1 and remap texture paths + names back to the 1.8.9
// layout (see flatmap.js). Models/blockstates are dropped so 1.8.9 uses its
// built-in models, which reference the (now restored) 1.8.9 texture names.
// This is texture-focused: full fidelity (custom models, CTM, sounds) is not
// possible, but typical texture packs come out mostly working.

const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const { oldBlockName, oldItemName } = require('./flatmap');

// Electron's nativeImage (Chromium PNG codec), used to re-encode PNG formats that
// 1.8.9's old texture loader can't read. Lazy + optional so this module still runs
// under plain node (tests) where electron isn't available.
let _nativeImage;
function nativeImage() {
  if (_nativeImage === undefined) {
    try { _nativeImage = require('electron').nativeImage || null; } catch (_) { _nativeImage = null; }
  }
  return _nativeImage;
}

// 1.8.9 renders GRAYSCALE (colour type 0 / 4) and 16-bit PNGs as solid white — its
// texture loader only handles indexed / RGB / RGBA at 8-bit. Detect those from the
// IHDR so we can re-encode just the problem files.
function pngNeedsReencode(buf) {
  if (!buf || buf.length < 26 || buf.toString('ascii', 1, 4) !== 'PNG') return false;
  const depth = buf[24], colorType = buf[25];
  return colorType === 0 || colorType === 4 || depth === 16;
}

// Re-encode a PNG to standard 8-bit RGB/RGBA via Chromium's codec (fixes grayscale).
function normalizePng(buf) {
  const ni = nativeImage();
  if (!ni) return buf; // no electron (tests) -> leave as-is
  try {
    const img = ni.createFromBuffer(buf);
    if (img.isEmpty()) return buf;
    const out = img.toPNG();
    return (out && out.length > 8) ? out : buf;
  } catch (_) { return buf; }
}

// ---- source abstraction (a folder or a .zip) --------------------------------

function openSource(srcPath) {
  const stat = fs.statSync(srcPath);
  if (stat.isDirectory()) {
    const entries = [];
    (function walk(dir, rel) {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name);
        const r = rel ? rel + '/' + e.name : e.name;
        if (e.isDirectory()) walk(full, r);
        else entries.push(r);
      }
    })(srcPath, '');
    return { entries, read: (rel) => fs.readFileSync(path.join(srcPath, rel.split('/').join(path.sep))) };
  }
  const zip = new AdmZip(srcPath);
  const map = new Map();
  for (const e of zip.getEntries()) if (!e.isDirectory) map.set(e.entryName.replace(/\\/g, '/'), e);
  return { entries: [...map.keys()], read: (rel) => map.get(rel).getData() };
}

// Packs are sometimes nested one folder deep inside the zip; find where
// pack.mcmeta lives and treat that as the pack root.
function findRoot(entries) {
  let best = null;
  for (const e of entries) {
    if (e.endsWith('pack.mcmeta')) {
      const prefix = e.slice(0, e.length - 'pack.mcmeta'.length);
      if (best === null || prefix.length < best.length) best = prefix;
    }
  }
  return best; // null if none
}

// ---- detection --------------------------------------------------------------

/** Inspect a pack: { kind, packFormat, name }. kind: java-native | java-new | bedrock | unknown. */
function detectPack(srcPath) {
  const name = path.basename(srcPath).replace(/\.(zip|mcpack|mcaddon)$/i, '');
  let src;
  try { src = openSource(srcPath); } catch (_) { return { kind: 'unknown', name }; }
  const root = findRoot(src.entries);
  if (root === null) {
    const hasManifest = src.entries.some((e) => e.endsWith('manifest.json'));
    return { kind: hasManifest ? 'bedrock' : 'unknown', name };
  }
  let packFormat = null;
  try {
    const meta = JSON.parse(src.read(root + 'pack.mcmeta').toString('utf8'));
    packFormat = meta && meta.pack && meta.pack.pack_format;
  } catch (_) { /* malformed mcmeta */ }
  const kind = (packFormat === 1) ? 'java-native' : 'java-new';
  return { kind, packFormat, name };
}

// ---- conversion -------------------------------------------------------------

// Flatten a (possibly rich-text) pack.mcmeta description to a plain string,
// since 1.8.9 expects a string.
function descText(desc) {
  if (desc == null) return '';
  if (typeof desc === 'string') return desc;
  if (Array.isArray(desc)) return desc.map(descText).join('');
  if (typeof desc === 'object') return (desc.text || '') + (desc.extra ? descText(desc.extra) : '');
  return String(desc);
}

function splitSuffix(file) {
  if (file.endsWith('.png.mcmeta')) return [file.slice(0, -11), '.png.mcmeta'];
  if (file.endsWith('.mcmeta')) return [file.slice(0, -7), '.mcmeta'];
  const dot = file.lastIndexOf('.');
  return dot >= 0 ? [file.slice(0, dot), file.slice(dot)] : [file, ''];
}

// Map a path under textures/ from the new layout to the 1.8.9 layout.
// Returns null to drop the file.
function mapTexturePath(rest) {
  const parts = rest.split('/');
  const top = parts[0];
  if (top !== 'block' && top !== 'item') return rest; // gui/entity/etc. unchanged
  const folder = top === 'block' ? 'blocks' : 'items';
  const file = parts[parts.length - 1];
  const mid = parts.slice(1, -1);
  const [base, suffix] = splitSuffix(file);
  const mapped = (top === 'block' ? oldBlockName(base) : oldItemName(base)) || base;
  return [folder, ...mid, mapped + suffix].join('/');
}

function safeName(name) {
  return name.replace(/[<>:"/\\|?*§]/g, '_').replace(/\s+/g, ' ').trim() || 'pack';
}

function stripBom(s) { return s.charCodeAt(0) === 0xFEFF ? s.slice(1) : s; }

function pngSize(buf) {
  if (!buf || buf.length < 24 || buf.toString('ascii', 1, 4) !== 'PNG') return null;
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

// Write a texture: fix grayscale/16-bit PNGs (1.8.9 whites them), and for a
// vertical-strip PNG (Bedrock flipbook animation) with no sibling .mcmeta, emit a
// default 1.8.9 animation .mcmeta so it animates instead of rendering white.
function writeTexture(outPath, buf, hasOwnMeta) {
  let fixed = 0, anim = 0;
  const isPng = outPath.toLowerCase().endsWith('.png');
  if (isPng && pngNeedsReencode(buf)) { const n = normalizePng(buf); if (n !== buf) fixed = 1; buf = n; }
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, buf);
  if (isPng && !hasOwnMeta) {
    const sz = pngSize(buf);
    const metaPath = outPath + '.mcmeta';
    if (sz && sz.w > 0 && sz.h > sz.w && sz.h % sz.w === 0 && !fs.existsSync(metaPath)) {
      fs.writeFileSync(metaPath, JSON.stringify({ animation: {} }));
      anim = 1;
    }
  }
  return { fixed, anim };
}

/**
 * Convert the pack at srcPath into destRoot (the game's resourcepacks folder).
 * Returns { name, packFormat, files, skipped }. emit(type,payload) reports progress.
 */
function convertJavaPack(srcPath, destRoot, emit = () => {}) {
  const src = openSource(srcPath);
  const root = findRoot(src.entries);
  if (root === null) throw new Error('pack.mcmeta not found (not a Java resource pack).');

  let packFormat = null, description = '';
  try {
    const meta = JSON.parse(src.read(root + 'pack.mcmeta').toString('utf8'));
    packFormat = meta && meta.pack && meta.pack.pack_format;
    description = descText(meta && meta.pack && meta.pack.description);
  } catch (_) { /* keep defaults */ }

  const baseName = safeName(path.basename(srcPath).replace(/\.(zip|mcpack|mcaddon)$/i, ''));
  const outName = `${baseName} (IEA 1.8.9)`;
  const outDir = path.join(destRoot, outName);
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });

  // rewritten pack.mcmeta (pack_format 1, string description)
  fs.writeFileSync(path.join(outDir, 'pack.mcmeta'), JSON.stringify({
    pack: { pack_format: 1, description: (description || 'Converted for IEA 1.8.9').slice(0, 255) },
  }, null, 2));

  // pack icon
  if (src.entries.includes(root + 'pack.png')) {
    try { fs.writeFileSync(path.join(outDir, 'pack.png'), src.read(root + 'pack.png')); } catch (_) {}
  }

  let files = 0, skipped = 0, fixed = 0;
  for (const entry of src.entries) {
    if (!entry.startsWith(root + 'assets/')) continue;
    const rel = entry.slice(root.length); // assets/<ns>/...
    const seg = rel.split('/');
    // assets/<ns>/<category>/...
    if (seg.length < 4 || seg[0] !== 'assets') { skipped++; continue; }
    const ns = seg[1];
    const category = seg[2];
    if (category !== 'textures') { skipped++; continue; } // drop models/blockstates/sounds/lang/...
    const under = seg.slice(3).join('/'); // path under textures/
    const mappedUnder = mapTexturePath(under);
    if (!mappedUnder) { skipped++; continue; }
    const outRel = path.join('assets', ns, 'textures', ...mappedUnder.split('/'));
    const outPath = path.join(outDir, outRel);
    try {
      let data = src.read(entry);
      // Re-encode grayscale/16-bit PNGs that 1.8.9 would render as white.
      if (outPath.toLowerCase().endsWith('.png') && pngNeedsReencode(data)) {
        const norm = normalizePng(data);
        if (norm !== data) fixed++;
        data = norm;
      }
      fs.mkdirSync(path.dirname(outPath), { recursive: true });
      fs.writeFileSync(outPath, data);
      files++;
    } catch (_) { skipped++; }
  }

  emit('log', `Converted "${baseName}": ${files} textures (pack_format ${packFormat} -> 1), `
    + `${fixed} grayscale PNGs fixed, ${skipped} skipped.`);
  return { name: outName, packFormat, files, fixed, skipped, dir: outDir };
}

// ---- Bedrock (統合版) conversion -------------------------------------------

// Roots inside a .mcpack/.mcaddon that hold a resource pack (a manifest.json with
// textures under it). A .mcaddon can bundle several packs; behaviour packs (no
// textures) are ignored.
function bedrockRoots(entries) {
  const roots = [];
  for (const e of entries) {
    if (e.endsWith('manifest.json')) {
      const prefix = e.slice(0, e.length - 'manifest.json'.length);
      if (entries.some((x) => x.startsWith(prefix + 'textures/'))) roots.push(prefix);
    }
  }
  if (roots.length === 0) { // no manifest but textures present -> treat that level as root
    for (const e of entries) {
      const i = e.indexOf('textures/blocks/');
      const j = e.indexOf('textures/items/');
      const k = i >= 0 ? i : j;
      if (k >= 0) { const p = e.slice(0, k); if (!roots.includes(p)) roots.push(p); }
    }
  }
  return roots;
}

/**
 * Convert a Bedrock resource pack (.mcpack/.mcaddon) to 1.8.9. Bedrock's block/item
 * texture file names are essentially the pre-flattening Java names, so we copy
 * textures/blocks and textures/items across (dropping Bedrock-only "_carried"
 * inventory variants), fix grayscale PNGs, and synthesise animation .mcmeta for
 * flipbook strips. manifest.json becomes a pack_format-1 pack.mcmeta. Returns an
 * array (one entry per bundled resource pack).
 */
function convertBedrockPack(srcPath, destRoot, emit = () => {}) {
  const src = openSource(srcPath);
  const roots = bedrockRoots(src.entries);
  if (!roots.length) throw new Error('No Bedrock textures found (textures/blocks or textures/items).');

  const results = [];
  let idx = 0;
  for (const root of roots) {
    idx++;
    let name = path.basename(srcPath).replace(/\.(mcpack|mcaddon|zip)$/i, '');
    let description = '';
    if (src.entries.includes(root + 'manifest.json')) {
      try {
        const m = JSON.parse(stripBom(src.read(root + 'manifest.json').toString('utf8')));
        if (m.header && m.header.name) name = String(m.header.name);
        if (m.header && m.header.description) description = String(m.header.description);
      } catch (_) { /* keep filename */ }
    }
    if (roots.length > 1) name += ' #' + idx;
    const outName = `${safeName(name)} (IEA 1.8.9)`;
    const outDir = path.join(destRoot, outName);
    fs.rmSync(outDir, { recursive: true, force: true });
    fs.mkdirSync(outDir, { recursive: true });

    fs.writeFileSync(path.join(outDir, 'pack.mcmeta'), JSON.stringify({
      pack: { pack_format: 1, description: (descText(description) || 'Converted for IEA 1.8.9').slice(0, 255) },
    }, null, 2));
    for (const icon of ['pack_icon.png']) {
      if (src.entries.includes(root + icon)) {
        try { fs.writeFileSync(path.join(outDir, 'pack.png'), src.read(root + icon)); } catch (_) {}
      }
    }

    let files = 0, skipped = 0, fixed = 0, anim = 0;
    const texPrefix = root + 'textures/';
    for (const entry of src.entries) {
      if (!entry.startsWith(texPrefix)) continue;
      const rel = entry.slice(texPrefix.length); // e.g. blocks/stone.png
      const seg = rel.split('/');
      const top = seg[0];
      if (top !== 'blocks' && top !== 'items') { skipped++; continue; } // focus on blocks/items
      const file = seg[seg.length - 1];
      const [base, suffix] = splitSuffix(file);
      if (!suffix || (suffix !== '.png' && suffix !== '.png.mcmeta' && suffix !== '.mcmeta')) { skipped++; continue; }
      if (/_carried$/.test(base)) { skipped++; continue; } // Bedrock-only inventory variants
      const outRel = path.join('assets', 'minecraft', 'textures', top, ...seg.slice(1, -1), base + suffix);
      const outPath = path.join(outDir, outRel);
      try {
        const hasOwnMeta = src.entries.includes(entry + '.mcmeta');
        const r = writeTexture(outPath, src.read(entry), hasOwnMeta);
        files++; fixed += r.fixed; anim += r.anim;
      } catch (_) { skipped++; }
    }
    emit('log', `Bedrock "${name}": ${files} textures, ${fixed} grayscale-fixed, ${anim} animated, ${skipped} skipped.`);
    results.push({ name: outName, files, fixed, anim, skipped, dir: outDir });
  }
  return results;
}

module.exports = { detectPack, convertJavaPack, convertBedrockPack };

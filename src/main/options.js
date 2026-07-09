'use strict';

// Read/write Minecraft's active resource pack list in options.txt. 1.8.9 writes
// options.txt in the JVM's default charset — cp932 on Japanese Windows, cp1252 on
// western Windows, etc. — so pack names with § colour codes get mangled if read as
// UTF-8. We auto-detect the charset by decoding with several candidates and keeping
// the one whose resourcePacks names actually match the resourcepacks folder.

const fs = require('fs');
const path = require('path');
const iconv = require('iconv-lite');

const CHARSETS = ['utf8', 'cp932', 'win1252', 'latin1'];

function optionsPath(gameDir) { return path.join(gameDir, 'options.txt'); }

function parseArray(txt) {
  const m = /^resourcePacks:(.*)$/m.exec(txt);
  if (!m) return null;
  try { return JSON.parse(m[1]); } catch (_) { return null; }
}

function detect(gameDir, folderNames) {
  const p = optionsPath(gameDir);
  let buf; try { buf = fs.readFileSync(p); } catch (_) { return { charset: 'utf8', names: [], buf: null }; }
  const folders = new Set(folderNames || []);
  let best = { charset: 'utf8', names: [], score: -1, buf };
  for (const cs of CHARSETS) {
    let txt; try { txt = iconv.decode(buf, cs); } catch (_) { continue; }
    const arr = parseArray(txt);
    if (!arr) continue;
    const score = arr.filter((n) => folders.has(n)).length;
    if (score > best.score) best = { charset: cs, names: arr, score, buf };
  }
  return best;
}

/** Names of the currently-active resource packs (charset-detected). */
function activeNames(gameDir, folderNames) {
  return detect(gameDir, folderNames).names || [];
}

// Build a JSON array literal WITHOUT escaping non-ASCII (JSON.stringify would emit
// \uXXXX; Minecraft stores the raw § characters in its charset instead).
function arrayLiteral(names) {
  return '[' + names.map((n) => '"' + String(n).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"').join(',') + ']';
}

/** Overwrite the active pack list, preserving options.txt's detected charset. */
function setActive(gameDir, names, folderNames) {
  const det = detect(gameDir, folderNames);
  const cs = det.charset;
  let txt = det.buf ? iconv.decode(det.buf, cs) : '';
  const line = 'resourcePacks:' + arrayLiteral(names);
  if (/^resourcePacks:.*$/m.test(txt)) txt = txt.replace(/^resourcePacks:.*$/m, line);
  else txt = txt + (txt && !txt.endsWith('\n') ? '\n' : '') + line + '\n';
  fs.mkdirSync(gameDir, { recursive: true });
  fs.writeFileSync(optionsPath(gameDir), iconv.encode(txt, cs));
  return names;
}

module.exports = { activeNames, setActive };

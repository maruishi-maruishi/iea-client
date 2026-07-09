'use strict';

// "What's new" panel data: the project's GitHub Releases. Reuses the release
// notes we already publish, so the launcher shows its own changelog. Cached for
// a few minutes; every failure is non-fatal (returns the last cache or []).

const { getJSON } = require('./minecraft/http');

const REPO = 'maruishi-maruishi/iea-client';
const TTL = 5 * 60 * 1000; // 5 min

let cache = null;
let cachedAt = 0;

async function getNews() {
  const now = Date.now();
  if (cache && now - cachedAt < TTL) return cache;
  try {
    const arr = await getJSON(
      `https://api.github.com/repos/${REPO}/releases?per_page=6`,
      { 'User-Agent': 'iea-client', 'Accept': 'application/vnd.github+json' }
    );
    const releases = (Array.isArray(arr) ? arr : [])
      .filter((r) => !r.draft)
      .map((r) => ({
        tag: r.tag_name,
        name: r.name || r.tag_name,
        body: (r.body || '').trim(),
        date: r.published_at,
        url: r.html_url,
        prerelease: !!r.prerelease,
      }));
    cache = releases;
    cachedAt = now;
    return releases;
  } catch (_) {
    return cache || [];
  }
}

module.exports = { getNews };

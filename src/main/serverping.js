'use strict';

// Minecraft Server List Ping (1.7+ protocol) over TCP: returns a server's
// online status, player counts, MOTD and latency. Used by the Servers tab.

const net = require('net');
const dns = require('dns');

// Minecraft resolves _minecraft._tcp.<host> SRV records (e.g. hypixel.net ->
// mc.hypixel.net). Do the same when no explicit port was given.
function resolveSrv(host) {
  return new Promise((resolve) => {
    dns.resolveSrv('_minecraft._tcp.' + host, (err, records) => {
      if (err || !records || !records.length) return resolve(null);
      const r = records[0];
      resolve({ host: r.name, port: r.port });
    });
  });
}

function writeVarInt(v) {
  const bytes = [];
  let value = v >>> 0;
  do {
    let temp = value & 0x7f;
    value >>>= 7;
    if (value !== 0) temp |= 0x80;
    bytes.push(temp);
  } while (value !== 0);
  return Buffer.from(bytes);
}

function writeString(s) {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([writeVarInt(b.length), b]);
}

function packet(id, ...parts) {
  const data = Buffer.concat([writeVarInt(id), ...parts]);
  return Buffer.concat([writeVarInt(data.length), data]);
}

function readVarInt(buf, offset) {
  let numRead = 0, result = 0, byte;
  do {
    if (offset + numRead >= buf.length) return null;
    byte = buf[offset + numRead];
    result |= (byte & 0x7f) << (7 * numRead);
    numRead++;
    if (numRead > 5) throw new Error('VarInt too big');
  } while ((byte & 0x80) !== 0);
  return { value: result >>> 0, size: numRead };
}

function parseHostPort(address) {
  let host = String(address || '').trim();
  let port = 25565;
  const m = /^(.+):(\d+)$/.exec(host);
  if (m) { host = m[1]; port = parseInt(m[2], 10); }
  return { host, port };
}

function motdText(desc) {
  if (desc == null) return '';
  if (typeof desc === 'string') return desc;
  let out = desc.text || '';
  if (Array.isArray(desc.extra)) out += desc.extra.map(motdText).join('');
  return out;
}

/** Ping a server. Resolves to { online, ping, players, max, motd, version }. */
async function ping(address, timeout = 4000) {
  let { host, port } = parseHostPort(address);
  if (!host) return { online: false };
  // no explicit ":port" -> try an SRV record like the vanilla client does
  if (!/:\d+$/.test(String(address).trim())) {
    const srv = await resolveSrv(host);
    if (srv) { host = srv.host; port = srv.port; }
  }
  return new Promise((resolve) => {
    let done = false;
    let stage = 'status';
    let buf = Buffer.alloc(0);
    let t0 = 0;
    let status = null;
    const socket = net.createConnection({ host, port });
    const finish = (r) => { if (done) return; done = true; try { socket.destroy(); } catch (_) {} resolve(r); };
    socket.setTimeout(timeout);
    socket.on('timeout', () => finish(status ? Object.assign({}, status, { ping: Date.now() - t0 }) : { online: false }));
    socket.on('error', () => finish({ online: false }));
    socket.on('connect', () => {
      const handshake = packet(0x00, writeVarInt(47), writeString(host),
        Buffer.from([(port >> 8) & 0xff, port & 0xff]), writeVarInt(1));
      socket.write(Buffer.concat([handshake, packet(0x00)])); // + status request
    });
    socket.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      try {
        if (stage === 'status') {
          const lenR = readVarInt(buf, 0); if (!lenR) return;
          const total = lenR.size + lenR.value;
          if (buf.length < total) return; // wait for the full packet
          let off = lenR.size;
          const idR = readVarInt(buf, off); off += idR.size;
          const strR = readVarInt(buf, off); off += strR.size;
          const data = JSON.parse(buf.slice(off, off + strR.value).toString('utf8'));
          const players = data.players || {};
          status = {
            online: true,
            players: players.online != null ? players.online : 0,
            max: players.max != null ? players.max : 0,
            motd: motdText(data.description),
            version: (data.version && data.version.name) || '',
            ping: 0,
          };
          buf = buf.slice(total);
          stage = 'pong';
          t0 = Date.now();
          socket.write(packet(0x01, Buffer.from([0, 0, 0, 0, 0, 0, 0, 1]))); // ping
          setTimeout(() => finish(Object.assign({}, status, { ping: Date.now() - t0 })), 1500);
        } else {
          finish(Object.assign({}, status, { ping: Date.now() - t0 }));
        }
      } catch (e) { finish(status || { online: false }); }
    });
  });
}

module.exports = { ping, parseHostPort };

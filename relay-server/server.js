'use strict';

/**
 * Family Link internet relay
 * ==========================
 *
 * A tiny WebSocket relay that lets a Family Link hub (Android server) and a
 * linked device (Android client) find each other and exchange their existing
 * JSON messages over the internet — even when both phones are behind NAT or
 * CGNAT, because BOTH sides dial out to this server.
 *
 * How it works
 * ------------
 *  1. After pairing, both phones can derive the same two secrets from the
 *     pairing key WITHOUT sending the key itself:
 *        roomId = sha256("family-link-room-v1:<KEY>").hex.slice(0, 24)
 *        token  = sha256("family-link-token-v1:<KEY>").hex
 *  2. Each phone opens a WebSocket and sends one control frame:
 *        {"flink":true,"cmd":"register","role":"hub"|"client",
 *         "room":"<roomId>","token":"<token>","deviceName":"..."}
 *  3. Once both sides of a room are present, the relay sends
 *        {"flink":true,"event":"paired", ...}
 *     to both and from then on pipes every further frame (text or binary)
 *     between the two sockets VERBATIM. Those frames are the app's ordinary
 *     StreamMessage JSON — handshakes, screen frames, taps, notifications —
 *     exactly as used on the LAN, so the relay needs to understand none of
 *     them. End-to-end: only pairing-derived secrets travel here.
 *  4. When one side disconnects the peer receives
 *        {"flink":true,"event":"peer-left"}
 *     and whoever reconnects first waits in the room for the other.
 *
 * Control frames always carry "flink":true; the app's own messages use
 * "type" as their discriminator, so the two can never collide.
 *
 * Configuration (environment variables)
 * --------------------------------------
 *   PORT            listen port                      (default 8080)
 *   HOST            bind address                     (default 0.0.0.0)
 *   REGISTER_SECRET optional shared secret; clients must supply it as
 *                   ?secret=... in the WebSocket URL or the "secret" field
 *   TLS_CERT/TLS_KEY  serve wss:// directly (otherwise terminate TLS in
 *                   front with Caddy/nginx — recommended)
 *
 * Health check: GET /health  ->  {"ok":true,"rooms":N,"uptime":S}
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '8080', 10);
const HOST = process.env.HOST || '0.0.0.0';
const REGISTER_SECRET = process.env.REGISTER_SECRET || '';
const ROOM_ID_RE = /^[a-f0-9]{8,64}$/;
const MAX_PAYLOAD = 8 * 1024 * 1024; // generous headroom for screen frames

// room -> { token, hub, hubName, client, clientName }
const rooms = new Map();

function sendJson(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function httpHandler(req, res) {
  if (req.url && req.url.split('?')[0] === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size, uptime: Math.floor(process.uptime()) }));
    return;
  }
  res.writeHead(426, { 'Content-Type': 'text/plain' });
  res.end('Family Link relay: connect with a WebSocket (wss://...).');
}

let httpServer;
if (process.env.TLS_CERT && process.env.TLS_KEY) {
  httpServer = https.createServer(
    {
      cert: fs.readFileSync(process.env.TLS_CERT),
      key: fs.readFileSync(process.env.TLS_KEY),
    },
    httpHandler
  );
  console.log('[relay] TLS enabled (direct wss://)');
} else {
  httpServer = http.createServer(httpHandler);
  console.log('[relay] plain HTTP/WS — put TLS in front (Caddy/nginx) or set TLS_CERT/TLS_KEY');
}

const wss = new WebSocketServer({ server: httpServer, maxPayload: MAX_PAYLOAD });

function urlSecret(req) {
  try {
    const u = new URL(req.url, 'http://localhost');
    return u.searchParams.get('secret') || '';
  } catch (_) {
    return '';
  }
}

function maybePair(entry) {
  if (entry.hub && entry.client) {
    sendJson(entry.hub, { flink: true, event: 'paired', role: 'client', deviceName: entry.clientName });
    sendJson(entry.client, { flink: true, event: 'paired', role: 'hub', deviceName: entry.hubName });
  }
}

function cleanup(ws) {
  const roomId = ws.roomId;
  ws.roomId = null;
  ws.role = null;
  if (!roomId) return;
  const entry = rooms.get(roomId);
  if (!entry) return;
  let peer = null;
  if (entry.hub === ws) {
    entry.hub = null;
    peer = entry.client;
  } else if (entry.client === ws) {
    entry.client = null;
    peer = entry.hub;
  }
  if (!entry.hub && !entry.client) {
    rooms.delete(roomId);
  }
  if (peer && peer !== ws) {
    sendJson(peer, { flink: true, event: 'peer-left' });
  }
}

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data, isBinary) => {
    // Registered connections: forward everything to the peer verbatim.
    if (ws.roomId) {
      const entry = rooms.get(ws.roomId);
      if (!entry) return;
      const peer = ws.role === 'hub' ? entry.client : entry.hub;
      if (peer && peer.readyState === peer.OPEN) {
        peer.send(data, { binary: isBinary });
      }
      return;
    }

    // Unregistered connections: accept exactly one register frame.
    if (isBinary) return;
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch (_) {
      return;
    }
    if (!msg || msg.flink !== true || msg.cmd !== 'register') return;

    if (REGISTER_SECRET) {
      const supplied = String(msg.secret || '') || urlSecret(req);
      if (supplied !== REGISTER_SECRET) {
        sendJson(ws, { flink: true, event: 'error', message: 'bad secret' });
        ws.close(4003, 'unauthorized');
        return;
      }
    }

    const room = String(msg.room || '');
    const token = String(msg.token || '');
    if (!ROOM_ID_RE.test(room) || token.length < 8) {
      sendJson(ws, { flink: true, event: 'error', message: 'invalid room or token' });
      ws.close(4003, 'invalid');
      return;
    }

    const role = msg.role === 'hub' ? 'hub' : 'client';
    let entry = rooms.get(room);
    if (!entry) {
      entry = { token, hub: null, hubName: '', client: null, clientName: '' };
      rooms.set(room, entry);
    } else if (entry.token !== token) {
      sendJson(ws, { flink: true, event: 'error', message: 'room token mismatch' });
      ws.close(4003, 'unauthorized');
      return;
    }

    // Replace a stale connection of the same role (e.g. hub re-dialing).
    if (entry[role] && entry[role] !== ws) {
      const stale = entry[role];
      entry[role] = null;
      try { stale.close(4000, 'replaced'); } catch (_) { /* noop */ }
    }

    entry[role] = ws;
    entry[`${role}Name`] = String(msg.deviceName || '').slice(0, 64);
    ws.roomId = room;
    ws.role = role;

    sendJson(ws, { flink: true, event: 'registered', role });
    maybePair(entry);
    console.log(`[relay] ${role} joined room ${room.slice(0, 6)}… (${rooms.size} rooms)`);
  });

  ws.on('close', () => cleanup(ws));
  ws.on('error', () => cleanup(ws));
});

// Drop dead connections so rooms don't hold half-open sockets.
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      ws.terminate();
      return;
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30_000);
heartbeat.unref();
wss.on('close', () => clearInterval(heartbeat));

httpServer.listen(PORT, HOST, () => {
  console.log(`[relay] Family Link relay listening on ${HOST}:${PORT}`);
  if (REGISTER_SECRET) console.log('[relay] REGISTER_SECRET is set: clients must authenticate');
});

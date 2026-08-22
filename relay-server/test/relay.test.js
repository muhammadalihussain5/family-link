'use strict';

/**
 * End-to-end test for the relay server: spins up server.js on a random port,
 * then simulates a hub and a client exactly the way the Android app behaves
 * (room/token derived from a shared pairing key, register control frames,
 * then verbatim StreamMessage JSON piping).
 *
 * Run:  npm test     (from relay-server/)
 */

const crypto = require('crypto');
const { spawn } = require('child_process');
const path = require('path');
const WebSocket = require('ws');

const PORT = 18080 + Math.floor(Math.random() * 2000);

function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}
const roomFor = (key) => sha256Hex(`family-link-room-v1:${key.toUpperCase()}`).slice(0, 24);
const tokenFor = (key) => sha256Hex(`family-link-token-v1:${key.toUpperCase()}`);

const results = [];
function check(name, ok) {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
}

/** Buffers every incoming frame so no message is ever dropped by a race. */
class TestPeer {
  constructor(ws) {
    this.ws = ws;
    this.queue = [];
    this.waiters = [];
    ws.on('message', (data, isBinary) => {
      const item = { data, isBinary };
      const waiter = this.waiters.shift();
      if (waiter) waiter(item);
      else this.queue.push(item);
    });
  }

  next(timeoutMs = 4000) {
    const buffered = this.queue.shift();
    if (buffered) return Promise.resolve(buffered);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout waiting for message')), timeoutMs);
      this.waiters.push((item) => {
        clearTimeout(timer);
        resolve(item);
      });
    });
  }

  async nextJson(timeoutMs) {
    return JSON.parse((await this.next(timeoutMs)).data.toString());
  }

  send(obj) {
    if (Buffer.isBuffer(obj)) {
      this.ws.send(obj, { binary: true });
    } else {
      this.ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj));
    }
  }

  close() {
    this.ws.close();
  }
}

function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const server = spawn('node', [path.join(__dirname, '..', 'server.js')], {
    env: { ...process.env, PORT: String(PORT), HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  server.stdout.on('data', (d) => process.env.VERBOSE && process.stdout.write(`[srv] ${d}`));
  server.stderr.on('data', (d) => process.stderr.write(`[srv-err] ${d}`));

  const url = `ws://127.0.0.1:${PORT}`;
  try {
    // Wait for the server to come up.
    let up = false;
    for (let i = 0; i < 50 && !up; i++) {
      try {
        const probe = await connect(url);
        probe.close();
        up = true;
      } catch (_) {
        await sleep(100);
      }
    }
    if (!up) throw new Error('relay did not start');

    const key = 'AB12-CD34';
    const room = roomFor(key);
    const token = tokenFor(key);

    // 1. Hub registers first and waits.
    const hub = new TestPeer(await connect(url));
    hub.send({ flink: true, cmd: 'register', role: 'hub', room, token, deviceName: 'Parent Hub' });
    check('hub receives registered', (await hub.nextJson()).event === 'registered');

    // 2. Client registers → both get "paired".
    const client = new TestPeer(await connect(url));
    client.send({ flink: true, cmd: 'register', role: 'client', room, token, deviceName: 'Kid Phone' });
    check('client receives registered', (await client.nextJson()).event === 'registered');
    const hubPaired = await hub.nextJson();
    check('hub receives paired with device name', hubPaired.event === 'paired' && hubPaired.deviceName === 'Kid Phone');
    const clientPaired = await client.nextJson();
    check('client receives paired with hub name', clientPaired.event === 'paired' && clientPaired.deviceName === 'Parent Hub');

    // 3. Client sends the app's real handshake JSON → hub receives it verbatim.
    client.send({ type: 'Handshake', deviceId: 'dev-1', pairingKey: key, deviceName: 'Kid Phone' });
    const handshake = await hub.nextJson();
    check('handshake forwarded verbatim', handshake.type === 'Handshake' && handshake.pairingKey === key);

    // 4. Hub replies with an ack → client receives it.
    hub.send({ type: 'HandshakeAck', accepted: true, serverDeviceId: 'hub-1' });
    const ack = await client.nextJson();
    check('handshake ack forwarded verbatim', ack.type === 'HandshakeAck' && ack.accepted === true);

    // 5. Binary frames (screen/audio data) are forwarded too.
    hub.send(Buffer.from([1, 2, 3, 4, 5]));
    const bin = await client.next();
    check('binary frames forwarded', bin.isBinary && Buffer.compare(bin.data, Buffer.from([1, 2, 3, 4, 5])) === 0);

    // 6. Large stream frames pass through untouched.
    const big = JSON.stringify({ type: 'ScreenFrame', data: new Array(600).fill(1), note: '{"flink":true}' });
    client.send(big);
    const forwarded = (await hub.next()).data.toString();
    check('large stream frames forwarded untouched', forwarded === big);

    // 7. Client disconnects → hub is told the peer left.
    client.close();
    const peerLeft = await hub.nextJson();
    check('hub notified of peer-left', peerLeft.event === 'peer-left');

    // 8. Client reconnects → both are paired again and messages flow.
    const client2 = new TestPeer(await connect(url));
    client2.send({ flink: true, cmd: 'register', role: 'client', room, token, deviceName: 'Kid Phone' });
    check('reconnect registered', (await client2.nextJson()).event === 'registered');
    check('re-pairing works after reconnect', (await hub.nextJson()).event === 'paired');
    client2.send({ type: 'Heartbeat' });
    check('messages flow again after re-pair', (await hub.nextJson()).type === 'Heartbeat');
    client2.close();
    await hub.nextJson(); // peer-left

    // 9. Wrong token is rejected.
    const intruder = new TestPeer(await connect(url));
    intruder.send({ flink: true, cmd: 'register', role: 'client', room, token: tokenFor('WRONG-KEY'), deviceName: 'Evil' });
    const rejected = await intruder.nextJson();
    check('wrong token rejected', rejected.event === 'error');
    intruder.close();

    // 10. Stale role replacement: a second hub connection replaces the first.
    const hub2 = new TestPeer(await connect(url));
    hub2.send({ flink: true, cmd: 'register', role: 'hub', room, token, deviceName: 'Parent Hub 2' });
    check('second hub registers', (await hub2.nextJson()).event === 'registered');
    const hubOldClosed = new Promise((resolve) => hub.ws.once('close', resolve));
    await hubOldClosed;
    check('old hub socket is replaced', true);
    hub2.close();

    // 11. Health endpoint answers.
    const health = await fetch(`http://127.0.0.1:${PORT}/health`).then((r) => r.json());
    check('health endpoint responds', health.ok === true && typeof health.rooms === 'number');
  } finally {
    server.kill();
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((e) => {
  console.error('Test crashed:', e);
  process.exit(1);
});

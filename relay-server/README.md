# Family Link relay server

A tiny Node.js WebSocket relay that keeps Family Link devices connected **over
the internet**, anywhere in the world, with **no port forwarding** and no
exposed home network. Both phones dial *out* to this server, so it works
behind NAT, CGNAT (mobile data) and firewalls.

```
   ┌──────────────┐        ┌───────────────┐        ┌──────────────┐
   │  Client app  │◄──────►│  This relay   │◄──────►│   Hub app    │
   │ (child phone)│  ws/wss│ (this server) │  ws/wss│ (parent phone)│
   └──────────────┘        └───────────────┘        └──────────────┘
```

The relay never decrypts or understands the traffic: after both sides of a
"room" are present, it pipes WebSocket frames between the two phones verbatim.

## How pairing maps to rooms

During pairing, the hub stores the client's pairing key (`XXXX-XXXX`). Both
phones can then derive — without ever sending the key itself:

```
roomId = sha256("family-link-room-v1:" + KEY.toUpperCase()).hex  → first 24 chars
token  = sha256("family-link-token-v1:" + KEY.toUpperCase()).hex → full 64 chars
```

Each phone opens a WebSocket and sends:

```json
{"flink":true,"cmd":"register","role":"hub"|"client",
 "room":"<roomId>","token":"<token>","deviceName":"..."}
```

When both are present the relay replies `{"flink":true,"event":"paired"}` to
each and starts forwarding. On disconnect the peer gets
`{"flink":true,"event":"peer-left"}` and whoever reconnects waits in the room.
An attacker who does not know the pairing key cannot compute the room or the
token, so they cannot join or watch. For defense in depth, still run the relay
behind TLS (`wss://`) — see below.

## Run it

```bash
cd relay-server
npm install
npm start                 # listens on 0.0.0.0:8080
npm test                  # end-to-end test simulating a hub + a client
```

### Environment variables

| Variable          | Default | Meaning                                                       |
| ----------------- | ------- | ------------------------------------------------------------- |
| `PORT`            | `8080`  | Listen port                                                    |
| `HOST`            | `0.0.0.0` | Bind address                                                 |
| `REGISTER_SECRET` | _(off)_ | If set, devices must append `?secret=…` to the relay URL       |
| `TLS_CERT`,`TLS_KEY` | _(off)_ | Serve `wss://` directly (else terminate TLS in front)      |

Health check: `GET /health` → `{"ok":true,"rooms":N,"uptime":S}`.

## Deploy

### Any VPS (Ubuntu/Debian) with systemd + Caddy TLS

1. Install Node 18+ and copy this folder to the server, then:

```bash
cd relay-server
npm install --omit=dev
sudo npm i -g pm2            # or use the systemd unit below
```

2. Easiest TLS: point a domain (e.g. `relay.example.com`) at the VPS and run
   [Caddy](https://caddyserver.com), which auto-issues a Let's Encrypt cert:

```
# /etc/caddy/Caddyfile
relay.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

3. systemd unit (`/etc/systemd/system/family-link-relay.service`):

```ini
[Unit]
Description=Family Link relay
After=network-online.target

[Service]
WorkingDirectory=/opt/family-link-relay
ExecStart=/usr/bin/node server.js
Environment=PORT=8080
Environment=HOST=127.0.0.1   # only Caddy talks to it directly
Restart=always
RestartSec=3
User=relay

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now family-link-relay
```

### Docker

```bash
docker build -t family-link-relay .
docker run -d --name relay -p 8080:8080 --restart unless-stopped family-link-relay
```

Put Caddy/nginx/Cloudflare in front for TLS. For small personal use,
Fly.io/Render free tiers also work — expose the port and add HTTPS at the edge.

### Then in the app

On **both** phones: *Settings → Internet relay* → enter the address, e.g.
`wss://relay.example.com` (add `/?secret=…` if you set `REGISTER_SECRET`).
On the hub, the relay activates as soon as a device is paired; on the client
it activates once paired. Everything else (screen share, remote taps,
notifications, audio) then works over the internet.

## Notes & limits

- Bandwidth: screen mirroring over the relay is throttled to a lower frame
  rate automatically. A ~1 TB/month VPS plan is plenty for family use.
- The relay keeps no logs of message content and stores nothing on disk; rooms
  live only in memory while a connection exists.
- One relay instance can host many independent families — each pairing key
  gets its own private room.

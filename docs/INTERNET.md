# Making Family Link work across the internet

The app is LAN-first: discovery and direct connections happen on the local
Wi‑Fi. To keep a hub and a linked device connected when they are on
**different networks** (different houses, mobile data, etc.) you have three
options. Option 1 (the relay) is the one this repo ships code for — see the
`relay-server/` folder.

---

## Option 1 — Relay server (recommended; ships with this repo)

Both phones always **dial out** to a small public server you run, which pipes
their traffic to each other. Works behind any NAT/CGNAT, survives IP changes,
nothing is exposed on your home network, and phones switch between Wi‑Fi and
mobile data without you doing anything.

**What you need**

1. A small always-on server reachable from the internet:
   - a cheap VPS (Hetzner, DigitalOcean, Vultr, …) with a domain or static IP, or
   - a free-tier host like Fly.io / Render, or
   - even a Raspberry Pi at a relative's home with port forwarding + dynamic DNS
     (least reliable).
2. A domain name (optional but recommended) so TLS is easy, e.g.
   `relay.example.com`.

**Steps**

1. Copy the `relay-server/` folder to the server.
2. `npm install && npm start` — it listens on port 8080 (see
   `relay-server/README.md` for systemd, Docker, and env options).
3. Put TLS in front (this is important — otherwise pairing keys, screen
   frames and audio cross the internet unencrypted):
   - easiest: [Caddy](https://caddyserver.com) with a one-line reverse proxy
     gets a free Let's Encrypt certificate automatically, or
   - `TLS_CERT`/`TLS_KEY` env vars for direct `wss://`, or
   - Cloudflare/nginx.
4. On the **hub**: *Family Link → Settings → Internet relay* → enter
   `wss://relay.example.com`.
5. Pair the devices as usual: the hub scans the client QR (or types the key),
   and the client scans the hub's invite QR. The invite carries the relay
   address, so the client joins the relay room the moment it scans — the very
   first pairing works over the internet, no shared Wi‑Fi needed. (Already
   paired before setting the relay up? Enter the same address on the client in
   *Settings → Internet relay*; a paired client always uses the relay when one
   is configured.) From then on they are linked over the internet too — the same
   dashboard, screen share, remote taps, notifications and audio work.

Bandwidth note: mirroring is automatically throttled to a lower frame rate
when going through the relay. Audio adds ~32 kB/s while sharing.

**Costs/scale:** one relay instance serves many families — every pairing key
gets its own private room and nobody else can join it without deriving the
same room+token from that key.

---

## Option 2 — VPN overlay (zero servers, very safe)

Install [Tailscale](https://tailscale.com) (easiest), ZeroTier or WireGuard on
**both phones** and log them into the same virtual network. Each phone gets a
stable private IP (e.g. `100.x.y.z`).

- On the client, use *Connect to a hub* once with the hub's VPN IP and port
  `8080`, after the hub has paired the device.
- Everything then behaves exactly like a LAN link; traffic is end-to-end
  encrypted by the VPN.
- Downsides: the VPN app must stay active on both phones (battery, occasional
  reconnects), and screen sharing is not throttled so it uses more bandwidth
  over slow links.

## Option 3 — Port forwarding (no extra software, least robust)

Forward TCP `8080` on the hub's router to the hub's Wi‑Fi IP (give the hub a
DHCP reservation first). Then on the client use *Connect to a hub* with the
house's public IP (or a dynamic-DNS hostname like `myhome.duckdns.org`).

- Requires an ISP that gives a public IPv4 (CGNAT breaks this), and the hub
  phone must stay on that Wi‑Fi.
- **Strongly** tunnel TLS in front (the hub listens on plain TCP), keep the
  pairing key secret, and expect to re-enter the address when the public IP
  changes. Prefer options 1 or 2.

---

## Security notes

- Over `wss://` (option 1) everything is encrypted in transit. Without TLS
  (plain `ws://`, or option 3) pairing keys, screen frames and audio are
  visible to the network path — use TLS.
- The relay never sees the pairing key: phones only send a room id and token
  derived from it (`SHA-256`), and the relay just pipes opaque frames between
  the two devices.
- Pairing is still required over the internet: a hub never accepts a device
  whose key it has not scanned/entered, regardless of transport.

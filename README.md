# Family Link

A dual-mode Android connector for two phones. One device is the **Family Hub**
(server). The other is a **linked client** that stays connected in the
background — on the same Wi‑Fi or across the internet through an optional
relay.

## What it does

1. **Dual-mode setup** — first launch chooses Hub or Client. The choice is stored in DataStore.
2. **Secure, explicit pairing** — the client generates a unique `XXXX-XXXX` key and QR code. The hub scans it (or you type the key) before the devices trust each other. Nothing connects before that first explicit pairing; after it, both sides remember the pairing forever — across restarts and reboots — until someone disconnects on purpose (see below).
3. **Always-on client** — a boot-started foreground service keeps the link alive and reconnects automatically to the *paired hub only* (matched by the hub's device id, never to a random hub on the network).
4. **Hub-driven screen mirroring & remote touch** — the hub starts and stops screen sharing by itself. The client approves the system projection dialog **once**; after that the hub can restart sharing remotely (instantly while the projection is alive, or from the saved consent after the service restarts — until the client phone reboots, or on Android 14+ via a one-tap notification when the OS requires fresh consent). Taps and swipes on the mirrored picture are injected with an Accessibility Service.
5. **Audio listening** — while the screen is shared, the client's currently playing audio is captured (Android 10+ playback capture) and played on the hub, with a mute toggle. Grant the *Microphone* permission on the client to enable it.
6. **Notification sync** — a Notification Listener (with Accessibility as backup) relays alerts to the hub dashboard.
7. **Permission desk** — one screen for Accessibility, notification access, microphone, status notifications, and battery exemptions.

## Pairing and disconnecting

**First time (explicit, never automatic)**

1. Open Family Link on both phones and pick **Hub** on the parent device, **Linked device** on the child device.
2. Grant the client permissions.
3. On the hub, tap the scan icon and read the client QR (or enter the printed key). The hub now *expects* this device — until then it rejects every connection.
4. On the client, connect to the hub once: scan the hub's invite QR (top bar) or use **Connect to a hub** and type the hub's address.

Both phones now remember the pairing (DataStore). They reconnect automatically
on boot, after crashes, and across network changes — the client ignores hubs
it is not paired with.

**Disconnecting (deliberate, protected)**

- On the **client**, the *Disconnect* section on the dashboard unpairs the phone. Confirming requires the **hub's PIN** (the pairing key shown on the hub) or **scanning the hub's QR code** — so the link can't be quietly removed from the child device.
- On the **hub**, Settings → *Unpair device* requires the **client's** pairing key. The client can also send an unpair request itself (its Disconnect section tells the hub to forget it).

## Working across the internet

The app still works LAN-first, but now ships with a relay server so both
devices can stay linked from anywhere — both phones dial out, so no port
forwarding is needed and NAT/CGNAT is irrelevant.

1. Deploy the ready-made relay from **[`relay-server/`](relay-server/README.md)** on any small VPS (Node.js, one file; Docker and systemd recipes included) with TLS in front (Caddy makes it automatic).
2. On both phones: **Settings → Internet relay** → enter `wss://relay.example.com`.
3. That's it. Pairing, presence, screen sharing, audio, remote taps and notifications all work through the relay; the client automatically throttles the mirror frame rate for the slower link.

Alternatives (VPN overlay like Tailscale, or manual port forwarding) and
security notes are described in **[`docs/INTERNET.md`](docs/INTERNET.md)**.

## Project layout

```
app/src/main/java/com/hashmi/familylink/
  audio/       hub-side PCM playback of the client's audio
  data/        models, DataStore, QR / JSON helpers
  network/     Ktor WebSocket client/server, UDP discovery, relay protocol + tunnel
  service/     boot, link, capture, approval, accessibility, notifications
  ui/          Compose screens (Navigation 3 + Material Adaptive)
relay-server/  standalone Node.js relay for internet linking
docs/          INTERNET.md — making it work across the internet
```

Stack: Kotlin, Jetpack Compose, Navigation 3, DataStore, Coroutines / Flow,
Ktor WebSockets, MediaProjection + AudioPlaybackCapture, Accessibility
Service. Relay: Node.js + ws.

## Build

Open the project in Android Studio (API 26–37) or run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The relay server builds nothing — `npm install && npm start` (plus `npm test`).

Two physical devices or emulators on the same network work best. Screen
capture and accessibility require a real device for a full test.

## Known platform limits

- **Screen share consent**: Android requires a user tap on the system dialog
  per projection grant. The app stores the grant and reuses it while valid
  (across service restarts until reboot). On Android 14+ the OS may demand a
  fresh consent — then the client shows a one-tap approval notification
  instead. While the projection is alive, hub start/stop is always instant.
- **Audio capture** uses Android 10+ playback capture: it hears media/game
  audio, not phone calls; individual apps may opt out (e.g. some streaming
  apps), and DRM content is never captured.

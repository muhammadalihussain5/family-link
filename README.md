# Family Link

A dual-mode Android connector for two phones on the same Wi‑Fi network. One device is the **Family Hub** (server). The other is a **linked client** that stays connected in the background.

## What it does

1. **Dual-mode setup** — first launch chooses Hub or Client. The choice is stored in DataStore.
2. **Secure pairing** — the client generates a unique `XXXX-XXXX` key and QR code. The hub scans it (or you type the key) before the devices trust each other.
3. **Always-on client** — a boot-started foreground service keeps the WebSocket alive and reconnects automatically.
4. **Screen mirroring & remote touch** — MediaProjection streams JPEG frames to the hub. Taps and swipes on the mirrored picture are injected on the client with an Accessibility Service.
5. **Notification sync** — a Notification Listener (with Accessibility as backup) relays alerts to the hub dashboard.
6. **Permission desk** — one screen for Accessibility, notification access, status notifications, and battery exemptions.

## Pairing

**Usual flow**

1. Open Family Link on both phones and pick **Hub** on the parent device, **Linked device** on the child device.
2. Grant the client permissions.
3. On the hub, tap the scan icon and read the client QR (or enter the printed key).
4. Leave both phones on the same Wi‑Fi. The hub broadcasts its address; the client connects and sends a handshake.

**If discovery is blocked**

The hub can show its own invite QR (IP + port). Scan that from the client, or
use **Connect by address** on the client to type the hub's IP and port.

> Pairing is two-way: the hub authorizes the client's key (scan the client QR or
> type its key), and the client separately needs to reach the hub (auto-discovery,
> hub invite QR, or a manually entered address).

## Connecting over the internet

The app is LAN-first: it discovers and connects over the local network. To link
devices on different networks you need one of:

1. **Port forwarding (quick, manual).** In the hub's router, forward TCP `8080`
   to the hub's Wi‑Fi IP and give the hub a static/DHCP-reserved address. Then on
   the client use **Connect by address** with the hub's public IP (and the
   forwarded port). Public IPs change, so pair this with a dynamic-DNS hostname.
2. **VPN overlay (easiest and safest).** Install Tailscale, ZeroTier, or WireGuard
   on both phones. They join one virtual network with stable private IPs — no
   router changes. Point the client at the hub's VPN IP.
3. **Relay/rendezvous server (production approach).** Run a public WebSocket
   relay (e.g. on a VPS/Fly.io/Render) that both devices dial out to. This works
   behind any NAT and survives IP changes, but requires hosting a server and
   changing the networking layer to connect through it.

> **Security note:** the hub currently listens on plain TCP with no TLS and, when
> no key is set yet, accepts the first device that connects. Before exposing the
> hub to the internet, terminate TLS (WSS) in front of it and always pair with an
> explicit key. Prefer option 2 (VPN) so the raw socket never touches the public
> internet.

## Project layout

```
app/src/main/java/com/hashmi/familylink/
  data/        models, DataStore, QR / JSON helpers
  network/     Ktor WebSocket client/server + UDP discovery
  service/     boot, link, capture, accessibility, notifications
  ui/          Compose screens (Navigation 3 + Material Adaptive)
```

Stack: Kotlin, Jetpack Compose, Navigation 3, DataStore, Coroutines / Flow, Ktor WebSockets, MediaProjection, Accessibility Service.

## Build

Open the project in Android Studio (API 26–37) or run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Two physical devices or emulators on the same network work best. Screen capture and accessibility require a real device for a full test.

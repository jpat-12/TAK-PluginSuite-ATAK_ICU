# Changelog

## 2.3.0 — 2026-08-14

Adds the four field-requested broadcast capabilities: audio on the LAN path, a
persistent LAN multicast feed, reconnection across a network handoff, and
automatic portrait/landscape orientation. All on the ATAK plugin.

### LAN multicast (MPEG-TS)
- **New `udp://@group:port` multicast feed** for the LAN destination. An MPEG-TS
  muxer (`serve/ts/MpegTsMuxer`) packetizes H.264 **and AAC** into a transport
  stream sent to a multicast group (default `239.1.1.1:5600`), so any number of
  receivers on the LAN/mesh can play one URL — ATAK's own video player, VLC, and
  ffmpeg all read it. Runs **alongside** the existing on-device RTSP server, so
  RTSP-pull viewers keep working; nothing about the old LAN behavior changes.
- **Settings:** the persistent Destination toggle (LAN ↔ Server) now exposes a
  *LAN multicast* on/off plus the group + port, saved to prefs.

### Audio
- **Audio on the LAN path.** The multicast feed carries the mic (AAC) track, and
  the **on-device RTSP server** now advertises and serves a second AAC (RFC 3640)
  track too — so LAN viewers get audio, not just server-push viewers.

### Reconnect on network change
- **Wi-Fi ↔ LTE handoff no longer kills the stream.** A `NetworkMonitor` watches
  the default network; on a change it rebuilds the transports on the new network
  and re-advertises the feed (self-marker + server feed) without restarting the
  camera/encoder. Debounced so a bouncing handoff reconnects once.

### Orientation
- **Auto rotation** (Rotation → *Auto*) matches the encoded video to how the phone
  is held, driven by the device orientation sensor. Same-aspect flips update the
  GL rotation live; a portrait ↔ landscape change re-sizes the encoder via a brief
  restart. Manual rotations are unchanged.

## 2.2.8 — 2026-07-25

This release consolidates the 1.4.x → 2.2.x line: the plugin gained full
self-marker FOV broadcasting, TAK Server video-feed publishing, a redesigned
in-panel UI, and a hardened streaming/encoding path.

### TAK / CoT integration
- **Broadcast FOV from the operator's skittle.** The camera field-of-view wedge
  is carried on the operator's own self CoT (not a separate sensor marker), aimed
  by live compass heading, so peers see where the camera is pointing.
- **Configurable FOV refresh rate** with a forced self-report, so the wedge
  tracks the camera promptly instead of waiting on ATAK's stationary report
  interval (default 3s).
- **Radial-menu controls on the self marker** — start/stop broadcast and a
  dedicated **BLK/OUT** (blackout) button.
- **Publish the stream to the TAK Server Video Feed Manager** (`/Marti/vcm`) so
  it appears in the server's video feed list for other clients.
- **Callsign-based stream identity** — stream name, alias, and feed id default to
  the operator's callsign so two operators no longer collide on the same path.

### Streaming & encoding
- **Near-live playback:** force a ~1–2s keyframe cadence so browser/HLS players
  stay close to live instead of buffering 30–60s behind.
- **Real bitrate cap:** encode in CBR so the wire rate holds near the configured
  value on motion instead of overshooting (~80% on the default VBR) — important
  for a metered cellular budget. Default tuned to the 720p/30/2500 kbps profile.
- **RTSP Digest authentication** (username/password) for servers such as MediaMTX
  that require it, plus a show-password toggle in settings.
- **Blackout mode** — a fake screen-off that keeps the camera/encoder alive so the
  feed doesn't drop when the operator darkens the screen.
- **Screen-off streaming toggle** with a wake lock.

### UI
- **Settings redesigned as an in-panel page** with a back button (replacing the
  modal dialog), with a consistent design system (surfaces, spacing, typography).
- **Collapsible settings sections** with dropdown carets.
- **Dropdown fields now show a caret** so they read as pickers, not text inputs.
- **Home HUD reworked** to match the settings design system.
- **Aspect-correct camera preview** — the preview rotates and center-crops to fill
  its pane while preserving the source aspect ratio (no more stretched video).
- Removed the floating endpoint-URL overlay that sat over the live preview.

### Fixes
- **Self-marker no longer corrupts** — stopped writing local FOV metadata onto the
  self marker, which previously broke "lock on self" and left the FOV stuck on.
- **Fixed the Marti `/vcm` publish URL** (host-only base, single path segment).

### Housekeeping
- Removed the stale `ATAK/` directory left over from the `ATAK5.6` rename.
- Expanded `.gitignore` (secrets, IDE files, SDK jars, build output).
- Added a support link to the README.

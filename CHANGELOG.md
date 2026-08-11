# Changelog

## Unreleased

### ATAK-ICU features (both 5.6 and 5.7 trees)
- **The broadcast survives a network change.** Switching Wi-Fi to LTE, or hopping
  between APs, used to end the stream permanently and say nothing: the outbound
  socket is bound to an address the device no longer owns, TCP has no idea its
  interface went away, and neither push transport had any reconnect path. The
  encoder kept running, the transports kept writing into dead sockets, and the
  pane kept reporting `LIVE` while no viewer received anything.
  - A new `util/NetworkMonitor` watches the default network via
    `ConnectivityManager` and reports real moves, debounced by 1.5s so the churn
    of a handover doesn't trigger a dial over a network that is about to be
    replaced.
  - Both push transports now redial on an exponential backoff (1s doubling to a
    15s cap, 8 attempts, roughly a minute of grace) and only report a terminal
    `failureReason` once they give up. The attempt count resets whenever the
    network moves again. RTSP auth failures still fail fast, since a rejected
    credential will be rejected identically on every retry.
  - Redials also cover a connection lost without a network change, e.g. the media
    server restarting. `RtmpPublisher` and `RtspPusher` now record *why* a write
    failed instead of silently closing, and the transports poll that to trigger a
    dial rather than dropping frames for the rest of the broadcast.
  - The pane shows `Reconnecting…` with the live dot off during the gap, instead
    of a confident `LIVE` over a connection that does not exist.
  - On LAN the advertised URL embeds the device's own IP, which the change
    invalidates. It is now re-derived and pushed out on the self marker via
    `SelfMarkerFov.setUrl`, so peers get a working link within one FOV refresh.
- **Known limitation:** viewers already connected over the old interface still
  have to reconnect themselves. That half of the connection is theirs, and
  nothing on the broadcasting side can repair it.
- **Local MP4 recording.** The Record button now works: it muxes the broadcast's
  own encoded H.264 (plus the AAC track when mic audio is on) into
  `ATAK ICU/recordings/ICU_<timestamp>.mp4` under ATAK's external files directory. It
  attaches to the running capture pipeline as a second sink rather than opening a
  second camera/encoder, so recording costs nothing beyond the mux and the file
  matches what viewers saw frame-for-frame. Consequences, by design: recording
  requires an active broadcast, it starts at the first keyframe after the tap (up
  to one GOP later — MP4 can't open mid-GOP), and stopping or reconfiguring the
  broadcast finalizes the file. The button shows a live `REC m:ss` elapsed readout
  and turns red while recording.
- **Blackout button in the operator pane**, next to Broadcast / Record / Snapshot.
  Previously blackout was reachable only from the self-marker radial's **BLK/OUT**
  button; it now sits in the pane's quick-action rail as well. Same behavior: the
  screen is painted black at minimum brightness with the app still foreground, so
  capture keeps running, and a tap anywhere wakes it.

### ATAK-CIV 5.7 target
- **New `ATAK5.7/` tree** — ATAK-ICU 2.5.0 ported to the ATAK-CIV 5.7.0.7 SDK.
  `ATAK5.6/` is left in place, still building against 5.6.0. Both `civDebug` and
  `civRelease` build clean on 5.7; runtime behavior is unverified on a 5.7 device.
- **`ConnectionEntry` moved packages.** 5.7 drops the
  `com.atakmap.android.video.ConnectionEntry` shim; `share/VideoConnectionPublisher`
  now imports `gov.tak.api.video.ConnectionEntry`. This was the only source change
  the SDK bump required.
- Gradle wrapper 8.13 → 8.14.3, matching the 5.7 SDK samples. AGP 8.13.0,
  `compileSdk 36`, Java 17, `minSdk 21` / `targetSdk 34` all unchanged.
- `sdk.path` for 5.7 must point at the **inner** nested SDK directory; see
  `ATAK5.7/local.properties.example`.

## 2.5.0 — 2026-08-06

USB camera support, in-band KLV telemetry, and a set of capture fixes — two of
which were silently degrading every stream.

### Capture sources
- **USB / UVC webcams as a video source.** A plugged-in USB camera can now feed
  the encoder, via a non-rooted USB-host driver (`com.herohan:UVCAndroid`), for
  the common case where Android doesn't surface the device through Camera2 at all.
- **External cameras auto-detected in the picker** when the OS *does* expose them
  through Camera2 as `LENS_FACING_EXTERNAL`.
- **Automatic fallback to the built-in camera** when a USB source never appears,
  loses permission, or is unplugged mid-stream — the broadcast keeps running
  instead of ending. A feed watchdog also covers the silent case where a UVC
  device accepts `startPreview` and then delivers no frames, which raises no error.

### TAK / CoT integration
- **MISB ST 0601 KLV telemetry** (position, heading, real IMU pitch/roll, FOV)
  emitted at 1 Hz on its own RTP track, mirroring the WinTAK `KlvService` cadence.
  Carried on the LAN RTSP transport as `trackID=1`; players that only set up the
  video track are unaffected.

### Fixes
- **The stream no longer loses field of view.** Capture-size selection could fall
  back to 16:9 on any failure, with nothing logged, so on a 4:3 sensor every
  viewer received a vertically cropped frame and the fallback was
  indistinguishable from a correct choice. The full-FOV aspect is now derived
  from the camera itself and resolution is purely a height budget.
- **The preview now matches what's broadcast.** The preview surface was left at
  the pane's shape, so Camera2 center-cropped it and the operator saw a narrower
  view than viewers did.
- **KLV telemetry actually sends.** Every packet was being dropped by
  `NetworkOnMainThreadException` — the 1 Hz emitter ran its socket write on the
  UI thread.
- **Switching cameras while live works.** `CameraDevice.close()` is asynchronous,
  so the restart reopened before the previous device had released.
- **Preview no longer freezes** when the capture session is rebuilt while an
  earlier configuration is still in flight.

### Housekeeping
- **ABI filters restored and unified** — `armeabi-v7a`, `arm64-v8a`, `x86`,
  `x86_64`. Now that the plugin ships native code, these decide which devices can
  install it; the APK path had drifted to arm64-only during the P2P spike.
- **R8 keep rules for `org.webrtc`.** Its native code resolves classes by literal
  name through JNI, so minification renaming them aborts the host process with
  `SIGABRT` rather than throwing.
- The Phase 0 WebRTC probe is debug-only. It confirmed libwebrtc loads inside
  ATAK's plugin classloader and `PeerConnectionFactory` constructs.

## 2.4.0 — 2026-07-31

### Streaming & encoding
- **Stream rotation is now baked into the encoded pixels** via a GL stage between
  camera and encoder — raw H.264 over RTSP carries no rotation metadata, so
  viewers previously saw the wrong orientation.
- **Optional microphone audio** as a second AAC RTP track.
- **Native-aspect capture** so the stream isn't forced to 16:9.
- Audio on by default.

### TAK / CoT integration
- **FOV wedge range is configurable** (default 100 m).
- FOV clears on stop.

## 2.3.9 — 2026-07-28

### Fixes
- **Fixed an RTSP publish drop.**
- Added auth status reporting, feed credentials, and connection diagnostics.

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

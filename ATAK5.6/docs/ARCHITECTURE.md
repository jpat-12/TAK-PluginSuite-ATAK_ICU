# ICU VideoStreamer (ATAK) — Architecture Map

**Goal:** An ATAK plugin that uses the **phone's own camera** as the video source and
**broadcasts** the feed to other ATAK users — presenting a video pane and sharing
model that **mirror the UAS Tool** drone-video experience, but with no drone.

**Decisions locked in (2026-07-13):**
- **Separate from `ATAK-Plugin_TAK_ICU`.** Clean-room build in this suite; TAK_ICU is
  reference only, not a dependency. (It proves the capture→RTSP→CoT path works.)
- **UI target:** the **UAS Tool video-pane look** (operator feed panel + quick-bar).
- **Transport:** same model the UAS Tool uses — *serve one stream, advertise its URL
  over CoT* (see §1). The phone becomes the stream **source**.

> Scope of this document: the **map**, not the build. It defines the pipeline,
> components, the exact ATAK APIs involved, the UI plan, and a phased path. No
> Gradle project is scaffolded yet.

---

## 1. The core insight — how "broadcast" really works in ATAK

Reverse-engineering the UAS Tool APK + reading the ATAK SDK (`main.jar`) shows the
drone plugin never pushes pixels to peers. It does two **separable** jobs:

```
           ┌─────────────────────── DRONE / GIMBAL ───────────────────────┐
           │  Camera → H.264/H.265 encoder → RTSP/SRT/UDP server           │
           │  produces:  rtsp://<drone-ip>:554/stream                      │
           └───────────────────────────────┬──────────────────────────────┘
                                            │  (pull)
                        ┌───────────────────▼─────────────────────┐
   UAS Tool plugin ───► │ INGEST + DISPLAY                         │
                        │  com.partech.pgscmedia (GStreamer core)  │
                        │  → SurfaceVideoConsumer renders to pane  │
                        │  + KLV geo overlay + TFLite detections   │
                        └───────────────────┬─────────────────────┘
                                            │
                        ┌───────────────────▼─────────────────────┐
                        │ ADVERTISE OVER CoT                       │
                        │  gov.tak.api.video.ConnectionEntry       │
                        │  + ConnectionEntryDetail on a marker     │
                        │  ("Attach Video To Self Marker")         │
                        └───────────────────┬─────────────────────┘
                                            │  CoT broadcast (URL only)
              ┌─────────────────────────────▼────────────────────────────┐
              │ OTHER EUDs: tap marker → open SAME url in native player   │
              │ each viewer pulls the stream itself                       │
              └───────────────────────────────────────────────────────────┘
```

**Broadcast = serve one stream + advertise its URL via CoT. No pixel relaying.**

### What changes for a phone camera
The drone was supplying the **source** box (top). Remove the drone and **the phone
must become that box.** Everything below the "pull" arrow is reusable UAS-Tool-style
behavior. So our plugin = *"the drone's onboard camera+encoder+server"* **plus** the
UAS Tool's *"display + CoT-advertise"* halves, all on one device.

```
   ┌──────────────────────── THIS PLUGIN, ON THE PHONE ─────────────────────┐
   │                                                                        │
   │  Camera2 ─► MediaCodec H.264 ─► RTP packetizer ─► RTSP/SRT server ──┐   │
   │  (capture)   (hardware encode)   (payload)        (serve URL)       │   │
   │                     │                                               │   │
   │                     ▼ (tee raw frames / loopback)                   │   │
   │            local preview pane (UAS-Tool look) ◄─────────────────────┘   │
   │                     │                                                    │
   │                     ▼                                                    │
   │   ConnectionEntry + CoT video detail on self-marker  ──► network         │
   └────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Relevant ATAK SDK APIs (confirmed present in `main.jar`, ATAK-CIV 5.7.0)

| API | Package | Use |
|---|---|---|
| `ConnectionEntry`, `ConnectionEntryBase` (`Builder`, `Protocol`, `Source`) | `gov.tak.api.video` | Model a video stream (URL, protocol, alias) the way ATAK's video framework expects |
| `IVideoConnectionManager` (+ `ConnectionListener`) | `gov.tak.api.video` | Register/enumerate video connections with the host so the feed appears in ATAK's Video tool |
| `ConnectionEntryDetail`, `VideoDetailHandler` | `gov.tak.api.video.cot` | The **video-over-CoT** detail — attach a stream URL to a marker so peers can open it |
| `SurfaceVideoConsumer` | `com.partech.mobilevid` | Renders decoded video to an Android `Surface` — the actual video-pane renderer |
| `MediaMultiplexer`, `VideoConsumer`, `VideoMediaFormat`, `VideoFrameConverter` | `com.partech.pgscmedia[.*]` | ATAK's built-in GStreamer-backed decode pipeline (for the **local preview** of our own RTSP) |
| `IPlugin`, `IServiceController`, `Pane`/`PaneBuilder`, `ToolbarItem` | `gov.tak.api.plugin` / `gov.tak.api.ui` | Plugin lifecycle + tool registration (new-style plugin, same as TAK_ICU) |
| `MapView`, `Marker`, `CotEvent`/`CotDetail`, `AtakBroadcast` | `com.atakmap.android.*`, `com.atakmap.coremap.*` | Self-marker, CoT construction, intent bus |

> **Note on GStreamer:** ATAK core already ships the PGSC/GStreamer decoder
> (`com.partech.pgscmedia`). The UAS Tool bundles its **own** 136 MB
> `libgstreamer_android.so` — we do **not** need to. For our plugin the decoder is
> only used for the *local preview*; we can even preview straight off the encoder
> input Surface and skip a decode entirely (see §4, Option A).

---

## 3. Component map (proposed packages)

Base package: `com.atakmap.android.icu` (suite: ICU VideoStreamer). Clean-room, no
`takicu` code.

```
com.atakmap.android.icu
├── plugin/
│   ├── ICUVideoLifecycle        // IPlugin impl — registered in assets/plugin.xml
│   ├── ICUVideoTool             // ToolbarItem + Pane host
│   └── ICUMapComponent          // (if a DropDownReceiver route is preferred)
│
├── capture/                     // ← the "drone" we're replacing
│   ├── CameraSource             // Camera2: open, preview Surface, encoder Surface
│   ├── H264Encoder              // MediaCodec surface-input, SPS/PPS/NAL callback
│   └── EncoderConfig            // resolution / fps / bitrate / GOP / front|back
│
├── serve/                       // ← the "datalink"
│   ├── RtspServer               // RTSP + RTP/UDP (unicast); yields rtsp://ip:8554/live
│   ├── (optional) SrtServer     // SRT caller/listener if SRT is required later
│   └── RtpH264Packetizer        // NAL → RTP payload (RFC 6184)
│
├── pane/                        // ← the "UAS Tool video pane look"
│   ├── VideoPaneView            // the operator feed panel (mirrors UAS Tool layout)
│   ├── QuickBar                 // record / snapshot / front-back / share toggles
│   └── StatusOverlay            // LIVE badge, viewer count, bitrate, GPS stamp
│
├── share/                       // ← the "broadcast" (CoT advertise)
│   ├── VideoConnectionPublisher // builds ConnectionEntry, registers w/ host
│   └── VideoCotBroadcaster      // attaches video CoT detail to self-marker, refresh
│
└── util/
    ├── NetworkUtils             // resolve the phone's reachable IP for the URL
    └── Prefs                    // encoder + sharing preferences
```

Mapping to the UAS Tool's own structure (for parity of mental model):

| UAS Tool package | Our equivalent | Notes |
|---|---|---|
| `uastool.av` (RTSP-Push / SRT VMS) | `serve/` + `share/` | they *consume* a VMS URL; we *produce* one |
| `uastool.pagers` (ControlFragment, video pane) | `pane/` | same operator-pane concept |
| `uastool.quickbar` | `pane/QuickBar` | fly/look-at → our record/snapshot/share |
| `uastool.plugin.*Receiver` | `share/VideoCotBroadcaster` | CoT wiring |
| `uastool.tflite` (AI overlay) | *(future)* `pane/detect/` | optional later (see §7) |

---

## 4. The one hard part: preview + serve from a single camera

Android gives the camera to a small set of Surfaces. Two viable wirings:

**Option A — Encoder-Surface preview (recommended, lowest latency)**
`Camera2` outputs to **two** Surfaces simultaneously: (1) the `MediaCodec` input
Surface (→ H.264 → RTSP), and (2) a `TextureView`/`SurfaceView` for the local pane.
The pane shows the raw camera preview; peers get the encoded RTSP. No self-decode.
*Con:* the local pane isn't the exact post-encode image, but visually identical.

**Option B — Loopback decode (exact parity with viewers)**
Serve RTSP, then locally consume `rtsp://127.0.0.1:8554/live` through
`com.partech.pgscmedia` → `SurfaceVideoConsumer`. The pane then shows *precisely*
what viewers see, using the very same renderer ATAK/UAS Tool uses.
*Con:* extra encode→decode round-trip (latency + CPU).

→ **Start with Option A.** Switch specific panes to B only if exact-parity or
metadata-overlay-on-decoded-frame is needed.

TAK_ICU already proved Option A end-to-end (`VideoEncoder` does dual-Surface,
`RtspServer` serves it). We rebuild it clean here, but the shape is known-good.

---

## 5. UI plan — mirroring the UAS Tool video pane

Target look (from UAS Tool `pagers.ControlFragment` + `quickbar`):

```
┌───────────────────────────── ICU VideoStreamer ─────────────────────────────┐
│  ● LIVE  720p  2.0 Mbps        👁 3 viewers            rtsp://10.0.0.5:8554/live │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                        [ camera video preview fills pane ]                   │
│                                                                              │
│                        (tap-to-focus, pinch-zoom optional)                   │
│                                                                              │
├──────────────────────────────────────────────────────────────────────────────┤
│  [◉ Broadcast]  [⟲ Front/Back]  [◍ Snapshot]  [⏺ Record]  [⚙ Settings]  [📍 Share]│
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Pane host:** new-style `Pane` + `ToolbarItem` (same pattern TAK_ICU/other 5.7
  plugins use). Toolbar icon opens the pane.
- **Broadcast** toggle: start/stop `CameraSource`+`H264Encoder`+`RtspServer`, then
  `VideoConnectionPublisher.publish()` + `VideoCotBroadcaster.start()`.
- **Share** (📍): attaches/detaches the video CoT detail to the self-marker — the
  literal analog of UAS Tool's *"Attach Video To Self Marker."*
- **Status overlay:** LIVE badge, resolution/bitrate, viewer count (from
  `RtspServer` client list), and the copyable URL.
- Reuse UAS-Tool-style iconography (record/snapshot/gear) for familiarity; assets
  are our own (UAS Tool art is not redistributable).

---

## 6. Data / control flow (Broadcast pressed)

```
User taps Broadcast
  → CameraSource.open(back, 720p)
  → H264Encoder.start()  (Surface input; emits SPS/PPS + NAL via callback)
  → RtspServer.start(8554)  ; setSps/setPps ; feed NALs
  → url = "rtsp://" + NetworkUtils.reachableIp() + ":8554/live"
  → VideoPaneView shows preview Surface (Option A)
  → VideoConnectionPublisher.publish(ConnectionEntry(url, alias, H264/RTSP))
        → IVideoConnectionManager registers it  (appears in ATAK Video tool)
  → VideoCotBroadcaster.start(url)
        → build CoT with ConnectionEntryDetail on self-marker uid
        → AtakBroadcast / internal dispatcher sends it ; refresh every 30 s
Peers receive CoT → VideoDetailHandler parses → marker tap → native player pulls url
```

Stop reverses it (stop CoT refresh + send stale, unregister ConnectionEntry, stop
server, stop encoder, close camera).

---

## 7. Phased build path

| Phase | Deliverable | Proves |
|---|---|---|
| **0. Scaffold** | Gradle plugin project in `ATAK/` (AGP 8.9 / gradle 8.13 / ATAK-CIV 5.7.0 SDK, `main.jar` as `compileOnly`, keystore staging) mirroring your EFJohnson/FieldTAK template | Plugin loads in ATAK, empty pane opens |
| **1. Local capture+preview** | `CameraSource` + `H264Encoder` + `VideoPaneView` (Option A) | Phone camera shows in the pane |
| **2. Serve** | `RtspServer` + packetizer; verify from VLC/ffplay on same LAN | A real `rtsp://` URL others can pull |
| **3. Advertise** | `VideoConnectionPublisher` + `VideoCotBroadcaster` | Feed appears in *another* ATAK's Video tool via CoT |
| **4. UI polish** | Quick-bar, status overlay, settings, front/back, record/snapshot | The UAS-Tool-look operator pane |
| **5. (optional) SRT / server push** | `SrtServer` or push-to-TAK-Server-video | Scales beyond the local network |
| **6. (optional) AI overlay** | TFLite detector on preview frames (à la `uastool.tflite`) | Feature parity with drone plugin |

---

## 7b. Multi-protocol serving (RTSP / RTSPS / SRT / RTMP)

Goal: let viewers use **any** of RTSP, RTSPS, SRT, RTMP. Grounded in ATAK's actual
`gov.tak.api.video.ConnectionEntryBase.Protocol` enum, which plays
`RAW, UDP, RTSP, RTMP, RTMPS, HTTP, HTTPS, FILE, TCP, RTP, DIRECTORY, SRT` —
note **there is no RTSPS** in ATAK's list.

**Hard constraint:** a phone can only *originate* peer-pull for RTSP (and SRT-listener).
- **RTMP is push-only** — a publisher always pushes to a server; peers can't pull RTMP off a phone.
- **RTSPS** can't originate cleanly from the phone, and ATAK can't select it anyway.

So **"all four" requires a media server** (MediaMTX) on the LAN: the phone pushes once,
MediaMTX re-serves the stream as RTSP + RTSPS + SRT + RTMP (+ HLS/WebRTC) simultaneously,
and terminates TLS to make RTSPS real.

```
 PHONE (source) ──push once──► MediaMTX (LAN) ──► viewers pick any protocol
   RTSP/RTMP push                fan-out:            rtsp:// rtsps:// srt:// rtmp://
```

### Serve-layer design (implemented as a pluggable abstraction)

| Class | Kind | Protocols | Status |
|---|---|---|---|
| `serve/Transport` | interface | — | ✅ |
| `serve/TransportManager` | fan-out (`CapturePipeline.Sink`) | — | ✅ |
| `serve/OnDeviceRtspTransport` (wraps `RtspServer`) | on-device server | RTSP | ✅ Phase 2a — real, wired |
| `serve/RtmpPushTransport` (+ `serve/rtmp/*`) | push → MediaMTX | RTSP+RTSPS+SRT+RTMP (via server) | ✅ Phase 2b — pure-Java RTMP publisher, **untested vs. live server** |
| `serve/SrtTransport` | listener or push | SRT (encrypted) | ⬜ Phase 2c (needs native libsrt) |

The `serve/rtmp/` package is a self-contained RTMP publisher: `RtmpPublisher` (handshake,
chunk read/write, AMF0 command sequence connect→createStream→publish), `Amf0`
(encode/decode), `H264` (Annex-B → AVCC + AVCDecoderConfigurationRecord / FLV). No native code.

### Enabling all four (operator steps)
1. Run **MediaMTX** on a box on the LAN (one binary; default ports RTSP 8554, RTSPS 8322,
   SRT 8890, RTMP 1935).
2. In the pane, **long-press Broadcast** → enter the MediaMTX host + stream path (e.g. `icu`).
3. Broadcast. The phone publishes via RTMP; MediaMTX re-serves all four. The status line
   lists every viewer URL (`rtsp:// rtsps:// srt:// rtmp://`); Phase 3 will advertise them over CoT.
| `serve/MediaServerConfig` | server URLs | — | ✅ |

- **Zero-infra path:** `OnDeviceRtspTransport` alone — plain RTSP, LAN/mesh peer-pull. Works today.
- **All-four path:** configure a `MediaServerConfig` (host) → `RtmpPushTransport` publishes,
  and its `endpoints()` advertise the server's RTSP/RTSPS/SRT/RTMP URLs.
- **Encrypted-direct path:** `SrtTransport` in listener mode (AES, no server) — the better
  answer than RTSPS for a closed mesh.

`TransportManager` implements `CapturePipeline.Sink`, so the same encoded NAL stream is
fanned out to every enabled transport; Phase 3's CoT layer advertises whichever
`endpoints()` are live.

## 7c. Phase 3 — self marker → sensor + video (CoT)

While broadcasting, the operator's **self marker** carries the feed so teammates see a
sensor with a tappable live video, embedded directly in the position (PLI) message.

- `share/SelfBroadcastDetailHandler` — a `com.atakmap.android.cot.detail.CotDetailHandler`.
  Registered under a **private** detail name (so it never intercepts inbound `__video`/
  `sensor` — those still go to ATAK core). Its `toCotDetail` runs for every outbound item;
  it only decorates when `broadcasting == true` **and** the item is `MapView.getSelfMarker()`,
  appending:
  - `<__video uid url><ConnectionEntry …/></__video>` — standard TAK video detail
    (attribute names verified against `gov.tak.api.video.cot.VideoDetailHandler`), so
    receiving ATAKs parse it with their own core handler and show a tappable feed.
  - `<sensor fov azimuth range …/>` — a FOV cone (keys verified against
    `com.atakmap.android.cot.detail.SensorDetailHandler`); azimuth follows the marker heading.
- `share/SelfMarkerSensorController` — registers the handler with `CotDetailManager`,
  and on start points it at the reachable endpoint (prefers RTSP), flips it on; on stop
  flips it off. **Revert = stop emitting** — the self marker returns to the user's normal
  preferences on the next PLI. Nothing on the marker's identity is mutated, so no saved
  state to restore.

Open items: the change rides the **next periodic self PLI** (a few seconds); an
immediate forced report (`ReportingRate.setReportAsap`) needs a handle we don't hold yet.
`share/VideoConnectionPublisher` (register with the local `IVideoConnectionManager` so the
feed also appears in *this* device's Video tool) remains a Phase 3b stub.

## 8. Known risks / open questions

- **Reachability of the phone's RTSP URL.** On a TAK Server / mesh-radio network the
  phone's IP may not be directly routable to every peer (NAT, subnets). On-device
  serving works cleanly on a shared LAN/mesh; for wide dissemination Phase 5
  (push to a central video service) is the real answer. *Flag for later.*
- **Battery/thermal.** Continuous camera + encode + Wi-Fi serving is heavy; expose
  resolution/bitrate caps and an auto-stop.
- **Backgrounding.** ATAK panes can be dismissed; a foreground service may be needed
  to keep the camera+server alive while the operator uses the map. (Manifest already
  used a Room/`InitializationProvider` pattern in UAS Tool; we'll add a
  foreground `Service` here.)
- **Permissions.** Adds `CAMERA` (UAS Tool didn't need it — it never captured). Plus
  `INTERNET`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`.
- **Audio.** Out of scope for v1 (UAS video is typically video-only); revisit.
- **SRT vs RTSP.** UAS Tool ingests both; RTSP is simplest to serve on-device. SRT
  needs a native lib. Default RTSP for v1.

---

## 9. Reference material in this repo

- `ATAK-Working/ATAK-Plugin-uastool-13.0.4-Analysis.md` — full teardown of the drone
  plugin whose UX we mirror (video pane, CoT sharing, AI overlay).
- `../ATAK-Plugin_TAK_ICU/` (sibling, **reference only**) — a working proof of the
  capture→encode→RTSP→CoT path we rebuild clean here.

*Next concrete step: Phase 0 scaffold — say the word and I'll stand up the Gradle
plugin project in `ATAK/` from your EFJohnson/FieldTAK 5.7.0 template.*

# TAK-PluginSuite-ATAK_ICU

Turns a TAK end-user device's **own camera** into a live video source for the map —
broadcasting it to other users over the LAN/mesh or through a media server — instead
of requiring a drone or external video datalink. It's the drone-video experience
(à la ATAK's UAS Tool), minus the drone. This suite ships it for **two** TAK clients:
**ATAK-ICU** (Android plugin) and **WinTAK-ICU** (Windows plugin).

## The key insight

**These are two independent implementations, not a shared codebase.** ATAK and
WinTAK expose completely different platform primitives for camera capture,
encoding, and CoT — there's no common runtime to share code through. Rather than
force a lowest-common-denominator abstraction, each subproject is built the way its
platform actually wants it built:

- **`ATAK5.6/` and `ATAK5.7/`** (ATAK-ICU) — Java, Camera2 + hardware MediaCodec H.264 encode,
  a pluggable `Transport` abstraction (on-device RTSP / push-RTSP / push-RTMP / SRT), and a
  self-marker sensor + `<__video>` CoT detail for sharing. Two trees, one per ATAK-CIV SDK
  (5.6.0 and 5.7.0.7); `ATAK5.7/` is the current target.
- **`WinTAK/`** (WinTAK-ICU) — C#/.NET, shells out to **FFmpeg** for capture/encode (camera or
  screen-share), reads position/callsign straight from WinTAK's own services, and
  announces a `b-i-v` CoT video event through WinTAK's existing server connection.

What's shared is the *product idea* — see each subproject's own README for the full
architecture (`ATAK5.6/README.md`, `WinTAK/README.md`) and, for the ATAK side, the
reverse-engineering writeup in `ATAK5.6/docs/ARCHITECTURE.md` that the whole design is
built against.

## Layout

```
ATAK5.7/   ATAK-ICU for ATAK-CIV 5.7.0.7 — current target, see ATAK5.7/README.md
ATAK5.6/   ATAK-ICU for ATAK-CIV 5.6.0 — kept building against the older SDK
WinTAK/    WinTAK-ICU — WinTAK (Windows) plugin, .NET/Visual Studio project, see WinTAK/README.md
```

## Status

| Platform | Capture | Serve/transport | CoT sharing | Notes |
|---|---|---|---|---|
| ATAK-ICU | ✅ Camera2 + H.264 | ✅ on-device RTSP, push RTSP/RTMP; ⬜ SRT (needs native libsrt) | ✅ self-marker sensor + video detail | Persistent live-status map badge, independent of the plugin panel |
| WinTAK-ICU | ✅ camera or screen-share (via FFmpeg) | ✅ RTMP/RTMPS/RTSP/RTSPS/SRT/UDP (FFmpeg) | ✅ `b-i-v` CoT event | Optional OpenTAK Server registration |

## Build

Each platform builds independently — there is no top-level build that produces both.

- **ATAK-ICU (5.7)**: see [`ATAK5.7/README.md`](ATAK5.7/README.md). `cd ATAK5.7 && ./gradlew
  :app:assembleCivDebug`.
- **ATAK-ICU (5.6)**: see [`ATAK5.6/README.md`](ATAK5.6/README.md). `cd ATAK5.6 && ./gradlew
  :app:assembleCivDebug`.
- **WinTAK-ICU**: see [`WinTAK/README.md`](WinTAK/README.md). Open `WinTAK/ICUVideoStreamer.sln`
  in Visual Studio 2022.

## Deviations / notes

- **No shared code between platforms, by design** — see "The key insight" above.
  Don't go looking for a common `core/` module; there isn't one.

## Support

If this project is useful to you, consider [buying me a coffee](https://buymeacoffee.com/jpat).

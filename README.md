# TAK-PluginSuite-ICU_VideoStreamer

Turns a TAK end-user device's **own camera** into a live video source for the map —
broadcasting it to other users over the LAN/mesh or through a media server — instead
of requiring a drone or external video datalink. It's the drone-video experience
(à la ATAK's UAS Tool), minus the drone. This suite ships it for **two** TAK clients:
an ATAK (Android) plugin and a WinTAK (Windows) plugin.

## The key insight

**These are two independent implementations, not a shared codebase.** ATAK and
WinTAK expose completely different platform primitives for camera capture,
encoding, and CoT — there's no common runtime to share code through. Rather than
force a lowest-common-denominator abstraction, each subproject is built the way its
platform actually wants it built:

- **`ATAK/`** — Java, Camera2 + hardware MediaCodec H.264 encode, a pluggable
  `Transport` abstraction (on-device RTSP / push-RTSP / push-RTMP / SRT), and a
  self-marker sensor + `<__video>` CoT detail for sharing.
- **`WinTAK/`** — C#/.NET, shells out to **FFmpeg** for capture/encode (camera or
  screen-share), reads position/callsign straight from WinTAK's own services, and
  announces a `b-i-v` CoT video event through WinTAK's existing server connection.

What's shared is the *product idea* — see each subproject's own README for the full
architecture (`ATAK/README.md`, `WinTAK/README.md`) and, for the ATAK side, the
reverse-engineering writeup in `ATAK/docs/ARCHITECTURE.md` that the whole design is
built against.

## Layout

```
ATAK/      ATAK (Android) plugin — Gradle project, see ATAK/README.md
WinTAK/    WinTAK (Windows) plugin — .NET/Visual Studio project, see WinTAK/README.md
```

## Status

| Platform | Capture | Serve/transport | CoT sharing | Notes |
|---|---|---|---|---|
| ATAK | ✅ Camera2 + H.264 | ✅ on-device RTSP, push RTSP/RTMP; ⬜ SRT (needs native libsrt) | ✅ self-marker sensor + video detail | Persistent live-status map badge, independent of the plugin panel |
| WinTAK | ✅ camera or screen-share (via FFmpeg) | ✅ RTMP/RTMPS/RTSP/RTSPS/SRT/UDP (FFmpeg) | ✅ `b-i-v` CoT event | Optional OpenTAK Server registration |

## Build

Each platform builds independently — there is no top-level build that produces both.

- **ATAK**: see [`ATAK/README.md`](ATAK/README.md). `cd ATAK && ./gradlew
  :app:assembleCivDebug`.
- **WinTAK**: see [`WinTAK/README.md`](WinTAK/README.md). Open `WinTAK/ICUVideoStreamer.sln`
  in Visual Studio 2022.

## Deviations / notes

- **No shared code between platforms, by design** — see "The key insight" above.
  Don't go looking for a common `core/` module; there isn't one.

## Support

If this project is useful to you, consider [buying me a coffee](https://buymeacoffee.com/jpat).

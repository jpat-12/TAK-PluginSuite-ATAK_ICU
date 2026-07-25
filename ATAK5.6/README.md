# ICU VideoStreamer — ATAK Plugin

Streams the **phone's own camera** into ATAK and **broadcasts** it to other users
over the local network / mesh — mirroring the UAS Tool drone-video experience, with
no drone. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design and
the phased build plan.

## Status

**Phase 0 — scaffold.** The plugin loads in ATAK, the toolbar button opens the
operator pane, and the package structure for later phases is stubbed in. Capture /
serve / share behavior is not implemented yet (buttons are placeholders).

| Phase | State | What it adds |
|---|---|---|
| 0 — scaffold | ✅ | loads + operator pane |
| 1 — capture | ✅ | `capture/` Camera2 + MediaCodec H.264 + live preview |
| 2a — serve (RTSP) | ✅ | `serve/` Transport abstraction + on-device RTSP server, wired to the encoder |
| 2b — serve (RTMP push) | ✅* | pure-Java RTMP publisher → MediaMTX re-serves RTSP/RTSPS/SRT/RTMP. *untested vs. live server |
| 2c — serve (SRT native) | ⬜ | encrypted SRT via native libsrt (direct, no server) |
| 3 — share | ✅* | `share/` self marker → sensor + `<__video>` in the PLI while live; reverts on stop. *propagation untested on a live network |
| 4 — UI polish | ⬜ | quick-bar, status overlay, settings |

**All four protocols:** run MediaMTX on the LAN, long-press **Broadcast** to enter its host + path,
then broadcast. The phone pushes once via RTMP; MediaMTX re-serves RTSP/RTSPS/SRT/RTMP.
Without a server, on-device RTSP alone still works.

For multi-protocol serving (RTSP/RTSPS/SRT/RTMP) see [docs/ARCHITECTURE.md §7b](docs/ARCHITECTURE.md).

## Build

Target: **ATAK-CIV 5.6.0** SDK. Modeled on the QuickCapture plugin build.

1. Copy `local.properties.example` → `local.properties` and set your paths
   (`sdk.dir`, `sdk.path` = the ATAK-CIV-5.6.0-SDK folder, `takdev.plugin`).
   The SDK signing keystore is auto-staged from `sdk.path` at build time.
2. Ensure `app/libs/main.jar` is the 5.6.0 SDK `main.jar` (gitignored; copy from the SDK).
3. Build:

   ```
   ./gradlew :app:assembleCivDebug
   ```

   Output APK: `app/build/outputs/apk/civ/debug/`.

4. Install to a device already running ATAK-CIV 5.6.0, then load via
   Settings → Tool Preferences → Plugins.

## Layout

```
app/src/main/java/com/atakmap/android/icu/
  plugin/   ICUVideoLifecycle, ICUVideoTool      — entry point + toolbar button
  ICUVideoMapComponent, ICUVideoDropDownReceiver — component + operator pane
  capture/  CameraSource, H264Encoder, EncoderConfig   (Phase 1)
  serve/    RtspServer                                  (Phase 2)
  share/    VideoConnectionPublisher, VideoCotBroadcaster (Phase 3)
  util/     NetworkUtils                                (reachable RTSP URL)
```

Reference material lives in `../ATAK-Working/` (UAS Tool teardown) and the
sibling `ATAK-Plugin_TAK_ICU` project (a working capture→RTSP→CoT proof).

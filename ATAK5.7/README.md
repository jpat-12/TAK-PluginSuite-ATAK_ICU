# ATAK-ICU — ATAK Plugin (ATAK-CIV 5.7)

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

Target: **ATAK-CIV 5.7.0.7** SDK. Modeled on the QuickCapture plugin build.
(The 5.6-targeted copy of this plugin still lives in `../ATAK5.6/`; the two are
kept as separate trees so each keeps building against its own SDK.)

1. Copy `local.properties.example` → `local.properties` and set your paths
   (`sdk.dir`, `sdk.path` = the ATAK-CIV-5.7.0.7-SDK folder, `takdev.plugin`).
   The SDK signing keystore is auto-staged from `sdk.path` at build time.

   > The 5.7 SDK zip extracts to a **nested** folder — `sdk.path` must point at the
   > inner `ATAK-CIV-5.7.0.7-SDK\ATAK-CIV-5.7.0.7-SDK` directory (the one holding
   > `main.jar`, `android_keystore`, `atak-gradle-takdev.jar`), not the outer one.

2. Ensure `app/libs/main.jar` is the 5.7.0.7 SDK `main.jar` (gitignored; copy from the SDK).
3. Build:

   ```
   ./gradlew :app:assembleCivDebug
   ```

   Output APK: `app/build/outputs/apk/civ/debug/`
   (`ATAK-Plugin-ICUVideoStreamer-<ver>-<git>-5.7.0-civ-debug.apk`).
   `:app:assembleCivRelease` also builds clean (R8 + the ATAK repackage rules).

4. Install to a device already running ATAK-CIV 5.7.0, then load via
   Settings → Tool Preferences → Plugins.

### 5.7 porting notes

Only one API break between 5.6 and 5.7 affected this plugin:

- **`com.atakmap.android.video.ConnectionEntry` is gone.** 5.6 still shipped that
  compatibility shim; in 5.7 the class exists only as `gov.tak.api.video.ConnectionEntry`.
  `share/VideoConnectionPublisher` imports the `gov.tak.api.video` type now —
  the `(String alias, String url)` constructor and `setUID` are unchanged, and
  `com.atakmap.android.video.manager.VideoXMLHandler.serialize(List<ConnectionEntry>)`
  stayed put (its signature just moved to the new type).

Build environment is otherwise identical to 5.6: AGP 8.13.0, `compileSdk 36`,
Java 17, `minSdk 21` / `targetSdk 34`. Gradle wrapper bumped 8.13 → 8.14.3 to
match the 5.7 SDK samples.

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

Design docs in `docs/` are carried over verbatim from the 5.6 tree; nothing in the
architecture changed for 5.7.

Reference material lives in `../ATAK-Working/` (UAS Tool teardown) and the
sibling `ATAK-Plugin_TAK_ICU` project (a working capture→RTSP→CoT proof).

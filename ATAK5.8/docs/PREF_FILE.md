# Provisioning ATAK-ICU with a `.pref` file

Use a standard ATAK preference file to set ATAK-ICU's settings: every field on the
**BROADCAST**, **VIDEO** and **DISPLAY & POWER** cards. The QR code fills in only the
stream URL. A `.pref` file sets everything, and it travels the same way as any other
ATAK preference file:

- **Import Manager:** Import → Local SD → select the file.
- **Data package:** put the `.pref` file in the package.
- **MDM:** push it to the device the way you push ATAK's own `.pref` files.

When the import finishes, the plugin loads the new values at once. You do not need to
restart ATAK. If a broadcast is live, the plugin restarts it with the new settings,
the same as tapping **Save** in Settings. If the Settings page is open, it closes, so
its old values cannot overwrite the import.

Start from [`icu-settings-template.pref`](icu-settings-template.pref).

## File format

```xml
<?xml version='1.0' standalone='yes'?>
<preferences>
  <preference version="1" name="icu_video_prefs">
    <entry key="server_host" class="class java.lang.String">10.0.0.5</entry>
    <entry key="server_port" class="class java.lang.Integer">8554</entry>
  </preference>
</preferences>
```

- The block name **must** be `icu_video_prefs`. ATAK writes each block into its own
  settings file of that name, and the plugin reads its settings from there.
- The file can also hold ATAK's own blocks (for example `com.atakmap.app_preferences`
  or `cot_streams`), so one file can set up ATAK and ICU together.
- **Every entry is optional.** A key that the file leaves out keeps its value on the
  device.
- An empty entry (`<entry ...></entry>`) sets that value to empty. To keep a value,
  delete its entry.
- Use the `class` shown below. If you write a number or a true/false value as
  `class java.lang.String`, the plugin still converts it (`"30"` → 30,
  `"true"` → true). If it cannot convert the value, the plugin uses the default for
  that setting. A bad entry never stops the plugin.
- ⚠️ The reverse case fails in ATAK, not in the plugin. If an entry says
  `class java.lang.Integer` (or `Long` or `Float`) and its value is not a number,
  ATAK's importer stops at that entry, and **the entries after it are not applied**.
  If you are not sure of a value, use `class java.lang.String`.
- For characters that XML or ATAK do not allow, use ATAK's escapes: `"` `"`,
  `'` `'`, `<` `<`, `>` `>`, `&` `&`.

## Keys

Type: **S** = `class java.lang.String`, **I** = `class java.lang.Integer`,
**B** = `class java.lang.Boolean`.

### BROADCAST

| Key | Type | Values | Default |
|---|---|---|---|
| `destination` | S | `LAN` (phone serves RTSP), `SERVER` (push to a media server) | `SERVER` |
| `push_protocol` | S | `RTSP`, `RTMP`, `SRT` | `RTSP` |
| `server_host` | S | host name or IP of the media server | empty |
| `server_port` | I | port | default port of the protocol (RTSP 8554, RTMP 1935, SRT 8890) |
| `username` | S | server user name | empty |
| `password` | S | server password | empty |
| `srt_passphrase` | S | SRT passphrase | empty |
| `alias` | S | name that viewers see | device callsign |
| `stream_path` | S | server stream path | device callsign (URL-safe) |

⚠️ **Do not put `alias` or `stream_path` in a file for many devices.** If you leave
them out, each device uses its own callsign. If two devices use the same path, each
one overwrites the other's stream. This is the same rule as the QR code.

⚠️ **Never set `feed_uuid`.** The plugin makes this value itself, one for each device.

### VIDEO, one profile for each destination

Each key below exists twice: once with `.LAN` and once with `.SERVER` at the end (for
example `fps.LAN` and `fps.SERVER`). The plugin uses the profile of the current
destination. If a key with a suffix is not set, the plugin uses the key without the
suffix (for example `fps`), and then the default.

| Key | Type | Values | Default |
|---|---|---|---|
| `resolution.<DEST>` | S | `P480`, `P720`, `P1080` (also `480p`, `720`, …) | `P720` |
| `fps.<DEST>` | I | `15`, `24`, `30` | `30` |
| `bitrate.<DEST>` | I | kbps | `2500` |
| `rotation.<DEST>` | I | `-1` Auto (match ATAK), `0` Portrait, `270` Landscape, `180` Reverse Portrait, `90` Reverse Landscape | `-1` |
| `front_camera.<DEST>` | B | `true` = front camera, `false` = rear camera | `false` |
| `camera_id.<DEST>` | S | empty = built-in camera, chosen by `front_camera`; `usb` = USB/UVC camera; `network` = network camera (`net_camera_url`). A Camera2 id is specific to one device model. | empty |
| `keyframe_sec.<DEST>` | I | `1`, `2`, `4` | `2` |
| `stream_audio.<DEST>` | B | `true` = send microphone audio | `true` |

### VIDEO, shared by both destinations

| Key | Type | Values | Default |
|---|---|---|---|
| `net_camera_url` | S | RTSP URL of the network camera (`rtsp://user:pass@host:554/path`) | `rtsp://172.20.1.1:554/stream1` |
| `record_height` | I | `0` (same as the stream), `480`, `720`, `1080` | `0` |
| `record_fps` | I | `0` (same as the stream), `15`, `24`, `30` | `0` |
| `fov_refresh_sec` | I | seconds, minimum 1 | `3` |
| `fov_range_m` | I | metres, minimum 1 | `100` |

### DISPLAY & POWER

| Key | Type | Values | Default |
|---|---|---|---|
| `show_status_widget` | B | `true` = show the live-status badge on the map | `true` |
| `stream_screen_off` | B | `true` = keep streaming when the screen is off | `false` |

## Security

A `.pref` file stores `password`, `srt_passphrase` and credentials in
`net_camera_url` as plain text. The Import Manager also keeps a copy of the file in
ATAK's `config/prefs` folder. Treat the file the same as the QR code or a
data package that holds server credentials.

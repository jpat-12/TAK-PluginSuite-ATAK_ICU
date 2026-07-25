# UAS Tool — UI Inventory (candidates to port)

Extracted from `ATAK-Working/ATAK-Plugin-uastool-13.0.4-2fcea3d5-5.7.0-civ-release.apk`
(TAK Product Center UAS Tool 13.0.4). Resource names are fully preserved in the APK,
so everything below is real names you can pull.

**How to pick:** mark the `[ ]` boxes for what you want brought into ICU VideoStreamer,
and I'll port it (recreating layouts/patterns as our own code; see the licensing note).

> **Licensing note:** the UAS Tool's PNG icons and layouts are TAK Product Center
> proprietary art. Safe path = replicate the *structure/pattern* and draw our own
> matching icons (as I did for the menu/settings/flip icons). If you want the actual
> PNGs copied verbatim, that's your call to make on redistribution — flag which ones.

Totals in the APK: **490 layouts** (392 plugin-specific), **619 drawables** (418
plugin), **2055 strings**, 13 radial menus, 21 map actions.

---

## 1. The operator video pane (the core "look")

This is the main drone-video experience — the thing worth mirroring for ICU.

**`operator_control_layout`** → hosts `pagers.ControlFragment`. Structure (decoded):
```
ControlFragment (RelativeLayout root)
├── video preview surface area
├── tflite.overlay.OverlayView          ← AI detection boxes drawn over video
├── <custom video button-bar views>     ← com.atakmap.android.uastool.pagers.video
├── RelativeLayout (top status/OSD area)
├── RelativeLayout (side widget area)
└── RelativeLayout (bottom quick-bar area)
```

- [ ] **Operator video pane** (`operator_control_layout` + `ControlFragment`) — the framed
      video + overlays + docked widget/status/quick-bar regions
- [ ] **PiP video info** (`pip_video_info`) — small picture-in-picture info overlay
- [ ] **Video broadcast toggle** (`operator_video_broadcast_layout`) — TextView + CheckBox
      = the "Attach Video To Self Marker" broadcast control (we built our own; this is the pattern)
- [ ] **OSD visibility editor** (`edit_osd_visibility_layout`) — choose which on-screen overlays show
- [ ] **Cog-wheel settings line** (`cog_wheel_text` + `pagers.CogWheelSettingsLine`) — the
      inline ⚙ settings row style
- [ ] **Custom `video` ImageButton widget** (`pagers.video`) — the tintable image button used
      all over the pane (stateful enabled/pending/danger button backgrounds — see §3 button set)

---

## 2. Quick-action bar (the button row)

**`quickbar_layout`** → `quickbar.Quickbar` with a row of image buttons:
```
Quickbar: [Go] [Fly] [Pause] [Follow] [RTH] [Altitude] [More] + label TextView
```
Icons: `quick_go`, `quick_fly`, `quick_pause`, `quick_follow`, `quick_rth`,
`quick_altitude`, `quick_more`.

- [ ] **Quick-bar** (`quickbar_layout` + `Quickbar`) — the horizontal action button strip
- [ ] **Altitude quick control** (`quickbar_altitude`)
- [ ] **`quick_*` icon set** (7 icons) — or ICU equivalents (record / snapshot / flip / share…)
- [ ] **More-menu** (`moremenu.MoreMenuContainer` + `more_menu_color_selector`) — overflow grid

---

## 3. Icon sets (the `video_ui_*` family — ~130 icons)

The complete operator-pane icon language. Grouped:

**Camera / recording**
- [ ] `video_ui_camera`, `_camera_play`, `_camera_record`, `_camera_recording`,
      `_camera_stop`, `_camera_rate`, `_camera_camerashot`, `_camera_mapshot`,
      `_camera_zoom` / `_zoomin` / `_zoomout`, `_camera_loading_0..3`, `_camera_videooverlay`
- [ ] `video_ui_broadcast`, `_broadcast_1..3`, `_broadcasting_loading` — **broadcast states**
- [ ] `status_uas_video_active`, `status_uas_observer_video_active` — marker status badges
- [ ] `swap_map_to_video`, `swap_uas`, `video_ui_map_resize`, `_resize`, `_map_background`

**Stateful button backgrounds** (full/half width × enabled/on/pending/danger/pressed/disabled + checked)
- [ ] `video_ui_button_full_*` and `video_ui_button_half_*` (~24 nine-patches) — the drone-pane
      button styling system

**Gimbal / lens / focus**
- [ ] `video_ui_gimbal`, `_gimbal_control`, `_gimbal_pitch`, `_gimbal_reset`, `_gimbal_snap`,
      `_gimbal_focus` (+`_land`/`_macro`), `video_ui_focus`, `video_ui_lens`, `video_ui_panto`

**Compass / attitude / telemetry**
- [ ] `video_ui_compass_inner/outer/plane/rotary/windsock`, `compass`, `dji_compass_horiz/vert`
- [ ] `video_ui_gps_sat`, `video_ui_fivebar`, `signal_0..3` (+`_shadow`), `battery_progress`,
      `video_ui_battery_progress` (+`_background`/`_fill`)

**Toggles / palettes / thermal**
- [ ] `video_ui_toggle_*` (background/selected/unselected/palette/low_light_mode),
      `video_ui_thermal_hotspot`/`_coldspot`, `evo_palette_selector`

**Reticles / laser**
- [ ] `reticle_01..15` (15 aiming reticles), `video_ui_laser`, `danger_laser`, `laser_widget`

**Generic menu glyphs**
- [ ] `ic_menu` (hamburger), `ic_menu_import`/`_export`/`_plus`/`_minus`, `action_settings`,
      `action_stop`, `ic_arrow_back_black_24`, `ic_video_alias`, `arrow_up/down/left/right`

**Per-vendor button bars** (only if you add that hardware)
- `video_ui_r80d_*`, `video_ui_trillium_buttonbar_*`, `video_ui_bh3_*`, `video_ui_dji_*`,
  `video_ui_indago_*`, `video_ui_lm_logo`

---

## 4. Settings / dialogs (93 layouts)

- [ ] **Broadcast settings** (`broadcast_settings_layout` + `prefs.BroadcastSettingsDialog`) —
      a ListView-based preferences dialog (**closest to what we're building**)
- [ ] **A/V settings** (`av_settings_layout` + `av.AVSettingsScreen`, `av.AvUASConfigView`) —
      RTSP-Push / SRT (VMS) source config — **directly relevant to our push settings**
- [ ] **Connection presets** (`connection_presets_list_layout`) — saved server presets list
- [ ] **Generic settings** (`generic_settings_layout`)
- [ ] **Camera selector** (`camera_component_selector_view` / `_widget`, `dialog_camera`)
- [ ] **Spinner styles** (`dark_spinner_item`, `dark_spinner_dropdown_item`,
      `dialog_spinner_item`, `spinner_drawer_*`) — dark dropdown styling
- [ ] **Alert dialog styling** (`alertdialog_layout`, `alert_ddr_layout`)
- [ ] **DTED check** (`check_install_dted_layout`) — pre-flight "no terrain data" warning
- [ ] **DJI settings** (`dji_settings_layout`, `dji_settings_v5_layout`) — big vendor settings

**Drawer/edit widgets** (reusable little editors)
- [ ] `drawer_textedit`, `drawer_toggleedit`, `drawer_selectedit`, `drawer_savecancel`,
      `drawer_reticle_edit` — the slide-out edit-row components

---

## 5. Radial menus & map actions (ATAK CoT menus)

Plaintext ATAK menu/action XML (easy to adapt). Menus:
`flight_log_menu`, `gimbal_submenu`, `layer_menu`, `task_menu`, `waypoint_menu`,
`route_{submenu,corner,line,shape,point,orbitpoint,genericpoint,waypoint}_menu`.

Actions (radial map taskings): `gimbal_look_tasking`, `gimbal_picture_tasking`,
`payload_config_tasking`, `show_spi_for_mapshot`, `showuavlist`, `show_task_list`,
`addroute`/`sendroute`/`editroute`/`toggleroute`, `route_insert_*`, `reject_uas_task_point`.

- [ ] **Gimbal look/picture tasking** (`gimbal_look_tasking`, `gimbal_picture_tasking`) —
      right-click a map point → slew camera / take picture
- [ ] **Show SPI for map-shot** (`show_spi_for_mapshot`)
- [ ] **Layer menu** (`layer_menu` + `layer_toggle_visibility`/`layer_delete`)
- [ ] Route/waypoint radial menus (only if we add mission planning)

---

## 6. Mission / route / autonomy UI (39 layouts) — *probably out of scope*

`generic_tasks_layout`, `geofence_edit`, `geofence_manager`, mission-manager screens
(`pagers.MissionManagerScreen`, `GenericMissionManagerScreen`), `flight_log_layout`,
`flight_log_export_options_layout`, survey/orbit editors, `fixed_wing_widget_layout`.

- [ ] Flight log viewer/export (`flight_log_layout`, `flightlog_export_layout`)
- [ ] Geofence editor (`geofence_edit` / `geofence_manager`)
- [ ] (skip mission planning unless you want route tasking)

---

## 7. Controller / joystick config (31 layouts) — *out of scope unless RC support*

`controller_setup_*` (Skydio, Anafi, Herelink, ELRS-BLE, TAC, generic, web…),
`controller_mapping_layout`, `joystick_config_widget_layout`, `controller_calib`.

---

## 8. Key custom UI classes (for reference when porting)

| Class | Role |
|---|---|
| `pagers.UASToolPager` / `UASToolFragment` | Swipeable multi-tab pane container |
| `pagers.ControlFragment` | The operator video pane fragment |
| `pagers.LandingPager` | Landing/home screen |
| `pagers.CogWheelSettingsLine` | Inline ⚙ settings row |
| `pagers.video` | Custom tintable ImageButton used across the video UI |
| `quickbar.Quickbar` | The quick-action button strip |
| `moremenu.MoreMenuContainer` | Overflow grid menu |
| `tflite.overlay.OverlayView` | Draws AI detection boxes over the video |
| `av.AVSettingsScreen` / `AvUASConfigView` | RTSP-Push / SRT source settings |
| `prefs.BroadcastSettingsDialog` | ListView-based broadcast preferences |

---

## 9. Recommended shortlist for ICU VideoStreamer

If the goal is "our video-broadcast plugin looks/feels like the UAS operator pane," the
high-value, in-scope items are:

1. **Operator video pane** (`operator_control_layout` pattern) — framed video + docked
   status bar + side widgets + bottom quick-bar.
2. **Quick-bar** (`quickbar_layout`) — reskinned for ICU actions (Broadcast, Record,
   Snapshot, Flip, Share, Settings).
3. **`video_ui_*` button backgrounds** (the stateful full/half button styling) — gives the
   authentic drone-pane button feel.
4. **A/V + broadcast settings** (`av_settings_layout` / `broadcast_settings_layout` patterns) —
   matches what we already built; could adopt the list/preset style.
5. **Camera/broadcast/status icon set** — record/play/stop/zoom/broadcast/status badges.
6. **Dark spinner + drawer-edit styling** — for polished settings.
7. **Gimbal look tasking** *(optional)* — if ICU ever controls a PTZ camera.

Tell me which boxes to check and I'll bring them over as our own implementation.

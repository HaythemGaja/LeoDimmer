# LeoDimmer — Android TV overlay dimmer

Pure Kotlin, Android Studio project. Package: `com.universal.tvdimmer`.

> Renamed in v1.1: the app was "TV Dimmer" in v1.0, but that name is
> already used by TvDimmer - NightCompanion, so the visible name is now
> LeoDimmer. The package (`com.universal.tvdimmer`), the
> `tv_dimmer_prefs` settings, and the signing key are unchanged, so v1.1
> updates a v1.0 install in place instead of installing side-by-side.

Why an overlay instead of the system brightness setting: on a Xiaomi Mi Box
there is no panel and HDMI carries no brightness signal, so the Android
`screen_brightness` setting stores a value and changes nothing on the TV.
A black overlay drawn above every app is the route that actually dims the
picture — and at full opacity it becomes an audio-only blackout screen.

Architectural sources, combined here (not vendored):
- **MidnightBeam** — click-through overlay service (`TYPE_APPLICATION_OVERLAY`
  + `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN`) and
  ADB control hooks.
- **nightd** — audio-only blackout plus native D-pad / remote button handling.
- **DimTV** — persistent background service lifecycle (foreground service +
  boot restart).

## What it does

- **Remap any remote button.** Open the app and use the Leanback settings
  screen (D-pad navigable, 10-foot UI) to map four actions:
  Increase Brightness, Decrease Brightness, Toggle Blackout, Sleep Light.
  Select a row and press the physical button you want — the dialog captures
  its raw integer keycode and stores it in `SharedPreferences` named
  `tv_dimmer_prefs`. Press BACK in the dialog to cancel. Mappings start
  **unset**: nothing is intercepted until you choose a button, so DPAD/volume
  navigation is never hijacked by default.
- **Global interception.** `DimmerAccessibilityService` reads those keycodes
  on every key event and, over any app or system screen:
  - mapped Increase key → overlay opacity −10% (brighter)
  - mapped Decrease key → overlay opacity +10% (dimmer)
  - mapped Toggle key → blackout on/off
  - mapped Sleep Light key → ambient (warm dim) mode on/off
  - **any** key while blackout is active → blackout exits instantly, and
    that press is consumed so it does not also fire underneath the black screen.
- **Blackout = audio-only mode.** At 0% brightness the overlay is a solid
  black view at `alpha = 1.0f`. Audio keeps playing; the picture is gone
  until any button (or the toggle key) restores the pre-blackout level.
- **Ambient / sleep light.** A warm, dim viewing state: the same overlay
  tinted warm brown instead of black, held at a configurable level (10–60%,
  default 20%). Unmapped keys keep working, so it is a viewing mode and not a
  blackout. The mapped brightness keys sculpt the light while it is on, and
  the sleep-light key (or the blackout key) leaves it. Never resumed after a
  restart.
- **Sleep timer.** Pick a duration (off / 10 / 15 / 20 / 30 / 45 / 60 min) on
  the "Sleep timer" row. It arms when the sleep light comes on, the ongoing
  notification counts down ("fades out in 14 min"), and when it reaches zero
  the light fades to black over 20 seconds instead of snapping off. Any remote
  press during the fade cancels it — a key press means you are still awake —
  and the light stays on. After the fade the screen is in normal blackout, so
  any key (or the toggle key) restores the *sleep level*, not full brightness:
  no blinding flash in a dark room. The timer is cleared whenever ambient mode
  ends, the dimmer is turned off, or the app restarts, so a stray timer can
  never dim the screen hours later.
- **Anti-flicker.** The overlay view is inflated once. Brightness steps only
  mutate `view.alpha` in memory — no re-inflate, no remove/re-add, no
  `updateViewLayout` per step. Even at 100% brightness the view stays
  attached at alpha 0, so the next dim step has no attach flash. This is
  specifically for the Xiaomi system compositor, which used to flash a bright
  frame on overlay re-attach.
- **Turn off.** The settings screen has "Turn dimmer off", and the ongoing
  notification has a "Turn off" action. Both remove the overlay and clear the
  restart-at-boot flag; nothing keeps running.
- **TV power link (best effort).** Two toggles:
  - *TV power on with box* — on boot and when the box wakes after a long
    standby, try HDMI-CEC One Touch Play. If the platform denies the CEC call
    (it is `signature|privileged`, so a sideloaded app never holds it), the
    invisible `WakeActivity` is launched briefly instead: an activity launch
    makes the box drive HDMI output again, which most TVs treat as a wake
    signal. Only attempted when "Display over other apps" is granted, since
    that is what allows a background activity launch.
  - *TV power off with box* — attempts CEC standby on shutdown. A source device
    has no public API for this, so on a retail box it is normally a no-op;
    the TV's own "auto power off when the source goes away" CEC setting is the
    reliable route. The row says so rather than pretending.
- **Fail-safes.** Both "no picture" states are one unrecoverable press away for
  a user who cannot reach a mapped key, so:
  - the service never resumes into blackout or ambient mode — a restart,
    crash, force stop or reboot comes back to the pre-blackout / pre-ambient
    level;
  - if blackout is entered while the accessibility service is **not**
    enabled, nothing can press "any key", so blackout auto-restores after
    one minute (the notification says so). This applies to the sleep timer
    too: with the accessibility service off, the picture comes back dimly a
    minute after the light fades out. Leave the accessibility service enabled
    if you want the screen to stay dark for the night.

## Headless ADB control

The background service processes these exact intents (the service is
exported for this; it is a local-device surface, not a network service):

```sh
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "BRIGHTER"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "DIMMER"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_PERCENT" --ei value [0-100]
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "TOGGLE_AMBIENT"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_AMBIENT_LEVEL" --ei value [5-60]
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_SLEEP_TIMER" --ei value [0-120]
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_AMBIENT_COLOR" --es color "Red"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "RESET_IDLE"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "STOP"
```

`SET_PERCENT` sets **brightness**: 100 = full bright (no dimming),
0 = blackout. `BRIGHTER` / `DIMMER` step by 10%. `SET_SLEEP_TIMER` stores the
sleep-timer duration in minutes (0 = off) and, if the sleep light is already
on, re-arms the countdown from now. `STOP` turns the dimmer off and disables
the boot restart.

## First run (onboarding)

1. Install, open LeoDimmer from the TV home screen.
2. Grant **Display over other apps** — the "Overlay permission" row opens
   `ACTION_MANAGE_OVERLAY_PERMISSION` for this app.
3. Enable **LeoDimmer** in Accessibility settings — the
   "Accessibility service" row opens `ACTION_ACCESSIBILITY_SETTINGS`.
   Without this step, remote buttons cannot be intercepted globally; the
   app will not swallow keys it cannot act on.
4. Map the buttons, then test with the "Test:" rows before relying
   on the global keys.
5. Optional: grant the notification permission (asked once, Android 13+) so
   the ongoing notification shows the current mode; enable the TV power
   toggles if the TV responds to CEC.

Platform limits, stated plainly:
- HOME and POWER never reach an app or an accessibility service, so they
  cannot be mapped. BACK cancels the capture dialog and cannot be mapped.
- The accessibility service must stay enabled; if Android or a cleaner app
  disables it, global keys stop working until it is re-enabled (the settings
  screen shows this as MISSING).
- After a reboot the overlay restarts only if it had been started before
  (`BootReceiver` + `service_enabled` in prefs), and it never restarts in
  blackout or ambient mode.
- HDMI-CEC control is a privileged permission; the TV power link works on
  boxes that grant it, and otherwise depends on the box's own CEC settings.

## Build

Android Studio: open this folder, let Gradle sync, Run on the TV device.

Command line:

```sh
./build-apk.sh
# -> app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android SDK with platform 34, JDK 17, Gradle 8.5 (via wrapper).

## Version notes

- **v1.7** — first-open permission prompt: until v1.6 the app never
  asked for anything on first open, it only listed "Overlay permission"
  and "Accessibility service" rows to tap. Now it prompts on open,
  overlay first, then accessibility on return with overlay granted
  (Android allows no simple Allow/Deny popup for either: both must be
  granted on a system settings page, so the prompt opens that page;
  "Later" dismisses without re-prompting in the same session). No
  behaviour change otherwise. Note: the notification permission is
  Android 13+ only, so on the Android 9 Mi Box the app correctly
  never asks for it.
- **v1.6** — key capture + preset-crash hardening (no single definite
  crash was found in the preset handlers, so every unguarded first-start
  path was fixed and a crash log was added): key-capture dialog now
  captures in `dispatchKeyEvent` and requests decor focus, and the
  accessibility service steps aside while a capture is in progress
  (a `capturing_key` flag), so an already-mapped button cannot be eaten
  before the dialog sees it; the service foregrounds immediately in
  `onCreate` (no more 5-second deadline risk on a slow box), the
  notification action now has a real icon instead of null, and
  `ensureOverlay` catches any `RuntimeException` instead of dying on
  a vendor addView failure. Sleep-light / blackout rows now warn when
  accessibility is missing (no remote-button restore path). New local
  crash logger: any uncaught exception is written to the app's private
  `crash.log` and its first line is shown on the settings screen at
  the next open, so a crash on a box with no ADB can still be reported
  exactly.
- **v1.5** — launch-crash fix: the settings screen is a Leanback
  GuidedStep, which requires `Theme_Leanback_GuidedStep` (or a derived
  theme) for its stylists to inflate; the host activity was using the
  app's plain Material theme, so the app crashed immediately on first
  open on the Mi Box. MainActivity now uses a new
  `LeoDimmerGuidedStep` theme derived from `Theme.Leanback.GuidedStep`;
  no behaviour change otherwise.
- **v1.4** — merge of the parallel local v1.2 feature set into v1.3, with
  no change to v1.3's sleep light, sleep timer, turn-off, or TV power link:
  night schedule (dim at 20–0, restore at 6–9, dim to 10/20/30/50%, applied
  on window transitions and on plain service starts (boot / settings open),
  skipped while the sleep light or blackout is active, and never fighting
  a manual brightness change mid-window; turning it off mid-window
  restores the pre-schedule level at once);
  presets 75 / 50 / 25 / blackout; on-screen brightness readout for 2 s;
  smooth fade (~300 ms) for manual brightness changes, off-able, never
  mixed with the 20 s sleep fade; idle blackout timer (off / 15 / 30 /
  60 min, any key resets it); long-press (hold Increase → 100%, Decrease →
  blackout, Toggle → sleep light, Sleep light → 100%) and double-press
  (Increase → 75%, Decrease → 25%, Toggle → 100%, Sleep light → 50%);
  sleep-light colour choice (Warm brown — the v1.3 default — Red, Amber,
  Orange, Green, Blue, Violet, Pink) with the existing level as intensity
  control and the existing sleep timer. New ADB actions: `SET_AMBIENT_COLOR`
  (by colour name), `RESET_IDLE`.
- **v1.3** — sleep timer for the sleep light: selectable duration (off /
  10 / 15 / 20 / 30 / 45 / 60 min), countdown in the ongoing notification,
  20-second fade to black on expiry, any key during the fade cancels it, and
  expiry lands in blackout whose restore level is the sleep level. Timer is
  cleared on ambient exit, turn-off and restart. New `SET_SLEEP_TIMER` ADB
  action.
- **v1.2** — ambient/sleep-light mode (warm dim + fourth key mapping), "turn
  dimmer off" row and notification action, TV power link (CEC + wake-activity
  fallback), blackout/ambient fail-safes, trimmed accessibility config
  (key filtering only), notification permission request, settings screen
  fixes (status lines no longer disappear during blackout; accessibility state
  detected from both component-name forms).
- **v1.1** — rename only: visible name TV Dimmer → LeoDimmer (see note at
  the top). No behaviour change; package, prefs, and signing key unchanged.
- **v1.0** — first build. `minSdk 28` on purpose: the Xiaomi Mi Box in use
  is a MIBOX4 on Android 9 (API 28), and a `minSdk 30` build would refuse to
  install on it. The overlay / foreground-service code is written in the
  Android 11+ shape and runs on API 28 and above. Raise `minSdk` to 30 in
  `app/build.gradle.kts` for a strict Android 11+ build.

## Files

- `app/src/main/java/com/universal/tvdimmer/MainActivity.kt` — Leanback
  GuidedStep settings screen + permission onboarding.
- `app/src/main/java/com/universal/tvdimmer/KeyListeningDialog.kt` — raw
  key capture dialog.
- `app/src/main/java/com/universal/tvdimmer/DimmerService.kt` — foreground
  overlay service, ambient/blackout state machine, sleep timer (countdown +
  fade-out), ADB intent handling, anti-flicker overlay, fail-safes.
- `app/src/main/java/com/universal/tvdimmer/DimmerAccessibilityService.kt` —
  global key interception.
- `app/src/main/java/com/universal/tvdimmer/DimmerPrefs.kt` — shared
  `tv_dimmer_prefs` helpers (raw keycodes, brightness, ambient, sleep-timer
  duration and end time, blackout restore, TV power flags).
- `app/src/main/java/com/universal/tvdimmer/AccessibilityState.kt` — robust
  "is the accessibility service enabled" check.
- `app/src/main/java/com/universal/tvdimmer/TvPowerLink.kt` — best-effort
  HDMI-CEC wake/standby + the wake-activity fallback.
- `app/src/main/java/com/universal/tvdimmer/TvPowerReceiver.kt` — screen
  on/off and shutdown triggers for the power link.
- `app/src/main/java/com/universal/tvdimmer/WakeActivity.kt` — invisible
  one-shot activity used only by the power link.
- `app/src/main/java/com/universal/tvdimmer/BootReceiver.kt` — reboot restart
  and "box powered on" trigger.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/layout/dialog_key_listening.xml`,
  `app/src/main/res/layout/view_dimmer_overlay.xml`,
  `app/src/main/res/xml/dimmer_accessibility_config.xml`.

No keys, keystores, or APKs are committed to the repo. The debug keystore
used for local APK signing stays outside the repo.

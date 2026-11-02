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
  screen (D-pad navigable, 10-foot UI) to map three actions:
  Increase Brightness, Decrease Brightness, Toggle Blackout. Select a row and
  press the physical button you want — the dialog captures its raw integer
  keycode and stores it in `SharedPreferences` named `tv_dimmer_prefs`.
  Press BACK in the dialog to cancel. Mappings start **unset**: nothing is
  intercepted until you choose a button, so DPAD/volume navigation is never
  hijacked by default.
- **Global interception.** `DimmerAccessibilityService` reads those keycodes
  on every key event and, over any app or system screen:
  - mapped Increase key → overlay opacity −10% (brighter)
  - mapped Decrease key → overlay opacity +10% (dimmer)
  - mapped Toggle key → blackout on/off
  - **any** key while blackout is active → blackout exits instantly, and
    that press is consumed so it does not also fire underneath the black screen.
- **Blackout = audio-only mode.** At 0% brightness the overlay is a solid
  black view at `alpha = 1.0f`. Audio keeps playing; the picture is gone
  until any button (or the toggle key) restores the pre-blackout level.
- **Anti-flicker.** The overlay view is inflated once. Brightness steps only
  mutate `view.alpha` in memory — no re-inflate, no remove/re-add, no
  `updateViewLayout` per step. Even at 100% brightness the view stays
  attached at alpha 0, so the next dim step has no attach flash. This is
  specifically for the Xiaomi system compositor, which used to flash a bright
  frame on overlay re-attach.

## Headless ADB control

The background service processes these exact intents (the service is
exported for this; it is a local-device surface, not a network service):

```sh
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "BRIGHTER"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "DIMMER"
adb shell am startservice -n com.universal.tvdimmer/.DimmerService --es action "SET_PERCENT" --ei value [0-100]
```

`SET_PERCENT` sets **brightness**: 100 = full bright (no dimming),
0 = blackout. `BRIGHTER` / `DIMMER` step by 10%.

## First run (onboarding)

1. Install, open LeoDimmer from the TV home screen.
2. Grant **Display over other apps** — the "Overlay permission" row opens
   `ACTION_MANAGE_OVERLAY_PERMISSION` for this app.
3. Enable **LeoDimmer** in Accessibility settings — the
   "Accessibility service" row opens `ACTION_ACCESSIBILITY_SETTINGS`.
   Without this step, remote buttons cannot be intercepted globally; the
   app will not swallow keys it cannot act on.
4. Map the three buttons, then test with the "Test:" rows before relying
   on the global keys.

Platform limits, stated plainly:
- HOME and POWER never reach an app or an accessibility service, so they
  cannot be mapped. BACK cancels the capture dialog and cannot be mapped.
- The accessibility service must stay enabled; if Android or a cleaner app
  disables it, global keys stop working until it is re-enabled (the settings
  screen shows this as MISSING).
- After a reboot the overlay restarts only if it had been started before
  (`BootReceiver` + `service_enabled` in prefs).

## Build

Android Studio: open this folder, let Gradle sync, Run on the TV device.

Command line:

```sh
./build-apk.sh
# -> app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android SDK with platform 34, JDK 17, Gradle 8.5 (via wrapper).

## Version notes

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
  overlay service + ADB intent handling + anti-flicker overlay.
- `app/src/main/java/com/universal/tvdimmer/DimmerAccessibilityService.kt` —
  global key interception.
- `app/src/main/java/com/universal/tvdimmer/DimmerPrefs.kt` — shared
  `tv_dimmer_prefs` helpers (raw keycodes, brightness, blackout restore).
- `app/src/main/java/com/universal/tvdimmer/BootReceiver.kt` — reboot restart.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/layout/dialog_key_listening.xml`,
  `app/src/main/res/layout/view_dimmer_overlay.xml`,
  `app/src/main/res/xml/dimmer_accessibility_config.xml`.

No keys, keystores, or APKs are committed to the repo. The debug keystore
used for local APK signing stays outside the repo.

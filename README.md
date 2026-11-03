# LeoDimmer 2.2

A dimmer for Android TV, built for the Xiaomi Mi Box 4 (Android 9 / API 28).
It dims the picture over any app, and it is designed around one constraint:
**the remote does not have a spare button per setting.**

Pure Kotlin. No external dependencies.

## The idea: three modes, one key

| Mode | What you see | Its own settings |
|---|---|---|
| **Day** | full picture | Day brightness (100 / 90 / 80 / 70%) |
| **Night** | the picture dimmed | Night brightness (10 … 50%) |
| **Sleep** | an opaque lamp in one colour, which fades itself out | level (5 … 100%), colour, auto-off timer |

You map **one** remote button — the **Mode key** — and press it:

```
Day  →  Night  →  Sleep  →  Day
```

Every mode keeps its own level, so switching never means re-tuning. Holding the
key does not race through the modes.

## The screen

One screen, no sub-menus, no dialogs:

```
▲ ▼   move between rows
◀ ▶   change the value in place
OK    switch a mode / learn a key
```

It opens with a **CHECK** block:

```
CHECK
  Screen dimming     Ready                    (red + OK to fix if not)
  Remote keys        On
```

If either is missing, the row says so and OK opens the right system page. A
dimmer that silently does nothing is worse than one that says why.

A 1080 panel cannot hold the whole sheet, so while there is content below the
bottom edge a **▼ more below** marker sits in the corner. `▼` scrolls to it;
the focused row is always scrolled into view.

## The Sleep lamp

Sleep does not dim the picture — it *is* the light. The whole screen is filled
with one opaque colour, and the level dims that colour's own value instead of
fading a black layer over whatever happens to be on screen:

| | Day / Night | Sleep |
|---|---|---|
| fill | black, alpha = 100 − brightness | the lamp colour, fully opaque |
| level means | how dim the picture is | how bright the light is |
| what the panel emits | the app's picture, dimmed | the lamp colour, and nothing else |

That difference matters. At the old default (level 20) a translucent layer left
80% of the *paused video* visible, so the "light" was a tinted picture and a
bright app could out-shine the lamp. An opaque fill has no picture in it, reaches
real panel brightness, and can be a true warm white instead of a dark wash. The
colours are warm white, candle, amber, sunset, rose, violet, ice and moonlight.

Two honest limits. The panel's LED backlight is what makes the light, but it is
not addressable — no zones, no Ambilight — and the box cannot touch it at all:
HDMI carries no brightness command, so the TV's own backlight setting stays the
ceiling and "intensity" here means the pixel value. And a static full-screen
colour for hours is the classic OLED wear pattern; on an LCD panel it is
harmless, but if this box ever drives an OLED, keep the level modest or let the
sleep timer end it.

## Turning the TV off and on

Android gives a sideloaded app no direct power control: `PowerManager.goToSleep()`
needs `DEVICE_POWER`, and HDMI-CEC needs `HDMI_CEC` — both signature-level. So the
**POWER · TEST EACH ONE** block tests each mechanism and reports what the box
answered, rather than one switch that pretends to always work.

| What | Really off? | Needs |
|---|---|---|
| **Picture off / on** | no — black overlay, TV stays lit | overlay permission |
| **Sleep (box power menu)** | yes — the box sleeps, TV follows over CEC | remote keys |
| **Ask the TV to sleep / wake (CEC)** | only where the box exposes it | nothing; usually refused |
| `adb shell input keyevent 177` | TV only; the box keeps running | a PC |
| `adb shell input keyevent 26` | box sleep; TV follows when CEC is on | a PC |

Blackout always works and is what the dimmer is for. The power menu is the one
in-app path to a real sleep: it opens the box's own power dialog, so the box
sleeps exactly as the remote's power key would make it, and a TV with
"auto power off" CEC follows along. Waking is the mirror image — any remote
button wakes the box, and `TvPowerLink` asks the TV to wake with CEC one-touch-play;
when the box refuses, it launches an invisible activity, which re-drives HDMI
output and wakes most TVs. If the TV still stays dark, the place to look is the
TV's own CEC settings, not this app.

## Permissions, exactly

1. **Display over other apps** — `Settings → Apps → Special access → Display
   over other apps → LeoDimmer → Allow`. This is the path that exists on the Mi
   Box 4; the per-app switch under app info does not exist there. OK therefore
   tries the per-app page, then the global list, then app info, and prints the
   adb equivalent on screen.
2. **Remote keys** — `Settings → Device Preferences → Accessibility → LeoDimmer
   → On`. Some firmwares leave that list empty; OK then tries the service's own
   toggle page first, and the adb equivalent is printed on screen.

Nothing else is needed. Without (1) nothing dims; without (2) the button does
nothing; both are visible on the screen at all times.

### When the pages themselves are missing

The Mi Box's TV Settings can show no "Display over other apps" entry at all, and
no LeoDimmer in the Accessibility list. Neither grant needs a menu — from a PC
with the box attached:

```
adb shell appops set com.universal.tvdimmer SYSTEM_ALERT_WINDOW allow
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services com.universal.tvdimmer/.KeyService
```

The screen prints these lines itself while either grant is missing — directly
under **CHECK**, and only the ones still missing. For remote keys, OK opens the
service's *own* toggle page by component (`ACTION_ACCESSIBILITY_DETAILS_SETTINGS`)
instead of the list, because the list is the part that comes up empty; it falls
back to the list and then to app info. A **Check again** row re-reads both grants
without leaving the screen.

## Learning a button

Press **OK** on "Mode key". The screen says *"Press a remote button…"*, and you
press the button you want.

The press is caught by the accessibility service, **not** by a dialog. This is
deliberate: the previous version listened inside a focusable dialog, and on the
Mi Box a dialog opened over the settings screen does not reliably take focus, so
the presses went to the screen behind it and nothing was ever learned. There is
no window here that can miss a press.

`BACK` cancels. `HOME` and `POWER` cannot be mapped: Android delivers those to
the system before any app sees them.

## Behaviour worth knowing

- **Blackout** clears on any remote button, and auto-restores after one minute if
  the key service is off, so the screen can never get stuck black.
- **A restart never resumes into blackout or Sleep.** The dimmer always comes
  back to a visible picture.
- **The sleep timer** dims the lamp out over 20 seconds when it runs out, then
  leaves a dim picture for your next wake-up press.
- **Night schedule** (optional) dims to the Night level at a chosen hour and
  restores the previous level in the morning.
- **Auto-dim when idle** (optional) blacks out after a chosen period without a
  keypress; any button brings the picture back.
- **Wake/Sleep TV with box** (optional) is best-effort HDMI-CEC. The CEC API is
  privileged and will not be granted to a sideloaded app, so the app falls back
  to briefly driving HDMI output; whether the TV wakes is up to the TV.

## Install

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Where the Gradle daemon cannot run, `./build-manual-apk.sh` produces the same
APK from the SDK build-tools and `kotlinc` alone. v2.0 has no libraries, so it
downloads nothing and there is no AAR graph that can fail to arrive.

`minSdk 28` on purpose — a higher minimum would refuse to install on the box.
The prefs file (`tv_dimmer_prefs`) and its key names are unchanged from 1.x, so
upgrading keeps existing values, including any keys already mapped.

## Headless control (adb)

```
adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action SET_MOOD --ei value 1
adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action CYCLE_MOOD
adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action SET_PERCENT --ei value 30
adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action TOGGLE_BLACKOUT
adb shell am startservice -n com.universal.tvdimmer/.OverlayService --es action STOP
```

`value` for `SET_MOOD`: `0` Day, `1` Night, `2` Sleep.

## When something goes wrong

The settings screen shows a **Last crash** row with the first line of the last
uncaught exception, written to `crash.log` in the app's files. That one line is
usually enough to identify a device-specific failure without a logcat. **OK**
clears it.

The trace is stored with the version code that produced it, and a trace from any
other build is deleted on start. That is not cosmetic: the files directory
survives `adb install -r`, so an old install's crash used to appear on the new
build's screen. v2.0 shipped showing
`NoClassDefFoundError: … Landroidx/leanback/…` — left by a hand-built 1.x APK
that was missing its Leanback jar, displayed by a build that contains no
Leanback at all.

## What changed from 1.x

The 1.x build was 800 lines of settings screen, a key-capture dialog and a
Leanback GuidedStep. Its three failures all came from that stack:

| Symptom | Cause in 1.x | In 2.0 |
|---|---|---|
| App died on launch | GuidedStep inflating under a plain Material theme | no fragments, no Leanback, plain views |
| A pressed button was never registered | capture lived in a dialog that never took focus | the accessibility service learns the key, no window involved |
| Overlay permission could not be granted | the per-app overlay page does not resolve on the box, so "Open settings" did nothing | three-step fallback, plus the menu path printed on the row |
| Not enough buttons on the remote | one key per action | one Mode key cycles all three modes |

`OverlayService`, `KeyService`, `Settings` and `MainActivity` are new; the
overlay mechanics (a single always-attached view whose alpha is the brightness)
are the part that already worked and are carried over.

In 2.2 that alpha became the *picture* dimmer only: Sleep fills the same view
opaquely with the lamp colour, so the lamp is light rather than a tint over the
picture.

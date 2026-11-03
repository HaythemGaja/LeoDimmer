# LeoDimmer 2.0

A dimmer for Android TV, built for the Xiaomi Mi Box 4 (Android 9 / API 28).
It dims the picture over any app, and it is designed around one constraint:
**the remote does not have a spare button per setting.**

Pure Kotlin. No external dependencies.

## The idea: three modes, one key

| Mode | What you see | Its own settings |
|---|---|---|
| **Day** | full picture | Day brightness (100 / 90 / 80 / 70%) |
| **Night** | the picture dimmed | Night brightness (10 … 50%) |
| **Sleep** | a warm sleep light that fades itself out | glow, colour, auto-off timer |

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

## Permissions, exactly

1. **Display over other apps** — `Settings → Apps → Special access → Display
   over other apps → LeoDimmer → Allow`. The app's row opens it directly; if the
   per-app page does not resolve on the box (it often does not), it falls back to
   the global page and then to the app info page, where Xiaomi hides the switch.
2. **Remote keys** — `Settings → Device → Accessibility → LeoDimmer → On`.

Nothing else is needed. Without (1) nothing dims; without (2) the button does
nothing; both are visible on the screen at all times.

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
- **The sleep timer** fades the light to black over 20 seconds when it runs out,
  then leaves a dim picture for your next wake-up press.
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

The settings screen shows **"Last crash: …"** — the first line of the last
uncaught exception, written to `crash.log` in the app's files. That one line is
usually enough to identify a device-specific failure without a logcat.

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

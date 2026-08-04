# Working on this repo

Single-APK Android app for the Rabbit R1 (480x640, LineageOS, see the
tap-root repo for the OS image). No Gradle, no dependencies: `build.sh` runs
aapt2/javac/d8 in the `r1-android-build` Docker image.

## Build and install

```bash
docker run --rm --platform linux/amd64 -v "$PWD":/app -w /app r1-android-build ./build.sh
adb install -r darkroom.apk
```

If the image is missing: `docker build --platform linux/amd64 -t r1-android-build .`
New `.java` files and resources are picked up automatically; nothing to
register except manifest components.

## Hard rules

- **ASCII only in `.java` files.** javac runs with a C locale in the
  container; an em dash or emoji in a comment fails the build with
  "unmappable character". Use `\uXXXX` escapes for any glyph you need at
  runtime (see the mouth icon in `TimerActivity`). Unicode in XML resources
  is fine.
- **Never block the `snd` HandlerThread.** All ToneGenerator, Vibrator, TTS,
  audio-focus, and widget-push calls are marshalled onto it precisely because
  they block; a `Thread.sleep` there delays the metronome ticks queued behind
  it. Chain `postDelayed` instead.
- **Keep every binder call off the UI thread.** The countdown display has
  been de-janked twice; `tts.speak`, `requestAudioFocus`,
  `AppWidgetManager.updateAppWidget`, and bitmap rendering all live on `snd`.
  Do not move them back.
- **Do not touch `display_temperature_mode`.** The tile parks only
  `display_auto_outdoor_mode`. 0 is this build's own default for the
  temperature mode and is fine; writing it yourself is not, and the provider
  rejects deleting the row.
- **Safelight can look broken after a reboot even when it is set correctly.**
  The setting persists and `dumpsys SurfaceFlinger` shows the red-only matrix,
  but the display sometimes comes up not applying it. Re-writing the value and
  rebooting restores it. Check the matrix first - if it is already red-only,
  the setting is not the problem and nothing in the app needs changing.
- **First-run state lives in `shared_prefs/safelight.xml`** (processes as
  JSON, setup flags, voice choice). `adb shell pm clear` destroys a user's
  chemistry - never run it casually on a device someone uses. A stock C-41 is
  seeded in `loadSteps()` when there are no processes and the `seeded` flag
  is unset, so a wipe leaves a working baseline rather than nothing.
- **Setup is in-app, not adb.** `showSetup()` offers the grants Android will
  not give silently (WRITE_SETTINGS, RECORD_AUDIO, the QS tile request, the
  wallpaper picker). Only the first two auto-trigger the screen. There is no
  API to query whether a QS tile is added - the tick is a remembered flag and
  the row always re-offers the prompt.
- **The wallpaper and the activity share one process.** The desk timer in the
  wallpaper reads `TimerActivity.sceneEndAt` (a static volatile). Splitting
  components into separate processes or apps breaks this silently.
- **`adb shell am force-stop com.calypso.darkroom` unsets the live wallpaper.**
  The system drops a killed wallpaper service. Prefer `adb install -r`
  (incremental, keeps the wallpaper) and reapply with the
  CHANGE_LIVE_WALLPAPER intent if it ever drops.

## Device input model

- Scroll wheel arrives as `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` (hall
  sensor, device `och1970_holl_key`).
- The single side button is wired as the **power key** in hardware. tap-root
  remaps it to `KEYCODE_F1` in the system keylayout; only then can the app
  see it. On a stock keylayout the button never reaches the app, five presses
  triggers Emergency SOS, and none of the button UX works.
- tap-root's bundled powerkey app (separate APK) gives the button its power
  behaviour back *outside* this app, passing F1 through when this app is
  foreground. This app never handles locking.
- Presses are collected in an 800 ms burst window (`buttonPress`/`burstEnd`)
  so multi-press gestures (2 = skip/reset, 5 = blackout) don't fire the
  single-press action on the way. Any new press semantics must go through
  that path.

## Testing without hands on the device

- Button: `adb shell input keyevent 131` (F1). Wheel: keyevents 19/20.
- Rapid sequences via one shell: `adb shell "input keyevent 131; input keyevent 131"`
  — separate adb invocations are ~400 ms apart and break burst gestures.
- `adb shell cmd statusbar click-tile com.calypso.darkroom/.SafelightTile` is
  unreliable (silently no-ops depending on SystemUI state). Verify safelight
  by reading state, not by clicking:
  `adb shell dumpsys SurfaceFlinger | grep -A4 "Color Matrix"` — red-only
  means rows 2 and 3 are all zeros.
- Screenshots (`adb exec-out screencap -p`) do NOT show the color matrix;
  a screenshot proves layout, never redness.
- Timers are wall-clock (`System.currentTimeMillis`) end to end. The audible
  tick is scheduled per-second against the deadline (`left % 1000`); do not
  replace it with a looping AudioTrack — the audio hardware clock drifts
  visibly from the countdown within minutes.

## Design language

Red on black, always: text `#FF2000`, secondary `#CC1A00`, dim `#801000`.
Custom views are stroke-only line art (see `BeakerView`, `AgitView`,
`VoiceIcon`, the film-strip `CelebrationView`). The wallpaper palette lives
at the top of `DarkroomWallpaper.java`; `WallClock.java` copies it for the
widget. No white, no gradients, nothing that would fog paper next to an
open box.

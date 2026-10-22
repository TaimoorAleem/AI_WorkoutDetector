# :wear (Proprio watch recorder)

Records raw accelerometer and gyroscope data on the watch (Raw Format v1, see `docs/specs/phase2-recorder.md`).

## Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Run `RecordingService` as a foreground service. |
| `FOREGROUND_SERVICE_HEALTH` | Required on API 34+ for `foregroundServiceType="health"`. |
| `HIGH_SAMPLING_RATE_SENSORS` | Sensor rates above 200 Hz are gated by it. **We request 50 Hz, so we do not need it for the rate.** It is declared because it satisfies the health foreground-service type's runtime prerequisite as a manifest-only declaration. |
| `WAKE_LOCK` | Partial wake lock while recording (screen off, sensors keep delivering). |
| `VIBRATE` | Haptic cues. |
| `POST_NOTIFICATIONS` | Requested at runtime on API 33+ so the ongoing recording notification is visible. |

Android's foreground-service-types documentation for `health` states that at least one of the following must hold:
declare `HIGH_SAMPLING_RATE_SENSORS` in the manifest, **or** be granted a runtime permission such as `BODY_SENSORS`
(API 35 and lower), `READ_HEART_RATE`, `ACTIVITY_RECOGNITION`, etc.
(https://developer.android.com/develop/background-work/services/fgs/service-types, section "Health").
Declaring `HIGH_SAMPLING_RATE_SENSORS` therefore means **no runtime health permission is needed**, and no body-sensor
data (heart rate) is collected. `BODY_SENSORS` and `ACTIVITY_RECOGNITION` are now requested only for the optional Health Services
exercise strategy (below), not for the foreground service.

Added for the anti-freeze work (D-023, see the next section):

| Permission | Why |
|---|---|
| `ACTIVITY_RECOGNITION`, `BODY_SENSORS` | Runtime permissions required by the Health Services **exercise** (`ExerciseClient.startExerciseAsync` fails with `SecurityException: Missing permissions: [BODY_SENSORS]` without it, measured on the Watch5 Pro). No heart-rate or other data type is requested (`setDataTypes(emptySet())`), so no data is read. Denial is handled: the exercise is skipped and the recording continues. |
| `<queries>` `com.google.android.wearable.healthservices` | Package visibility for the Health Services client. |

## Samsung Freecess and the anti-freeze strategies (D-023)
Samsung's Freecess freezes the process (`MARsmini_FreecessController: FZ : dev.proprio(...), reason: LEV`) seconds after the
screen turns off, and disables our partial wake lock, even with a `health` foreground service. The sensor FIFO then overflows.
`Strategy` (`session/Strategy.kt`) selects which mechanisms are active; the default is `all`:

| Strategy | What it does | Docs |
|---|---|---|
| `none` | Foreground service + partial wake lock only (baseline). | |
| `ongoing` | Attaches a Wear OS **Ongoing Activity** (`androidx.wear:wear-ongoing`) to the recording notification: category workout, touch intent = open the app, status text = elapsed time. Shows the app as a chip on the watch face / launcher. | https://developer.android.com/training/wearables/ongoing-activity |
| `ambient` | `MainActivity` registers an `AmbientLifecycleObserver` (`androidx.wear:wear`) so the recording screen stays the top activity in ambient mode instead of being backgrounded when the screen dims; the ambient face is a black, dim timer. No `FLAG_KEEP_SCREEN_ON`. **Only effective when the watch's Always-on display setting is on** (see results). | https://developer.android.com/training/wearables/apps/always-on |
| `exercise` | Starts a **Health Services** exercise (`ExerciseType.STRENGTH_TRAINING`, no data types, no GPS, no auto-pause) for the whole session and ends it on session end and on app start (crash recovery). | https://developer.android.com/health-and-fitness/health-services/active-data |
| `all` | All three. | |

The strategy lives in memory (`StrategyHolder`, default `all`); only the debug receiver below changes it.

## Device tests over adb (debug builds only)
`src/debug` holds `DebugCommandReceiver` (exported, declared in the debug manifest only: a release APK contains no trace of it).
It calls the same `RecordingController` paths as the UI with `source="watch"`. Replace `$ADB` with `adb -s <watch>`.

```bash
# one-time: permissions (only for dev.proprio)
$ADB shell pm grant dev.proprio android.permission.ACTIVITY_RECOGNITION
$ADB shell pm grant dev.proprio android.permission.BODY_SENSORS
$ADB shell pm grant dev.proprio android.permission.POST_NOTIFICATIONS

R=dev.proprio/dev.proprio.wear.debug.DebugCommandReceiver
# start a gym session. strategy: none|ongoing|ambient|exercise|all (omitted = keep the current one, default all)
# keep=true keeps the zip in the outbox after the phone's ack (renamed to *.zip.kept); launch=false does not open the app
$ADB shell am broadcast -n $R -a dev.proprio.debug.SESSION_START --es strategy exercise --ez keep true
$ADB shell input keyevent KEYCODE_SLEEP            # screen off
$ADB shell am broadcast -n $R -a dev.proprio.debug.SEGMENT_START --es kind set     # or: other
$ADB shell am broadcast -n $R -a dev.proprio.debug.SEGMENT_STOP
$ADB shell am broadcast -n $R -a dev.proprio.debug.SESSION_END
# The broadcast result (data="...") reports ok=<bool> and the session id. Pull the zip:
$ADB shell run-as dev.proprio ls files/outbox
$ADB exec-out run-as dev.proprio cat files/outbox/proprio-session-<id>.zip.kept > session.zip   # .zip if never acked
cd ml && python -m workout_ml.sensors.android ../session.zip                                     # rates, gaps
$ADB logcat -d | grep -E "Freecess.*proprio|Dropping"                                           # freeze events
```
The matrix below is these commands in a loop (not committed as a script).

### Test matrix, 2026-10-22 (Galaxy Watch5 Pro SM-R920, API 34, **on the charger**)
Procedure per strategy: force-stop, open the app, `SESSION_START`, `KEYCODE_SLEEP`, record 180 s, `SESSION_END`, pull the zip,
`python -m workout_ml.sensors.android`, grep logcat. Requested rate 50 Hz.

| strategy | samples (acc) | effective rate | gaps > 100 ms | max gap | Freecess `FZ` events (after session start) | `Dropping` |
|---|---|---|---|---|---|---|
| none | 545 | 2.9 Hz | 1 | 174.6 s | 1 (after ~11 s, LEV) | 0 |
| ongoing | 483 | 2.6 Hz | 1 | 175.3 s | 1 (after ~10 s) | 0 |
| ambient | 521 | 2.8 Hz | 1 | 175.2 s | 1 (after ~10 s) | 0 |
| exercise (started and ACTIVE) | 603 | 3.2 Hz | 1 | 178.4 s | 1 (after ~8 s) | 0 |
| all | 611 | 49.8 Hz over only 12.3 s | 0 reported, but the zip covers 12 s of 180 s | the tail (~167 s, never delivered before the session ended) is not counted by the tool | 1 (after ~12 s) | 0 |

**Result: no strategy prevented the freeze on the charger.** Every run was frozen (`FZ : dev.proprio(10043), reason: LEV`) 8-12 s after the session started (about 5 s after screen-off)
and thawed only when the end-of-session broadcast ran (`UFZ ... reason: broadcast`), with the partial wake
lock `DISABLED` in between. The process state at that time was `FGS` (procState 4), so Freecess freezes foreground-service
processes on this firmware. Observations that limit the conclusions:
- The watch was charging; the focused window after screen-off was Samsung's `ChargingAod`, not our activity, so charging may change Freecess behaviour. Repeat the best candidates on the wrist.
- **`ambient` could not work here**: the system log says `isAmbientEnabled(), ambient on=false`, i.e. the watch's Always-on display setting is off, so the screen is simply turned off (`Dozing`) and no activity stays in ambient. It needs that setting on; the test did not change it.
- `exercise` did start (`USER_STARTING` -> `ACTIVE`, `ENDED` at session end) once `BODY_SENSORS` was granted; it had no effect on Freecess for our process (the exercise lives in the Health Services process).
- `Dropping ... cached events` did not appear in logcat during these runs; the loss shows as the gap (events not read while frozen).

## Reference device
Galaxy Watch5 Pro (SM-R920), Android 14 / API 34 (Wear OS 5), build R920XXU1CXK1. Relevant sensors from `dumpsys sensorservice`:
`LSM6DSO Accelerometer` (STM, 6.25-100 Hz, FIFO 300), `LSM6DSO Gyroscope` (STM, 6.25-100 Hz, no batching),
plus `Corrected Gyroscope Sensor` (AOSP, **also TYPE_GYROSCOPE**, software virtual), `LSM6DSO Gyroscope Uncalibrated`,
and Samsung-private/linear-acceleration sensors that are not used.

Sensor selection is explicit (`SensorSelector`): from `getSensorList(type)` pick a hardware sensor (vendor not `AOSP`,
name not containing `Corrected`, non-wake-up preferred); fall back to `getDefaultSensor` only if none matches. The choice and
reason are logged (tag `Proprio`); a fallback is flagged by a `[default_fallback]` suffix on the recorded sensor `name`
(`Meta` has no selection field). The hardware sensor name in `meta.json` identifies the choice.

## Behaviour notes
- Queue overflow policy: drop newest, counted (`RecorderSnapshot.droppedEvents`, logged at session end); capacity 60 000 events.
- `RecordingService` is `START_NOT_STICKY`. If the process is killed, files written so far stay readable
  (`meta.json` `complete=false`); on next app start `TransferManager.recoverOrphans` zips and sends them.
- Calibration adds a 5 s unsegmented lead-in before the first step.
- Unit tests: `./gradlew :wear:testDebugUnitTest`. Smoke test (device/emulator only): `RecordingSmokeTest` in `androidTest`.

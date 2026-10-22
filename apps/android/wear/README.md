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
data (heart rate) is collected, so `BODY_SENSORS` and `ACTIVITY_RECOGNITION` are deliberately not requested.

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

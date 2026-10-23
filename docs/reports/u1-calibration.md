# U1 calibration: Galaxy Watch5 Pro (SM-R920)

> Written by hand from session `9390dce6-8af1-44b7-9306-31994e90e314` (2026-10-23, 275 s, calibration mode, watch held in the hand).
> The raw data is versioned in DVC: `data/raw/watch/calibration/` (`data/raw/watch.dvc`, dataset `watch-calib-v1`).
> The analysis was exploratory (`.claude/scratch/u1_analysis.py`); the Phase 3 eval harness formalizes it.

## Result: all calibration checks pass
| Check | Result | Gate (PROJECT.md §6.5) |
|---|---|---|
| Timing | 49.88 Hz; interval p50 20.05 ms, p99 20.11 ms, max 20.11 ms; 0 gaps; acc–gyr offset 0.00 ms; clock drift −0.17 ms | none |
| Static magnitude (table, 120 s) | 0.989 g | 1.00 ± 0.03 g ✅ |
| Gyro bias at rest | 0.11 deg/s (x −0.03, y −0.02, z −0.10) | < 1 deg/s ✅ |
| Noise at rest | acc 3.2–3.5 mg std per axis; gyro 0.10 / 0.15 / 0.48 deg/s (x/y/z) | none |
| Static poses | all 6 on the expected signed axis (+z face up, −z face down, +x buttons up, −x buttons down, +y 12 o'clock up, −y 12 o'clock down) | ✅ |
| Gyro sign and units | integrated gyro reproduces the measured gravity change: median error 2.0° (rot_x), 2.7° (rot_y). Flipping any single axis raises it to 16–74° | ✅ |

## What this establishes
- **Units:** the accelerometer is m/s² with gravity included, and the gyro is rad/s. A unit error would make the integrated rotation off by a factor of about 57.
- **Frame:** the Android device frame is as documented (+x toward the crown at 3 o'clock, +y toward 12 o'clock, +z out of the screen) and right-handed, so the profile's `unit_scale` values and the det = +1 constraint are correct.
- **Bias:** the gyro is bias-compensated, as expected for `TYPE_GYROSCOPE`.

## Minor findings
- **Accelerometer z offset ≈ −0.03 g.** Face down read 1.031 g and face up 0.970 g. x and y offsets are below 0.015 g. That's equivalent to under 2° of tilt, far inside the 15° tolerance of the production candidate (D-021), so it isn't corrected. It could be added to the device profile later as a per-axis bias.
- **The owner's rot_x step was mostly a rotation about z**, an instruction misread. It still contained about 90° about x, and the single-axis flip test pins the x sign (66.9° error when flipped vs 2.0° as-is). No re-recording is needed.
- **rot_z (spinning flat on the table) can't test the gyro sign,** because gravity doesn't change. That's inconclusive by design.

## Not covered by U1
The rotation **R** between the watch frame and the MetaMotion frame needs exercise data (PROJECT.md §6.4, Kabsch on per-exercise gravity vectors), so it comes from U2.

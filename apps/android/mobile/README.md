# Proprio phone app (`:mobile`)

## Labeling conventions

- Labels are written to `labels.json` (Raw Format v1) keyed by the watch's segment id; the watch's raw files are never modified.
- **Discarded segments.** `labels.schema.json` has no "discarded" kind. A segment the user marks "Discard (accidental)" in the stop prompt (offered by default when a segment was shorter than 3.0 s, measured on the phone from the STARTED/STOPPED event arrival times) is stored as kind `other` with the exact note `"discarded"`; exercise, load, category, reps and RPE are cleared. Readers must treat `note == "discarded"` as "ignore this segment": `workout_ml.sensors.android.Session.segments()` skips such segments unless `include_discarded=True`.
- **Unconfirmed labels.** The Review screen marks a `set` without `reps_user` as "unconfirmed" (chip in the list, orange bar on the plot). A watch-started set only gets `reps_user` when the user confirms exercise and reps in the stop prompt, so a missing value means the exercise/load were only the phone's guess at press time. No file-format change.
- **Starting a session from the phone.** The phone first opens Proprio on the watch with a `RemoteActivityHelper` intent (`ACTION_VIEW`, `proprio://session/start?kind=GYM&participant=P01`) so the watch app is visible and may start its foreground service, then sends the usual `session/start` message as a fallback. If both fail the phone shows "Open Proprio on your watch, then try again."

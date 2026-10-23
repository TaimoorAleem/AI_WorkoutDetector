"""``to_csf``: raw SensorSet + device profile -> CSF-1 DataFrame."""

from __future__ import annotations

import pandas as pd

from workout_ml.sensors.profiles import Profile
from workout_ml.sensors.resample import bin_mean, emulate_source_rate
from workout_ml.sensors.transforms import to_canonical_values
from workout_ml.sensors.types import RawStream, SensorSet

CSF_VERSION = 1
CSF_COLUMNS = ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]


def _convert_stream(stream: RawStream, profile: Profile) -> pd.DataFrame:
    expected = profile.sensor_spec(stream.sensor).source_unit
    if stream.unit != expected:
        raise ValueError(
            f"{stream.sensor} stream is in {stream.unit!r} but profile "
            f"{profile.profile_id!r} expects {expected!r}"
        )
    xyz = to_canonical_values(stream.xyz, profile, stream.sensor)  # steps 1-3
    t_ns = stream.t_ns
    em = profile.emulation
    if em.enabled:  # step 4
        t_ns, xyz = emulate_source_rate(
            t_ns,
            xyz,
            em.target_hz(stream.sensor),
            anti_alias_order=em.anti_alias_order,
            anti_alias_fraction=em.anti_alias_fraction,
            max_gap_ms=em.max_gap_ms,
            label=stream.sensor,
        )
    return bin_mean(t_ns, xyz, stream.sensor)  # step 5 (per sensor)


def to_csf(sensor_set: SensorSet, profile: Profile) -> pd.DataFrame:
    """Convert one recorded set to CSF-1 (200 ms bins, g and deg/s, MetaMotion frame).

    A bin is kept only if both sensors have at least one sample in it.
    """
    acc = _convert_stream(sensor_set.acc, profile)
    gyr = _convert_stream(sensor_set.gyr, profile)
    df = acc.join(gyr, how="inner")[CSF_COLUMNS]
    df.attrs["csf"] = {
        "csf_version": CSF_VERSION,
        "profile_id": profile.profile_id,
        "transform_id": profile.transform_id,
    }
    return df

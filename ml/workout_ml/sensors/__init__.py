"""Sensor normalization layer: raw device streams -> CSF-1 (see docs/sensor_spec.md)."""

from workout_ml.sensors.convert import CSF_COLUMNS, CSF_VERSION, to_csf
from workout_ml.sensors.metamotion import read_metamotion
from workout_ml.sensors.profiles import Profile, load_profile
from workout_ml.sensors.simulate import profile_with_rotation, random_rotation, simulate_android
from workout_ml.sensors.transforms import from_canonical
from workout_ml.sensors.types import RawStream, SensorSet

__all__ = [
    "CSF_COLUMNS",
    "CSF_VERSION",
    "Profile",
    "RawStream",
    "SensorSet",
    "from_canonical",
    "load_profile",
    "profile_with_rotation",
    "random_rotation",
    "read_metamotion",
    "simulate_android",
    "to_csf",
]

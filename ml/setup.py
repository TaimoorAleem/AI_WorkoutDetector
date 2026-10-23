"""Build hook: copy the shared JSON Schemas (protocol/schema) into the wheel as package data.

The schemas' source of truth is ``protocol/schema/`` at the repo root (shared with the Android
:protocol module). ``workout_ml.sensors.android`` reads that directory directly in a checkout
(including editable installs) and falls back to the copy made here for an installed wheel.
"""

import shutil
from pathlib import Path

from setuptools import setup
from setuptools.command.build_py import build_py


class BuildPyWithSchemas(build_py):
    def run(self):
        super().run()
        src = Path(__file__).resolve().parent.parent / "protocol" / "schema"
        if not src.is_dir():
            return
        dst = Path(self.build_lib) / "workout_ml" / "sensors" / "schemas"
        dst.mkdir(parents=True, exist_ok=True)
        for f in src.glob("*.json"):
            shutil.copy2(f, dst / f.name)


setup(cmdclass={"build_py": BuildPyWithSchemas})

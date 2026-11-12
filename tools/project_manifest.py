"""Compatibility import; the manifest contract lives in the installed package."""
from pathlib import Path
import sys

_source = Path(__file__).resolve().parents[1] / 'python'
if (_source / 'ros2android').is_dir():
    sys.path.insert(0, str(_source))

from ros2android.project_manifest import (  # noqa: E402,F401
    SCHEMA_VERSION, RUNTIME_ABIS, PACKAGE_PATTERN, validate_manifest, load_manifest,
)

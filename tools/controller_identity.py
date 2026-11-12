"""Compatibility import; implementation lives in the installed host package."""
from pathlib import Path
import sys

_source = Path(__file__).resolve().parents[1] / 'python'
if (_source / 'ros2android').is_dir():
    sys.path.insert(0, str(_source))

from ros2android.controller_identity import controller_certificate  # noqa: E402,F401

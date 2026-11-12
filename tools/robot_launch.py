"""Compatibility import; implementation lives in the installed host package."""
from pathlib import Path
import sys

_source = Path(__file__).resolve().parents[1] / 'python'
if (_source / 'ros2android').is_dir():
    sys.path.insert(0, str(_source))

from ros2android.robot_launch import validate_launch, TOPIC, IDENTIFIER, NAMESPACE  # noqa: E402,F401

"""Compatibility import for workspace inspection; implementation lives in the package."""

from pathlib import Path
import sys

_source = Path(__file__).resolve().parents[1] / 'python'
if (_source / 'ros2android').is_dir():
    sys.path.insert(0, str(_source))

from ros2android.workspace_inspection import (  # noqa: E402,F401
    DEPENDENCY_TAGS,
    EXCLUDED,
    default_host_prefixes,
    indexed_packages,
    inspect_dependencies,
    inventory,
    read_package,
)

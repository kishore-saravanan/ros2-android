#!/usr/bin/env python3
"""Compatibility entry point for the packaged managed colcon/vcstool preparation."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if (ROOT / 'python/ros2android').is_dir():
    sys.path.insert(0, str(ROOT / 'python'))

from ros2android.build_tools import run  # noqa: E402


def main():
    return run(sys.argv[1:])


if __name__ == '__main__':
    raise SystemExit(main())

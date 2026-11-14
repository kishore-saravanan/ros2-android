#!/usr/bin/env python3
"""Compatibility entry point for packaged pinned-source preparation."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if (ROOT / 'python/ros2android').is_dir():
    sys.path.insert(0, str(ROOT / 'python'))

from ros2android.native_sources import run  # noqa: E402


def main():
    return run(tooling_root=ROOT)


if __name__ == '__main__':
    raise SystemExit(main())

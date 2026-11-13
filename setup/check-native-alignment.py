#!/usr/bin/env python3
"""Reject 4 KB target ELF binaries before APK/SDK publication (does not test runtime behavior)."""
import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'python'))
from ros2android.elf_alignment import check_apk, check_tree

parser = argparse.ArgumentParser(description=__doc__)
selection = parser.add_mutually_exclusive_group(required=True)
selection.add_argument('--apk', type=Path)
selection.add_argument('--native-root', type=Path)
args = parser.parse_args()
try:
    count = check_apk(args.apk) if args.apk else check_tree(args.native_root)
except (OSError, ValueError) as error:
    parser.exit(1, str(error) + '\n')
print(f'{count} target ELF files pass 16 KB load-segment alignment')

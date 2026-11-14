#!/usr/bin/env python3
"""Sample ROS2 Android's own process scheduling/freezer state while a command runs.

Only ROS2 Android package names are accepted, so no other app is queried. Each
JSON line uses the phone boot clock in milliseconds (``boot_ms``), the same clock
as Android ``SystemClock.elapsedRealtime()``, so samples can be correlated with
instrumentation logs and fixture ``stamp_ms`` telemetry. Observation only: it
never signals, freezes, thaws or reconfigures a process.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

PACKAGE = re.compile(r'^dev\.ros2android\.android(\.runner\.[A-Za-z0-9_]{1,64})?$')
SAMPLE = re.compile(
    r'^S\|(?P<uptime>\d+(?:\.\d+)?)\|(?P<package>[^|]+)\|(?P<pid>\d+)\|(?P<state>[A-Za-z]?'
    r')\|(?P<freezer>[^|]*)\|(?P<cpuset>[^|]*)(?:\|(?P<v2frozen>[^|]*)\|(?P<wchan>[^|]*)\|'
    r'(?P<cpu>[^|]*))?$'
)


def device_loop(packages, interval):
    names = ' '.join(packages)
    # Toybox sh: one long-lived shell avoids per-sample adb overhead.
    return (
        f'while :; do read up idle < /proc/uptime; for name in {names}; do '
        'for pid in $(pidof "$name"); do '
        "st=$(cut -d' ' -f3 /proc/$pid/stat 2>/dev/null); "
        "fz=$(grep -m1 ':freezer:' /proc/$pid/cgroup 2>/dev/null | cut -d: -f3); "
        "cs=$(grep -m1 ':cpuset:' /proc/$pid/cgroup 2>/dev/null | cut -d: -f3); "
        "cg=$(awk -F: '$1==0 {print $3}' /proc/$pid/cgroup 2>/dev/null); "
        'vf=""; case "$cg" in */pid_"$pid") '
        "vf=$(grep '^frozen ' \"/sys/fs/cgroup$cg/cgroup.events\" 2>/dev/null | cut -d' ' "
        '-f2);; esac; '
        'wc=$(cat /proc/$pid/wchan 2>/dev/null); '
        "cpu=$(cut -d' ' -f14,15 /proc/$pid/stat 2>/dev/null); "
        'echo "S|$up|$name|$pid|$st|$fz|$cs|$vf|$wc|$cpu"; done; done; '
        f'sleep {interval:.3f}; done'
    )


def parse(line):
    match = SAMPLE.match(line.strip())
    if not match:
        return None

    sample = {
        'boot_ms': round(float(match['uptime']) * 1000),
        'package': match['package'],
        'pid': int(match['pid']),
        'state': match['state'],
        'freezer': match['freezer'],
        'cpuset': match['cpuset'],
    }

    if match['v2frozen'] is not None:
        sample.update(
            cgroupFrozen={'0': False, '1': True}.get(match['v2frozen']),
            wchan=match['wchan'],
            cpuTicks=match['cpu'],
        )

    return sample


def summarize(samples):
    """Per package/pid: sample count and contiguous frozen intervals on the boot clock."""
    summary = {}

    for sample in samples:
        key = f'{sample["package"]}#{sample["pid"]}'
        row = summary.setdefault(
            key,
            {
                'samples': 0,
                'frozenSamples': 0,
                'frozenIntervals': [],
                'freezerReadableSamples': 0,
                'states': {},
                'first_ms': sample['boot_ms'],
            },
        )
        row['samples'] += 1
        row['last_ms'] = sample['boot_ms']
        row['states'][sample['state']] = row['states'].get(sample['state'], 0) + 1
        if sample['freezer'] or sample.get('cgroupFrozen') is not None:
            row['freezerReadableSamples'] += 1

        frozen = (
            sample['freezer'].rstrip('/').endswith('frozen') or sample.get('cgroupFrozen') is True
        )
        intervals = row['frozenIntervals']

        if frozen:
            row['frozenSamples'] += 1

            if intervals and intervals[-1]['open']:
                intervals[-1]['to_ms'] = sample['boot_ms']
            else:
                intervals.append(
                    {'from_ms': sample['boot_ms'], 'to_ms': sample['boot_ms'], 'open': True}
                )
        elif intervals and intervals[-1]['open']:
            intervals[-1]['open'] = False

    for row in summary.values():
        for interval in row['frozenIntervals']:
            interval.pop('open', None)

    return summary


def main(arguments=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        '--package',
        action='append',
        required=True,
        help='ROS2 Android package to observe (repeatable): dev.ros2android.android[.runner.<id>]',
    )
    parser.add_argument(
        '--serial', help='Explicit USB ADB target; otherwise the single USB device'
    )
    parser.add_argument(
        '--interval', type=float, default=0.2, help='Seconds between device samples (0.05..5)'
    )
    parser.add_argument(
        '--output',
        type=Path,
        required=True,
        help='JSON-lines output (keep under ignored device-logs/)',
    )
    parser.add_argument(
        'command', nargs=argparse.REMAINDER, help='-- command to run while observing'
    )
    args = parser.parse_args(arguments)
    packages = sorted(set(args.package))
    invalid = [name for name in packages if not PACKAGE.match(name)]
    if invalid:
        parser.error('Only ROS2 Android packages may be observed: ' + ', '.join(invalid))

    if not 0.05 <= args.interval <= 5:
        parser.error('--interval must be between 0.05 and 5 seconds')

    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('Give the command to observe after --')

    sdk = Path(
        os.environ.get('ANDROID_HOME')
        or os.environ.get('ANDROID_SDK_ROOT')
        or Path.home() / 'Android/Sdk'
    )
    adb = [str(sdk / 'platform-tools/adb'), *(['-s', args.serial] if args.serial else ['-d'])]
    args.output.parent.mkdir(parents=True, exist_ok=True)

    samples = []
    observer = subprocess.Popen(
        [*adb, 'shell', device_loop(packages, args.interval)],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )

    def collect():
        with args.output.open('w') as stream:
            for line in observer.stdout:
                sample = parse(line)
                if sample:
                    samples.append(sample)
                    stream.write(json.dumps(sample) + '\n')
                    stream.flush()

    reader = threading.Thread(target=collect, daemon=True)
    reader.start()
    time.sleep(max(args.interval * 2, 0.5))  # Record a baseline before the command starts.

    try:
        code = subprocess.call(command)
    finally:
        time.sleep(max(args.interval * 2, 0.5))
        observer.terminate()

        try:
            observer.wait(timeout=10)
        except subprocess.TimeoutExpired:
            observer.kill()

        reader.join(timeout=10)

    summary = summarize(samples)
    (args.output.with_suffix('.summary.json')).write_text(json.dumps(summary, indent=2) + '\n')
    print(
        json.dumps({'commandExit': code, 'samples': len(samples), 'processes': summary}, indent=2)
    )
    return code


if __name__ == '__main__':
    sys.exit(main())

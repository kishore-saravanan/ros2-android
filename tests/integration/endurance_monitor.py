#!/usr/bin/env python3
"""Sample memory/CPU of ROS2 Android's own processes plus battery and thermal state while a command runs.

Only ROS2 Android package names are accepted. Battery and thermal readings are device-wide
hardware diagnostics; no other app is named or inspected. Output: JSON lines with the phone
boot clock (``boot_ms``) and a ``.summary.json`` (peak/mean RSS, CPU %, battery drop, peak
temperatures and thermal status). Observation only: nothing on the phone is changed.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

_spec = importlib.util.spec_from_file_location('own_process_observer', Path(__file__).with_name('own_process_observer.py'))
_observer = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_observer)
PACKAGE = _observer.PACKAGE
TEMPERATURE_TYPES = {0: 'cpu', 1: 'gpu', 2: 'battery', 3: 'skin'}


def sample_command(packages):
    names = ' '.join(packages)
    return ('read up idle < /proc/uptime; echo "U|$up"; '
            f'for name in {names}; do for pid in $(pidof "$name"); do '
            "rss=$(grep VmRSS /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2); "
            "cpu=$(cut -d' ' -f14,15 /proc/$pid/stat 2>/dev/null); "
            'echo "P|$name|$pid|$rss|$cpu"; done; done; '
            "dumpsys battery | grep -E '^ +(level|temperature|voltage|status|AC powered|USB powered):' | sed 's/^ */B|/'; "
            "dumpsys thermalservice | grep -E 'Thermal Status|mValue=' | sed 's/^ */T|/'")


def parse(output, ticks_per_second=100):
    sample = {'processes': {}, 'battery': {}, 'temperatures': {}}
    for line in output.splitlines():
        kind, _, rest = line.strip().partition('|')
        if kind == 'U':
            sample['boot_ms'] = round(float(rest) * 1000)
        elif kind == 'P':
            name, pid, rss, cpu = (rest.split('|') + ['', '', '', ''])[:4]
            ticks = [int(v) for v in cpu.split()] if cpu.strip() else []
            sample['processes'][f'{name}#{pid}'] = {'rssKb': int(rss) if rss.isdigit() else None,
                                                    'cpuSeconds': sum(ticks) / ticks_per_second if ticks else None}
        elif kind == 'B':
            key, _, value = rest.partition(':')
            sample['battery'][key.strip()] = value.strip()
        elif kind == 'T':
            status = re.search(r'Thermal Status: (\d+)', rest)
            if status:
                sample['thermalStatus'] = max(sample.get('thermalStatus', 0), int(status[1]))
            value = re.search(r'mValue=([-\d.]+), mType=(\d+)', rest)
            if value and int(value[2]) in TEMPERATURE_TYPES:
                label = TEMPERATURE_TYPES[int(value[2])]
                sample['temperatures'][label] = max(sample['temperatures'].get(label, -273.0), float(value[1]))
    return sample


def summarize(samples):
    processes = {}
    for sample in samples:
        for key, row in sample['processes'].items():
            entry = processes.setdefault(key, {'rssKb': [], 'cpu': []})
            if row['rssKb'] is not None:
                entry['rssKb'].append(row['rssKb'])
            if row['cpuSeconds'] is not None:
                entry['cpu'].append((sample['boot_ms'], row['cpuSeconds']))
    result = {'samples': len(samples), 'processes': {}}
    for key, entry in processes.items():
        cpu = entry['cpu']
        span = (cpu[-1][0] - cpu[0][0]) / 1000 if len(cpu) > 1 else 0
        result['processes'][key] = {
            'peakRssMb': round(max(entry['rssKb']) / 1024, 1) if entry['rssKb'] else None,
            'meanRssMb': round(sum(entry['rssKb']) / len(entry['rssKb']) / 1024, 1) if entry['rssKb'] else None,
            'rssGrowthMb': round((entry['rssKb'][-1] - entry['rssKb'][0]) / 1024, 1) if len(entry['rssKb']) > 1 else None,
            'meanCpuPercent': round(100 * (cpu[-1][1] - cpu[0][1]) / span, 1) if span > 0 else None}
    levels = [int(s['battery']['level']) for s in samples if s['battery'].get('level', '').isdigit()]
    temps = [int(s['battery']['temperature']) / 10 for s in samples if s['battery'].get('temperature', '').isdigit()]
    result['battery'] = {'startLevel': levels[0] if levels else None, 'endLevel': levels[-1] if levels else None,
                         'peakTemperatureC': max(temps) if temps else None}
    result['thermal'] = {'peakStatus': max((s.get('thermalStatus', 0) for s in samples), default=None),
                         'peakTemperaturesC': {label: max(s['temperatures'][label] for s in samples if label in s['temperatures'])
                                               for label in {k for s in samples for k in s['temperatures']}}}
    return result


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--package', action='append', required=True, help='ROS2 Android package to sample (repeatable)')
    parser.add_argument('--serial', help='Explicit ADB target; otherwise the single USB device')
    parser.add_argument('--interval', type=float, default=10, help='Seconds between samples (1..300)')
    parser.add_argument('--output', type=Path, required=True, help='JSON-lines output (keep under ignored device-logs/)')
    parser.add_argument('command', nargs=argparse.REMAINDER, help='-- command to run while sampling')
    args = parser.parse_args(arguments)
    packages = sorted(set(args.package))
    invalid = [name for name in packages if not PACKAGE.match(name)]
    if invalid:
        parser.error('Only ROS2 Android packages may be sampled: ' + ', '.join(invalid))
    if not 1 <= args.interval <= 300:
        parser.error('--interval must be between 1 and 300 seconds')
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('Give the command to run after --')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk')
    adb = [str(sdk / 'platform-tools/adb'), *(['-s', args.serial] if args.serial else ['-d'])]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    samples, stop = [], threading.Event()

    def collect():
        with args.output.open('w') as stream:
            while not stop.is_set():
                result = subprocess.run([*adb, 'shell', sample_command(packages)], capture_output=True, text=True, timeout=60)
                sample = parse(result.stdout)
                if 'boot_ms' in sample:
                    samples.append(sample); stream.write(json.dumps(sample) + '\n'); stream.flush()
                stop.wait(args.interval)

    thread = threading.Thread(target=collect, daemon=True); thread.start()
    try:
        code = subprocess.call(command)
    finally:
        stop.set(); thread.join(timeout=90)
    summary = summarize(samples)
    args.output.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'commandExit': code, **summary}, indent=2))
    return code


if __name__ == '__main__':
    sys.exit(main())

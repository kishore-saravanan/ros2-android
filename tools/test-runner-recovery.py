#!/usr/bin/env python3
"""Physical default-demo test: callback exception, visible failure, cleanup, restart.

Requires a built/deployed default C++ demo and an unlocked connected phone.
Leaves the demo running normally on success. Does not access other applications.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--peer', required=True)
parser.add_argument('--local', required=True)
parser.add_argument('--serial')
args = parser.parse_args()
cli = [str(Path(__file__).resolve().parent / 'ros2android')]
if args.serial:
    cli += ['--serial', args.serial]


def command(*values):
    return subprocess.check_output([*cli, *values], text=True)


def status():
    return json.loads(command('status'))


command('stop')
command('start', '--peer', args.peer, '--local', args.local,
        '--', '-p', 'fail_after_ticks:=3')
deadline = time.monotonic() + 15
while time.monotonic() < deadline:
    failed = status()
    if failed['state'].startswith('Failed:') and not failed['busy']:
        break
    time.sleep(.2)
else:
    raise RuntimeError('Runner did not report callback failure: ' + json.dumps(status()))
assert not failed['native']['running'], failed
assert not failed['native']['node'], failed
assert 'Requested demo callback failure' in failed['native']['error'], failed
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
adb = [str(sdk / 'platform-tools/adb'), *(['-s', args.serial] if args.serial else ['-d'])]
deadline = time.monotonic() + 5
while time.monotonic() < deadline:
    services = subprocess.check_output([*adb, 'shell', 'dumpsys', 'activity', 'services',
                                        'dev.robotbrain.android.runner.demo'], text=True)
    if '/dev.robotbrain.runner.ProjectService}' not in services:
        break
    time.sleep(.2)
else:
    raise RuntimeError('Failed runner foreground service did not terminate')
command('start', '--peer', args.peer, '--local', args.local)
first = status()
deadline = time.monotonic() + 8
while time.monotonic() < deadline:
    recovered = status()
    if recovered['native']['heartbeat'] > first['native']['heartbeat'] + 1:
        break
    time.sleep(.2)
else:
    raise RuntimeError('Restarted executor heartbeat did not advance')
assert recovered['state'] == 'Running' and not recovered['native']['error'], recovered
print(json.dumps({'callback_failure_detected': True, 'failed_service_removed': True,
                  'restart_heartbeat_advanced': True,
                  'failure': failed, 'recovered': recovered}, indent=2))

#!/usr/bin/env python3
"""Physical ABI-2 regression: validation, partial-start rollback, group failure/restart.

Requires the built/deployed cpp_workspace example and an unlocked phone.
Leaves its default launch running on success. Temporary configs stay on the PC.
"""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--peer', required=True)
parser.add_argument('--local', required=True)
parser.add_argument('--serial')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
project = root / 'examples/cpp_workspace'
cli = [str(root / 'tools/ros2android'), '--project', str(project)]
if args.serial:
    cli += ['--serial', args.serial]


def command(*values, success=True):
    result = subprocess.run([*cli, *values], text=True, capture_output=True)
    if success and result.returncode:
        raise RuntimeError(result.stderr + result.stdout)
    if not success and not result.returncode:
        raise RuntimeError('Invalid launch unexpectedly succeeded')
    return result


def status():
    return json.loads(command('status').stdout)


def wait_for(check, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = status()
        if check(value):
            return value
        time.sleep(.2)
    raise RuntimeError('Unexpected workspace state: ' + json.dumps(status()))


def start(path=None, success=True):
    command('start', '--peer', args.peer, '--local', args.local,
            *(['--launch', str(path)] if path else []), success=success)


launch = json.loads((project / 'launch/robot.launch.json').read_text())
with tempfile.TemporaryDirectory(prefix='ros2-android-launch-') as folder:
    path = Path(folder) / 'launch.json'
    if status()['state'] != 'Running':
        start()
    invalid = copy.deepcopy(launch)
    invalid['include'] = 'unsupported.launch.py'
    path.write_text(json.dumps(invalid))
    start(path, success=False)
    assert status()['state'] == 'Running', 'Rejected input disturbed running project'
    command('stop')
    broken = copy.deepcopy(launch)
    broken['nodes'][1]['component'] = 'missing_component'
    path.write_text(json.dumps(broken))
    start(path, success=False)
    rolled_back = wait_for(lambda s: s['state'].startswith('Failed:') and not s['busy'])
    assert not rolled_back['native']['running'] and rolled_back['native']['nodes'] == [], rolled_back
    assert 'Unknown project component' in rolled_back['native']['error'], rolled_back
    failing = copy.deepcopy(launch)
    failing['nodes'][0]['parameters']['fail_after_ticks'] = 5
    path.write_text(json.dumps(failing))
    start(path)
    failed = wait_for(lambda s: s['state'].startswith('Failed:') and not s['busy'])
    assert not failed['native']['running'] and failed['native']['nodes'] == [], failed
    assert 'Requested workspace callback failure' in failed['native']['error'], failed
    start()
    recovered = wait_for(lambda s: s['state'] == 'Running' and s['native']['heartbeat'] >= 2)
    assert set(recovered['native']['nodes']) == {'/robot/producer', '/robot/processor'}, recovered
    assert not recovered['native']['error'], recovered
print(json.dumps({'invalid_launch_preserves_running_group': True, 'partial_start_rolled_back': True,
                  'callback_failure_stops_group': True, 'restart_verified': True,
                  'recovered': recovered}, indent=2))

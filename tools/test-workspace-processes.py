#!/usr/bin/env python3
"""Physical native-process workspace test: stop, child exit detection, teardown and restart."""
import argparse
import json
from pathlib import Path
import subprocess
import time
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--workspace', type=Path, required=True)
parser.add_argument('--peer', required=True)
parser.add_argument('--local', required=True)
parser.add_argument('--domain', type=int, default=42)
args = parser.parse_args()
cli = [str(Path(__file__).resolve().parent / 'ros2android-workspace'), '--workspace', str(args.workspace)]

def command(*values):
    return subprocess.check_output([*cli, *values], text=True)

def status():
    return json.loads(command('status'))

def wait(predicate):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        value = status()
        if predicate(value): return value
        time.sleep(.2)
    raise RuntimeError('Unexpected process-runner state: ' + json.dumps(status()))

command('stop')
stopped = status()
assert not stopped['native']['running'] and stopped['native']['nodes'] == [], stopped
settings = ['--domain', str(args.domain), '--peer', args.peer, '--local', args.local]
command('start', *settings, '--', '-p', 'fail_after_samples:=5')
failed = wait(lambda s: s['state'].startswith('Failed:') and not s['busy'])
assert 'ws_nodes__processor exited with code' in failed['native']['error'], failed
assert not failed['native']['running'] and failed['native']['nodes'] == [], failed
command('start', *settings)
recovered = wait(lambda s: s['state'] == 'Running' and s['native']['running'])
assert set(recovered['native']['nodes']) == {'/android_test/talker', '/android_test/processor'}, recovered
print(json.dumps({'clean_stop': True, 'child_process_exit_detected': True,
                  'failed_group_stopped': True, 'controller_survived': True,
                  'restart': True, 'failure': failed, 'recovered': recovered}, indent=2))

#!/usr/bin/env python3
"""Opt-in physical main-process death test; only the disposable protocol fixture.

Requires USB ADB, current debug app/fixture, Wi-Fi off, inactive sessions and an
unlocked ROS2 Android screen. No cameras, MCU, motor commands or personal apps.
The prepare phase intentionally aborts instrumentation; recovery must pass.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import shutil
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'dev.ros2android.android'
FIXTURE = MAIN + '.runner.ws_protocol_workspace'
CHECKPOINT = 'cache/workspace-main-death.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Explicit USB ADB target when needed')
    parser.add_argument('--mode', choices=('sigkill', 'force-stop'), default='sigkill',
                        help='Terminate the active main app with SIGKILL or Android Force stop')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Android/Sdk')))
    adb = [str(sdk / 'platform-tools/adb'), *(['-s', args.serial] if args.serial else ['-d'])]
    env = dict(os.environ)
    if args.serial:
        env['ANDROID_SERIAL'] = args.serial
    output = ROOT / 'device-logs/offline-workspace' / ('force-stop-driver' if args.mode == 'force-stop' else 'main-death-driver')
    output.mkdir(parents=True, exist_ok=True)

    def device(*command, required=True):
        result = subprocess.run([*adb, *command], capture_output=True, text=True, timeout=15)
        if required and result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return result

    def own_screen():
        focus = device('shell', 'dumpsys window | grep mCurrentFocus').stdout
        locked = device('shell', "dumpsys window policy | grep 'showing='").stdout
        assert MAIN + '/' in focus and 'showing=false' in locked, 'Open/unlock ROS2 Android first'

    def phase(name, method, on_started=None):
        command = [str(ROOT / 'setup/build.sh'), ':app:connectedDebugAndroidTest',
                   f'-Pandroid.testInstrumentationRunnerArguments.class={MAIN}.WorkspaceLocalLifecycleTest#{method}',
                   f'-Pandroid.testInstrumentationRunnerArguments.workspaceLocalTestPackage={FIXTURE}',
                   f'-Pandroid.testInstrumentationRunnerArguments.workspaceMainDeathPhase={name}',
                   f'-Pandroid.testInstrumentationRunnerArguments.workspaceMainDeathMode={args.mode}']
        with (output / f'{name}.log').open('w') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                if on_started:
                    on_started(process)
                code = process.wait(timeout=180)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
        return subprocess.CompletedProcess(command, code, (output / f'{name}.log').read_text(), '')

    # Gradle and the outside observer must target the same device even if others attach.
    env['ANDROID_SERIAL'] = device('get-serialno').stdout.strip()
    own_screen()
    existing = device('shell', 'run-as', MAIN, 'cat', CHECKPOINT, required=False)
    assert existing.returncode != 0, 'Resolve the previous checkpoint before starting another trial'
    def force_stop_live_main(process):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and process.poll() is None:
            record = device('shell', 'run-as', MAIN, 'cat', CHECKPOINT, required=False)
            if record.returncode == 0:
                try:
                    checkpoint = json.loads(record.stdout)
                except json.JSONDecodeError:
                    time.sleep(0.25)
                    continue
                assert checkpoint['mode'] == 'force-stop'
                assert device('shell', 'pidof', MAIN).stdout.strip() == str(checkpoint['pid']), 'Main died before Force stop'
                device('shell', 'am', 'force-stop', MAIN)
                return
            time.sleep(0.5)
        raise RuntimeError('Preparation did not reach a live main-app checkpoint; inspect prepare.log')

    print('Preparing isolated ROS session and intentional main-process termination...', flush=True)
    prepared = phase('prepare', 'prepareMainProcessDeath',
                     force_stop_live_main if args.mode == 'force-stop' else None)
    assert prepared.returncode != 0, 'Preparation unexpectedly completed without terminating instrumentation'
    checkpoint = json.loads(device('shell', 'run-as', MAIN, 'cat', CHECKPOINT).stdout)
    assert checkpoint['schema'] == 1 and checkpoint.get('mode', 'sigkill') == args.mode
    assert 'Process crashed' in prepared.stdout + prepared.stderr, 'Preparation failed before the deliberate termination'
    (output / 'checkpoint.json').write_text(json.dumps(checkpoint, indent=2))
    expected = {(p['pid'], p['parent'], p['name']) for p in checkpoint['processes']}
    assert len(expected) == 3 and sum(name == FIXTURE for _, _, name in expected) == 1
    uid = device('shell', 'run-as', FIXTURE, 'id', '-u').stdout.strip()
    assert uid.isdigit()
    observations = []
    print('Observing from PC: main absent, workspace process identities unchanged...', flush=True)
    for sample in range(4):
        assert not device('shell', 'pidof', MAIN, required=False).stdout.strip(), 'Main process restarted during the observation gap'
        rows = device('shell', 'run-as', FIXTURE, 'ps', '-u', uid, '-o', 'PID,PPID,NAME').stdout.splitlines()[1:]
        processes = [line.split(maxsplit=2) for line in rows if line.strip()]
        current = {(int(pid), int(parent), name) for pid, parent, name in processes
                   if name == FIXTURE or name.startswith('libworkspace_exec_')}
        assert current == expected, 'Workspace processes changed while main was absent'
        observations.append({'elapsed': time.monotonic(), 'mainAbsent': True, 'workspaceUnchanged': True})
        if sample < 3:
            time.sleep(5)
    (output / 'observations.json').write_text(json.dumps(observations, indent=2))
    # Explicitly open only the project app; never dismiss or bypass the lock screen.
    device('shell', 'am', 'start', '-W', '-n', MAIN + '/.MainActivity')
    own_screen()
    print('Checking saved settings, fresh ROS data and explicit stop/restart recovery...', flush=True)
    recovered = phase('recover', 'recoverAfterMainProcessDeath')
    assert recovered.returncode == 0, f'Recovery failed; review {output / "recover.log"}'
    reports = list((ROOT / 'android/app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))
    report_output = output / 'recovery-report'
    report_output.mkdir(exist_ok=True)
    for report in reports:
        shutil.copy2(report, report_output / report.name)
    cases = [case for report in reports for case in ET.parse(report).getroot().iter('testcase')]
    assert len(cases) == 1 and cases[0].get('name') == 'recoverAfterMainProcessDeath'
    assert not any(cases[0].find(tag) is not None for tag in ('failure', 'error', 'skipped'))
    assert device('shell', 'run-as', MAIN, 'cat', CHECKPOINT, required=False).returncode != 0, 'Recovery checkpoint was not cleaned up'
    (output / 'result.json').write_text(json.dumps({'intentionalMainDeath': True, 'mode': args.mode,
        'externalObservationSeconds': 15, 'workspaceProcessIdentityPreserved': True,
        'recoveryTestPassed': True, 'uninterruptedRosReceptionMeasured': False}, indent=2))
    device('shell', 'am', 'start', '-W', '-n', MAIN + '/.MainActivity')
    print('PASS: ' + args.mode + ', workspace survival and explicit ROS recovery. Private evidence: ' + str(output))


if __name__ == '__main__':
    main()

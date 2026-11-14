#!/usr/bin/env python3
"""Run the standard ROS2 Android qualification batch on one phone and summarize the results.

Checks preconditions without changing phone settings, installs only this project's debug app
and disposable runner fixtures, then runs: the instrumentation suite (opt-in hardware tests
skip), the headless Dashboard peer test for both runner runtimes and, optionally, an
endurance run with resource monitoring. Results go to device-logs/qualification/<label>/
with a summary.json that records Android version and model but never the serial.
"""

import argparse
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'dev.ros2android.android'
FIXTURES = {'cpp': MAIN + '.runner.fixture_cpp', 'python': MAIN + '.runner.fixture_python'}


def results(xml_dir):
    cases = []

    for path in sorted(Path(xml_dir).glob('*.xml')):
        for case in ET.parse(path).getroot().iter('testcase'):
            status = (
                'fail'
                if case.find('failure') is not None or case.find('error') is not None
                else 'skip'
                if case.find('skipped') is not None
                else 'pass'
            )
            cases.append(
                {
                    'test': f'{case.get("classname").split(".")[-1]}#{case.get("name")}',
                    'status': status,
                    'seconds': float(case.get('time') or 0),
                }
            )

    return cases


def batch_result(name, code, cases):
    counts = {
        status: sum(case['status'] == status for case in cases)
        for status in ('pass', 'fail', 'skip')
    }
    qualified = code == 0 and counts['fail'] == 0 and counts['pass'] > 0

    # Opt-in batches must actually execute every selected check; default-suite skips are expected.
    if name != 'suite':
        qualified = qualified and counts['skip'] == 0

    if name == 'dashboard-peers':
        qualified = qualified and counts['pass'] == 2

    return {'exitCode': code, 'qualified': qualified, 'cases': cases, 'counts': counts}


def run_batch(name, command, xml_dir, out, env):
    # A Gradle/setup failure must never inherit successful XML from the previous batch.
    if xml_dir.exists():
        shutil.rmtree(xml_dir)

    with (out / f'{name}.log').open('w') as log:
        code = subprocess.call(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)

    destination = out / f'{name}-results'
    if destination.exists():
        raise ValueError(f'Results already exist for {name}; choose a new qualification label')

    if xml_dir.exists():
        shutil.copytree(xml_dir, destination)

    return batch_result(name, code, results(destination))


def main(arguments=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        '--serial', required=True, help='ADB serial of the phone (see adb devices)'
    )
    parser.add_argument(
        '--label',
        required=True,
        help='Short result folder name, e.g. phone-b-android14 (no serials)',
    )
    parser.add_argument(
        '--skip-install', action='store_true', help='Reuse already installed current builds'
    )
    parser.add_argument(
        '--endurance-minutes',
        type=int,
        default=0,
        help='Also run EnduranceTest for this long (0 = skip)',
    )
    args = parser.parse_args(arguments)
    if not args.label.replace('-', '').replace('_', '').isalnum():
        parser.error('--label may contain letters, digits, - and _ only')

    if not 0 <= args.endurance_minutes <= 240:
        parser.error('--endurance-minutes must be between 0 and 240')

    out = ROOT / 'device-logs/qualification' / args.label
    if (out / 'summary.json').exists() or any(out.glob('*-results')):
        parser.error('Result label already used; choose a new label to preserve previous evidence')

    out.mkdir(parents=True, exist_ok=True)
    sdk = Path(
        os.environ.get('ANDROID_HOME')
        or os.environ.get('ANDROID_SDK_ROOT')
        or Path.home() / 'Android/Sdk'
    )
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    env = dict(os.environ, ANDROID_SERIAL=args.serial)

    def shell(command):
        return subprocess.run(
            [*adb, 'shell', command], capture_output=True, text=True, timeout=30
        ).stdout.strip()

    facts = {
        'android': shell('getprop ro.build.version.release'),
        'sdk': shell('getprop ro.build.version.sdk'),
        'model': shell('getprop ro.product.model'),
        'abis': shell('getprop ro.product.cpu.abilist'),
    }
    if 'arm64-v8a' not in facts['abis']:
        sys.exit('This phone does not report arm64-v8a; ROS2 Android needs ARM64.')

    problems = []

    if 'showing=false' not in shell("dumpsys window policy | grep -o 'showing=[a-z]*'"):
        problems.append('Unlock the phone and keep the screen on for UI tests.')

    if shell('settings get global wifi_on') != '0':
        problems.append('Turn Wi-Fi off (phone-local runtime checks require it).')

    for package in [MAIN, *FIXTURES.values()]:
        if not shell(f'pm path --user current {package}').startswith('package:'):
            problems.append(f'Install {package} in the active Android user profile first.')
            continue

        # Filter on the phone so other apps' names never leave the device.
        if not shell(f"dumpsys deviceidle whitelist | grep -F ',{package},'"):
            problems.append(
                f'Allow background activity (battery optimization off) for {package} '
                '(install it first if missing; ROS2 Android → Projects → Details → '
                'Android app settings).'
            )

    print(json.dumps({'device': facts, 'preconditionProblems': problems}, indent=2))

    if problems and not args.skip_install:
        print(
            'Installing current builds first; fix the problems above, then rerun with '
            '--skip-install.',
            file=sys.stderr,
        )

    if not args.skip_install:
        for command in (
            ['./setup/build.sh', ':app:assembleDebug'],
            [
                './setup/build.sh',
                ':runner:assembleDebug',
                '-ProbotProjectDir=examples/runner_fixture_cpp',
            ],
            [
                './setup/run-device.sh',
                '--serial',
                args.serial,
                '--runner',
                FIXTURES['cpp'],
                '--install-only',
            ],
            [
                './setup/build.sh',
                ':pythonRunner:assembleDebug',
                '-ProbotProjectDir=examples/runner_fixture_python',
            ],
            [
                './setup/run-device.sh',
                '--serial',
                args.serial,
                '--python-runner',
                FIXTURES['python'],
                '--install-only',
            ],
            ['./setup/run-device.sh', '--serial', args.serial, '--install-only'],
        ):
            subprocess.run(command, cwd=ROOT, check=True, env=env)

    if problems:
        return 2

    if not shell(
        "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | grep -F 'dev.ros2android.android'"
    ):
        print('Open ROS2 Android and leave it unlocked before UI qualification.', file=sys.stderr)
        return 2

    report = {'schemaVersion': 1, 'label': args.label, 'device': facts, 'batches': {}}
    xml_dir = ROOT / 'android/app/build/outputs/androidTest-results/connected/debug'
    batches = [
        ('suite', []),
        (
            'dashboard-peers',
            [
                '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.'
                'android.DashboardPeerRecoveryTest',
                '-Pandroid.testInstrumentationRunnerArguments.dashboardRecoveryPackage=both',
            ],
        ),
    ]
    # Update the running C++ fixture with its own current build while controls are bound.
    update_apk = ROOT / 'android/runner/build/outputs/apk/debug/runner-debug.apk'
    if update_apk.is_file():
        subprocess.run(
            [*adb, 'push', str(update_apk), '/data/local/tmp/ros2android_runner_update.apk'],
            check=True,
            capture_output=True,
        )
        batches.append(
            (
                'runner-update',
                [
                    '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.'
                    'android.RunnerUpdateTest',
                    '-Pandroid.testInstrumentationRunnerArguments.runnerUpdatePackage='
                    + FIXTURES['cpp'],
                    '-Pandroid.testInstrumentationRunnerArguments.runnerUpdateApk=/data/'
                    'local/tmp/ros2android_runner_update.apk',
                ],
            )
        )

    if args.endurance_minutes:
        batches.append(
            (
                'endurance',
                [
                    '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.'
                    'android.EnduranceTest',
                    '-Pandroid.testInstrumentationRunnerArguments.enduranceRunnerPackage='
                    + FIXTURES['cpp'],
                    '-Pandroid.testInstrumentationRunnerArguments.enduranceMinutes='
                    f'{args.endurance_minutes}',
                ],
            )
        )

    try:
        for name, extra in batches:
            command = ['./setup/build.sh', ':app:connectedDebugAndroidTest', *extra]

            if name == 'endurance':
                command = [
                    sys.executable,
                    str(ROOT / 'tests/integration/endurance_monitor.py'),
                    '--serial',
                    args.serial,
                    '--package',
                    MAIN,
                    '--package',
                    FIXTURES['cpp'],
                    '--output',
                    str(out / 'endurance-monitor.jsonl'),
                    '--',
                    *command,
                ]

            report['batches'][name] = run_batch(name, command, xml_dir, out, env)
            (out / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
            print(
                f'{name}: exit {report["batches"][name]["exitCode"]} '
                f'{report["batches"][name]["counts"]}'
            )
    finally:
        subprocess.run(
            [*adb, 'shell', 'rm', '-f', '/data/local/tmp/ros2android_runner_update.apk'],
            capture_output=True,
        )

    print(f'Wrote {out / "summary.json"}')
    return 0 if all(batch['qualified'] for batch in report['batches'].values()) else 1


if __name__ == '__main__':
    sys.exit(main())

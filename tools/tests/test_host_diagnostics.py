import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import __version__
from ros2android.diagnostics import compatibility, diagnose, probe
from ros2android import cli


class HostDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.sdk = self.root / 'Android SDK'
        self.tooling = self.root / 'tooling'
        self.home = self.root / 'home'; self.home.mkdir()
        self.setup = self.root / 'ros/setup.bash'; self.write(self.setup)
        self.colcon = self.home / '.local/share/ros2-android-tools/build-venv/bin/colcon'; self.write(self.colcon, executable=True)
        self.env = {'ANDROID_HOME': str(self.sdk), 'ROBOT_ROS_SETUP': str(self.setup)}
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, self.env, clear=True).start()
        patch('ros2android.diagnostics.Path.home', return_value=self.home).start()
        patch('ros2android.diagnostics.platform.system', return_value='Linux').start()
        patch('ros2android.diagnostics.platform.machine', return_value='x86_64').start()
        patch('ros2android.diagnostics.shutil.which', side_effect=lambda name: '/usr/bin/' + name).start()
        self.probe = patch('ros2android.diagnostics.probe', side_effect=self.version).start()

    def write(self, path, content='', executable=False):
        path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content)
        if executable: path.chmod(0o755)

    def version(self, command):
        if command[-1] == '-version': return 'openjdk version "21.0.6"'
        if command[0] == '/usr/bin/python3': return 'Python 3.12.7'
        return 'cmake version 3.22.1-g37088a8'

    def prepare_android(self):
        for relative in ('cmdline-tools/latest/bin/sdkmanager', 'platform-tools/adb',
                         'build-tools/35.0.0/aapt', 'build-tools/35.0.0/apksigner', 'cmake/3.22.1/bin/cmake'):
            self.write(self.sdk / relative, executable=True)
        self.write(self.sdk / 'platforms/android-35/android.jar')
        self.write(self.sdk / 'ndk/27.2.12479018/source.properties', 'Pkg.Revision = 27.2.12479018\n')
        self.write(self.sdk / 'ndk/27.2.12479018/build/cmake/android.toolchain.cmake')
        self.write(self.sdk / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/clang', executable=True)
        for relative in compatibility()['sourceLocks']:
            self.write(self.tooling / relative, (ROOT / relative).read_text())
        self.write(self.tooling / 'native-deps/install/lib/librcl.so')
        self.write(self.tooling / 'native-deps/install/lib/librclcpp.so')

    def test_host_profile_does_not_need_android_or_java_or_a_phone(self):
        report = diagnose('host')
        self.assertTrue(report['prerequisitesPresent'], report['missingOrIncompatible'])
        self.assertNotIn('java', report['checks'])
        self.assertNotIn('adb', report['checks'])
        self.assertEqual([['/usr/bin/python3', '--version']], [call.args[0] for call in self.probe.call_args_list])

    def test_android_accepts_sdk_tools_off_path_and_android_cmake_suffix(self):
        self.prepare_android()
        report = diagnose('android', self.tooling)
        self.assertTrue(report['prerequisitesPresent'], report['missingOrIncompatible'])
        self.assertEqual('3.22.1', report['checks']['android_cmake']['found']['version'])
        self.assertEqual(str(self.sdk / 'platform-tools/adb'), report['checks']['adb']['found'])

    def test_wrong_java_ndk_and_source_lock_are_not_hidden_by_directory_presence(self):
        self.prepare_android()
        self.probe.side_effect = lambda cmd: 'openjdk version "25.0.1"' if cmd[-1] == '-version' else self.version(cmd)
        self.write(self.sdk / 'ndk/27.2.12479018/source.properties', 'Pkg.Revision = 28.0.0\n')
        self.write(self.tooling / 'native/ros-sources.lock.json', '[]')
        report = diagnose('android', self.tooling)
        for name in ('java', 'android_ndk', 'sdk_source_contract'):
            self.assertIn(name, report['missingOrIncompatible'])
            self.assertTrue(report['checks'][name]['action'])

    def test_shell_and_app_have_distinct_artifact_requirements(self):
        self.prepare_android()
        shell = diagnose('shell', self.tooling)
        self.assertIn('python_ros', shell['missingOrIncompatible'])
        self.assertIn('python_runtime', shell['missingOrIncompatible'])
        self.assertNotIn('micro_ros_agent', shell['checks'])
        self.assertIn('micro_ros_agent', diagnose('app', self.tooling)['missingOrIncompatible'])
        self.assertNotIn('native_cpp', diagnose('app', self.tooling)['checks'])

    def test_colcon_override_is_used_instead_of_an_unrelated_path_tool(self):
        self.colcon.unlink()
        self.assertIn('colcon', diagnose('host')['missingOrIncompatible'])
        alternative = self.root / 'custom/bin/colcon'; self.write(alternative, executable=True)
        with patch.dict(os.environ, {'COLCON_BIN': str(alternative)}):
            self.assertEqual(str(alternative), diagnose('host')['checks']['colcon']['found'])
            self.assertNotIn('colcon', diagnose('host')['missingOrIncompatible'])

    def test_missing_sdk_does_not_create_directories(self):
        report = diagnose('android')
        self.assertFalse(report['prerequisitesPresent'])
        self.assertIsNone(report['toolingRoot'])
        self.assertIn('sdk_source_contract', report['missingOrIncompatible'])
        self.assertFalse(self.sdk.exists())

    def test_sdk_root_fallback_and_java_home_follow_actual_build_selection(self):
        self.prepare_android()
        os.environ.pop('ANDROID_HOME')
        with patch.dict(os.environ, {'ANDROID_HOME': '', 'ANDROID_SDK_ROOT': str(self.sdk), 'JAVA_HOME': '/selected/jdk'}):
            report = diagnose('android', self.tooling)
            self.assertNotIn('adb', report['missingOrIncompatible'])
            self.assertEqual('/selected/jdk/bin/java', report['checks']['java']['found']['path'])

    def test_cli_compatibility_and_doctor_never_extract_or_query_device(self):
        with patch.object(cli, 'sdk_root', side_effect=AssertionError('Must not extract SDK')), \
             patch.object(cli, 'existing_tooling_root', return_value=None):
            for args, code in [(['compatibility'], 0), (['doctor', '--profile', 'host'], 0), (['doctor'], 1)]:
                stream = io.StringIO()
                with patch.object(sys, 'argv', ['ros2-android', *args]), contextlib.redirect_stdout(stream):
                    self.assertEqual(code, cli.main())
                self.assertEqual(1, json.loads(stream.getvalue())['schemaVersion'])

    def test_version_probes_fail_cleanly_on_missing_tools_timeouts_and_nonzero_exit(self):
        for error in (FileNotFoundError(), subprocess.TimeoutExpired(['java'], 5)):
            with patch('ros2android.diagnostics.subprocess.run', side_effect=error):
                self.assertEqual('', probe(['java', '-version']))
        with patch('ros2android.diagnostics.subprocess.run', return_value=subprocess.CompletedProcess(['java'], 1, 'partial', 'error')):
            self.assertEqual('', probe(['java', '-version']))

    def test_matrix_matches_live_source_contracts(self):
        matrix = compatibility()
        self.assertEqual(__version__, matrix['hostClientVersion'])
        for relative, expected in matrix['sourceLocks'].items():
            self.assertEqual(hashlib.sha256((ROOT / relative).read_bytes()).hexdigest(), expected)
        self.assertIn('version = "' + matrix['hostClientVersion'] + '"', (ROOT / 'pyproject.toml').read_text())
        self.assertEqual(hashlib.sha256((ROOT / matrix['ros']['sourceLock']).read_bytes()).hexdigest(), matrix['ros']['sourceLockSha256'])
        self.assertEqual(hashlib.sha256((ROOT / 'native/python-runtime.lock.json').read_bytes()).hexdigest(), matrix['ros']['pythonRuntimeLockSha256'])
        self.assertEqual(json.loads((ROOT / 'native/python-runtime.lock.json').read_text())['python'], matrix['ros']['pythonRuntime'])
        app = (ROOT / 'android/app/build.gradle.kts').read_text()
        for field, value in matrix['app'].items():
            self.assertIn(f'{field} = ' + (f'"{value}"' if isinstance(value, str) else str(value)), app)
        for field, value in [('compileSdk', matrix['android']['compileApi']), ('targetSdk', matrix['android']['targetApi']), ('minSdk', matrix['android']['minApi'])]:
            self.assertIn(f'{field} = {value}', app)
        self.assertIn('ndkVersion = "' + matrix['android']['ndk'] + '"', app)
        self.assertIn('version = "' + matrix['android']['cmake'] + '"', app)
        build = (ROOT / 'build.gradle.kts').read_text()
        self.assertIn('version "' + matrix['build']['androidGradlePlugin'] + '"', build)
        self.assertIn('version "' + matrix['build']['kotlin'] + '"', build)
        self.assertIn('gradle-' + matrix['build']['gradle'] + '-bin.zip', (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text())
        sys.path.insert(0, str(ROOT / 'tools'))
        from project_manifest import RUNTIME_ABIS, SCHEMA_VERSION
        self.assertEqual(SCHEMA_VERSION, matrix['contracts']['manifestSchema'])
        self.assertEqual({name: list(abis) for name, abis in RUNTIME_ABIS.items()}, matrix['contracts']['runtimeAbis'])
        protocol = (ROOT / 'android/protocol/src/main/java/dev/ros2android/protocol/ProjectProtocol.kt').read_text()
        self.assertIn(f"const val VERSION = {matrix['contracts']['managementProtocol']}", protocol)

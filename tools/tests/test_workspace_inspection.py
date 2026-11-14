"""Dependency diagnosis must not confuse Linux packages with Android artifacts."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from workspace_inspection import inventory, inspect_dependencies

ROOT = Path(__file__).resolve().parents[2]


class WorkspaceInspectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ws = self.root / 'External workspace'
        self.target = self.root / 'android'
        self.host = self.root / 'host'

    def package(self, folder, name='app', body='', build_type='ament_cmake'):
        path = self.ws / 'src' / folder / 'package.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            f'<package format="3"><name>{name}</name>{body}'
            f'<export><build_type>{build_type}</build_type></export></package>'
        )
        return path

    def marker(self, prefix, name):
        marker = prefix / 'share/ament_index/resource_index/packages' / name
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text('')

    def report(self):
        return inspect_dependencies(
            inventory(self.ws, allow_unsupported=True), [self.target], [self.host]
        )

    def test_workspace_and_android_evidence_take_precedence_over_linux(self):
        self.package(
            'one',
            body=(
                '<depend>local_msgs</depend><depend>rclcpp</depend><depend>desktop_driver</depend>'
            ),
        )
        self.package('two', name='local_msgs')

        for name in ('local_msgs', 'rclcpp', 'desktop_driver'):
            self.marker(self.host, name)

        self.marker(self.target, 'local_msgs')
        self.marker(self.target, 'rclcpp')
        rows = {r['dependency']: r for r in self.report()['dependencies']}
        self.assertEqual('workspace', rows['local_msgs']['provider'])
        self.assertEqual('android-sdk', rows['rclcpp']['provider'])
        self.assertEqual([str(self.target)], rows['rclcpp']['locations'])
        self.assertEqual('host-only', rows['desktop_driver']['provider'])
        self.assertIn('alone does not provide Android', rows['desktop_driver']['guidance'])

    def test_missing_sdk_and_non_ament_dependencies_are_not_called_incompatible(self):
        self.package('one', body='<depend>opencv</depend>')
        report = self.report()
        self.assertFalse(report['targetPrefixes'][0]['indexPresent'])
        self.assertEqual(1, report['summary']['not-found'])
        self.assertIn('non-ament/system', report['dependencies'][0]['guidance'])
        # A source checkout's share/package.xml alone is not an installed marker.
        path = self.target / 'share/opencv/package.xml'
        path.parent.mkdir(parents=True)
        path.write_text('<package><name>opencv</name></package>')
        self.assertEqual('not-found', self.report()['dependencies'][0]['provider'])

    def test_build_tools_have_distinct_guidance_and_test_doc_deps_are_out_of_scope(self):
        self.package(
            'one',
            body='<buildtool_depend>ament_cmake</buildtool_depend>'
            '<test_depend>gtest</test_depend><doc_depend>doxygen</doc_depend>',
        )
        self.marker(self.host, 'ament_cmake')
        rows = {r['dependency']: r for r in self.report()['dependencies']}
        self.assertEqual('host-only', rows['ament_cmake']['provider'])
        self.assertIn('Build tools run on Linux', rows['ament_cmake']['guidance'])
        self.assertEqual('not-used', rows['gtest']['provider'])
        self.assertEqual('not-used', rows['doxygen']['provider'])

    def test_conditions_versions_and_groups_preserve_declared_metadata(self):
        self.package(
            'one',
            body='<depend condition="$ROS_VERSION == 2" version_gte="9.0">driver</depend>'
            '<group_depend>interface_group</group_depend>',
        )
        self.marker(self.target, 'interface_group')  # Not evidence of group members.
        report = self.report()
        rows = {r['dependency']: r for r in report['dependencies']}
        self.assertEqual('$ROS_VERSION == 2', rows['driver']['condition'])
        self.assertEqual({'version_gte': '9.0'}, rows['driver']['versionConstraints'])
        self.assertEqual('group-unresolved', rows['interface_group']['provider'])
        self.assertEqual(1, report['summary']['conditional'])
        self.assertEqual(1, report['summary']['versionConstrained'])

    def test_unknown_condition_context_and_absent_optional_parser_stay_unresolved(self):
        self.package(
            'one',
            body='<depend condition="$BOARD == mobile">driver</depend>'
            '<depend condition="$ROS_VERSION == 2">other</depend>',
        )

        with patch.dict(sys.modules, {'catkin_pkg.condition': None}):
            rows = {r['dependency']: r for r in self.report()['dependencies']}

        self.assertEqual('unresolved', rows['driver']['conditionEvaluation']['state'])
        self.assertIn('BOARD', rows['driver']['conditionEvaluation']['reason'])
        self.assertIn('inspection extra', rows['other']['conditionEvaluation']['reason'])
        self.assertEqual('not-found', rows['driver']['provider'])

    def test_upstream_conditions_use_target_context_instead_of_host_environment(self):
        try:
            import catkin_pkg.condition
        except ImportError:
            self.skipTest('Optional upstream condition evaluator is not installed')

        self.package(
            'one',
            body='<depend condition="$ROS_VERSION == 1">roscpp</depend>'
            '<depend condition="$ROS_DISTRO == jazzy and $ROS_PYTHON_VERSION == 3">rclcpp</depend>'
            '<depend condition="__import__(bad)">invalid</depend>',
        )

        with patch.dict(os.environ, ROS_VERSION='1', ROS_DISTRO='humble'):
            report = self.report()

        rows = {r['dependency']: r for r in report['dependencies']}
        self.assertEqual('not-used', rows['roscpp']['provider'])
        self.assertEqual('active', rows['rclcpp']['conditionEvaluation']['state'])
        self.assertEqual('unresolved', rows['invalid']['conditionEvaluation']['state'])
        self.assertEqual(1, report['summary']['inactive'])

    def test_version_checks_compare_numeric_components_and_all_bounds(self):
        self.package(
            'one',
            body='<depend version_gte="1.2.0" version_lt="2.0.0">driver</depend>'
            '<depend version_eq="1.2.0">local</depend>',
        )
        self.package('two', name='local', body='<version>1.10.0</version>')
        self.marker(self.target, 'driver')
        manifest = self.target / 'share/driver/package.xml'
        manifest.parent.mkdir(parents=True)
        manifest.write_text('<package><name>driver</name><version>1.10.0</version></package>')
        report = self.report()
        rows = {r['dependency']: r for r in report['dependencies']}
        self.assertEqual('satisfied', rows['driver']['versionCheck']['state'])
        self.assertEqual('1.10.0', rows['driver']['versionCheck']['providerVersion'])
        self.assertEqual('unsatisfied', rows['local']['versionCheck']['state'])
        self.assertEqual(1, report['summary']['versionUnsatisfied'])

    def test_version_checks_do_not_guess_from_missing_wrong_or_nonstandard_metadata(self):
        self.package('one', body='<depend version_eq="1.0.0">driver</depend>')
        self.marker(self.target, 'driver')
        self.assertEqual('unresolved', self.report()['dependencies'][0]['versionCheck']['state'])
        manifest = self.target / 'share/driver/package.xml'
        manifest.parent.mkdir(parents=True)

        for name, version in [('other', '1.0.0'), ('driver', 'rolling')]:
            manifest.write_text(
                f'<package><name>{name}</name><version>{version}</version></package>'
            )
            self.assertEqual(
                'unresolved', self.report()['dependencies'][0]['versionCheck']['state']
            )

    def test_inspect_reports_unsupported_package_without_admitting_it_to_build(self):
        self.package('python', build_type='ament_python')
        self.assertEqual(
            [{'name': 'app', 'buildType': 'ament_python'}], self.report()['unsupportedPackages']
        )

        with self.assertRaisesRegex(ValueError, 'unsupported: app: ament_python'):
            inventory(self.ws)

    def test_manifest_errors_identify_the_file_and_field(self):
        path = self.package('bad')

        for text, error in [
            ('<package>', 'invalid package XML'),
            ('<package><name/></package>', 'valid name'),
            ('<package><name>app</name><depend>../bad</depend></package>', 'invalid depend'),
        ]:
            path.write_text(text)

            with self.assertRaisesRegex(ValueError, error) as caught:
                self.report()

            self.assertIn(str(path), str(caught.exception))

    def test_generated_and_ignored_packages_are_excluded(self):
        self.package('active')
        self.package('build/generated', name='generated')
        ignored = self.package('vendor/ignored', name='ignored')
        (ignored.parent.parent / 'COLCON_IGNORE').write_text('')
        self.assertEqual(['app'], [p['name'] for p in inventory(self.ws)])

    def test_selected_target_prefixes_do_not_leak_from_the_other_profile(self):
        self.package('one', body='<exec_depend>launch_ros</exec_depend>')
        python_sdk = self.root / 'python-sdk'
        self.marker(python_sdk, 'launch_ros')
        packages = inventory(self.ws)
        minimal = inspect_dependencies(packages, [self.target], [])
        shell = inspect_dependencies(packages, [python_sdk, self.target], [])
        self.assertEqual('not-found', minimal['dependencies'][0]['provider'])
        self.assertEqual('android-sdk', shell['dependencies'][0]['provider'])

    def test_cli_is_read_only_and_rejects_unknown_arguments(self):
        self.package('one', body='<depend>external_lib</depend>')
        (self.ws / 'src/one/CMakeLists.txt').write_text('message(FATAL_ERROR "must not execute")')
        home = self.root / 'home'
        home.mkdir()
        command = [
            sys.executable,
            str(ROOT / 'tools/ros2android-workspace'),
            '--workspace',
            str(self.ws),
            'inspect',
        ]
        env = dict(
            os.environ,
            HOME=str(home),
            ANDROID_HOME='/nonexistent',
            ROBOT_ROS_SETUP='/nonexistent/setup.bash',
        )
        result = subprocess.run(
            command + ['--without-shell'], env=env, capture_output=True, text=True
        )
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual('not-found', report['inspection']['dependencies'][0]['provider'])
        self.assertEqual([], list(home.iterdir()))
        self.assertEqual(['src'], [p.name for p in self.ws.iterdir()])
        result = subprocess.run(command + ['--typo'], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Unknown arguments', result.stderr)
        self.assertEqual([], list(home.iterdir()))

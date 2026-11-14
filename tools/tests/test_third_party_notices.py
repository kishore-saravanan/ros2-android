import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
NOTICES = (ROOT / 'THIRD-PARTY-NOTICES.md').read_text()


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ThirdPartyNoticesTest(unittest.TestCase):
    """The generated inventory must follow every lock change; regenerate it with a prepared SDK."""

    def test_every_locked_native_source_is_listed_at_its_revision(self):
        for repository in json.loads((ROOT / 'native/ros-sources.lock.json').read_text()):
            with self.subTest(source=repository['directory']):
                self.assertRegex(
                    NOTICES,
                    re.escape(f'[{repository["directory"]}]')
                    + r'\(.*\) \| `'
                    + repository['revision'][:12]
                    + '`',
                )

    def test_runtime_toolchain_agent_and_shell_versions_match_their_pins(self):
        python = json.loads((ROOT / 'native/python-runtime.lock.json').read_text())
        for value in (
            python['chaquopyPlugin'],
            python['python'],
            python['numpy'],
            python['pyyaml'],
        ):
            self.assertIn(f'| {value} |', NOTICES)

        ndk = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())['android'][
            'ndk'
        ]
        self.assertIn(f'NDK {ndk}', NOTICES)
        agent = re.search(
            r'--branch (v[\d.]+) https://github.com/eProsima',
            (ROOT / 'setup/build-micro-ros-agent.sh').read_text(),
        )[1]
        self.assertIn(f'`{agent}`', NOTICES)
        kotlin = re.search(
            r'kotlin\.android"\) version "([^"]+)"', (ROOT / 'build.gradle.kts').read_text()
        )[1]
        self.assertIn(f'| Kotlin standard library | {kotlin} |', NOTICES)
        shell = json.loads((ROOT / 'native/ros-shell-packages.lock.json').read_text())
        for name, version in shell['rosPackages'].items():
            self.assertIn(f'{name} {version}', NOTICES)

        for module in shell['purePythonDependencies']:
            self.assertIn(module, NOTICES)


class LicenseStagingTest(unittest.TestCase):
    def setUp(self):
        self.stage = load('stage_licenses', 'setup/stage-licenses.py')
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def test_top_level_files_win_and_per_package_files_are_the_fallback(self):
        top = self.root / 'top'
        (top / 'pkg').mkdir(parents=True)
        (top / 'LICENSE').write_text('a')
        (top / 'NOTICE.md').write_text('n')
        (top / 'pkg/LICENSE').write_text('b')
        self.assertEqual(['LICENSE', 'NOTICE.md'], [p.name for p in self.stage.license_files(top)])
        nested = self.root / 'nested'
        (nested / 'one').mkdir(parents=True)
        (nested / 'two').mkdir()
        (nested / 'one/LICENSE').write_text('x')
        (nested / 'two/COPYING').write_text('y')
        self.assertEqual(
            {'one/LICENSE', 'two/COPYING'},
            {str(p.relative_to(nested)) for p in self.stage.license_files(nested)},
        )
        self.assertEqual([], self.stage.license_files(self.root / 'nothing-here'))

    def test_llvm_block_is_extracted_without_neighbouring_licenses(self):
        separator = '=' * 78
        notice = self.root / 'NOTICE.toolchain'
        notice.write_text(
            '\n'.join(
                [
                    'Other GPL text',
                    '',
                    separator,
                    self.stage.LLVM_HEADER,
                    separator,
                    'Apache body',
                    '---- LLVM Exceptions to the Apache 2.0 License ----',
                    'exception text',
                    separator,
                    'Legacy LLVM License',
                    'NCSA body',
                    '',
                    separator,
                    self.stage.LLVM_HEADER,
                    separator,
                    'repeat',
                ]
            )
        )
        block = self.stage.llvm_license(notice)
        self.assertTrue(block.startswith(separator + '\n' + self.stage.LLVM_HEADER))
        self.assertIn('NCSA body', block)
        self.assertNotIn('Other GPL text', block)
        self.assertNotIn('repeat', block)
        notice.write_text('no llvm here')

        with self.assertRaises(RuntimeError):
            self.stage.llvm_license(notice)


if __name__ == '__main__':
    unittest.main()

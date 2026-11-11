import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
source = Path(__file__).resolve().parents[1] / 'ros2android-workspace'
loader = importlib.machinery.SourceFileLoader('workspace_tool', str(source))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)


class ColconWorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'src').mkdir()

    def package(self, folder, name='demo', build_type='ament_cmake'):
        directory = self.root / 'src' / folder
        directory.mkdir(parents=True)
        (directory / 'package.xml').write_text(f'<package><name>{name}</name><export><build_type>{build_type}</build_type></export></package>')
        return directory

    def test_discovers_normal_package_without_android_manifest(self):
        self.package('code')
        result = module.inventory(self.root)
        self.assertEqual([(p['name'], p['buildType']) for p in result], [('demo', 'ament_cmake')])
        self.assertFalse((self.root / 'robot-project.json').exists())

    def test_respects_colcon_ignore_in_repository_subtree(self):
        self.package('active')
        ignored = self.package('vendor/other', name='ignored')
        (ignored.parent / 'COLCON_IGNORE').write_text('')
        self.assertEqual([p['name'] for p in module.inventory(self.root)], ['demo'])

    def test_duplicate_package_names_are_errors(self):
        self.package('one'); self.package('two')
        with self.assertRaisesRegex(ValueError, 'Duplicate package names'):
            module.inventory(self.root)

    def test_python_build_type_is_explicitly_pending(self):
        self.package('python', build_type='ament_python')
        with self.assertRaisesRegex(ValueError, 'unsupported: demo: ament_python'):
            module.inventory(self.root)

    def test_fingerprint_detects_source_edits_but_ignores_build_artifacts(self):
        directory = self.package('code')
        before = module.digest_tree(self.root / 'src')
        output = directory / 'build'; output.mkdir(); (output / 'generated.cpp').write_text('temporary')
        self.assertEqual(before, module.digest_tree(self.root / 'src'))
        (directory / 'main.cpp').write_text('int main() { return 0; }')
        self.assertNotEqual(before, module.digest_tree(self.root / 'src'))

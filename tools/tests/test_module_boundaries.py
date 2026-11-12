"""Keep installed host modules independent of checkout-only compatibility imports."""
import ast
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'python/ros2android'


class HostModuleBoundaryTest(unittest.TestCase):
    def test_package_imports_do_not_require_checkout_wrappers(self):
        local_modules = {p.stem for p in PACKAGE.glob('*.py')}
        for path in PACKAGE.glob('*.py'):
            with self.subTest(module=path.name):
                tree = ast.parse(path.read_text(), filename=str(path))
                for node in ast.walk(tree):
                    if isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
                        self.assertNotIn(node.module.split('.')[0], local_modules,
                                         'Use relative imports inside the installed package')
                        self.assertNotEqual('tools', node.module.split('.')[0])
                    if isinstance(node, ast.Import):
                        for alias in node.names:
                            self.assertNotIn(alias.name.split('.')[0], local_modules | {'tools'})
                    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                        self.assertFalse(isinstance(node.func.value, ast.Attribute) and
                                         isinstance(node.func.value.value, ast.Name) and
                                         node.func.value.value.id == 'sys' and
                                         node.func.value.attr == 'path' and
                                         node.func.attr in ('insert', 'append', 'extend'),
                                         'Installed modules must not modify import paths')

    def test_migrated_compatibility_wrappers_do_not_grow_implementations(self):
        for name in ('project_manifest.py', 'workspace_inspection.py', 'ros2android-workspace', 'ros2android', 'robot_launch.py', 'controller_identity.py', 'connect-shell', 'esp32-via-phone', '../setup/fetch-native-ros.py', '../setup/prepare-build-tools.py'):
            path = ROOT / 'tools' / name
            with self.subTest(wrapper=name):
                tree = ast.parse(path.read_text(), filename=str(path))
                self.assertTrue(any(isinstance(n, ast.ImportFrom) and
                                    (n.module or '').startswith('ros2android.') for n in tree.body))
                functions = [n for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.ClassDef))]
                self.assertTrue(all(isinstance(n, ast.FunctionDef) and n.name == 'main' and
                                    len(n.body) == 1 and isinstance(n.body[0], ast.Return)
                                    for n in functions), 'Keep behavior in the package')

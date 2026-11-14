import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import native_sources


class NativeSourcesTest(unittest.TestCase):
    def test_wrong_existing_revision_is_left_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'native').mkdir()
            (root / 'native-deps/src/fixture').mkdir(parents=True)
            (root / 'native/ros-sources.lock.json').write_text(
                json.dumps(
                    [
                        dict(
                            directory='fixture',
                            url='https://example.invalid/fixture.git',
                            revision='a' * 40,
                        )
                    ]
                )
            )

            with (
                patch.object(
                    native_sources.subprocess, 'check_output', return_value='b' * 40
                ) as head,
                patch.object(native_sources.subprocess, 'run') as mutate,
                contextlib.redirect_stderr(io.StringIO()) as errors,
            ):
                self.assertEqual(1, native_sources.run([], tooling_root=root))

            self.assertIn('leaving it untouched', errors.getvalue())
            self.assertEqual(1, head.call_count)
            mutate.assert_not_called()

    def test_unknown_options_fail_before_cache_or_network_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with (
                patch.dict(os.environ, HOME=str(root)),
                patch.object(native_sources.subprocess, 'run') as mutate,
                contextlib.redirect_stderr(io.StringIO()),
                self.assertRaises(SystemExit) as result,
            ):
                native_sources.run(['--typo'], tooling_root=root)

            self.assertEqual(2, result.exception.code)
            mutate.assert_not_called()
            self.assertEqual([], list(root.iterdir()))

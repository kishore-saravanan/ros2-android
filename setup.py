"""Refuse incomplete distributions that omit the generated backend snapshot."""
from pathlib import Path
from setuptools import setup
from setuptools.command.build_py import build_py


class BundledBuild(build_py):
    def run(self):
        if not Path('python/ros2android/sdk.zip').is_file():
            raise RuntimeError('Build with python setup/build-python-package.py to include the SDK snapshot')
        super().run()


setup(cmdclass={'build_py': BundledBuild})

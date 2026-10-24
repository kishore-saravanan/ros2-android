#!/usr/bin/env python3
"""Fetch pinned upstream sources and apply reviewed Android build patches."""
import concurrent.futures
import json
from pathlib import Path
import subprocess

project = Path(__file__).resolve().parents[1]
link = project / 'native-deps'
if not link.exists():
    workspace = Path.home() / '.cache' / 'ros2-android-native'
    workspace.mkdir(parents=True, exist_ok=True)
    link.symlink_to(workspace, target_is_directory=True)
workspace = link.resolve()
if ' ' in str(workspace):
    raise SystemExit('Use an external native-deps workspace without spaces; upstream Cyclone DDS generators require it.')
sources = workspace / 'src'
sources.mkdir(exist_ok=True)
repositories = json.loads((project / 'native/ros-sources.lock.json').read_text())


def fetch(repo):
    target = sources / repo['directory']
    if not target.exists():
        subprocess.run(['git', 'init', '--quiet', str(target)], check=True)
        subprocess.run(['git', '-C', str(target), 'remote', 'add', 'origin', repo['url']], check=True)
        subprocess.run(['git', '-C', str(target), 'fetch', '--quiet', '--depth', '1', 'origin', repo['revision']], check=True)
        subprocess.run(['git', '-C', str(target), 'checkout', '--quiet', '--detach', 'FETCH_HEAD'], check=True)
    head = subprocess.check_output(['git', '-C', str(target), 'rev-parse', 'HEAD'], text=True).strip()
    if head != repo['revision']:
        raise RuntimeError(f'{target.name} has a different revision; leaving it untouched')
    patch = project / 'native' / 'patches' / (target.name + '.patch')
    if patch.exists():
        command = ['git', '-C', str(target), 'apply']
        if subprocess.run(command + ['--reverse', '--check', str(patch)], capture_output=True).returncode == 0:
            return
        subprocess.run(command + ['--check', str(patch)], check=True)
        subprocess.run(command + [str(patch)], check=True)


with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    list(pool.map(fetch, repositories))
print(f'{len(repositories)} pinned repositories ready in {workspace}')

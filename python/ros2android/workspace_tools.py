"""Prepare everything needed to build and deploy workspaces, in one command after the .deb.

Checks the Ubuntu packages and ROS 2 Jazzy, installs Google's Android command-line tools and the
pinned Android SDK packages (you accept Google's licences yourself), this release's native SDK and
the managed build tools, then runs the Android health check. Steps already done are skipped.
"""

import argparse
from importlib.resources import files
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

from . import build_tools, native_sdk
from .diagnostics import compatibility, diagnose

# Ubuntu 24.04 packages for workspace builds; the Debian package recommends the same list.
APT_PACKAGES = (
    'build-essential',
    'cmake',
    'ninja-build',
    'git',
    'python3-dev',
    'python3-venv',
    'python3-numpy',
    'pybind11-dev',
    'openjdk-21-jdk-headless',
    'ros-jazzy-rmw-cyclonedds-cpp',
)
# Google's Android command-line tools 22.0. Google publishes the SHA-1; the SHA-256 below was
# measured from that same file.
CMDLINE_TOOLS = {
    'url': 'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip',
    'sha256': '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583',
}
RELEASES = 'https://github.com/kishore-saravanan/ros2-android/releases/download'


def release_info():
    return json.loads(files('ros2android').joinpath('release.json').read_text())


def android_root():
    return Path(
        os.environ.get('ANDROID_HOME')
        or os.environ.get('ANDROID_SDK_ROOT')
        or Path.home() / 'Android/Sdk'
    ).expanduser()


def downloads():
    return (
        Path(os.environ.get('XDG_CACHE_HOME', Path.home() / '.cache'))
        / 'ros2-android'
        / 'downloads'
    )


def missing_apt(packages=APT_PACKAGES):
    missing = []

    for package in packages:
        result = subprocess.run(
            ['dpkg-query', '-W', '-f=${Status}', package], capture_output=True, text=True
        )
        if result.returncode or 'install ok installed' not in result.stdout:
            missing.append(package)

    return missing


def download(url, target, sha256, label):
    """Download url to target, reusing an already verified copy, and check its SHA-256."""
    target.parent.mkdir(parents=True, exist_ok=True)

    if target.is_file() and native_sdk.sha256_file(target) == sha256:
        print(f'  {label}: using the verified download in {target.parent}', flush=True)
        return target

    partial = target.with_name(target.name + '.partial')
    print(f'  {label}: downloading {url}', flush=True)

    try:
        with urllib.request.urlopen(url, timeout=60) as response, open(partial, 'wb') as out:
            total, done, shown = int(response.headers.get('Content-Length') or 0), 0, 0

            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break

                out.write(chunk)
                done += len(chunk)
                if total and done * 100 // total >= shown + 20:
                    shown = done * 100 // total
                    print(f'    {shown}%', flush=True)

        actual = native_sdk.sha256_file(partial)
        if actual != sha256:
            raise ValueError(
                f'{label}: checksum mismatch (expected {sha256}, got {actual}). Run the '
                'command again.'
            )

        os.replace(partial, target)
    finally:
        partial.unlink(missing_ok=True)

    return target


def extract_cmdline_tools(archive, root):
    """Unpack Google's zip as root/cmdline-tools/latest, keeping executable bits."""
    root.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.cmdline-tools-', dir=root))
    try:
        with zipfile.ZipFile(archive) as bundle:
            for info in bundle.infolist():
                name = PurePosixPath(info.filename)
                if name.is_absolute() or '..' in name.parts or name.parts[0] != 'cmdline-tools':
                    raise ValueError(
                        'Unexpected entry in the command-line tools archive: ' + info.filename
                    )

                bundle.extract(info, stage)
                mode = (info.external_attr >> 16) & 0o777
                if mode and not info.is_dir():
                    os.chmod(stage / info.filename, mode)

        latest = root / 'cmdline-tools' / 'latest'
        latest.parent.mkdir(parents=True, exist_ok=True)
        os.replace(stage / 'cmdline-tools', latest)
    finally:
        shutil.rmtree(stage, ignore_errors=True)

    return latest / 'bin' / 'sdkmanager'


def android_packages(matrix):
    """sdkmanager package name -> directory that proves it is installed."""
    android = matrix['android']
    return {
        'platform-tools': 'platform-tools',
        f'platforms;android-{android["compileApi"]}': f'platforms/android-{android["compileApi"]}',
        f'build-tools;{android["buildTools"]}': f'build-tools/{android["buildTools"]}',
        f'ndk;{android["ndk"]}': f'ndk/{android["ndk"]}',
        f'cmake;{android["cmake"]}': f'cmake/{android["cmake"]}',
    }


def ensure_android(matrix, accept_licenses=False):
    root = android_root()
    if ' ' in str(root):
        raise ValueError(
            f'The Android SDK path must not contain spaces: {root}. Set ANDROID_HOME to '
            'another folder.'
        )

    packages = android_packages(matrix)
    missing = [
        name
        for name, folder in packages.items()
        if not (root / folder / 'source.properties').is_file()
    ]
    sdkmanager = root / 'cmdline-tools' / 'latest' / 'bin' / 'sdkmanager'
    if not missing and sdkmanager.is_file():
        print(f'  Android SDK packages: already installed in {root}', flush=True)
        return

    if (
        not sdkmanager.is_file()
    ):  # Also when other tools (for example Android Studio) installed the packages.
        archive = download(
            CMDLINE_TOOLS['url'],
            downloads() / Path(CMDLINE_TOOLS['url']).name,
            CMDLINE_TOOLS['sha256'],
            'Android command-line tools',
        )
        sdkmanager = extract_cmdline_tools(archive, root)

    if not missing:
        print(f'  Android SDK packages: already installed in {root}', flush=True)
        return

    base = [str(sdkmanager), f'--sdk_root={root}']
    print("  Review and accept Google's Android SDK licences (answer y):", flush=True)

    if accept_licenses:
        subprocess.run(
            [*base, '--licenses'],
            input='y\n' * 30,
            text=True,
            check=True,
            stdout=subprocess.DEVNULL,
        )
    elif sys.stdin.isatty():
        subprocess.run([*base, '--licenses'], check=True)
    else:
        raise ValueError(
            "Accepting Google's licences needs an interactive terminal; run this command in one."
        )

    print('  Installing ' + ', '.join(missing) + ' (about 1.5 GB)…', flush=True)
    subprocess.run([*base, *missing], check=True)
    still = [
        name for name in missing if not (root / packages[name] / 'source.properties').is_file()
    ]
    if still:
        raise ValueError(
            'Android SDK packages still missing: '
            + ', '.join(still)
            + '. Check that you accepted the licences.'
        )


def ensure_native_sdk(tooling_root, matrix, archive=None):
    tooling_root = Path(tooling_root)
    link = tooling_root / 'native-deps'
    target = (
        link
        if link.is_symlink() or link.is_dir()
        else Path.home() / '.cache' / 'ros2-android-native'
    ).resolve()

    def keep(message):
        # A client update extracts new tooling without the link; recreate it for the kept SDK.
        if not link.exists() and not link.is_symlink():
            link.symlink_to(target, target_is_directory=True)

        print('  Native SDK: ' + message, flush=True)

    record = target / '.sdk-install.json'
    complete = all((target / path).exists() for path in native_sdk.REQUIRED)
    present = any(
        (target / entry).exists() or (target / entry).is_symlink() for entry in native_sdk.CONTENT
    )

    if complete and not record.is_file():
        return keep(f'using your existing build in {target}')

    wanted = native_sdk.inputs_digest(tooling_root)
    if complete and json.loads(record.read_text()).get('inputsDigest') == wanted:
        return keep('already installed')

    if present:
        print(
            '  Native SDK: replacing the outputs in '
            + str(target)
            + ' (sources and build trees are kept)',
            flush=True,
        )

    version = matrix['hostClientVersion']
    name = f'ros2-android-native-sdk-{version}-{native_sdk.ABI}.tar.xz'
    expected = release_info().get('nativeSdk', {})
    sha256 = expected.get('sha256') if expected.get('version') == version else None

    if archive is None:
        if not sha256:
            raise ValueError(
                'This build of ros2-android has no published native SDK; pass --sdk-archive FILE.'
            )

        archive = download(
            f'{RELEASES}/v{version}/{name}', downloads() / name, sha256, 'Native SDK'
        )

    native_sdk.install(
        archive,
        tooling_root=tooling_root,
        target=target,
        replace=present,
        expected_sha256=sha256 if Path(archive).name == name else None,
    )


def run(arguments=None, *, tooling_root):
    parser = argparse.ArgumentParser(
        prog='ros2-android setup workspace-tools',
        description=__doc__.split('\n\n')[0],
        epilog='Then: ros2-android workspace --workspace PATH build, then deploy.',
    )
    parser.add_argument(
        '--sdk-archive',
        type=Path,
        help='Use a downloaded native SDK archive instead of downloading it',
    )
    parser.add_argument(
        '--accept-android-licenses',
        action='store_true',
        help=(
            "Accept Google's Android SDK licences without prompting (only if you have read "
            'and accept them)'
        ),
    )
    selected = parser.parse_args(arguments)
    matrix = compatibility()
    try:
        print('[1/5] Ubuntu packages and ROS 2 Jazzy', flush=True)
        missing = missing_apt()
        if missing:
            print(
                'Install these Ubuntu packages, then run this command again:\n  sudo apt install '
                + ' '.join(missing)
            )
            return 1

        if not Path('/opt/ros/jazzy/setup.bash').is_file():
            print(
                'Install ROS 2 Jazzy first: '
                'https://docs.ros.org/en/jazzy/Installation/Ubuntu-Install-Debs.html'
            )
            return 1

        print('[2/5] Android SDK', flush=True)
        ensure_android(matrix, selected.accept_android_licenses)
        print('[3/5] Native SDK', flush=True)
        ensure_native_sdk(tooling_root, matrix, selected.sdk_archive)
        print('[4/5] Build tools', flush=True)
        build_tools.prepare(build_tools.default_venv())
        print('[5/5] Health check', flush=True)
        report = diagnose('android', tooling_root=tooling_root)
    except KeyboardInterrupt:
        return 130
    except (
        OSError,
        ValueError,
        RuntimeError,
        subprocess.CalledProcessError,
        zipfile.BadZipFile,
    ) as error:
        print('setup workspace-tools: ' + str(error), file=sys.stderr)
        return 1

    failed = {name: row for name, row in report['checks'].items() if row['status'] != 'ok'}
    for name, row in failed.items():
        print(f'  {name}: {row["action"]}')

    if failed:
        return 1

    print(
        'Ready. Build and deploy a workspace:\n'
        '  ros2-android workspace --workspace PATH build --controller-apk '
        'ros2-android-VERSION.apk\n'
        '  ros2-android workspace --workspace PATH deploy'
    )
    return 0

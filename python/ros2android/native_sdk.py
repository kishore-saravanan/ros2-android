"""Install a prebuilt native SDK archive instead of building the SDK from source.

The archive (made by setup/package-native-sdk.py) holds the outputs of `setup native --with-cpp`,
`setup python` and `setup agent`, their license texts and sdk-manifest.json. Installing checks the
archive checksum and that it was built from the same pinned inputs as this tooling, extracts it
safely, rewrites the recorded build prefix in text files and links the tooling's native-deps to it.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import tempfile

FORMAT = 1
ABI = 'arm64-v8a'
MANIFEST = 'sdk-manifest.json'
# Built outputs consumed by doctor, app/runner builds and workspace builds.
CONTENT = ('install', 'apk-libs', 'agent-apk-libs', 'agent-assets', 'python-install', 'python-packages',
           'python-target', 'python-apk-libs', 'python-assets', 'ros-assets',
           'native-library-checksums.json', 'python-library-checksums.json', 'micro-ros-agent-checksums.json')
NOTICES = ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md')
REQUIRED = ('install/lib/librcl.so', 'install/lib/librclcpp.so', 'apk-libs/arm64-v8a/librcl.so',
            'agent-apk-libs/arm64-v8a/libmicro_ros_agent_exec.so', 'python-packages/rclpy/_rclpy_pybind11.so',
            'python-target/jniLibs/arm64-v8a/libpython3.12.so', 'ros-assets/licenses')
# Files that determine the SDK's content; an archive only fits tooling built from the same inputs.
INPUTS = ('native/ros-sources.lock.json', 'native/python-runtime.lock.json', 'native/ros-shell-packages.lock.json',
          'native/patches', 'examples/python_talker/interfaces',
          'setup/build-native-ros.sh', 'setup/build-python-ros.sh', 'setup/build-micro-ros-agent.sh',
          'setup/stage-native-libs.py', 'setup/stage-python-ros.py', 'setup/stage-micro-ros-agent.py',
          'setup/stage-licenses.py', 'setup/prepare-python-target.py')


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def inputs_digest(root):
    """SHA-256 over the relative paths and bytes of every SDK input file under root."""
    root = Path(root)
    files = []
    for entry in INPUTS:
        path = root / entry
        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files += [item for item in path.rglob('*') if item.is_file()
                      and '__pycache__' not in item.parts and item.suffix != '.pyc']
        else:
            raise ValueError(f'SDK input missing from tooling: {entry}')
    digest = hashlib.sha256()
    for path in sorted(files, key=lambda item: item.relative_to(root).as_posix()):
        digest.update(path.relative_to(root).as_posix().encode() + b'\0')
        digest.update(path.read_bytes() + b'\0')
    return digest.hexdigest()


def read_manifest(archive):
    """The archive's sdk-manifest.json, which the packager writes as the first member."""
    with tarfile.open(archive, 'r|*') as tar:
        member = tar.next()
        if member is None or member.name != MANIFEST or not member.isfile():
            raise ValueError(f'{Path(archive).name} is not a ros2-android native SDK archive')
        return json.loads(tar.extractfile(member).read())


def _expected_checksum(archive, given):
    if given:
        return given.strip().lower()
    published = archive.with_name(archive.name + '.sha256')
    if published.is_file():
        return published.read_text().split()[0].lower()
    release_sums = archive.with_name('SHA256SUMS')  # A downloaded release folder lists every file.
    if release_sums.is_file():
        for line in release_sums.read_text().splitlines():
            digest, _, name = line.partition('  ')
            if name == archive.name:
                return digest.lower()
    raise ValueError(f'Pass --sha256, or keep {published.name} or the release SHA256SUMS next to the archive')


def _check_member(member, seen_top):
    path = PurePosixPath(member.name)
    if path.is_absolute() or not path.parts or '..' in path.parts:
        raise ValueError(f'Unsafe archive path: {member.name}')
    top = path.parts[0]
    if top not in CONTENT and top not in NOTICES and top != MANIFEST:
        raise ValueError(f'Unexpected archive entry: {member.name}')
    if member.issym():
        target = PurePosixPath(member.linkname)
        resolved = os.path.normpath((path.parent / target).as_posix())
        if target.is_absolute() or resolved == '..' or resolved.startswith('../'):
            raise ValueError(f'Archive link leaves the SDK: {member.name} -> {member.linkname}')
    elif not (member.isfile() or member.isdir()):
        raise ValueError(f'Unsupported archive entry type: {member.name}')
    seen_top.add(top)


def _extract(archive, staging):
    seen = set()
    with tarfile.open(archive, 'r|*') as tar:
        for member in tar:
            _check_member(member, seen)
            member.mode &= 0o755 if member.isdir() or member.mode & 0o111 else 0o644
            if hasattr(tarfile, 'data_filter'):
                tar.extract(member, staging, filter='data')
            else:  # Python without extraction filters; members were validated above.
                tar.extract(member, staging)
    return seen


def install(archive, *, tooling_root, expected_sha256=None, replace=False, target=None):
    archive = Path(archive).resolve()
    tooling_root = Path(tooling_root)
    if not archive.is_file():
        raise ValueError(f'No such archive: {archive}')
    link = tooling_root / 'native-deps'
    if target is None:
        target = link.resolve() if link.is_symlink() or link.is_dir() else Path.home() / '.cache' / 'ros2-android-native'
    target = Path(target).absolute()
    if ' ' in str(target):
        raise ValueError('Use a native-deps location without spaces; upstream Cyclone DDS generators require it.')
    present = [entry for entry in CONTENT if (target / entry).exists() or (target / entry).is_symlink()]
    if present and not replace:
        raise ValueError(f'An SDK is already in {target} ({", ".join(present[:3])}...). Use --replace to swap it.')
    expected = _expected_checksum(archive, expected_sha256)
    print('Checking archive checksum…', flush=True)
    actual = sha256_file(archive)
    if actual != expected:
        raise ValueError(f'Checksum mismatch: expected {expected}, archive has {actual}. Download it again.')
    wanted = inputs_digest(tooling_root)
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f'.{target.name}-staging-', dir=target.parent))
    backup = None
    try:
        print('Extracting…', flush=True)
        _extract(archive, staging)
        manifest = json.loads((staging / MANIFEST).read_text())
        if manifest.get('format') != FORMAT or manifest.get('abi') != ABI:
            raise ValueError(f'Unsupported SDK archive (format {manifest.get("format")}, ABI {manifest.get("abi")})')
        if manifest.get('inputsDigest') != wanted:
            raise ValueError('This SDK was built from different pinned inputs than this ros2-android version. '
                             'Download the SDK published with this release, or build it with setup sources/native/python/agent.')
        missing = [path for path in REQUIRED if not (staging / path).exists()]
        if missing:
            raise ValueError('SDK archive is incomplete: ' + ', '.join(missing))
        prefix = manifest.get('prefix', '')
        if not isinstance(prefix, str) or not prefix.startswith('/') or len(prefix) < 2:
            raise ValueError('SDK archive manifest has no valid build prefix')
        old, new = prefix.encode(), str(target).encode()
        for name in manifest.get('textFilesWithPrefix', []):
            path = staging / name
            if path.is_symlink() or not path.is_file() or staging.resolve() not in path.resolve().parents:
                raise ValueError(f'Invalid prefix entry: {name}')
            path.write_bytes(path.read_bytes().replace(old, new))
        # Swap only SDK outputs; sources, build trees and logs in target stay untouched.
        target.mkdir(exist_ok=True)
        if present:
            backup = Path(tempfile.mkdtemp(prefix=f'.{target.name}-previous-', dir=target.parent))
            for entry in present:
                os.replace(target / entry, backup / entry)
        moved = []
        try:
            for entry in CONTENT:
                if (staging / entry).exists():
                    os.replace(staging / entry, target / entry)
                    moved.append(entry)
        except OSError:
            for entry in moved:
                if (target / entry).is_dir() and not (target / entry).is_symlink():
                    shutil.rmtree(target / entry, ignore_errors=True)
                else:
                    (target / entry).unlink(missing_ok=True)
            if backup:
                for entry in present:
                    os.replace(backup / entry, target / entry)
                backup = None
            raise
        for name in NOTICES:
            if (staging / name).is_file():
                os.replace(staging / name, target / f'SDK-{name}')
        record = {key: manifest.get(key) for key in ('format', 'abi', 'version', 'source', 'inputsDigest')}
        record.update(archive=archive.name, sha256=actual, prefix=str(target))
        (target / '.sdk-install.json').write_text(json.dumps(record, indent=2) + '\n')
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        if backup:
            shutil.rmtree(backup, ignore_errors=True)
    if not link.exists() and not link.is_symlink():
        link.symlink_to(target, target_is_directory=True)
    elif link.resolve() != target.resolve():
        print(f'Note: {link} points to {link.resolve()}, not {target}.', file=sys.stderr)
    return manifest


def run(arguments=None, *, tooling_root):
    parser = argparse.ArgumentParser(prog='ros2-android setup sdk', description=__doc__.split('\n\n')[0],
                                     epilog='Replaces setup sources, native --with-cpp, python and agent. '
                                            'Then run: ros2-android doctor --profile android')
    parser.add_argument('--archive', required=True, type=Path, help='Downloaded ros2-android-native-sdk-*.tar.xz')
    parser.add_argument('--sha256', help='Published SHA-256; default: the .sha256 file next to the archive')
    parser.add_argument('--replace', action='store_true', help='Swap an SDK that is already installed')
    selected = parser.parse_args(arguments)
    try:
        manifest = install(selected.archive, tooling_root=tooling_root, expected_sha256=selected.sha256, replace=selected.replace)
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, tarfile.TarError, json.JSONDecodeError) as error:
        print('setup sdk: ' + str(error), file=sys.stderr)
        return 1
    print(f'Native SDK {manifest.get("version", "")} installed. Next: ros2-android doctor --profile android')
    return 0

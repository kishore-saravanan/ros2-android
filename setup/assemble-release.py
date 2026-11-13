#!/usr/bin/env python3
"""Maintainer: sign the main APK and assemble a checksummed, documented release folder.

Inputs are artifacts built from the recorded source revision: the unsigned release APK
(`./setup/build.sh :app:assembleRelease`), optionally the bundled host wheel and Debian
package. Signing passwords are read only from ROS2_ANDROID_KEYSTORE_PASSWORD and
ROS2_ANDROID_KEY_PASSWORD (defaults to the keystore password); keys never enter the
release folder. Nothing is published, uploaded or installed on a phone.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import native_sdk  # noqa: E402
PACKAGE = 'dev.ros2android.android'
DOCUMENTS = ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES.md', 'CHANGELOG.md')
CHECKSUMS = 'SHA256SUMS'
MANIFEST = 'release-manifest.json'


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def build_tools():
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk')
    version = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())['android']['buildTools']
    return sdk / 'build-tools' / version


def badging(apk):
    output = subprocess.run([str(build_tools() / 'aapt'), 'dump', 'badging', str(apk)], check=True,
                            capture_output=True, text=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not package:
        raise RuntimeError(f'Cannot read package identity from {apk}')
    return {'package': package[1], 'versionCode': int(package[2]), 'versionName': package[3],
            'debuggable': 'application-debuggable' in output,
            'abis': re.findall(r"'([^']+)'", re.search(r"native-code:(.*)", output)[1]) if 'native-code:' in output else []}


def signer(apk):
    """Signer certificate digest and verified schemes from apksigner."""
    result = subprocess.run([str(build_tools() / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('APK signature verification failed: ' + (result.stderr or result.stdout).strip())
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', result.stdout)
    schemes = sorted(set(re.findall(r'Verified using (v\d(?:\.\d)?) scheme \([^)]*\): true', result.stdout)))
    if len(digests) != 1:
        raise RuntimeError('Expected exactly one APK signer, found %d' % len(digests))
    return {'certificateSha256': digests[0], 'schemes': schemes}


AGENT_LICENSES = ('micro-xrce-agent', 'fastdds', 'fastcdr', 'foonathan-memory', 'spdlog', 'asio', 'tinyxml2', 'android-ifaddrs')


def require_contents(apk):
    """Native runtime plus a license text for every staged component (ROS index and Agent stack)."""
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        index = json.loads(archive.read('assets/licenses/index.json')) if 'assets/licenses/index.json' in names else []
    problems = [name for name in ('lib/arm64-v8a/libros2android.so', 'lib/arm64-v8a/libc++_shared.so') if name not in names]
    if not index:
        problems.append('assets/licenses/index.json (run setup/stage-licenses.py)')
    for entry in index:
        problems += [f"assets/licenses/{entry['component']}/{file}" for file in entry['files']
                     if f"assets/licenses/{entry['component']}/{file}" not in names]
    for component in AGENT_LICENSES:
        if not any(name.startswith(f'assets/licenses/{component}/') for name in names):
            problems.append(f'assets/licenses/{component}/ (empty)')
    if problems:
        raise RuntimeError('Release APK lacks required native/license content: ' + ', '.join(problems))


def _bundled_apk(tar_stream, deb):
    with tarfile.open(fileobj=tar_stream) as tar:
        for member in tar.getmembers():
            if member.name.endswith('ros2android/artifacts/main.apk'):
                return hashlib.sha256(tar.extractfile(member).read()).hexdigest()
    raise RuntimeError(f'{deb} has no bundled main APK')


def deb_apk_sha256(deb):
    """SHA-256 of the main APK bundled inside a Debian package."""
    data = Path(deb).read_bytes()
    if not data.startswith(b'!<arch>\n'):
        raise RuntimeError(f'{deb} is not a Debian archive')
    if shutil.which('dpkg-deb'):  # handles every dpkg compression, including zstd
        tar = subprocess.run(['dpkg-deb', '--fsys-tarfile', str(deb)], check=True, capture_output=True).stdout
        return _bundled_apk(io.BytesIO(tar), deb)
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        name, size = header[:16].decode().strip().rstrip('/'), int(header[48:58].decode().strip())
        if name.startswith('data.tar'):
            if name.endswith('.zst'):
                raise RuntimeError(f'{deb} uses zstd; install dpkg to inspect it')
            return _bundled_apk(io.BytesIO(data[offset + 60:offset + 60 + size]), deb)
        offset += 60 + size + (size % 2)
    raise RuntimeError(f'{deb} has no data archive')


def wheel_matrix(wheel):
    with zipfile.ZipFile(wheel) as archive:
        return json.loads(archive.read('ros2android/compatibility.json'))


def write_checksums(folder):
    files = sorted(p.name for p in folder.iterdir() if p.is_file() and p.name != CHECKSUMS)
    (folder / CHECKSUMS).write_text(''.join(f'{sha256(folder / name)}  {name}\n' for name in files))
    return files


def sdk_manifest(archive, matrix, release=True):
    """Accept a prebuilt native SDK only if it was built from this release's pinned inputs."""
    sdk = native_sdk.read_manifest(archive)
    if sdk.get('format') != native_sdk.FORMAT or sdk.get('abi') != native_sdk.ABI:
        raise RuntimeError(f"Unsupported SDK archive format {sdk.get('format')} / ABI {sdk.get('abi')}")
    if sdk.get('version') != matrix['hostClientVersion']:
        raise RuntimeError(f"SDK archive is for client {sdk.get('version')}, not {matrix['hostClientVersion']}")
    if sdk.get('inputsDigest') != native_sdk.inputs_digest(ROOT):
        raise RuntimeError('SDK archive was built from different pinned inputs; rebuild it from this revision')
    if release and not sdk.get('source', {}).get('clean'):
        raise RuntimeError('SDK archive was packaged from a dirty tree; use a clean CI build')
    return sdk


def source_state(allow_dirty):
    revision = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], check=True, capture_output=True, text=True).stdout.strip()
    dirty = bool(subprocess.run(['git', '-C', str(ROOT), 'status', '--porcelain', '--untracked-files=no'],
                                check=True, capture_output=True, text=True).stdout.strip())
    if dirty and not allow_dirty:
        raise RuntimeError('Tracked files differ from HEAD; commit first or pass --allow-dirty for a dry run')
    return revision, not dirty


def assemble(args):
    matrix = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())
    revision, clean = source_state(args.allow_dirty or args.unsigned_dry_run)
    identity = badging(args.apk)
    expected = matrix['app']
    if identity['package'] != PACKAGE or identity['debuggable']:
        raise RuntimeError(f"Release requires the non-debuggable {PACKAGE} APK, got {identity}")
    if (identity['versionName'], identity['versionCode']) != (expected['versionName'], expected['versionCode']):
        raise RuntimeError(f"APK version {identity['versionName']}/{identity['versionCode']} differs from the "
                           f"compatibility matrix {expected['versionName']}/{expected['versionCode']}")
    require_contents(args.apk)
    out = args.out
    if out.exists() and any(out.iterdir()):
        raise RuntimeError(f'{out} is not empty; choose a new release folder')
    out.mkdir(parents=True, exist_ok=True)
    apk_name = f"ros2-android-{identity['versionName']}{'-UNSIGNED-DRY-RUN' if args.unsigned_dry_run else ''}.apk"
    apk = out / apk_name
    if args.unsigned_dry_run:
        shutil.copyfile(args.apk, apk)
        signature = None
    else:
        password = os.environ.get('ROS2_ANDROID_KEYSTORE_PASSWORD')
        if not password:
            raise RuntimeError('Set ROS2_ANDROID_KEYSTORE_PASSWORD (and optionally ROS2_ANDROID_KEY_PASSWORD)')
        key_variable = 'ROS2_ANDROID_KEY_PASSWORD' if os.environ.get('ROS2_ANDROID_KEY_PASSWORD') else 'ROS2_ANDROID_KEYSTORE_PASSWORD'
        aligned = out / '.aligned.apk'
        subprocess.run([str(build_tools() / 'zipalign'), '-f', '-P', '16', '4', str(args.apk), str(aligned)], check=True)
        subprocess.run([str(build_tools() / 'apksigner'), 'sign', '--ks', str(args.keystore), '--ks-key-alias', args.key_alias,
                        '--ks-pass', 'env:ROS2_ANDROID_KEYSTORE_PASSWORD', '--key-pass', f'env:{key_variable}',
                        '--out', str(apk), str(aligned)], check=True)
        aligned.unlink()
        for leftover in out.glob('*.idsig'):
            leftover.unlink()
        signature = signer(apk)
        if args.expect_certificate and signature['certificateSha256'] != args.expect_certificate.lower():
            raise RuntimeError('Signed with an unexpected certificate: ' + signature['certificateSha256'])
    artifacts = {'apk': {'file': apk_name, **identity, 'signature': signature}}
    if args.wheel:
        bundled = wheel_matrix(args.wheel)
        if bundled.get('hostClientVersion') != matrix['hostClientVersion'] or 'artifact' not in bundled:
            raise RuntimeError('Wheel is not a bundled build of this client version; use setup/build-python-package.py')
        shutil.copyfile(args.wheel, out / args.wheel.name)
        artifacts['wheel'] = {'file': args.wheel.name, 'version': bundled['hostClientVersion'],
                              'sdkSnapshotSha256': bundled['artifact']['sdkSnapshotSha256']}
    if args.sdk:
        sdk = sdk_manifest(args.sdk, matrix, release=not args.unsigned_dry_run)
        shutil.copyfile(args.sdk, out / args.sdk.name)
        artifacts['sdk'] = {'file': args.sdk.name, 'version': sdk['version'], 'abi': sdk['abi'],
                            'inputsDigest': sdk['inputsDigest'], 'source': sdk['source']}
    if args.deb:
        if deb_apk_sha256(args.deb) != sha256(apk):
            raise RuntimeError('The Debian package does not bundle this release APK; rebuild it with setup/build-deb.py --apk ' + str(apk))
        shutil.copyfile(args.deb, out / args.deb.name)
        artifacts['deb'] = {'file': args.deb.name, 'bundledApkSha256': sha256(apk)}
    for name in DOCUMENTS:
        shutil.copyfile(ROOT / name, out / name)
    manifest = {'schemaVersion': 1, 'createdUtc': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                'status': 'unsigned dry run - not a release' if args.unsigned_dry_run else matrix['status'],
                'source': {'revision': revision, 'trackedTreeClean': clean},
                'versions': {'app': expected, 'hostClient': matrix['hostClientVersion'], 'contracts': matrix['contracts']},
                'toolchain': {'android': matrix['android'], 'host': matrix['host'], 'ros': matrix['ros']},
                'artifacts': artifacts,
                'notes': 'Checksums identify files; the APK signature identifies its signer. Neither proves runtime qualification.'}
    (out / MANIFEST).write_text(json.dumps(manifest, indent=2) + '\n')
    files = write_checksums(out)
    return manifest, files


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', type=Path, required=True, help='Unsigned non-debuggable release APK')
    parser.add_argument('--out', type=Path, required=True, help='New, empty release folder')
    parser.add_argument('--wheel', type=Path, help='Bundled host wheel from setup/build-python-package.py')
    parser.add_argument('--deb', type=Path, help='Debian package built from the signed APK')
    parser.add_argument('--sdk', type=Path, help='Prebuilt native SDK archive from setup/package-native-sdk.py (CI artifact)')
    signing = parser.add_mutually_exclusive_group(required=True)
    signing.add_argument('--keystore', type=Path, help='Release keystore held by the maintainer, outside the repository')
    signing.add_argument('--unsigned-dry-run', action='store_true', help='Exercise the flow without signing; never publish')
    parser.add_argument('--key-alias', help='Key alias inside --keystore')
    parser.add_argument('--expect-certificate', help='Fail unless the signer certificate has this SHA-256')
    parser.add_argument('--allow-dirty', action='store_true', help='Allow uncommitted tracked changes (dry runs only)')
    args = parser.parse_args(arguments)
    if args.keystore and not args.key_alias:
        parser.error('--keystore requires --key-alias')
    if args.keystore and args.allow_dirty:
        parser.error('Signed releases must come from a clean tracked tree')
    if args.keystore and ROOT in args.keystore.resolve().parents:
        parser.error('Keep the release keystore outside the repository')
    try:
        manifest, files = assemble(args)
    except (OSError, RuntimeError, subprocess.SubprocessError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print('assemble release: ' + str(error), file=sys.stderr)
        return 1
    print(json.dumps({'release': str(args.out), 'files': files, 'apk': manifest['artifacts']['apk']['file'],
                      'signer': (manifest['artifacts']['apk']['signature'] or {}).get('certificateSha256')}, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

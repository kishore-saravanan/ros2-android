#!/usr/bin/env python3
"""Verify an assembled release folder without network or phone access.

Checks SHA256SUMS against every file, the manifest against the actual APK identity,
signer certificate and schemes, and optional wheel/Debian/SDK cross-references. Pass
--expect-certificate with the maintainer's published certificate digest; a matching
checksum alone only proves the files were not changed after assembly.
"""

import argparse
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location(
    'assemble_release', ROOT / 'setup/assemble-release.py'
)
release = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(release)


def verify(folder, expect_certificate=None, allow_unsigned=False):
    problems = []
    listed = {}

    for line in (folder / release.CHECKSUMS).read_text().splitlines():
        digest, _, name = line.partition('  ')
        if not name or '/' in name or name in listed:
            problems.append(f'Malformed checksum line: {line!r}')
            continue

        listed[name] = digest

    present = {p.name for p in folder.iterdir() if p.is_file() and p.name != release.CHECKSUMS}
    problems += [f'Unlisted file: {name}' for name in sorted(present - set(listed))]
    problems += [f'Missing file: {name}' for name in sorted(set(listed) - present)]
    problems += [
        f'Checksum mismatch: {name}'
        for name in sorted(present & set(listed))
        if release.sha256(folder / name) != listed[name]
    ]
    manifest = json.loads((folder / release.MANIFEST).read_text())
    apk_entry = manifest['artifacts']['apk']
    apk = folder / apk_entry['file']
    identity = release.badging(apk)

    for key in ('package', 'versionCode', 'versionName'):
        if identity[key] != apk_entry[key]:
            problems.append(f'APK {key} {identity[key]} differs from manifest {apk_entry[key]}')

    if identity['debuggable']:
        problems.append('APK is debuggable')

    release.require_contents(apk)

    if apk_entry['signature'] is None:
        if not allow_unsigned:
            problems.append('APK is unsigned (dry run); not a release')
    else:
        actual = release.signer(apk)
        if actual != apk_entry['signature']:
            problems.append(f'Signer {actual} differs from manifest {apk_entry["signature"]}')

        if expect_certificate and actual['certificateSha256'] != expect_certificate.lower():
            problems.append('Signer certificate is not the expected maintainer certificate')

        if not set(actual['schemes']) & {'v2', 'v3', 'v3.1'}:
            problems.append('APK lacks a v2/v3 signature scheme')

    if 'wheel' in manifest['artifacts']:
        bundled = release.wheel_matrix(folder / manifest['artifacts']['wheel']['file'])
        if (
            bundled['artifact']['sdkSnapshotSha256']
            != manifest['artifacts']['wheel']['sdkSnapshotSha256']
        ):
            problems.append('Wheel SDK snapshot differs from manifest')

        if bundled['app'] != manifest['versions']['app']:
            problems.append('Wheel compatibility matrix expects a different app version')

    if 'sdk' in manifest['artifacts']:
        entry = manifest['artifacts']['sdk']
        sdk = release.native_sdk.read_manifest(folder / entry['file'])

        for key in ('version', 'abi', 'inputsDigest', 'source'):
            if sdk.get(key) != entry[key]:
                problems.append(f'SDK archive {key} differs from manifest')

        if sdk.get('version') != manifest['versions']['hostClient']:
            problems.append('SDK archive is for a different client version')

    artifacts = manifest['artifacts']
    sdk_entry = artifacts.get('sdk')
    problems += release.release_consistency(
        folder / artifacts['wheel']['file'] if 'wheel' in artifacts else None,
        folder / artifacts['deb']['file'] if 'deb' in artifacts else None,
        folder / sdk_entry['file'] if sdk_entry else None,
        release.native_sdk.read_manifest(folder / sdk_entry['file']) if sdk_entry else None,
    )

    if 'deb' in manifest['artifacts']:
        if release.deb_apk_sha256(folder / manifest['artifacts']['deb']['file']) != release.sha256(
            apk
        ):
            problems.append('Debian package bundles a different APK')

    return {
        'schemaVersion': 1,
        'folder': folder.name,
        'files': len(present),
        'problems': problems,
        'signer': (apk_entry['signature'] or {}).get('certificateSha256'),
        'verified': not problems,
    }


def main(arguments=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument('folder', type=Path)
    parser.add_argument(
        '--expect-certificate', help="Maintainer's published signer certificate SHA-256"
    )
    parser.add_argument(
        '--allow-unsigned', action='store_true', help='Accept an unsigned dry-run folder'
    )
    args = parser.parse_args(arguments)
    try:
        report = verify(args.folder, args.expect_certificate, args.allow_unsigned)
    except (OSError, RuntimeError, KeyError, ValueError) as error:
        print('verify release: ' + str(error), file=sys.stderr)
        return 1

    print(json.dumps(report, indent=2))
    return 0 if report['verified'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

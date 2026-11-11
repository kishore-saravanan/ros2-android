"""Read-only APK inspection and conservative replacement checks; never contacts ADB."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from .diagnostics import compatibility


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else _digest_stream(stream)


def _digest_stream(stream):
    result = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        result.update(chunk)
    return result.hexdigest()


def probe(command):
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=20).stdout
    except FileNotFoundError as failure:
        raise ValueError('Android build tools are missing; prepare the pinned SDK build-tools version.') from failure
    except subprocess.TimeoutExpired as failure:
        raise ValueError('APK inspection timed out; no device operation was attempted.') from failure
    except subprocess.CalledProcessError as failure:
        raise ValueError('APK metadata/signature verification failed; no device operation was attempted.') from failure


def manifest_version(tree):
    """Badging in build-tools 35 omits versionCodeMajor; read typed root attributes."""
    lines = tree.splitlines()
    roots = [i for i, line in enumerate(lines) if line.strip().startswith('E: manifest ')]
    if len(roots) != 1:
        raise ValueError('Expected one APK manifest root')
    attributes = []
    for line in lines[roots[0] + 1:]:
        if line.strip().startswith('E:'):
            break
        attributes.append(line.strip())

    def attribute(name, default, maximum):
        found = [line for line in attributes if line.startswith('A: android:' + name + '(')]
        if not found:
            if default is None:
                raise ValueError('Missing manifest ' + name)
            return default
        match = re.fullmatch(r'A: android:' + name + r'\(0x[0-9a-fA-F]+\)=\(type 0x1[01]\)(0x[0-9a-fA-F]+)', found[0])
        if len(found) != 1 or not match or int(match[1], 16) > maximum:
            raise ValueError('Invalid manifest ' + name)
        return int(match[1], 16)

    return attribute('versionCode', None, 0xffffffff), attribute('versionCodeMajor', 0, 0x7fffffff)


def inspect(apk, sdk=None):
    path = Path(apk).expanduser().resolve()
    if not path.is_file():
        raise ValueError('APK file does not exist')
    if sdk is None:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk'
    tools = Path(sdk).expanduser() / 'build-tools' / compatibility()['android']['buildTools']
    before = digest(path)
    badging = probe([str(tools / 'aapt'), 'dump', 'badging', str(path)])
    packages = re.findall(r'^package: (.+)$', badging, re.M)
    if len(packages) != 1:
        raise ValueError('Expected one APK package declaration')
    fields = dict(re.findall(r"([A-Za-z]+)='([^']*)'", packages[0]))
    if 'split' in fields:
        raise ValueError('Split APK installation is not supported by this standalone-artifact check')
    package = fields.get('name', '')
    if not re.fullmatch(r'dev\.ros2android\.android(?:\.runner\.[a-z][a-z0-9_]{0,63})?', package):
        raise ValueError('Refusing APK outside the ROS2 Android main/runner namespace')

    def integer(value, label, maximum):
        if not isinstance(value, str) or not re.fullmatch(r'[0-9]+', value) or not 0 <= int(value) <= maximum:
            raise ValueError('Invalid APK ' + label)
        return int(value)

    code = integer(fields.get('versionCode'), 'versionCode', 0xffffffff)
    tree = probe([str(tools / 'aapt'), 'dump', 'xmltree', str(path), 'AndroidManifest.xml'])
    manifest_code, major = manifest_version(tree)
    if manifest_code != code or ('versionCodeMajor' in fields and integer(fields['versionCodeMajor'], 'versionCodeMajor', 0x7fffffff) != major):
        raise ValueError('Inconsistent APK version metadata')
    levels = {}
    for tag in ('sdkVersion', 'targetSdkVersion'):
        matches = re.findall(r"^" + tag + r":'([^']*)'$", badging, re.M)
        if len(matches) != 1:
            raise ValueError('Expected numeric APK ' + tag)
        levels[tag] = integer(matches[0], tag, 10000)
    signing = probe([str(tools / 'apksigner'), 'verify', '--print-certs', str(path)])
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', signing, re.M)
    if len(signers) != 1:
        raise ValueError('APK requires exactly one current signing certificate; signer migration is not supported')
    native = re.findall(r'^native-code: (.+)$', badging, re.M)
    if len(native) > 1:
        raise ValueError('Invalid APK native ABI declaration')
    abis = re.findall(r"'([^']+)'", native[0]) if native else []
    if digest(path) != before:
        raise ValueError('APK changed while it was being verified; retry with a stable artifact')
    return {'schemaVersion': 1, 'applicationId': package, 'versionCode': code,
            'versionCodeMajor': major, 'longVersionCode': (major << 32) | code,
            'versionName': fields.get('versionName', ''), 'minSdk': levels['sdkVersion'],
            'targetSdk': levels['targetSdkVersion'], 'abis': abis,
            'debuggable': bool(re.search(r'^application-debuggable(?:\s|$)', badging, re.M)),
            'signerSha256': signers[0].lower(), 'sha256': before}


def compare(previous, replacement):
    reasons = []
    if previous['applicationId'] != replacement['applicationId']:
        reasons.append('Package name differs; this is a separate app, not a replacement.')
    if previous['signerSha256'] != replacement['signerSha256']:
        reasons.append('Current signing certificate differs; this preview does not qualify signing lineage or key rotation.')
    if replacement['longVersionCode'] < previous['longVersionCode']:
        reasons.append('Replacement version code is lower; do not force a downgrade.')
    return {'schemaVersion': 1, 'scope': 'two-local-apk-files-only',
            'decision': 'blocked' if reasons else ('identical' if previous['sha256'] == replacement['sha256'] else 'candidate'),
            'reasons': reasons, 'previous': previous, 'replacement': replacement,
            'limits': 'No installed-app identity, device compatibility, data migration, runtime behavior or rollback qualification. No install was attempted.'}


def main(arguments):
    parser = argparse.ArgumentParser(prog='ros2-android apk', description='Inspect or compare local APKs without a phone, SDK extraction or installation.')
    parser.add_argument('--sdk', type=Path, help='Android SDK root (otherwise use environment/default)')
    commands = parser.add_subparsers(dest='action', required=True)
    single = commands.add_parser('inspect', help='Verify a main/runner APK and print public metadata')
    single.add_argument('apk', type=Path)
    pair = commands.add_parser('compare', help='Compare saved previous APK and replacement; never install')
    pair.add_argument('previous', type=Path)
    pair.add_argument('replacement', type=Path)
    args = parser.parse_args(arguments)
    try:
        if args.action == 'inspect':
            result = inspect(args.apk, args.sdk)
        else:
            result = compare(inspect(args.previous, args.sdk), inspect(args.replacement, args.sdk))
    except (OSError, ValueError) as failure:
        print(json.dumps({'schemaVersion': 1, 'error': str(failure)}))
        return 2
    print(json.dumps(result, indent=2))
    return 1 if result.get('decision') == 'blocked' else 0

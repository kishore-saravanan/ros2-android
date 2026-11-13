#!/usr/bin/env python3
"""Build a local Debian alpha containing the host CLI and validated main-app APK."""
import argparse
from email.parser import Parser
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wheel', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--version', default='1.0.0~beta1-1')
    parser.add_argument('--maintainer', help='Name <email>; defaults to configured Git identity')
    parser.add_argument('--allow-debug-apk', action='store_true', help='Local development only; never a production release')
    parser.add_argument('--out', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+~:-]*', args.version):
        parser.error('Invalid Debian version')
    maintainer = args.maintainer
    if not maintainer:
        name = subprocess.check_output(['git', '-C', str(ROOT), 'config', 'user.name'], text=True).strip()
        email = subprocess.check_output(['git', '-C', str(ROOT), 'config', 'user.email'], text=True).strip()
        maintainer = f'{name} <{email}>'
    if '\n' in maintainer or '\r' in maintainer or not re.fullmatch(r'[^<>]+ <[^<>\s]+@[^<>\s]+>', maintainer):
        parser.error('Provide a valid configured maintainer identity')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
    build_tools = sdk / 'build-tools/35.0.0'
    badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)'", badging, re.M)
    if not package or package[1] != 'dev.ros2android.android':
        parser.error('Refusing APK outside main app identity')
    if "sdkVersion:'26'" not in badging or "native-code: 'arm64-v8a'" not in badging:
        parser.error('Expected API 26 / ARM64 reference APK')
    debug = 'application-debuggable' in badging
    if debug and not args.allow_debug_apk:
        parser.error('Debug APK requires --allow-debug-apk for local alpha validation')
    subprocess.run([str(build_tools / 'apksigner'), 'verify', str(args.apk)], check=True)
    with tempfile.TemporaryDirectory(prefix='ros2android-deb-') as temp:
        root = Path(temp) / 'package'
        library = root / 'usr/lib/ros2-android'; library.mkdir(parents=True)
        with zipfile.ZipFile(args.wheel) as wheel:
            metadata_files = [name for name in wheel.namelist() if name.endswith('.dist-info/METADATA')]
            if len(metadata_files) != 1 or Parser().parsestr(wheel.read(metadata_files[0]).decode())['Name'] != 'ros2-android':
                parser.error('Expected a ROS2 Android wheel')
            for name in wheel.namelist():
                relative = Path(name)
                if relative.is_absolute() or '..' in relative.parts:
                    parser.error('Unsafe wheel path')
                if name.endswith('/'):
                    continue
                target = library / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(wheel.read(name))
        artifacts = library / 'ros2android/artifacts'; artifacts.mkdir()
        shutil.copyfile(args.apk, artifacts / 'main.apk')
        (artifacts / 'main.json').write_text(json.dumps({
            'schemaVersion': 1, 'applicationId': package[1], 'minSdk': 26,
            'abis': ['arm64-v8a'], 'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
            'debug': debug, 'debianVersion': args.version,
            'wheelSha256': hashlib.sha256(args.wheel.read_bytes()).hexdigest()}, indent=2) + '\n')
        launcher = root / 'usr/bin/ros2-android'; launcher.parent.mkdir(parents=True)
        launcher.write_text('''#!/usr/bin/python3
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'lib/ros2-android'))
from ros2android.cli import main
raise SystemExit(main())
'''); launcher.chmod(0o755)
        doc = root / 'usr/share/doc/ros2-android'; doc.mkdir(parents=True)
        for name in ('LICENSE', 'NOTICE', 'CITATION.cff'):
            shutil.copyfile(ROOT / name, doc / name)
        shutil.copyfile(ROOT / 'docs/DEBIAN-PACKAGE.md', doc / 'README')
        (doc / 'copyright').write_text('''ROS2 Android
Copyright 2026 Kishore Saravanan
Original project material: Apache-2.0; see LICENSE and NOTICE.
Third-party components retain their respective terms.
This local alpha has not completed the third-party distribution audit.
''')
        control = root / 'DEBIAN'; control.mkdir()
        size = sum(path.stat().st_size for path in root.rglob('*') if path.is_file()) // 1024 + 1
        (control / 'control').write_text(f'''Package: ros2-android
Version: {args.version}
Section: devel
Priority: optional
Architecture: all
Maintainer: {maintainer}
Depends: python3 (>= 3.10), adb, bash
Installed-Size: {size}
Homepage: https://github.com/kishore-saravanan/ros2-android
Description: Native ROS 2 Android developer client (local alpha)
 Host tools and a bundled ARM64 Android APK, installed onto a phone only
 through an explicit authorized device setup command. Cross-build SDK and
 ROS prerequisites require separate preparation. Not a public release.
''')
        args.out.mkdir(parents=True, exist_ok=True)
        output = args.out.resolve() / f'ros2-android_{args.version}_all.deb'
        subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(root), str(output)], check=True)
        print(output)


if __name__ == '__main__':
    main()

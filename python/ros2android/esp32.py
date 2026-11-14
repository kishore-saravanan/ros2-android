"""Run esptool through an authorized phone's CP2102 USB connection.

Examples:
  tools/esp32-via-phone --serial PHONE_ADB_ID status
  tools/esp32-via-phone --serial PHONE_ADB_ID flash-id
  tools/esp32-via-phone --serial PHONE_ADB_ID write-flash 0x10000 firmware.bin
The UART uses 115200 baud; do not pass serial/baud/reset options.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import socket
import subprocess
import threading
import time

PACKAGE = 'dev.ros2android.android'
RECEIVER = PACKAGE + '/.ProgrammingControlReceiver'


def relay(listener, forwarded_port, token, errors, stopped):
    sockets = []
    try:
        listener.settimeout(1)
        while not stopped.is_set():
            try:
                local, _ = listener.accept()
                break
            except socket.timeout:
                continue
        else:
            return
        sockets.append(local)
        remote = socket.create_connection(('127.0.0.1', forwarded_port), 5)
        sockets.append(remote)
        remote.sendall(token.encode('ascii'))
        for connection in sockets:
            connection.settimeout(10)
            connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with selectors.DefaultSelector() as selected:
            selected.register(local, selectors.EVENT_READ, remote)
            selected.register(remote, selectors.EVENT_READ, local)
            while not stopped.is_set():
                for key, _ in selected.select(1):
                    data = key.fileobj.recv(65536)
                    if not data:
                        return
                    key.data.sendall(data)
    except OSError as error:
        if not stopped.is_set():
            errors.append(str(error))
    finally:
        for connection in sockets:
            connection.close()


def esptool_command(tool, port, operation, arguments, no_stub, legacy):
    """esptool invocation for the phone bridge. Writes are always compressed: the ESP32 ROM loader
    supports it, but esptool only compresses by default with its stub, and uncompressed writes stall
    on the 115200-baud phone bridge."""
    reset = 'no_reset' if legacy else 'no-reset'
    name = operation.replace('-', '_') if legacy else operation
    compress = ['--compress'] if operation == 'write-flash' else []
    return [*tool, '--chip', 'esp32', '--port', port, '--before', reset, '--after', reset,
            *(['--no-stub'] if no_stub else []), name, *compress, *arguments]


def esptool_version(banner):
    match = re.search(r'(?<![\w.])v?([45])\.\d+(?:\.\d+)?(?![\w.])', banner)
    if not match:
        raise ValueError('This command supports esptool 4.x or 5.x')
    return match[1], match[0].lstrip('v')


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', help='Authorized USB or wireless ADB device')
    parser.add_argument('--python', type=Path, default=Path.home() / '.cache/ros2-android-esp32/toolchain-venv/bin/python', help='Python environment with pyserial and esptool')
    parser.add_argument('--esptool-script', type=Path, help='Explicit esptool script; defaults to the pinned PlatformIO tool when available')
    loader_options = parser.add_mutually_exclusive_group()
    loader_options.add_argument('--no-stub', dest='no_stub', action='store_true', default=True, help='Use the ESP32 ROM loader (default for the phone bridge)')
    loader_options.add_argument('--stub', dest='no_stub', action='store_false', help='Opt into the RAM helper; sustained transfers remain unreliable on the reference phone')
    parser.add_argument('operation', choices=['status', 'flash-id', 'chip-id', 'read-flash', 'write-flash', 'verify-flash'])
    parser.add_argument('arguments', nargs='*', help='Offsets, sizes and files for the selected esptool operation')
    args = parser.parse_args(arguments)
    # Restrict global esptool options: the phone owns baud and reset control.
    if any(value.startswith('-') for value in args.arguments):
        parser.error('Only positional operation arguments are accepted; no baud/reset/port overrides')
    # Validate operation inputs before touching the phone/board.
    if args.operation in ('status', 'flash-id', 'chip-id') and args.arguments:
        parser.error('This operation takes no additional arguments')
    if args.operation in ('write-flash', 'verify-flash'):
        if not args.arguments or len(args.arguments) % 2:
            parser.error('Use OFFSET FILE pairs')
        for offset, name in zip(args.arguments[::2], args.arguments[1::2]):
            if int(offset, 0) < 0 or not Path(name).is_file():
                parser.error('Offsets must be nonnegative and input files must exist')
    if args.operation == 'read-flash':
        if len(args.arguments) != 3 or int(args.arguments[0], 0) < 0 or int(args.arguments[1], 0) <= 0:
            parser.error('Use read-flash OFFSET SIZE OUTPUT_FILE')
        if Path(args.arguments[2]).exists():
            parser.error('Refusing to overwrite an existing flash backup')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
    adb = [str(sdk / 'platform-tools/adb'), *(['-s', args.serial] if args.serial else ['-d'])]

    def device(*command):
        result = subprocess.run([*adb, *command], capture_output=True, text=True)
        if result.returncode:
            # Never print a failed START command containing the session token.
            raise RuntimeError(result.stderr.strip() or 'ADB command failed')
        return result.stdout.strip()

    def control(command, *extras):
        output = device('shell', shlex.join(['am', 'broadcast', '-n', RECEIVER, '--es', 'command', command, *extras]))
        match = re.search(r'Broadcast completed: result=(-?\d+)(?:, data="(.*)")?', output)
        if not match or match[1] != '0':
            raise RuntimeError('Programming control rejected; install ROS2 Android 1.0.0 or newer and ensure no programming session is active')
        return json.loads(match[2])

    device('get-state')
    if args.operation == 'status':
        # Reading process status must not launch UI, reset UART or require esptool.
        print(json.dumps(control('status'), indent=2))
        return
    python = args.python.expanduser().absolute()  # Preserve venv executable symlink and pyvenv.cfg discovery.
    if not python.is_file():
        parser.error('Prepare the ESP32 toolchain using setup/esp32-usb.sh build, or pass --python')
    cache = Path(os.environ.get('ROS2_ANDROID_ESP32_CACHE', str(Path.home() / '.cache/ros2-android-esp32')))
    pinned = cache / 'platformio/packages/tool-esptoolpy/esptool.py'
    script = args.esptool_script.expanduser().absolute() if args.esptool_script else (pinned if pinned.is_file() else None)
    if script and not script.is_file():
        parser.error('Selected esptool script does not exist')
    tool = [str(python), str(script)] if script else [str(python), '-m', 'esptool']
    version = subprocess.check_output([*tool, 'version'], text=True)
    major, version_text = esptool_version(version)
    legacy = major == '4'
    print('Using esptool ' + version_text + (' from the selected SDK script.' if script else ' from the Python environment.'), flush=True)
    if 'showing=true' in device('shell', 'dumpsys window policy'):
        parser.error('Unlock the phone first')
    device('shell', 'am start -W -n ' + PACKAGE + '/.MainActivity')
    token = secrets.token_hex(32)
    forwarded = None
    started = False
    success = False
    listener = socket.socket()
    stopped = threading.Event()
    thread = None
    errors = []
    try:
        control('start', '--es', 'token', token)
        started = True
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            status = control('status')
            if status['state'].startswith('Failed'):
                raise RuntimeError(status['state'])
            if status['port']:
                break
            time.sleep(.3)
        else:
            raise RuntimeError('Programming mode timed out; check Android USB permission')
        forwarded = int(device('forward', 'tcp:0', 'tcp:' + str(status['port'])))
        listener.bind(('127.0.0.1', 0)); listener.listen(1)
        thread = threading.Thread(target=relay, args=(listener, forwarded, token, errors, stopped), daemon=True)
        thread.start()
        command = esptool_command(tool, f'socket://127.0.0.1:{listener.getsockname()[1]}', args.operation,
                                  args.arguments, args.no_stub, legacy)
        # No --baud: raw sockets cannot negotiate serial baud. Phone sets 115200.
        result = subprocess.run(command, timeout=14 * 60)
        if result.returncode:
            raise RuntimeError('esptool failed; previous Agent is left stopped. Reconnect/retry programming or start Agent explicitly')
        if errors:
            raise RuntimeError('Programming tunnel failed: ' + errors[0])
        success = True
        print('Operation passed. Returning ESP32 to normal boot; restoring the previous Agent if it was running.')
    finally:
        stopped.set(); listener.close()
        if thread:
            thread.join(2)
        if forwarded:
            try:
                device('forward', '--remove', 'tcp:' + str(forwarded))
            except RuntimeError:
                pass
        if started:
            try:
                control('stop', '--ez', 'success', str(success).lower())
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline and control('status')['active']:
                    time.sleep(.2)
            except RuntimeError:
                print('Phone unreachable during cleanup; programming mode expires automatically. Check USB/Agent state on phone.')


def run(arguments=None):
    try:
        main(arguments)
        return 0
    except KeyboardInterrupt:
        return 130
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        print('esp32-via-phone:', str(error))
        return 1

"""Exercise the real socket relay with binary data and its authentication prelude."""
from pathlib import Path
import socket
import threading
import unittest
from unittest import mock
import contextlib
import io
import json
import subprocess

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'python'))
from ros2android import esp32 as tool


class VersionTest(unittest.TestCase):
    def test_actual_pinned_banner_selects_major_four_not_minor_five(self):
        self.assertEqual(tool.esptool_version('esptool.py v4.5.1\n4.5.1\n'), ('4', '4.5.1'))
        self.assertEqual(tool.esptool_version('esptool v5.6.0\n5.6.0\n'), ('5', '5.6.0'))
        with self.assertRaises(ValueError):
            tool.esptool_version('esptool v6.5.1')


class RelayTest(unittest.TestCase):
    def test_token_and_binary_round_trip(self):
        token = 'ab' * 32
        payload = bytes(range(256)) * 17
        errors = []
        stopped = threading.Event()
        with socket.socket() as remote, socket.socket() as local:
            for server in (remote, local):
                server.bind(('127.0.0.1', 0)); server.listen(1)
            received = []
            def echo():
                with remote.accept()[0] as connection:
                    connection.settimeout(3)
                    authenticated = bytearray()
                    while len(authenticated) < 64:
                        authenticated.extend(connection.recv(64 - len(authenticated)))
                    received.append(bytes(authenticated))
                    data = bytearray()
                    while len(data) < len(payload):
                        data.extend(connection.recv(len(payload) - len(data)))
                    received.append(bytes(data))
                    connection.sendall(data[::-1])
            echo_thread = threading.Thread(target=echo)
            echo_thread.start()
            relay = threading.Thread(target=tool.relay, args=(local, remote.getsockname()[1], token, errors, stopped))
            relay.start()
            with socket.create_connection(local.getsockname(), 3) as client:
                client.settimeout(3); client.sendall(payload)
                reply = bytearray()
                while len(reply) < len(payload):
                    chunk = client.recv(len(payload) - len(reply))
                    self.assertTrue(chunk)
                    reply.extend(chunk)
                self.assertEqual(bytes(reply), payload[::-1])
            relay.join(3); echo_thread.join(3)
            self.assertFalse(relay.is_alive()); self.assertFalse(echo_thread.is_alive())
            self.assertEqual(received, [token.encode(), payload]); self.assertEqual(errors, [])

    def test_cancel_before_client_connects(self):
        with socket.socket() as local:
            local.bind(('127.0.0.1', 0)); local.listen(1)
            stopped = threading.Event(); errors = []
            relay = threading.Thread(target=tool.relay, args=(local, 1, 'a' * 64, errors, stopped))
            relay.start(); stopped.set(); relay.join(2)
            self.assertFalse(relay.is_alive()); self.assertEqual(errors, [])


class StatusTest(unittest.TestCase):
    def test_status_only_reads_adb_without_toolchain_or_board_reset(self):
        status = {'active': False, 'state': 'Stopped', 'port': 0,
                  'agentRunning': True, 'agentState': 'Running', 'usbDiagnostics': 'RX 64'}
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            output = 'device' if command[-1] == 'get-state' else (
                'Broadcast completed: result=0, data="' + json.dumps(status) + '"')
            return subprocess.CompletedProcess(command, 0, output, '')

        output = io.StringIO()
        with mock.patch('sys.argv', ['esp32-via-phone', '--serial', 'test-phone',
                                     '--python', '/missing/toolchain', 'status']), \
                mock.patch.object(tool.subprocess, 'run', side_effect=run), \
                mock.patch.object(tool.subprocess, 'check_output') as version, \
                contextlib.redirect_stdout(output):
            tool.main()
        self.assertEqual(json.loads(output.getvalue()), status)
        version.assert_not_called()
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][-3:], ['-s', 'test-phone', 'get-state'])
        self.assertIn('command status', calls[1][-1])
        self.assertNotIn('am start', calls[1][-1])

    def test_writes_are_compressed_for_the_phone_bridge(self):
        write = tool.esptool_command(['esptool'], 'socket://x', 'write-flash', ['0x10000', 'a.bin'], True, False)
        self.assertEqual(write[-4:], ['write-flash', '--compress', '0x10000', 'a.bin'])
        self.assertIn('--no-stub', write)
        legacy = tool.esptool_command(['esptool'], 'socket://x', 'write-flash', ['0x10000', 'a.bin'], True, True)
        self.assertEqual(legacy[-4:], ['write_flash', '--compress', '0x10000', 'a.bin'])
        read = tool.esptool_command(['esptool'], 'socket://x', 'read-flash', ['0x0', '0x10', 'out.bin'], True, False)
        self.assertNotIn('--compress', read)

    def test_status_rejects_arguments_before_device_access(self):
        with mock.patch('sys.argv', ['esp32-via-phone', 'status', 'extra']), \
                mock.patch.object(tool.subprocess, 'run') as device, \
                contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as error:
            tool.main()
        self.assertEqual(error.exception.code, 2)
        device.assert_not_called()


if __name__ == '__main__':
    unittest.main()

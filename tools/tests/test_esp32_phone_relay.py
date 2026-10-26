"""Exercise the real socket relay with binary data and its authentication prelude."""
import importlib.machinery
import importlib.util
from pathlib import Path
import socket
import threading
import unittest

loader = importlib.machinery.SourceFileLoader('esp32_phone_tool', str(Path(__file__).parents[1] / 'esp32-via-phone'))
spec = importlib.util.spec_from_loader(loader.name, loader)
tool = importlib.util.module_from_spec(spec)
loader.exec_module(tool)


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


if __name__ == '__main__':
    unittest.main()

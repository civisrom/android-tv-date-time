import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from src.adb_server import ADBServerLease


class AdbServerTests(unittest.TestCase):
    def lease(self, root):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        return ADBServerLease('adb-test', dict(os.environ, ANDROID_ADB_SERVER_PORT=str(port)), root)

    def start(self, lease):
        spawn = subprocess.Popen
        script = ('import socket; s=socket.socket(); s.bind(("127.0.0.1",int(__import__("sys").argv[1]))); '
                  's.listen();\nwhile True:\n c,a=s.accept(); c.close()')

        def fake_adb(args, **kwargs):
            self.assertEqual(args, ['adb-test', 'server', 'nodaemon'])
            return spawn([sys.executable, '-c', script, str(lease.port)], **kwargs)

        with mock.patch('src.adb_server.subprocess.Popen', side_effect=fake_adb):
            lease.ensure()

    def test_unused_manager_never_touches_a_listening_foreign_server(self):
        with tempfile.TemporaryDirectory() as root, socket.socket() as foreign:
            foreign.bind(('127.0.0.1', 0)); foreign.listen()
            lease = ADBServerLease('adb-test', {'ANDROID_ADB_SERVER_PORT': str(foreign.getsockname()[1])}, root)
            lease.release()
            self.assertEqual(list(Path(root).iterdir()), [])
            with mock.patch.object(subprocess, 'Popen') as started:
                lease.ensure(); lease.release(); lease.release()
            started.assert_not_called()
            self.assertTrue(lease._listening())

    def test_only_last_lease_releases_the_owned_server_and_double_cleanup_is_safe(self):
        with tempfile.TemporaryDirectory() as root:
            first = self.lease(root)
            self.start(first)
            second = ADBServerLease('adb-test', first.env, root)
            try:
                second.ensure()
                first.release()
                self.assertTrue(second._listening())
                second.release(); second.release()
                self.assertFalse(first._listening())
            finally:
                first.release(); second.release()
                first.child.wait(timeout=5)

    def test_a_real_second_process_keeps_the_server_until_it_exits(self):
        with tempfile.TemporaryDirectory() as root:
            first = self.lease(root)
            self.start(first)
            script = ('import sys; from src.adb_server import ADBServerLease; '
                      's=ADBServerLease("adb-test",{"ANDROID_ADB_SERVER_PORT":sys.argv[1]},sys.argv[2]); '
                      's.ensure(); print("ready",flush=True); input(); s.release()')
            child = subprocess.Popen([sys.executable, '-c', script, str(first.port), root],
                                     cwd=Path(__file__).resolve().parents[1], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual(child.stdout.readline().strip(), 'ready')
                first.release()
                self.assertTrue(first._listening())
                child.communicate('\n', timeout=10)
                self.assertEqual(child.returncode, 0)
                self.assertFalse(first._listening())
            finally:
                if child.poll() is None:
                    child.kill(); child.communicate()
                first.release()
                if first.child.poll() is None:
                    first.child.terminate()
                first.child.wait(timeout=5)

    def test_replaced_process_identity_is_never_terminated(self):
        with tempfile.TemporaryDirectory() as root:
            lease = self.lease(root)
            self.start(lease)
            try:
                path = Path(root) / f'{lease.port}.json'
                state = json.loads(path.read_text())
                state['server']['birth'] += 1
                path.write_text(json.dumps(state))
                lease.release()
                self.assertTrue(lease._listening())
            finally:
                lease.child.terminate(); lease.child.wait(timeout=5)

    def test_standard_android_studio_port_is_rejected(self):
        with self.assertRaises(ValueError):
            ADBServerLease('adb-test', {'ANDROID_ADB_SERVER_PORT': '5037'})

import contextlib
import io
import json
import logging
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from src.android_time_fixer import APP_VERSION, AndroidTVTimeFixer, AndroidTVTimeFixerError, locales
from scripts.generate_icon import create_icns, create_png


class _FakeDevice:
    def __init__(self, current_ntp: str) -> None:
        self.current_ntp = current_ntp
        self.closed = False

    def shell(self, command: str) -> str:
        if command.startswith('settings get global ntp_server'):
            return self.current_ntp
        return ''

    def close(self) -> None:
        self.closed = True


class CoreValidationTests(unittest.TestCase):
    def test_version_is_exposed_for_packaged_smoke_test(self) -> None:
        self.assertRegex(APP_VERSION, r'^\d+\.\d+\.\d+$')

    def test_ip_validation_includes_port_bounds(self) -> None:
        self.assertTrue(AndroidTVTimeFixer.validate_ip('192.168.1.20'))
        self.assertTrue(AndroidTVTimeFixer.validate_ip('10.0.0.2:5555'))
        self.assertFalse(AndroidTVTimeFixer.validate_ip('10.0.0.2:0'))
        self.assertFalse(AndroidTVTimeFixer.validate_ip('10.0.0.2:65536'))
        self.assertFalse(AndroidTVTimeFixer.validate_ip('999.0.0.1'))

    def test_ntp_validation(self) -> None:
        self.assertTrue(AndroidTVTimeFixer.validate_ntp_server('time.google.com'))
        self.assertTrue(AndroidTVTimeFixer.validate_ntp_server('192.168.1.1'))
        self.assertFalse(AndroidTVTimeFixer.validate_ntp_server('-bad.example'))
        self.assertFalse(AndroidTVTimeFixer.validate_ntp_server('not a host'))

    def test_saved_server_schema_is_normalized(self) -> None:
        result = AndroidTVTimeFixer._normalize_saved_servers({
            'favorite_servers': ['time.google.com', 'time.google.com'],
            'custom_servers': ['1.1.1.1'],
        })
        self.assertEqual(result['favorite_servers'], ['time.google.com'])
        self.assertEqual(result['custom_servers'], ['1.1.1.1'])

        with self.assertRaises(ValueError):
            AndroidTVTimeFixer._normalize_saved_servers([])
        with self.assertRaises(ValueError):
            AndroidTVTimeFixer._normalize_saved_servers({'favorite_servers': 'time.google.com'})


class ReliabilityTests(unittest.TestCase):
    def test_generated_icns_has_valid_container_lengths(self) -> None:
        png = create_png(1, 1, [(0, 0, 0, 0)])
        icns = create_icns([(b'ic08', png)])

        self.assertEqual(icns[:4], b'icns')
        self.assertEqual(int.from_bytes(icns[4:8], 'big'), len(icns))
        self.assertEqual(icns[8:12], b'ic08')
        self.assertEqual(int.from_bytes(icns[12:16], 'big'), len(png) + 8)

    def test_atomic_json_write(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir) / 'settings.json'
            AndroidTVTimeFixer._atomic_write_json(target, {'language': 'ru'})
            self.assertEqual(json.loads(target.read_text(encoding='utf-8')), {'language': 'ru'})
            if os.name != 'nt':
                self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_generated_private_key_is_user_only(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
            fixer.keys_folder = Path(temp_dir) / 'keys'
            fixer.logger = logging.getLogger('test')

            fixer.gen_keys()

            private_key = fixer.keys_folder / 'adbkey'
            self.assertTrue(private_key.is_file())
            self.assertTrue((fixer.keys_folder / 'adbkey.pub').is_file())
            if os.name != 'nt':
                self.assertEqual(private_key.stat().st_mode & 0o777, 0o600)
                self.assertEqual(fixer.keys_folder.stat().st_mode & 0o777, 0o700)

    def test_large_network_is_rejected_before_materialization(self) -> None:
        import ipaddress

        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = fixer._scan_networks([ipaddress.IPv4Network('10.0.0.0/8')])
        self.assertEqual(result, [])
        self.assertIn(str(AndroidTVTimeFixer.MAX_SCAN_HOSTS), output.getvalue())

    def test_command_timeout_kills_child(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        process = subprocess.Popen(
            [sys.executable, '-c', 'import time; time.sleep(30)'],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            **fixer._popen_group_options(),
        )
        with self.assertRaises(TimeoutError):
            fixer._process_command_output(process, timeout=1)
        self.assertIsNotNone(process.poll())

    def test_command_timeout_kills_child_process_tree(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        child_code = (
            "import subprocess,sys,time; "
            "subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']); "
            "time.sleep(30)"
        )
        process = subprocess.Popen(
            [sys.executable, '-c', child_code],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            **fixer._popen_group_options(),
        )
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            fixer._process_command_output(process, timeout=1)
        self.assertLess(time.monotonic() - started, 5)

    def test_non_connect_adb_command_is_not_retried(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.get_adb_path = lambda: 'adb'
        with mock.patch.object(fixer, '_retry_adb_connection') as retry, mock.patch(
                'src.android_time_fixer.Popen'
        ) as popen:
            process = popen.return_value
            process.stdout = io.StringIO('')
            process.stderr = io.StringIO('')
            process.wait.return_value = 0
            process.returncode = 0
            fixer.execute_terminal_command('adb devices')

        retry.assert_not_called()
        popen.assert_called_once()

    def test_import_reports_failure_when_persistence_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            backup = Path(temp_dir) / 'backup.json'
            backup.write_text(
                json.dumps({
                    'language': 'ru',
                    'saved_servers': {'favorite_servers': ['time.google.com'],
                                      'custom_servers': []},
                }),
                encoding='utf-8',
            )
            fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
            fixer.logger = logging.getLogger('test')
            fixer.settings_file = Path(temp_dir) / 'settings.json'
            previous_servers = {'favorite_servers': [], 'custom_servers': []}
            fixer.saved_servers = previous_servers
            fixer.save_servers = lambda: True
            fixer._save_settings = lambda values: False
            output = io.StringIO()

            with contextlib.redirect_stdout(output):
                fixer.import_settings(str(backup))

            self.assertNotIn('successfully', output.getvalue().lower())
            # Список серверов не должен остаться применённым от неудачного импорта
            self.assertIs(fixer.saved_servers, previous_servers)

    def test_ntp_confirmation_requires_exact_value(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.device = _FakeDevice('old.time.google.com')
        fixer.logger = logging.getLogger('test')
        fixer.verify_ntp_server = lambda server: True

        with self.assertRaises(AndroidTVTimeFixerError):
            fixer.set_ntp_server('time.google.com')

    def test_invalid_server_never_enters_favorites(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.saved_servers = {'favorite_servers': [], 'custom_servers': []}
        fixer.save_servers = lambda: True

        self.assertFalse(fixer.add_to_favorites('not a host'))
        self.assertEqual(fixer.saved_servers['favorite_servers'], [])
        self.assertTrue(fixer.add_to_favorites('time.google.com'))
        self.assertEqual(fixer.saved_servers['favorite_servers'], ['time.google.com'])

    def test_scan_results_are_ordered_by_address(self) -> None:
        import ipaddress

        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        passes = [['192.168.0.112'], ['192.168.0.9']]
        fixer._probe_hosts = lambda hosts, timeout, workers: passes.pop(0)

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = fixer._scan_networks([ipaddress.IPv4Network('192.168.0.0/24')])

        self.assertEqual(result, ['192.168.0.9', '192.168.0.112'])

    def test_connect_retries_and_reports_each_authorization_prompt(self) -> None:
        created = []

        class _FlakyDevice:
            def __init__(self, host, port, default_transport_timeout_s=None):
                self.closed = False
                created.append(self)

            def connect(self, rsa_keys, auth_timeout_s, auth_callback):
                auth_callback(self)
                if len(created) == 1:
                    raise RuntimeError('user dismissed the prompt')
                return True

            def close(self):
                self.closed = True

        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.device = None
        fixer.connected_ip = None
        fixer.connection_timeout = 30
        fixer.process_manager = mock.Mock(device_ip=None)
        fixer.load_keys = lambda: (b'pub', b'priv')
        fixer._wait_for_port = lambda host, port: True

        output = io.StringIO()
        with mock.patch('src.android_time_fixer.AdbDeviceTcp', _FlakyDevice), \
                mock.patch('src.android_time_fixer.PythonRSASigner', lambda pub, priv: object()), \
                mock.patch('src.android_time_fixer.time.sleep'), \
                contextlib.redirect_stdout(output):
            fixer.connect('192.168.1.20')

        # Каждая попытка заново запрашивает подтверждение на устройстве
        printed = output.getvalue()
        self.assertEqual(len(created), 2)
        for attempt in (1, 2):
            self.assertIn(locales.get('connection_prompt_sent', attempt=attempt), printed)
        # Сокет неудачной попытки закрыт, успешный остаётся открытым
        self.assertTrue(created[0].closed)
        self.assertFalse(created[1].closed)
        self.assertIs(fixer.device, created[1])
        self.assertEqual(fixer.connected_ip, '192.168.1.20:5555')
        self.assertEqual(fixer.process_manager.device_ip, '192.168.1.20:5555')

    def test_batch_reports_authorization_prompt_per_device(self) -> None:
        created = []

        class _PromptingDevice:
            def __init__(self, host, port, default_transport_timeout_s=None):
                self.host = host
                self.closed = False
                self.ntp = ''
                created.append(self)

            def connect(self, rsa_keys, auth_timeout_s, auth_callback):
                auth_callback(self)
                return True

            def shell(self, command):
                if command.startswith('settings put global ntp_server'):
                    self.ntp = command.rsplit(' ', 1)[1]
                    return ''
                return self.ntp

            def close(self):
                self.closed = True

        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.device = None
        fixer.connected_ip = None
        fixer.load_keys = lambda: (b'pub', b'priv')
        fixer._wait_for_port = lambda host, port: True
        fixer.verify_ntp_server = lambda server: True

        output = io.StringIO()
        with mock.patch('src.android_time_fixer.AdbDeviceTcp', _PromptingDevice), \
                mock.patch('src.android_time_fixer.PythonRSASigner', lambda pub, priv: object()), \
                contextlib.redirect_stdout(output):
            fixer.batch_set_ntp('time.google.com', ['192.168.1.20', '192.168.1.21'])

        printed = output.getvalue()
        # Подсказка печатается для каждого устройства и содержит его адрес
        for ip in ('192.168.1.20', '192.168.1.21'):
            self.assertIn(locales.get('batch_prompt_sent', ip=ip), printed)
        self.assertEqual(len(created), 2)
        self.assertTrue(all(device.closed for device in created))

    def test_connect_closes_socket_when_interrupted(self) -> None:
        created = []

        class _InterruptedDevice:
            def __init__(self, host, port, default_transport_timeout_s=None):
                self.closed = False
                created.append(self)

            def connect(self, rsa_keys, auth_timeout_s, auth_callback):
                raise KeyboardInterrupt

            def close(self):
                self.closed = True

        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.device = None
        fixer.connected_ip = None
        fixer.connection_timeout = 30
        fixer.process_manager = mock.Mock(device_ip=None)
        fixer.load_keys = lambda: (b'pub', b'priv')
        fixer._wait_for_port = lambda host, port: True

        with mock.patch('src.android_time_fixer.AdbDeviceTcp', _InterruptedDevice), \
                mock.patch('src.android_time_fixer.PythonRSASigner', lambda pub, priv: object()), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(KeyboardInterrupt):
                fixer.connect('192.168.1.20')

        self.assertTrue(created[0].closed)
        self.assertIsNone(fixer.device)

    def test_close_device_closes_transport(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        device = _FakeDevice('time.google.com')
        fixer.device = device
        fixer.connected_ip = '192.168.1.20:5555'
        fixer.logger = logging.getLogger('test')

        fixer._close_device()

        self.assertTrue(device.closed)
        self.assertIsNone(fixer.device)
        self.assertIsNone(fixer.connected_ip)


if __name__ == '__main__':
    unittest.main()

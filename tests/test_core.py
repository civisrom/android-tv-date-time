import contextlib
import io
import json
import logging
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

from src.android_time_fixer import (
    APP_VERSION,
    A_STLS,
    ADB_HOME_FILES,
    DEFAULT_ADB_PORT,
    DEFAULT_ADB_SERVER_PORT,
    adb_env,
    AndroidTVTimeFixer,
    AndroidTVTimeFixerError,
    PlatformToolsTransport,
    locales,
)
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
        fixer.adb_env = adb_env(None, DEFAULT_ADB_SERVER_PORT)
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
        fixer._probe_hosts = lambda hosts, timeout, workers, port: passes.pop(0)

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = fixer._scan_networks([ipaddress.IPv4Network('192.168.0.0/24')])

        self.assertEqual(result, ['192.168.0.9:5555', '192.168.0.112:5555'])

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
        fixer._detect_adb_protocol = lambda host, port: 'legacy'

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
        fixer._detect_adb_protocol = lambda host, port: 'legacy'
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
        fixer._detect_adb_protocol = lambda host, port: 'legacy'

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

    # ──────────────────────────────────────────────────────────
    # Определение протокола и транспорт через platform-tools
    # ──────────────────────────────────────────────────────────

    @staticmethod
    def _fake_adbd(reply_command: int):
        """Поднимает на loopback сокет, отвечающий одним ADB-заголовком."""
        server = socket.socket()
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(('127.0.0.1', 0))
        server.listen(1)

        def serve() -> None:
            try:
                conn, _ = server.accept()
                conn.recv(4096)  # проглатываем CNXN клиента
                conn.sendall(struct.pack(
                    b'<6I', reply_command, 1, 0, 0, 0, reply_command ^ 0xFFFFFFFF
                ))
                conn.close()
            except OSError:
                pass
            finally:
                server.close()

        threading.Thread(target=serve, daemon=True).start()
        return server.getsockname()[1]

    def test_protocol_probe_classifies_adbd_replies(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')

        cases = {
            struct.unpack(b'<I', b'AUTH')[0]: 'legacy',
            struct.unpack(b'<I', b'CNXN')[0]: 'authorized',
            A_STLS: 'tls',
        }
        for reply_command, expected in cases.items():
            port = self._fake_adbd(reply_command)
            self.assertEqual(fixer._detect_adb_protocol('127.0.0.1', port), expected)

    def test_protocol_probe_returns_none_on_unknown_reply(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')

        port = self._fake_adbd(struct.unpack(b'<I', b'JUNK')[0])
        self.assertIsNone(fixer._detect_adb_protocol('127.0.0.1', port))

    def test_protocol_probe_returns_none_when_unreachable(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')

        # Порт закрываем сразу, чтобы получить отказ в соединении без ожидания
        closed = socket.socket()
        closed.bind(('127.0.0.1', 0))
        port = closed.getsockname()[1]
        closed.close()

        self.assertIsNone(fixer._detect_adb_protocol('127.0.0.1', port, timeout=1.0))

    def test_platform_tools_transport_keeps_nonzero_remote_exit(self) -> None:
        calls = []

        def runner(args, **kwargs):
            calls.append(args)
            # adb пробрасывает код возврата команды НА устройстве: `grep`,
            # ничего не нашедший, не должен ронять сбор информации
            return subprocess.CompletedProcess(args, 1, stdout='0\n')

        transport = PlatformToolsTransport('adb', '192.168.1.20:5555', runner=runner)
        self.assertEqual(transport.shell('cat /proc/cpuinfo | grep x | wc -l'), '0\n')
        self.assertEqual(
            calls[0],
            ['adb', '-s', '192.168.1.20:5555', 'shell', 'cat /proc/cpuinfo | grep x | wc -l']
        )

    def test_platform_tools_transport_raises_on_connection_error(self) -> None:
        def runner(args, **kwargs):
            return subprocess.CompletedProcess(args, 1, stdout='error: device offline')

        transport = PlatformToolsTransport('adb', '192.168.1.20:5555', runner=runner)
        with self.assertRaises(AndroidTVTimeFixerError):
            transport.shell('getprop')

    def test_platform_tools_transport_close_disconnects(self) -> None:
        calls = []

        def runner(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 0, stdout='')

        PlatformToolsTransport('adb', '192.168.1.20:5555', runner=runner).close()
        self.assertEqual(calls, [['adb', 'disconnect', '192.168.1.20:5555']])

    def test_tls_device_reports_pairing_required(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.device = None
        fixer.connected_ip = None
        fixer.connection_timeout = 30
        fixer.process_manager = mock.Mock(device_ip=None)
        fixer.get_adb_path = lambda: 'adb'
        fixer.adb_env = adb_env(None, DEFAULT_ADB_SERVER_PORT)
        fixer._wait_for_port = lambda host, port: True
        fixer._detect_adb_protocol = lambda host, port: 'tls'

        failed = subprocess.CompletedProcess(['adb'], 1, stdout='failed to connect')
        with mock.patch('src.android_time_fixer.subprocess.run', return_value=failed), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AndroidTVTimeFixerError) as ctx:
                fixer.connect('192.168.1.20:37105')

        self.assertIn('192.168.1.20:37105', str(ctx.exception))
        self.assertIsNone(fixer.device)

    def test_tls_device_uses_platform_tools_transport(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.device = None
        fixer.connected_ip = None
        fixer.connection_timeout = 30
        fixer.process_manager = mock.Mock(device_ip=None)
        fixer.get_adb_path = lambda: 'adb'
        fixer.adb_env = adb_env(None, DEFAULT_ADB_SERVER_PORT)
        fixer._wait_for_port = lambda host, port: True
        fixer._detect_adb_protocol = lambda host, port: 'tls'

        connected = subprocess.CompletedProcess(['adb'], 0, stdout='connected to 192.168.1.20:37105')
        with mock.patch('src.android_time_fixer.subprocess.run', return_value=connected), \
                contextlib.redirect_stdout(io.StringIO()):
            fixer.connect('192.168.1.20:37105')

        self.assertIsInstance(fixer.device, PlatformToolsTransport)
        self.assertEqual(fixer.connected_ip, '192.168.1.20:37105')
        self.assertEqual(fixer.process_manager.device_ip, '192.168.1.20:37105')

    # ──────────────────────────────────────────────────────────
    # Порт больше не подразумевается
    # ──────────────────────────────────────────────────────────

    def test_parse_ip_port_falls_back_to_default(self) -> None:
        self.assertEqual(AndroidTVTimeFixer.parse_ip_port('192.168.1.20'),
                         ('192.168.1.20', DEFAULT_ADB_PORT))
        self.assertEqual(AndroidTVTimeFixer.parse_ip_port('192.168.1.20:37105'),
                         ('192.168.1.20', 37105))
        self.assertEqual(AndroidTVTimeFixer.parse_ip_port('192.168.1.20:0'),
                         ('192.168.1.20', DEFAULT_ADB_PORT))

    def test_scan_results_carry_the_scanned_port(self) -> None:
        import ipaddress

        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        probed = {}

        def probe(hosts, timeout, workers, port):
            probed['port'] = port
            # Второй проход идёт только по не ответившим адресам
            return [ip for ip in ['192.168.0.9'] if ip in hosts]

        fixer._probe_hosts = probe

        with contextlib.redirect_stdout(io.StringIO()):
            result = fixer._scan_networks([ipaddress.IPv4Network('192.168.0.0/24')], 37105)

        self.assertEqual(probed['port'], 37105)
        self.assertEqual(result, ['192.168.0.9:37105'])

    def test_split_cidr_port(self) -> None:
        self.assertEqual(AndroidTVTimeFixer._split_cidr_port('192.168.0.0/24'),
                         ('192.168.0.0/24', None))
        self.assertEqual(AndroidTVTimeFixer._split_cidr_port('192.168.0.0/24:37105'),
                         ('192.168.0.0/24', 37105))
        with self.assertRaises(ValueError):
            AndroidTVTimeFixer._split_cidr_port('192.168.0.0/24:70000')
        with self.assertRaises(ValueError):
            AndroidTVTimeFixer._split_cidr_port('192.168.0.0/24:abc')

    # ──────────────────────────────────────────────────────────
    # Шаг 5: изоляция от системного adb
    # ──────────────────────────────────────────────────────────

    def _fixer_with_data_dir(self, temp_dir: str) -> AndroidTVTimeFixer:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.data_dir = Path(temp_dir)
        fixer.settings_file = Path(temp_dir) / 'settings.json'
        fixer.adb_home = Path(temp_dir) / 'adb'
        fixer.adb_server_port = DEFAULT_ADB_SERVER_PORT
        fixer.adb_env = adb_env(fixer.adb_home, DEFAULT_ADB_SERVER_PORT)
        return fixer

    def test_adb_environment_isolates_the_server_port_everywhere(self) -> None:
        env = adb_env(Path('/tmp/example/adb'), 5038)
        self.assertEqual(env['ANDROID_ADB_SERVER_PORT'], '5038')
        # Остальное окружение сохраняется, иначе дочерний adb потеряет PATH
        self.assertIn('PATH', env)

    @unittest.skipIf(os.name == 'nt', 'на Windows adb игнорирует любые переменные')
    def test_adb_environment_redirects_the_key_store_on_posix(self) -> None:
        # Проверено прямым запуском platform-tools 37.0.1: на Linux и macOS
        # уводит только HOME, а ANDROID_USER_HOME/ANDROID_SDK_HOME — нет.
        # Путь сравниваем через Path: разделители зависят от ОС
        adb_home = Path('/tmp/example/adb')
        env = adb_env(adb_home, 5038)
        self.assertEqual(env['HOME'], str(adb_home))
        self.assertEqual(env['USERPROFILE'], str(adb_home))

    @unittest.skipUnless(os.name == 'nt', 'поведение, специфичное для Windows')
    def test_adb_environment_leaves_home_alone_on_windows(self) -> None:
        # adb там всё равно читает профиль через системный API, поэтому
        # подменять домашние переменные — риск без выгоды
        env = adb_env(Path('C:/example/adb'), 5038)
        self.assertEqual(env.get('HOME'), os.environ.get('HOME'))
        self.assertEqual(env.get('USERPROFILE'), os.environ.get('USERPROFILE'))

    @unittest.skipIf(os.name == 'nt', 'на Windows каталог намеренно не создаётся')
    def test_adb_environment_never_touches_process_env(self) -> None:
        # Подмена HOME всему процессу сломала бы Path.home() и platformdirs
        with tempfile.TemporaryDirectory() as temp_dir:
            fixer = self._fixer_with_data_dir(temp_dir)
            fixer._migrate_adb_home = lambda: None
            before = dict(os.environ)
            fixer._setup_adb_environment()
            self.assertEqual(dict(os.environ), before)

            self.assertTrue(fixer.adb_dot_android.is_dir())
            if os.name != 'nt':
                self.assertEqual(fixer.adb_home.stat().st_mode & 0o777, 0o700)

    def test_adb_server_port_is_not_the_default_5037(self) -> None:
        # Весь смысл изоляции: не драться с сервером пользователя и Android Studio
        self.assertNotEqual(DEFAULT_ADB_SERVER_PORT, 5037)

    def test_adb_home_migration_copies_existing_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fake_home = Path(temp_dir) / 'home'
            (fake_home / '.android').mkdir(parents=True)
            for name in ADB_HOME_FILES:
                (fake_home / '.android' / name).write_text(name, encoding='utf-8')

            fixer = self._fixer_with_data_dir(temp_dir)
            fixer.adb_dot_android.mkdir(parents=True)

            with mock.patch('src.android_time_fixer.Path.home', return_value=fake_home):
                fixer._migrate_adb_home()

            for name in ADB_HOME_FILES:
                migrated = fixer.adb_dot_android / name
                self.assertTrue(migrated.is_file(), name)
                self.assertEqual(migrated.read_text(encoding='utf-8'), name)
                if os.name != 'nt':
                    self.assertEqual(migrated.stat().st_mode & 0o777, 0o600)

    def test_adb_home_migration_does_not_overwrite(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fake_home = Path(temp_dir) / 'home'
            (fake_home / '.android').mkdir(parents=True)
            (fake_home / '.android' / 'adbkey').write_text('from-home', encoding='utf-8')

            fixer = self._fixer_with_data_dir(temp_dir)
            fixer.adb_dot_android.mkdir(parents=True)
            (fixer.adb_dot_android / 'adbkey').write_text('already-here', encoding='utf-8')

            with mock.patch('src.android_time_fixer.Path.home', return_value=fake_home):
                fixer._migrate_adb_home()

            self.assertEqual(
                (fixer.adb_dot_android / 'adbkey').read_text(encoding='utf-8'), 'already-here'
            )

    def test_adb_home_migration_survives_missing_source(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixer = self._fixer_with_data_dir(temp_dir)
            fixer.adb_dot_android.mkdir(parents=True)
            with mock.patch('src.android_time_fixer.Path.home',
                            return_value=Path(temp_dir) / 'nonexistent'):
                fixer._migrate_adb_home()  # не должно бросать

    def test_prompt_adb_port_does_not_persist_when_asked(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixer = self._fixer_with_data_dir(temp_dir)
            fixer.scan_port = DEFAULT_ADB_PORT
            saved = []
            fixer.save_scan_port = lambda port: saved.append(port)

            with mock.patch('builtins.input', return_value='37105'), \
                    contextlib.redirect_stdout(io.StringIO()):
                port = fixer.prompt_adb_port(persist=False)

            self.assertEqual(port, 37105)
            self.assertEqual(saved, [])

            with mock.patch('builtins.input', return_value='37105'), \
                    contextlib.redirect_stdout(io.StringIO()):
                fixer.prompt_adb_port(persist=True)
            self.assertEqual(saved, [37105])

    def test_load_adb_server_port_falls_back_on_garbage(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixer = self._fixer_with_data_dir(temp_dir)
            fixer.settings_file.write_text('{"adb_server_port": "не число"}', encoding='utf-8')
            self.assertEqual(fixer.load_adb_server_port(), DEFAULT_ADB_SERVER_PORT)

            fixer.settings_file.write_text('{"adb_server_port": "70000"}', encoding='utf-8')
            self.assertEqual(fixer.load_adb_server_port(), DEFAULT_ADB_SERVER_PORT)

            fixer.settings_file.write_text('{"adb_server_port": "5039"}', encoding='utf-8')
            self.assertEqual(fixer.load_adb_server_port(), 5039)

    # ──────────────────────────────────────────────────────────
    # Шаг 3: спаривание
    # ──────────────────────────────────────────────────────────

    def test_pairing_code_must_be_six_digits(self) -> None:
        self.assertTrue(AndroidTVTimeFixer.validate_pairing_code('123456'))
        self.assertTrue(AndroidTVTimeFixer.validate_pairing_code(' 123456 '))
        self.assertFalse(AndroidTVTimeFixer.validate_pairing_code('12345'))
        self.assertFalse(AndroidTVTimeFixer.validate_pairing_code('1234567'))
        self.assertFalse(AndroidTVTimeFixer.validate_pairing_code('12345a'))
        self.assertFalse(AndroidTVTimeFixer.validate_pairing_code(''))

    def _pairing_fixer(self, returncode: int, output: str):
        calls = []
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')

        def run_adb(args, timeout=15):
            calls.append(args)
            return returncode, output

        fixer._run_adb = run_adb
        return fixer, calls

    def test_pairing_succeeds_on_confirmed_output(self) -> None:
        fixer, calls = self._pairing_fixer(
            0, 'Successfully paired to 192.168.1.20:37105 [guid=adb-XYZ]'
        )
        with contextlib.redirect_stdout(io.StringIO()):
            fixer.pair_device('192.168.1.20:41234', '123456')
        self.assertEqual(calls, [['pair', '192.168.1.20:41234', '123456']])

    def test_pairing_fails_when_output_does_not_confirm(self) -> None:
        # adb умеет завершаться с нулевым кодом, ничего при этом не спарив
        fixer, _calls = self._pairing_fixer(0, 'Enter pairing code:')
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AndroidTVTimeFixerError):
                fixer.pair_device('192.168.1.20:41234', '123456')

    def test_pairing_reports_adb_failure(self) -> None:
        fixer, _calls = self._pairing_fixer(1, 'failed to connect to 192.168.1.20:41234')
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AndroidTVTimeFixerError) as ctx:
                fixer.pair_device('192.168.1.20:41234', '123456')
        self.assertIn('192.168.1.20:41234', str(ctx.exception))

    def test_pairing_rejects_bad_code_before_running_adb(self) -> None:
        fixer, calls = self._pairing_fixer(0, 'Successfully paired')
        with self.assertRaises(AndroidTVTimeFixerError):
            fixer.pair_device('192.168.1.20:41234', '12345')
        self.assertEqual(calls, [])

    # ──────────────────────────────────────────────────────────
    # Шаг 4: обнаружение по mDNS
    # ──────────────────────────────────────────────────────────

    def test_mdns_parses_adb_services_output(self) -> None:
        output = (
            'List of discovered mdns services\n'
            'adb-ABC123-xyz\t_adb-tls-connect._tcp\t192.168.1.20:37105\n'
            'adb-DEF456-uvw\t_adb-tls-pairing._tcp\t192.168.1.21:41234\n'
            'adb-GHI789-rst\t_adb-tls-connect._tcp\t192.168.1.5:44100\n'
            'мусорная строка без адреса\n'
            'adb-BAD-000\t_adb-tls-connect._tcp\tno-ip-here\n'
        )
        connect = AndroidTVTimeFixer._parse_mdns_services(output, '_adb-tls-connect._tcp')
        self.assertEqual(connect, ['192.168.1.20:37105', '192.168.1.5:44100'])

        pairing = AndroidTVTimeFixer._parse_mdns_services(output, '_adb-tls-pairing._tcp')
        self.assertEqual(pairing, ['192.168.1.21:41234'])

    def test_mdns_parser_survives_empty_and_broken_output(self) -> None:
        for output in ('', 'List of discovered mdns services\n', 'мусор\nещё мусор\n'):
            self.assertEqual(
                AndroidTVTimeFixer._parse_mdns_services(output, '_adb-tls-connect._tcp'), []
            )
        # Порт вне диапазона игнорируется
        self.assertEqual(
            AndroidTVTimeFixer._parse_mdns_services(
                'x\t_adb-tls-connect._tcp\t192.168.1.20:99999\n', '_adb-tls-connect._tcp'
            ),
            []
        )

    def test_mdns_falls_back_to_zeroconf_when_adb_backend_unavailable(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.mdns_available = lambda: False
        used = []

        def zeroconf_path(service, timeout):
            used.append(service)
            return ['192.168.1.9:37105']

        fixer._mdns_via_zeroconf = zeroconf_path
        self.assertEqual(fixer.mdns_discover('connect'), ['192.168.1.9:37105'])
        self.assertEqual(used, ['_adb-tls-connect._tcp'])

    def test_mdns_returns_empty_when_both_backends_unavailable(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.mdns_available = lambda: False
        fixer._mdns_via_zeroconf = lambda service, timeout: []
        self.assertEqual(fixer.mdns_discover('pairing'), [])

    def test_mdns_results_match_the_scanner_contract(self) -> None:
        # Результаты mDNS и сканера должны быть взаимозаменяемы
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')
        fixer.mdns_available = lambda: True
        fixer._run_adb = lambda args, timeout=15: (
            0,
            'adb-A\t_adb-tls-connect._tcp\t192.168.1.112:37105\n'
            'adb-B\t_adb-tls-connect._tcp\t192.168.1.9:37106\n'
        )

        result = fixer.mdns_discover('connect')
        # Отсортировано по адресу, как и у сканера
        self.assertEqual(result, ['192.168.1.9:37106', '192.168.1.112:37105'])
        for entry in result:
            self.assertTrue(AndroidTVTimeFixer.validate_ip(entry))
            host, port = AndroidTVTimeFixer.parse_ip_port(entry)
            self.assertTrue(1 <= port <= 65535)
            self.assertNotEqual(host, '')

    def test_mdns_available_rejects_unavailable_daemon(self) -> None:
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('test')

        fixer._run_adb = lambda args, timeout=15: (0, 'mdns daemon version [Openscreen 0.0.0]')
        self.assertTrue(fixer.mdns_available())

        fixer._run_adb = lambda args, timeout=15: (0, 'ERROR: mdns daemon unavailable')
        self.assertFalse(fixer.mdns_available())

        fixer._run_adb = lambda args, timeout=15: (1, '')
        self.assertFalse(fixer.mdns_available())

        def boom(args, timeout=15):
            raise OSError('adb missing')

        fixer._run_adb = boom
        self.assertFalse(fixer.mdns_available())


if __name__ == '__main__':
    unittest.main()

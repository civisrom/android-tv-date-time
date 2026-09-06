import contextlib
import io
import logging
import socket
import subprocess
import threading
import unittest
from unittest import mock

from src.android_time_fixer import (
    AndroidTVTimeFixer, AndroidTVTimeFixerError, PlatformToolsTransport,
    UsbAdbDevice, adb_env, parse_usb_devices, read_adb_device_list,
)


def record(serial='TV123', transport=7, kind='USB', state='DEVICE', model='Android_TV'):
    return ('device {\n'
            f'  serial: "{serial}"\n  state: {state}\n  model: "{model}"\n'
            f'  connection_type: {kind}\n  transport_id: {transport}\n}}\n')


class UsbDiscoveryTests(unittest.TestCase):
    def test_windows_usb_without_bus_address_and_duplicate_serials(self):
        devices = parse_usb_devices(record() + record(transport=9))
        self.assertEqual([d.target for d in devices], ['usb:7', 'usb:9'])

    def test_network_and_emulator_are_excluded_by_type_not_serial(self):
        devices = parse_usb_devices(record('192.168.1.2:5555') + record('TV123', kind='SOCKET'))
        self.assertEqual(len(devices), 1)
        self.assertEqual(devices[0].serial, '192.168.1.2:5555')

    def test_escaped_strings_and_all_unready_states(self):
        for state in ('UNAUTHORIZED', 'NOPERMISSION', 'OFFLINE', 'BOOTLOADER', 'RECOVERY'):
            device = parse_usb_devices(record(state=state, model=r'TV\"x\n}'))[0]
            self.assertEqual(device.model, 'TV"x\n}')
            self.assertEqual(device.state, state)

    def test_empty_and_malformed_lists(self):
        self.assertEqual(parse_usb_devices(''), [])
        for data in ('garbage', record(transport=0), record()[:-3]):
            with self.assertRaises(ValueError):
                parse_usb_devices(data)

    def test_utf8_octal_escapes_preserve_device_identity(self):
        device = parse_usb_devices(record(serial=r'\320\242\320\2221', model=r'\320\242\320\222'))[0]
        self.assertEqual(device.serial, 'ТВ1')
        self.assertEqual(device.model, 'ТВ')

    def test_snapshot_reads_fragmented_frame_and_closes_tracking_connection(self):
        self._serve_snapshot(b'OKAY', record().encode(), expected=record())

    def test_server_failure_is_not_an_empty_device_list(self):
        self._serve_snapshot(b'FAIL', b'unknown host service', error=RuntimeError)

    def test_truncated_frame_fails(self):
        self._serve_snapshot(b'OKAY', b'ab', declared_size=10, error=ConnectionError)

    def _serve_snapshot(self, status, payload, expected=None, error=None, declared_size=None):
        received = []
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            listener.settimeout(3)

            def serve():
                with listener.accept()[0] as peer:
                    peer.settimeout(3)
                    stream = peer.makefile('rb')
                    size = int(stream.read(4), 16)
                    received.append(stream.read(size))
                    size = len(payload) if declared_size is None else declared_size
                    for byte in status + f'{size:04x}'.encode() + payload:
                        peer.sendall(bytes([byte]))
                    if declared_size is None:
                        received.append(peer.recv(1))

            thread = threading.Thread(target=serve, daemon=True)
            thread.start()
            if error:
                with self.assertRaises(error):
                    read_adb_device_list(listener.getsockname()[1])
            else:
                self.assertEqual(read_adb_device_list(listener.getsockname()[1]), expected)
            thread.join(4)
            self.assertFalse(thread.is_alive())
        self.assertEqual(received[0], b'host:track-devices-proto-text')
        if declared_size is None:
            self.assertEqual(received[1], b'')

    def test_inherited_server_overrides_cannot_redirect_usb_or_cleanup(self):
        with mock.patch.dict('os.environ', {'ADB_SERVER_SOCKET': 'tcp:evil:5037',
                                         'ANDROID_ADB_SERVER_ADDRESS': 'evil',
                                         'ANDROID_SERIAL': 'another-device'}):
            env = adb_env(None, 5040)
        self.assertNotIn('ADB_SERVER_SOCKET', env)
        self.assertNotIn('ANDROID_SERIAL', env)
        self.assertNotIn('ANDROID_ADB_SERVER_ADDRESS', env)
        self.assertEqual(env['ANDROID_ADB_SERVER_PORT'], '5040')


class UsbConnectionTests(unittest.TestCase):
    def fixer(self, state='DEVICE'):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('usb-test')
        fixer.device = None
        fixer.connected_ip = None
        fixer.process_manager = mock.Mock(device_ip=None)
        fixer.adb_env = {'ANDROID_ADB_SERVER_PORT': '5038'}
        fixer.get_adb_path = mock.Mock(return_value='adb')
        fixer.usb_devices = mock.Mock(return_value=[UsbAdbDevice('TV123', 7, state)])
        return fixer

    def test_usb_connect_probes_exact_transport_without_tcp_or_keys(self):
        fixer = self.fixer()
        def runner(args, **kwargs):
            self.assertEqual(args[:3], ['adb', '-t', '7'])
            return subprocess.CompletedProcess(args, 0, 'TV123\n' if args[3] == 'get-serialno' else 'androidtvtimefixer\n')
        with mock.patch('subprocess.run', side_effect=runner), contextlib.redirect_stdout(io.StringIO()):
            fixer.connect('usb:7')
        self.assertEqual(fixer.connected_ip, 'usb:7')
        self.assertIsNone(fixer.process_manager.device_ip)

    def test_unready_or_missing_usb_never_executes_shell(self):
        for state in ('UNAUTHORIZED', 'NOPERMISSION', 'OFFLINE', 'RECOVERY', 'AUTHORIZING'):
            fixer = self.fixer(state)
            with mock.patch('subprocess.run') as runner, self.assertRaises(AndroidTVTimeFixerError):
                fixer.connect_usb('usb:7')
            runner.assert_not_called()
        fixer = self.fixer()
        with self.assertRaises(AndroidTVTimeFixerError):
            fixer.connect_usb('usb:8')

    def test_failed_probe_does_not_publish_connection(self):
        fixer = self.fixer()
        with mock.patch.object(PlatformToolsTransport, 'shell', return_value='wrong'), self.assertRaises(AndroidTVTimeFixerError):
            fixer.connect_usb('usb:7')
        self.assertIsNone(fixer.device)
        self.assertIsNone(fixer.connected_ip)

    def test_usb_close_never_disconnects_tcp_or_resets_daemon(self):
        runner = mock.Mock()
        transport = PlatformToolsTransport('adb', 'TV123', runner=runner, transport_id=7)
        transport.close()
        transport.close()
        with self.assertRaises(AndroidTVTimeFixerError):
            transport.shell('reboot')
        runner.assert_not_called()

    def test_reassigned_transport_id_cannot_receive_a_command(self):
        runner = mock.Mock(return_value=subprocess.CompletedProcess([], 0, 'OTHER_TV\n'))
        transport = PlatformToolsTransport('adb', 'TV123', runner=runner, transport_id=7)
        with self.assertRaises(AndroidTVTimeFixerError):
            transport.shell('settings put global ntp_server ru.pool.ntp.org')
        self.assertEqual(runner.call_count, 1)

    def test_usb_selection_can_refresh_after_authorization(self):
        fixer = self.fixer('UNAUTHORIZED')
        with mock.patch('builtins.input', side_effect=['r', '1']), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(fixer.select_usb_device(), 'usb:7')
        self.assertEqual(fixer.usb_devices.call_count, 2)

    def test_usb_is_never_persisted_as_last_ip(self):
        fixer = self.fixer()
        fixer._save_setting = mock.Mock()
        self.assertFalse(fixer.save_last_ip('usb:7'))
        fixer._save_setting.assert_not_called()

    def test_target_validation_preserves_network_validation(self):
        for value in ('usb:7', '192.168.1.2:5555'):
            self.assertTrue(AndroidTVTimeFixer.validate_device_target(value))
        for value in ('usb:0', 'usb:-1', 'usb:1;reboot', 'usb:TV', '192.168.1.2:0'):
            self.assertFalse(AndroidTVTimeFixer.validate_device_target(value))
        self.assertFalse(AndroidTVTimeFixer.validate_ip('usb:7'))

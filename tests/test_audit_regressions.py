import contextlib
from dataclasses import replace
import datetime
import io
import json
import logging
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
import tomllib
import unittest
from unittest import mock

from src import android_time_fixer as app
from src import network_check
from console_cancel import cancellable_connect
from device_time_check import TimeCheck
from device_time_settings import TimeSnapshotStore, apply_time_settings, device_identity, read_time_settings
from time_console import _profiles, diagnostic_report, print_clock_check
from tests.test_device_time_settings import TimeDevice


class AuditRegressions(unittest.TestCase):
    def fixer(self):
        fixer = object.__new__(app.AndroidTVTimeFixer)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        fixer.data_dir = Path(directory.name)
        fixer.logger = logging.getLogger('audit-test')
        fixer.device = TimeDevice()
        fixer.connected_ip = '192.0.2.1:5555'
        return fixer

    def test_mdns_auto_connect_is_disabled_only_for_application_server(self):
        with mock.patch.dict(os.environ, {'ADB_MDNS_AUTO_CONNECT': 'all'}):
            environment = app.adb_env(None, 5058)
            self.assertEqual(environment['ADB_MDNS_AUTO_CONNECT'], '0')
            self.assertEqual(os.environ['ADB_MDNS_AUTO_CONNECT'], 'all')

    def test_cancel_interrupts_real_blocked_adb_auth_without_waiting_for_socket_timeout(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            accepted = threading.Event()
            def serve():
                peer, _ = listener.accept()
                with peer:
                    peer.recv(4096)
                    accepted.set()
                    peer.recv(4096)
            server = threading.Thread(target=serve, daemon=True)
            server.start()
            device = app.AdbDeviceTcp('127.0.0.1', listener.getsockname()[1], default_transport_timeout_s=9)
            started = time.monotonic()
            with mock.patch('console_cancel.cancel_requested', side_effect=accepted.is_set), self.assertRaises(KeyboardInterrupt):
                cancellable_connect(device, rsa_keys=[], auth_timeout_s=15)
            server.join(1)
            self.assertLess(time.monotonic() - started, 2)
            self.assertFalse(server.is_alive())
            self.assertIsNone(device._io_manager._transport._connection)

    def test_successful_connection_is_not_closed_by_worker_completion_race(self):
        device = mock.Mock()
        device.connect.return_value = True
        with mock.patch('console_cancel.cancel_requested', return_value=False):
            for _ in range(30):
                self.assertTrue(cancellable_connect(device))
        device.close.assert_not_called()

    def test_unstarted_network_probes_are_counted_separately(self):
        with mock.patch.object(network_check, '_PROBE_SLOTS', threading.BoundedSemaphore(0)), \
                mock.patch.object(network_check, 'HTTPS_URLS', ('https://one.test',)), \
                mock.patch.object(network_check, 'NTP_HOSTS', ('one.test', 'two.test', 'three.test')):
            result = network_check.check_network()
        self.assertEqual((result.https_total, result.ntp_total), (1, 3))
        self.assertEqual((result.https_skipped, result.ntp_skipped), (1, 3))
        self.assertEqual((result.https_ok, result.ntp_ok), (0, 0))

    def test_broken_interface_address_does_not_hide_valid_lan(self):
        from types import SimpleNamespace as Item
        with mock.patch.object(network_check.psutil, 'net_if_stats', return_value={
                'eth0': Item(isup=True), 'docker0': Item(isup=True)}), \
                mock.patch.object(network_check.psutil, 'net_if_addrs', return_value={
                    'docker0': [Item(family=socket.AF_INET, address='172.17.0.1')],
                    'eth0': [Item(family=socket.AF_INET, address='broken'),
                             Item(family=socket.AF_INET, address='192.168.1.2')]}):
            self.assertTrue(network_check._probe_local_network(None, 0, threading.Event()))

    def test_mdns_omits_unusable_link_local_but_keeps_scoped_ipv6(self):
        output = '\n'.join('tv _adb-tls-connect._tcp ' + address for address in (
            '[fe80::1]:1234', '[fe80::1%eth0]:1234', '192.168.1.2:1234'))
        self.assertEqual(app.AndroidTVTimeFixer._parse_mdns_services(output, '_adb-tls-connect._tcp'),
                         ['[fe80::1%eth0]:1234', '192.168.1.2:1234'])

    def test_wireless_manual_address_requires_actual_port_and_validates_before_code(self):
        fixer = self.fixer()
        fixer.mdns_discover = mock.Mock(return_value=[])
        for answers, expected in ((['192.168.1.2', '45678'], '192.168.1.2:45678'),
                                  (['192.168.1.2', ''], '')):
            with mock.patch('builtins.input', side_effect=answers), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(fixer._pick_wireless_address('pairing'), expected)
        with mock.patch('builtins.input', return_value='bad-address') as read, \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(app.AndroidTVTimeFixerError):
            fixer._pick_wireless_address('pairing')
        read.assert_called_once()

    def test_pairing_protocol_error_is_explained_without_adb_prompt_or_code(self):
        fixer = self.fixer()
        fixer._run_adb = mock.Mock(return_value=(1, "Enter pairing code: error: protocol fault: Success 123456"))
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(app.AndroidTVTimeFixerError) as error:
            fixer.pair_device('192.168.1.2:40000', '123456')
        self.assertEqual(str(error.exception), app.locales.get('pairing_retry_hint', ip='192.168.1.2:40000'))
        self.assertNotIn('123456', str(error.exception))

    def test_profile_name_and_zone_rejected_before_confirmation_without_leaving_menu(self):
        for answers in (['2', '', '0'], ['2', 'profile', '', '', '', 'Mars/Olympus_Mons', '0']):
            fixer = self.fixer()
            with mock.patch('builtins.input', side_effect=answers) as read, contextlib.redirect_stdout(io.StringIO()):
                _profiles(fixer)
            self.assertEqual(read.call_count, len(answers))
            self.assertFalse((fixer.data_dir / 'time-profiles.json').exists())
            self.assertFalse(any(command.startswith('settings put') for command in fixer.device.commands))

    def test_empty_firmware_timezone_does_not_block_snapshot(self):
        fixer = self.fixer()
        fixer.device.props['persist.sys.timezone'] = ''
        store = TimeSnapshotStore(fixer.data_dir, fixer._atomic_write_json)
        self.assertEqual(store.save(fixer.device)['settings']['timezone'], '')

    def test_automatic_zone_detection_may_replace_confirmed_manual_zone(self):
        device = TimeDevice()
        device.globals['auto_time_zone'] = '0'
        before = read_time_settings(device)
        desired = replace(before, timezone='UTC', auto_time_zone='1', effective_auto_zone=True)
        shell = device.shell
        def automatic(command):
            output = shell(command)
            if command.startswith('cmd time_zone_detector set_auto_detection_enabled true'):
                device.props['persist.sys.timezone'] = 'Europe/London'
            return output
        device.shell = automatic
        actual = apply_time_settings(device, desired, device_identity(device))
        self.assertEqual(actual.timezone, 'Europe/London')
        self.assertEqual(device.props['persist.sys.timezone'], 'Europe/London')
        self.assertEqual(device.globals['auto_time_zone'], '1')

    def test_diagnostic_version_matches_application_and_package(self):
        fixer = self.fixer()
        fixer.device = None
        package = tomllib.loads(Path('pyproject.toml').read_text(encoding='utf-8'))['tool']['poetry']['version']
        self.assertEqual(diagnostic_report(fixer)['app_version'], app.APP_VERSION)
        self.assertEqual(app.APP_VERSION, package)

    def test_numeric_saved_ports_are_used_but_booleans_are_not_ports(self):
        fixer = self.fixer()
        fixer.settings_file = fixer.data_dir / 'settings.json'
        fixer.settings_file.write_text(json.dumps({'scan_port': 5556, 'adb_server_port': 5049}))
        self.assertEqual(fixer.load_scan_port(), 5556)
        self.assertEqual(fixer.load_adb_server_port(), 5049)
        fixer.settings_file.write_text(json.dumps({'scan_port': True, 'adb_server_port': True}))
        self.assertEqual(fixer.load_scan_port(), app.DEFAULT_ADB_PORT)
        self.assertEqual(fixer.load_adb_server_port(), app.DEFAULT_ADB_SERVER_PORT)

    def test_etc_utc_is_not_a_geographical_region(self):
        fixer = self.fixer()
        with mock.patch('builtins.open', mock.mock_open(read_data='Etc/UTC\n')):
            self.assertEqual(fixer._detect_user_region(), ([], []))

    def test_list_cancel_has_no_invalid_input_message(self):
        fixer = self.fixer()
        for answer in ('q', '0', ''):
            output = io.StringIO()
            with mock.patch('builtins.input', return_value=answer), contextlib.redirect_stdout(output):
                self.assertEqual(fixer._select_from_list(['192.0.2.1:5555'], 'enter_device_number'), '')
            self.assertNotIn(app.locales.get('invalid_input'), output.getvalue())

    def test_monitor_output_is_timestamped_compact_and_localized(self):
        previous = app.locales.current_language
        try:
            app.set_language('ru')
            check = TimeCheck('MATCH', -.556, .541, 'time.example', 1,
                              datetime.datetime.now(datetime.timezone.utc).isoformat(), time.monotonic())
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                print_clock_check(check, monitoring=True)
            self.assertEqual(len(output.getvalue().splitlines()), 1)
            self.assertIn('-0,6', output.getvalue())
            self.assertRegex(output.getvalue(), r'\d\d:\d\d:\d\d')
            self.assertNotIn(app.locales.get('state_source_unconfirmed'), output.getvalue())
        finally:
            app.locales.current_language = previous

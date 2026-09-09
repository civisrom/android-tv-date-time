import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from src.android_time_fixer import AndroidTVTimeFixer, AndroidTVTimeFixerError, locales


class SettingsReliabilityTests(unittest.TestCase):
    def fixer(self, directory):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = mock.Mock()
        fixer.data_dir = Path(directory)
        fixer.servers_file = Path(directory) / 'saved_servers.json'
        fixer.settings_file = Path(directory) / 'settings.json'
        fixer.saved_servers = {'favorite_servers': ['time.google.com', 'pool.ntp.org'], 'custom_servers': []}
        return fixer

    def test_dns_labels_and_total_length_match_android_validation(self):
        longest = '.'.join(['a' * 63] * 3 + ['a' * 61])
        for value in ['time.-pool.org', 'time.pool-.org', 'a' * 64 + '.org',
                      'time.' + 'a' * 64, longest + 'a', '١٢٧.٠.٠.١', 'https://time.google.com']:
            with self.subTest(value=value):
                self.assertFalse(AndroidTVTimeFixer.validate_ntp_server(value))
        for value in [longest, 'time.pool-1.org', ' time.google.com ', '192.0.2.1']:
            self.assertTrue(AndroidTVTimeFixer.validate_ntp_server(value), value)

    def test_invalid_port_cannot_be_replaced_or_reused(self):
        for value in ['+5555', ' 5555', '５５５５', '000001', '0', '65536', 'abc']:
            address = '192.0.2.1:' + value
            with self.subTest(address=address):
                self.assertFalse(AndroidTVTimeFixer.validate_ip(address))
                with self.assertRaises(AndroidTVTimeFixerError):
                    AndroidTVTimeFixer.parse_ip_port(address)
                fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
                fixer.connect = mock.Mock()
                with self.assertRaises(AndroidTVTimeFixerError):
                    fixer.connect_or_reuse(address)
                fixer.connect.assert_not_called()

    def test_remove_favorite_preserves_order_when_actual_save_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            fixer = self.fixer(directory)
            original = fixer.saved_servers
            with mock.patch.object(fixer, '_atomic_write_json', side_effect=OSError('disk full')):
                self.assertFalse(fixer.remove_from_favorites('time.google.com'))
            self.assertIs(fixer.saved_servers, original)
            self.assertEqual(fixer.saved_servers['favorite_servers'], ['time.google.com', 'pool.ntp.org'])

    def test_remove_favorite_is_persisted_and_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            fixer = self.fixer(directory)
            self.assertTrue(fixer.remove_from_favorites('time.google.com'))
            self.assertTrue(fixer.remove_from_favorites('time.google.com'))
            self.assertEqual(fixer.load_saved_servers()['favorite_servers'], ['pool.ntp.org'])

    def test_old_invalid_address_does_not_hide_other_favorites_or_rewrite_file(self):
        with tempfile.TemporaryDirectory() as directory:
            fixer = self.fixer(directory)
            text = json.dumps({'favorite_servers': ['time.-pool.org', 'time.google.com']})
            fixer.servers_file.write_text(text)
            self.assertEqual(fixer.load_saved_servers()['favorite_servers'], ['time.google.com'])
            self.assertEqual(fixer.servers_file.read_text(), text)

    def test_export_import_round_trip_preserves_ports(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            fixer = self.fixer(directory)
            fixer.settings_file.write_text(json.dumps({'language': 'en', 'last_device_ip': '192.0.2.1',
                                                       'scan_port': '5556', 'adb_server_port': '5039'}))
            original_language = locales.current_language
            try:
                fixer.export_settings()
                fixer.settings_file.write_text('{}')
                fixer.import_settings(str(Path(directory) / 'backup.json'))
                self.assertEqual(fixer.load_scan_port(), 5556)
                self.assertEqual(fixer.load_adb_server_port(), 5039)
                self.assertEqual(fixer.scan_port, 5556)
            finally:
                locales.current_language = original_language

    def test_import_rejects_oversized_or_unknown_backup_without_writing(self):
        for payload in [' ' * (1024 * 1024 + 1), '{"version":"999"}', '{"scan_port":true}', '{"adb_server_port":5037}']:
            with self.subTest(size=len(payload)), tempfile.TemporaryDirectory() as directory:
                fixer = self.fixer(directory)
                path = Path(directory) / 'backup.json'
                path.write_text(payload)
                fixer.save_servers = mock.Mock()
                fixer._save_settings = mock.Mock()
                with contextlib.redirect_stdout(io.StringIO()):
                    fixer.import_settings(str(path))
                fixer.save_servers.assert_not_called()
                fixer._save_settings.assert_not_called()

    def test_failure_of_import_rollback_is_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            fixer = self.fixer(directory)
            original = fixer.saved_servers
            path = Path(directory) / 'backup.json'
            path.write_text('{"saved_servers":{"favorite_servers":["time.cloudflare.com"]}}')
            fixer.save_servers = mock.Mock(side_effect=[True, False])
            fixer._save_settings = mock.Mock(return_value=False)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                fixer.import_settings(str(path))
            self.assertIn(locales.get('import_rollback_failed'), output.getvalue())
            self.assertIs(fixer.saved_servers, original)

    def test_device_clock_is_explicit_utc_and_not_pc_local_time(self):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.device = mock.Mock()
        fixer.device.shell.return_value = '1767225600'
        output = io.StringIO()
        with mock.patch('src.android_time_fixer.time.monotonic', side_effect=[100, 100.2]), \
                mock.patch('src.android_time_fixer.time.time', return_value=1767225600), \
                contextlib.redirect_stdout(output):
            fixer.show_device_time()
        self.assertIn(locales.get('device_time', time='2026-01-01 00:00:00'), output.getvalue())
        self.assertIn('UTC', output.getvalue())
        self.assertIn(locales.get('time_in_sync'), output.getvalue())

    def test_slow_clock_read_does_not_claim_a_precise_match(self):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.device = mock.Mock()
        fixer.device.shell.return_value = '1767225600'
        output = io.StringIO()
        with mock.patch('src.android_time_fixer.time.monotonic', side_effect=[100, 240]), \
                mock.patch('src.android_time_fixer.time.time', return_value=1767225600), \
                contextlib.redirect_stdout(output):
            fixer.show_device_time()
        self.assertIn(locales.get('time_comparison_uncertain'), output.getvalue())

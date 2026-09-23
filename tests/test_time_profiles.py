import contextlib
from dataclasses import replace
import io
import json
import logging
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from src.android_time_fixer import AndroidTVTimeFixer, AndroidTVTimeFixerError, PlatformToolsTransport, locales
from device_time_check import TimeCheck
from device_time_settings import DeviceStateError, device_identity, read_time_settings
from time_console import advanced_time_action, diagnostic_report, snapshot_store
from time_profiles import TimeProfileStore, build_ntp_configuration, parse_ntp_configuration
from tests.test_device_time_settings import TimeDevice


class TimeProfileTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        self.fixer.device = TimeDevice()
        self.fixer.device.globals['ntp_server'] = 'old.example'
        self.fixer.data_dir = self.directory
        self.fixer.connected_ip = '[2001:db8::25]:45555'
        self.fixer.logger = logging.getLogger('time-profile-test')
        self.fixer.verify_ntp_server = mock.Mock(return_value=True)
        self.fixer.connect_or_reuse = mock.Mock()
        self.store = TimeProfileStore(self.directory, self.fixer._atomic_write_json)

    def action(self, action, answers):
        output = io.StringIO()
        with mock.patch('builtins.input', side_effect=answers), contextlib.redirect_stdout(output), \
                mock.patch('time_console.show_time_status'):
            advanced_time_action(self.fixer, action)
        return output.getvalue()

    def test_multi_ntp_uses_android14_uris_and_ipv6_brackets_but_old_android_rejects_it(self):
        validate = self.fixer.validate_ntp_server
        hosts = ['one.example', '192.0.2.1', '2001:db8::123', 'two.example']
        raw = build_ntp_configuration(hosts, 34, validate)
        self.assertEqual('ntp://one.example|ntp://192.0.2.1|ntp://[2001:db8::123]|ntp://two.example', raw)
        self.assertEqual([(host, 123) for host in hosts], parse_ntp_configuration(raw, validate))
        self.assertEqual('one.example', build_ntp_configuration(['one.example'], 29, validate))
        for invalid in (hosts, ['one.example', 'one.example'], hosts + ['five.example'], ['ntp://one.example'], ['one.example:1123']):
            with self.assertRaises(DeviceStateError):
                build_ntp_configuration(invalid, 29 if invalid == hosts else 34, validate)

    def test_profile_preserves_an_existing_empty_ntp_setting_without_probing_a_host(self):
        state = replace(read_time_settings(self.fixer.device), ntp_server='')
        self.store.save('Empty', self.fixer.connected_ip, device_identity(self.fixer.device), state)
        self.action('p', ['3', '1', 'yes', '0'])
        self.assertEqual(state, read_time_settings(self.fixer.device))
        self.fixer.verify_ntp_server.assert_not_called()

    def test_busy_snapshot_storage_blocks_single_ntp_mutation(self):
        before = read_time_settings(self.fixer.device)
        with mock.patch('device_time_settings.time_store_lock', side_effect=TimeoutError):
            with self.assertRaises(AndroidTVTimeFixerError):
                self.fixer._write_ntp_setting('new.example')
        self.assertEqual(before, read_time_settings(self.fixer.device))

    def test_existing_custom_port_uri_parses_without_losing_its_original_value(self):
        raw = 'ntp://[2001:db8::1]:1123|ntp://old.example:123'
        self.assertEqual([('2001:db8::1', 1123), ('old.example', 123)],
                         parse_ntp_configuration(raw, self.fixer.validate_ntp_server))
        for raw in ('ntp://host:0', 'ntp://user:password@host', 'ntp://host/path', 'ntp://host#fragment', 'ntp://[::1]:65536'):
            with self.assertRaises(DeviceStateError):
                parse_ntp_configuration(raw, self.fixer.validate_ntp_server)

    def test_profile_creation_edits_configuration_without_mutating_device(self):
        before = read_time_settings(self.fixer.device)
        self.action('p', ['2', 'Living room', 'one.example ::1', '0', '0', 'UTC', 'yes', '0'])
        self.assertEqual(before, read_time_settings(self.fixer.device))
        profile = self.store.load()[0]
        self.assertEqual('ntp://one.example|ntp://[::1]', profile['settings']['ntp_server'])
        self.assertEqual('UTC', profile['settings']['timezone'])
        self.assertEqual(self.fixer.connected_ip, profile['target'])
        self.assertNotIn(self.fixer.device.android_id, self.store.path.read_text())

    def test_profile_apply_cancel_apply_and_delete_are_explicit_and_persisted(self):
        before = read_time_settings(self.fixer.device)
        desired = replace(before, ntp_server='new.example', auto_time='0', auto_time_zone='0',
                          timezone='UTC', effective_auto_zone=False)
        self.store.save('TV', self.fixer.connected_ip, device_identity(self.fixer.device), desired)
        printed = self.action('p', ['3', '1', 'no', '0'])
        self.assertIn('old.example', printed)
        self.assertIn('new.example', printed)
        self.assertEqual(before, read_time_settings(self.fixer.device))
        self.action('p', ['3', '1', 'yes', '0'])
        self.assertEqual(desired, read_time_settings(self.fixer.device))
        snapshot = snapshot_store(self.fixer).load(device_identity(self.fixer.device))
        self.assertEqual(before.ntp_server, snapshot['settings']['ntp_server'])
        self.action('p', ['4', '1', 'yes', '0'])
        self.assertEqual([], TimeProfileStore(self.directory, self.fixer._atomic_write_json).load())

    def test_profile_cannot_apply_to_a_different_device_reusing_the_saved_address(self):
        state = replace(read_time_settings(self.fixer.device), ntp_server='new.example')
        self.store.save('TV', self.fixer.connected_ip, device_identity(self.fixer.device), state)
        self.fixer.device.android_id = 'fedcba9876543210'
        self.fixer.device.commands.clear()
        output = self.action('p', ['3', '1'])
        self.assertIn(locales.get('state_identity_changed'), output)
        self.assertFalse(any(command.startswith('settings put') for command in self.fixer.device.commands))

    def test_target_change_while_editing_cannot_bind_old_settings_to_new_identity(self):
        answers = iter(['2', 'TV', '', '', '', '', 'yes'])

        def answer(prompt):
            value = next(answers)
            if value == 'yes':
                self.fixer.device.android_id = 'fedcba9876543210'
            return value

        with mock.patch('builtins.input', side_effect=answer), contextlib.redirect_stdout(io.StringIO()):
            advanced_time_action(self.fixer, 'p')
        self.assertFalse(self.store.path.exists())

    def test_profile_limit_never_creates_an_unreadable_file(self):
        state, identity = read_time_settings(self.fixer.device), device_identity(self.fixer.device)
        for index in range(64):
            self.store.save(str(index), self.fixer.connected_ip, identity, state)
        original = self.store.path.read_bytes()
        with self.assertRaises(DeviceStateError):
            self.store.save('65th', self.fixer.connected_ip, identity, state)
        self.assertEqual(original, self.store.path.read_bytes())
        self.assertEqual(64, len(self.store.load()))
        large = replace(state, ntp_server='😀' * 4096)
        failed = False
        for index in range(64):
            before = self.store.path.read_bytes()
            try:
                self.store.save(str(index), self.fixer.connected_ip, identity, large, replace=True)
            except DeviceStateError:
                self.assertEqual(before, self.store.path.read_bytes())
                failed = True
                break
        self.assertTrue(failed)
        self.assertEqual(64, len(self.store.load()))

    def test_manual_snapshot_confirmation_cannot_overwrite_a_different_device_baseline(self):
        original_identity = device_identity(self.fixer.device)
        def swap(_prompt):
            self.fixer.device.android_id = 'fedcba9876543210'
            return 'yes'
        with mock.patch('builtins.input', side_effect=swap), contextlib.redirect_stdout(io.StringIO()):
            advanced_time_action(self.fixer, 's')
        self.assertIsNone(snapshot_store(self.fixer).load(original_identity))
        self.assertIsNone(snapshot_store(self.fixer).load(device_identity(self.fixer.device)))

    def test_device_reusing_an_active_address_cannot_receive_single_ntp_write(self):
        self.fixer._remember_time_identity()
        self.fixer.device.android_id = 'fedcba9876543210'
        before = read_time_settings(self.fixer.device)
        with self.assertRaises(AndroidTVTimeFixerError):
            self.fixer._write_ntp_setting('new.example')
        self.assertEqual(before, read_time_settings(self.fixer.device))
        self.assertFalse(any(command.startswith('settings put') for command in self.fixer.device.commands))

    def test_multiple_ntp_preview_cannot_be_applied_after_target_identity_changes(self):
        before = read_time_settings(self.fixer.device)
        answers = iter(['new.example ::1', 'yes'])
        def answer(_prompt):
            value = next(answers)
            if value == 'yes':
                self.fixer.device.android_id = 'fedcba9876543210'
            return value
        with mock.patch('builtins.input', side_effect=answer), contextlib.redirect_stdout(io.StringIO()):
            advanced_time_action(self.fixer, 'm')
        self.assertEqual(before, read_time_settings(self.fixer.device))

    def test_failed_baseline_persistence_blocks_single_ntp_mutation(self):
        before = read_time_settings(self.fixer.device)
        self.fixer._atomic_write_json = mock.Mock(side_effect=PermissionError('read-only directory'))
        with self.assertRaises(AndroidTVTimeFixerError):
            self.fixer._write_ntp_setting('new.example')
        self.assertEqual(before, read_time_settings(self.fixer.device))
        self.assertFalse(any(command.startswith('settings put') for command in self.fixer.device.commands))

    def test_snapshot_restore_cancel_does_not_change_external_settings(self):
        snapshot_store(self.fixer).save(self.fixer.device)
        self.fixer.device.globals['ntp_server'] = 'external.example'
        self.action('b', ['no'])
        self.assertEqual('external.example', self.fixer.device.globals['ntp_server'])

    def test_diagnostic_export_is_an_allowlist_even_with_sensitive_device_content(self):
        secret = 'SENSITIVE_MARKER'
        self.fixer.connected_ip = secret
        self.fixer.device.props['ro.serialno'] = secret
        self.fixer.device.props['ro.product.model'] = secret
        self.fixer.device.globals['ntp_server'] = secret.lower() + '.example'
        check = TimeCheck('MATCH', 0.1, 0.5, secret, 1790000000, secret, 1.0)
        with mock.patch('time_console.clock_check', return_value=check), \
                mock.patch('device_time_check.time.monotonic', return_value=1.1):
            report = diagnostic_report(self.fixer)
        rendered = json.dumps(report)
        self.assertNotIn(secret, rendered)
        self.assertNotIn(secret.lower(), rendered)
        self.assertEqual('MATCH', report['clock_status'])
        self.assertEqual('unconfirmed', report['system_clock_source'])
        self.assertEqual({'schema_version', 'app_version', 'platform', 'exported_at', 'transport_selected', 'android_api',
                          'read_failure',
                          'settings_read', 'automatic_time', 'automatic_timezone', 'ntp_configuration', 'clock_status',
                          'clock_difference_seconds', 'clock_uncertainty_seconds', 'system_clock_source'}, set(report))

    def test_diagnostic_read_error_does_not_export_exception_text(self):
        self.fixer.device.shell = mock.Mock(side_effect=RuntimeError('SENSITIVE_PATH_AND_TOKEN'))
        report = diagnostic_report(self.fixer)
        self.assertFalse(report['settings_read'])
        self.assertNotIn('SENSITIVE', json.dumps(report))

    def test_monitor_stop_returns_to_menu_without_device_writes(self):
        with mock.patch('time_console.monitor_device_time', side_effect=KeyboardInterrupt):
            output = self.action('v', [])
        self.assertIn(locales.get('state_monitor_stopped'), output)
        self.assertFalse(any(command.startswith('settings put') for command in self.fixer.device.commands))

    def test_platform_shell_budget_covers_usb_identity_guard_and_actual_command(self):
        runner = mock.Mock(side_effect=[mock.Mock(returncode=0, stdout='serial\n'), mock.Mock(returncode=0, stdout='100\n')])
        client = PlatformToolsTransport('adb', 'serial', runner=runner, transport_id=4)
        with mock.patch('src.android_time_fixer.time.monotonic', side_effect=[10.0, 10.2, 10.7]):
            self.assertEqual('100\n', client.shell('date +%s', timeout_s=1.0))
        self.assertAlmostEqual(0.8, runner.call_args_list[0].kwargs['timeout'])
        self.assertAlmostEqual(0.3, runner.call_args_list[1].kwargs['timeout'])

    def test_legacy_clock_adapter_uses_the_shared_monotonic_guard(self):
        device = mock.Mock()
        with mock.patch('src.android_time_fixer.legacy_shell_with_timeout', return_value='100') as guard:
            self.assertEqual('100', AndroidTVTimeFixer._timed_device_shell(device, 'date +%s', 0.4))
        guard.assert_called_once_with(device, 'date +%s', 0.4)

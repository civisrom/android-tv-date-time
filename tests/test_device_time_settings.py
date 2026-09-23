import json
from pathlib import Path
import shlex
import tempfile
import unittest

from src.android_time_fixer import AndroidTVTimeFixer
from device_time_settings import (DeviceStateError, TimeSettings, TimeSnapshotStore,
                                     apply_time_settings, checked_shell, device_identity, read_time_settings)


class TimeDevice:
    def __init__(self, sdk='36'):
        self.props = {'ro.serialno': '', 'ro.boot.serialno': '', 'ro.product.manufacturer': 'Maker',
                      'ro.product.model': 'TV', 'ro.product.device': 'television',
                      'ro.build.version.sdk': sdk, 'persist.sys.timezone': 'Europe/Moscow'}
        self.globals = {'ntp_server': 'ntp://old.example:1123|ntp://second.example',
                        'auto_time': '1', 'auto_time_zone': '1'}
        self.android_id = '0123456789abcdef'
        self.commands = []
        self.fail = lambda command: False
        self.zone_supported = True

    def shell(self, wrapped):
        command = wrapped.split('; printf ', 1)[0]
        self.commands.append(command)
        args = shlex.split(command)
        code, output = 0, ''
        if self.fail(command):
            code, output = 1, 'Permission denied; private-path SECRET'
        elif args[:1] == ['getprop']:
            output = self.props.get(args[1], '')
        elif args == ['settings', 'get', 'secure', 'android_id']:
            output = self.android_id
        elif args[:3] == ['settings', 'get', 'global']:
            output = self.globals.get(args[3], 'null')
        elif args[:3] == ['settings', 'put', 'global']:
            self.globals[args[3]] = args[4]
        elif args[:3] == ['settings', 'delete', 'global']:
            self.globals[args[3]] = 'null'
        elif args == ['cmd', 'time_zone_detector', 'help']:
            code, output = 255, '\n'.join(('  is_auto_detection_enabled', '  set_auto_detection_enabled',
                                          '  is_telephony_detection_supported', '  is_geo_detection_supported'))
        elif args[:2] == ['cmd', 'time_zone_detector']:
            if args[2] == 'is_auto_detection_enabled':
                output = 'true' if self.globals['auto_time_zone'] == '1' else 'false'
            elif args[2] == 'set_auto_detection_enabled':
                self.globals['auto_time_zone'] = '1' if args[3] == 'true' else '0'
            else:
                output = 'true' if self.zone_supported else 'false'
        elif args == ['cmd', 'alarm', 'help']:
            code, output = 255, '  set-timezone ZONE'
        elif args[:3] == ['cmd', 'alarm', 'set-timezone']:
            self.props['persist.sys.timezone'] = args[3]
        else:
            code, output = 1, 'Unknown command'
        return output + '\n__TVTF_EXIT__' + str(code) + '\n' if '; printf ' in wrapped else output


class DeviceTimeSettingsTests(unittest.TestCase):
    def store(self, directory):
        return TimeSnapshotStore(directory, AndroidTVTimeFixer._atomic_write_json)

    def test_command_failure_and_missing_marker_never_become_setting_values(self):
        target = TimeDevice()
        target.fail = lambda command: command == 'settings get global ntp_server'
        with self.assertRaises(DeviceStateError) as error:
            read_time_settings(target)
        self.assertNotIn('SECRET', str(error.exception))
        target.shell = lambda command: '1'
        with self.assertRaises(DeviceStateError):
            checked_shell(target, 'settings get global auto_time')

    def test_first_snapshot_survives_restart_and_never_silently_replaces_baseline(self):
        target = TimeDevice()
        with tempfile.TemporaryDirectory() as directory:
            first = self.store(directory).save(target)
            target.globals['ntp_server'] = 'changed.example'
            store = self.store(directory)
            self.assertEqual(first, store.save(target))
            desired = TimeSettings.from_dict(first['settings'])
            apply_time_settings(target, desired, first['identity'])
            self.assertEqual(desired, read_time_settings(target))
            payload = next(Path(directory).rglob('*.json')).read_text()
            self.assertNotIn(target.android_id, payload)
            self.assertNotIn('192.168', payload)

    def test_unicode_and_shell_quotes_respect_negotiated_legacy_payload_limit(self):
        target = TimeDevice()
        target._maxdata = 4096
        for raw in ('😀' * 4096, "'" * 2000):
            target.globals['ntp_server'] = raw
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(DeviceStateError) as error:
                    self.store(directory).save(target)
                self.assertEqual('state_transport_limit', error.exception.code)
                self.assertEqual([], list(Path(directory).rglob('*.json')))
        target._maxdata = 1024 * 1024
        target.globals['ntp_server'] = '😀' * 4096
        with tempfile.TemporaryDirectory() as directory:
            saved = self.store(directory).save(target)
            self.assertEqual(saved, self.store(directory).load(device_identity(target)))
            apply_time_settings(target, TimeSettings.from_dict(saved['settings']), saved['identity'])

    def test_failed_snapshot_write_leaves_all_device_settings_unchanged(self):
        target = TimeDevice()
        before = read_time_settings(target)
        with tempfile.TemporaryDirectory() as directory:
            def fail(*args):
                raise PermissionError('blocked')
            with self.assertRaises(DeviceStateError):
                TimeSnapshotStore(directory, fail).save(target)
        self.assertEqual(before, read_time_settings(target))
        self.assertFalse(any('settings put' in command for command in target.commands))

    def test_reused_address_with_another_android_id_cannot_restore_the_first_device(self):
        target = TimeDevice()
        identity, state = device_identity(target), read_time_settings(target)
        target.android_id = 'fedcba9876543210'
        target.commands.clear()
        with self.assertRaises(DeviceStateError):
            apply_time_settings(target, state, identity)
        self.assertFalse(any('settings put' in command for command in target.commands))

    def test_unknown_or_all_zero_identities_cannot_create_a_snapshot(self):
        target = TimeDevice()
        for value in ('null', '', '0000000000000000', 'Permission denied', '9774d56d682e549c'):
            target.android_id = value
            with self.assertRaises(DeviceStateError):
                device_identity(target)

    def test_empty_and_padded_raw_ntp_settings_roundtrip_without_normalization(self):
        for raw in ('', '  ', '  ntp://one.example|ntp://two.example:1123  '):
            target = TimeDevice()
            target.globals['ntp_server'] = raw
            with tempfile.TemporaryDirectory() as directory:
                snapshot = self.store(directory).save(target)
                self.assertEqual(raw, snapshot['settings']['ntp_server'])
                target.globals['ntp_server'] = 'changed.example'
                apply_time_settings(target, TimeSettings.from_dict(snapshot['settings']), snapshot['identity'])
                self.assertEqual(raw, target.globals['ntp_server'])

    def test_android_command_newline_is_removed_but_raw_spaces_are_retained(self):
        target = TimeDevice()
        target.shell = lambda command: '  ntp://old.example  \r\n\n__TVTF_EXIT__0\n'
        self.assertEqual('  ntp://old.example  ', checked_shell(target, 'ignored', preserve=True))

    def test_concurrent_setting_change_prevents_publishing_an_inconsistent_snapshot(self):
        target = TimeDevice()
        shell = target.shell
        reads = 0

        def changing(command):
            nonlocal reads
            if command.startswith('settings get global ntp_server'):
                reads += 1
                if reads == 2:
                    target.globals['auto_time'] = '0'
            return shell(command)

        target.shell = changing
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(DeviceStateError) as error:
                self.store(directory).save(target)
            self.assertEqual('state_changed_during_read', error.exception.code)
            self.assertEqual([], list(Path(directory).rglob('*.json')))

    def test_identity_change_between_writes_blocks_every_later_mutation(self):
        target = TimeDevice()
        identity = device_identity(target)
        shell = target.shell

        def replaced(command):
            result = shell(command)
            if command.startswith('settings put global ntp_server'):
                target.android_id = 'fedcba9876543210'
            return result

        target.shell = replaced
        desired = TimeSettings('new.example', '0', '0', 'UTC', False)
        with self.assertRaises(DeviceStateError) as error:
            apply_time_settings(target, desired, identity)
        self.assertFalse(error.exception.rollback)
        self.assertFalse(any(command.startswith(('cmd alarm set-timezone', 'cmd time_zone_detector set_',
                                                'settings put global auto_')) for command in target.commands))

    def test_changes_after_preview_are_not_overwritten_by_confirmation(self):
        target = TimeDevice()
        identity = device_identity(target)
        preview = read_time_settings(target)
        target.globals['auto_time'] = '0'
        desired = TimeSettings('new.example', '1', '1', 'UTC', True)
        with self.assertRaises(DeviceStateError) as error:
            apply_time_settings(target, desired, identity, expected_current=preview)
        self.assertEqual('state_changed_before_apply', error.exception.code)
        self.assertFalse(any(command.startswith('settings put') for command in target.commands))

    def test_interrupt_during_apply_attempts_compensation_and_propagates_cancellation(self):
        target = TimeDevice()
        identity, before = device_identity(target), read_time_settings(target)
        shell = target.shell

        def interrupted(command):
            if command.startswith('cmd alarm set-timezone UTC'):
                raise KeyboardInterrupt()
            return shell(command)

        target.shell = interrupted
        with self.assertRaises(KeyboardInterrupt):
            apply_time_settings(target, TimeSettings('new.example', '0', '0', 'UTC', False), identity)
        self.assertEqual(before, read_time_settings(target))

    def test_modern_and_legacy_restore_zone_and_flags_with_exact_ntp_configuration(self):
        for sdk in ('29', '36'):
            target = TimeDevice(sdk)
            expected = read_time_settings(target)
            identity = device_identity(target)
            target.globals.update(ntp_server='other.example', auto_time='0', auto_time_zone='0')
            target.props['persist.sys.timezone'] = 'UTC'
            self.assertEqual(expected, apply_time_settings(target, expected, identity))
            self.assertEqual(expected, read_time_settings(target))

    def test_partial_permission_failure_restores_pre_apply_state_and_reports_failure(self):
        target = TimeDevice()
        identity = device_identity(target)
        before = read_time_settings(target)
        desired = TimeSettings('new.example', '0', '0', 'UTC', False)
        target.fail = lambda command: command == 'cmd alarm set-timezone UTC'
        with self.assertRaises(DeviceStateError) as error:
            apply_time_settings(target, desired, identity)
        self.assertTrue(error.exception.rollback)
        self.assertEqual(before, read_time_settings(target))

    def test_failed_compensation_is_explicit_and_keeps_snapshot_available(self):
        target = TimeDevice()
        with tempfile.TemporaryDirectory() as directory:
            store = self.store(directory)
            snapshot = store.save(target)
            desired = TimeSettings('new.example', '0', '0', 'UTC', False)
            target.fail = lambda command: command == 'cmd alarm set-timezone UTC' or (
                command.startswith('settings put global ntp_server') and 'old.example' in command)
            with self.assertRaises(DeviceStateError) as error:
                apply_time_settings(target, desired, snapshot['identity'])
            self.assertFalse(error.exception.rollback)
            self.assertEqual(snapshot, store.load(snapshot['identity']))

    def test_corrupt_snapshot_is_not_replaced_or_applied(self):
        target = TimeDevice()
        with tempfile.TemporaryDirectory() as directory:
            store = self.store(directory)
            snapshot = store.save(target)
            path = next(Path(directory).rglob('*.json'))
            bad = dict(snapshot, settings={'ntp_server': 'unexpected'})
            path.write_text(json.dumps(bad))
            with self.assertRaises(DeviceStateError):
                store.save(target)
            self.assertEqual(bad, json.loads(path.read_text()))

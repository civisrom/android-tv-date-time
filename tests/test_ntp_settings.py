import contextlib
import io
import logging
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from src.android_time_fixer import AndroidTVTimeFixer, AndroidTVTimeFixerError, locales
from tests.test_device_time_settings import TimeDevice


class Target(TimeDevice):
    def __init__(self, raw='null', api='29', automatic='0'):
        super().__init__(api)
        self.raw = raw
        self.globals['auto_time'] = automatic

    @property
    def raw(self):
        return self.globals['ntp_server']

    @raw.setter
    def raw(self, value):
        self.globals['ntp_server'] = value

    @property
    def automatic(self):
        return self.globals['auto_time']


class NtpSettingsTests(unittest.TestCase):
    def fixer(self, target):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.device = target
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        fixer.data_dir = Path(directory.name)
        fixer.logger = logging.getLogger('ntp-setting-test')
        fixer.verify_ntp_server = mock.Mock(return_value=True)
        status = mock.patch('src.android_time_fixer.show_time_status')
        status.start()
        self.addCleanup(status.stop)
        return fixer

    def test_save_reports_restart_and_disabled_auto_time_instead_of_sync_success(self):
        target = Target()
        fixer = self.fixer(target)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            fixer.set_ntp_server('pool.ntp.org')
        self.assertIn(locales.get('ntp_restart_required'), output.getvalue())
        self.assertIn(locales.get('ntp_auto_off'), output.getvalue())
        self.assertEqual(target.automatic, '0')
        self.assertFalse(any('reboot' in cmd or 'settings put global auto_time' in cmd for cmd in target.commands))

    def test_reset_and_undo_restore_a_modern_multi_server_configuration_exactly(self):
        raw = 'ntp://time.example.org:1123|ntp://other.example.org'
        target = Target(raw)
        fixer = self.fixer(target)
        with contextlib.redirect_stdout(io.StringIO()):
            fixer.reset_ntp_server()
            self.assertEqual(target.raw, 'null')
            fixer.undo_ntp_server()
        self.assertEqual(target.raw, raw)

    def test_undo_cannot_overwrite_an_external_change_or_another_device(self):
        target = Target('time.example.org')
        fixer = self.fixer(target)
        with contextlib.redirect_stdout(io.StringIO()):
            fixer.set_ntp_server('pool.ntp.org')
        target.raw = 'external.example.org'
        with self.assertRaises(AndroidTVTimeFixerError):
            fixer.undo_ntp_server()
        self.assertEqual(target.raw, 'external.example.org')
        fixer.device = Target('pool.ntp.org')
        with self.assertRaises(AndroidTVTimeFixerError):
            fixer.undo_ntp_server()
        self.assertEqual(fixer.device.commands, [])

    def test_probe_failure_blocks_normal_apply_but_explicit_override_can_save(self):
        target = Target()
        fixer = self.fixer(target)
        fixer.verify_ntp_server.return_value = False
        with self.assertRaises(AndroidTVTimeFixerError):
            fixer.set_ntp_server('pool.ntp.org')
        self.assertEqual(target.raw, 'null')
        with contextlib.redirect_stdout(io.StringIO()):
            fixer.set_ntp_server('pool.ntp.org', allow_unverified=True)
        self.assertEqual(target.raw, 'pool.ntp.org')

    def test_optional_device_commands_do_not_hide_model_or_firmware(self):
        fixer = self.fixer(Target())
        fixer._get_all_props = lambda: {'ro.product.model': 'TV', 'ro.build.version.release': '14'}
        fixer._get_device_network_info = lambda: ('', '')
        fixer.device = mock.Mock()
        fixer.device.shell.side_effect = OSError('optional command unsupported')
        info = fixer.get_device_info()
        self.assertEqual(info['model'], 'TV')
        self.assertEqual(info['android_version'], '14')
        self.assertEqual(info['screen_resolution'], '')

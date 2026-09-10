import contextlib
import io
import logging
import os
from logging.handlers import RotatingFileHandler
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from src.android_time_fixer import AndroidTVTimeFixer, AndroidTVTimeFixerError


class TerminalSafetyTests(unittest.TestCase):
    def fixer(self):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = mock.Mock()
        fixer.process_manager = mock.Mock()
        fixer._close_device = mock.Mock()
        return fixer

    def test_eof_stops_reading_without_reporting_an_error(self):
        fixer = self.fixer()
        with mock.patch('builtins.input', side_effect=EOFError) as read, \
                contextlib.redirect_stdout(io.StringIO()), \
                mock.patch('src.android_time_fixer.os.system'):
            fixer.terminal_mode()
        read.assert_called_once()
        fixer.logger.error.assert_not_called()

    def test_large_stdout_and_stderr_without_newlines_have_bounded_tails(self):
        fixer = self.fixer()
        child = "import sys; sys.stdout.write('a'*200000+'OUT_END'); sys.stderr.write('b'*200000+'ERR_END')"
        process = subprocess.Popen(
            [sys.executable, '-c', child], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding='utf-8', **fixer._popen_group_options(),
        )
        with mock.patch('builtins.print'):
            code, stdout, stderr = fixer._process_command_output(process, timeout=10)
        self.assertEqual(code, 0)
        self.assertTrue(stdout.endswith('OUT_END'))
        self.assertTrue(stderr.endswith('ERR_END'))
        self.assertLess(len(stdout), fixer.TERMINAL_OUTPUT_LIMIT + 200)
        self.assertLess(len(stderr), fixer.TERMINAL_OUTPUT_LIMIT + 200)

    def test_small_command_output_is_preserved(self):
        fixer = self.fixer()
        process = subprocess.Popen(
            [sys.executable, '-c', "import sys; print('ok'); sys.stderr.write('warning')"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            **fixer._popen_group_options(),
        )
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(fixer._process_command_output(process, timeout=10), (0, 'ok\n', 'warning'))

    def test_application_log_rotates_with_a_finite_number_of_backups(self):
        fixer = self.fixer()
        app_logger = logging.getLogger('src.android_time_fixer')
        previous = app_logger.handlers[:]
        app_logger.handlers = []
        try:
            with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
                fixer.data_dir = Path(directory)
                fixer._setup_logging()
                handler = next(h for h in fixer.logger.handlers if isinstance(h, RotatingFileHandler))
                self.assertGreater(handler.maxBytes, 0)
                self.assertEqual(handler.backupCount, 2)
                handler.maxBytes = 256
                for _ in range(20):
                    fixer.logger.info('x' * 80)
                files = list(Path(directory).glob('android_tv_fixer.log*'))
                self.assertEqual(len(files), 3)
                self.assertTrue(all(f.stat().st_size < 256 for f in files))
                if os.name != 'nt':
                    self.assertTrue(all(f.stat().st_mode & 0o077 == 0 for f in files))
                for item in fixer.logger.handlers:
                    item.close()
        finally:
            app_logger.handlers = previous

    def test_pairing_passes_code_only_on_stdin(self):
        fixer = self.fixer()
        fixer.get_adb_path = lambda: 'adb'
        fixer.adb_env = {}
        with mock.patch('src.android_time_fixer.subprocess.run') as run, \
                contextlib.redirect_stdout(io.StringIO()):
            run.return_value = subprocess.CompletedProcess([], 0, 'Successfully paired')
            fixer.pair_device('192.0.2.1:37000', '123456')
        args, kwargs = run.call_args
        self.assertEqual(args[0], ['adb', 'pair', '192.0.2.1:37000'])
        self.assertEqual(kwargs['input'], '123456\n')

    def test_pairing_error_and_output_cannot_repeat_the_code(self):
        for failure in [subprocess.TimeoutExpired(['adb', 'pair', '123456'], 1), None]:
            with self.subTest(failure=type(failure).__name__):
                fixer = self.fixer()
                fixer._run_adb = mock.Mock(side_effect=failure, return_value=(1, 'invalid code 123456'))
                with self.assertRaises(AndroidTVTimeFixerError) as caught, \
                        contextlib.redirect_stdout(io.StringIO()):
                    fixer.pair_device('192.0.2.1:37000', '123456')
                self.assertNotIn('123456', str(caught.exception))

    def test_pairing_rejects_non_ascii_digits(self):
        self.assertFalse(AndroidTVTimeFixer.validate_pairing_code('١٢٣٤٥٦'))

import contextlib
import io
import unittest
from unittest import mock

from src import android_time_fixer as app
from src.network_check import NetworkCheckResult


class StartupExitTests(unittest.TestCase):
    def test_interrupt_or_expected_error_in_an_operation_returns_to_menu(self):
        for failure in (KeyboardInterrupt(), app.AndroidTVTimeFixerError('device unavailable')):
            fixer = mock.Mock()
            fixer.load_language.return_value = 'en'
            fixer.ping_ntp_servers.side_effect = failure
            with mock.patch.object(app, 'AndroidTVTimeFixer', return_value=fixer), \
                    mock.patch('builtins.input', side_effect=['', '6', '3', '0', '']), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as exit:
                app.main()
            self.assertEqual(exit.exception.code, 0)
            fixer.show_country_codes.assert_called_once()
            fixer.close.assert_called_once()

    def test_windows_redirected_streams_preserve_Russian_text(self):
        output = io.BytesIO()
        errors = io.BytesIO()
        with io.TextIOWrapper(io.BytesIO('Гостиная\n'.encode('utf-8')), encoding='cp1252') as stdin, \
                io.TextIOWrapper(output, encoding='cp1252') as stdout, \
                io.TextIOWrapper(errors, encoding='cp1252') as stderr:
            fixer = mock.Mock()
            fixer.load_language.return_value = None

            def construct():
                print('Проверка вывода')
                app.sys.stderr.write('Проверка ошибки\n')
                self.assertEqual(app.sys.stdin.readline(), 'Гостиная\n')
                return fixer

            with mock.patch.object(app, 'AndroidTVTimeFixer', side_effect=construct), \
                    mock.patch.object(app.sys, 'platform', 'win32'), \
                    mock.patch.multiple(app.sys, stdin=stdin, stdout=stdout, stderr=stderr), \
                    mock.patch('builtins.input', side_effect=EOFError), \
                    self.assertRaises(SystemExit) as exit:
                app.main()
            self.assertEqual(exit.exception.code, 0)
            stdout.flush()
            stderr.flush()
            self.assertIn('Проверка вывода', output.getvalue().decode('utf-8'))
            self.assertIn('Проверка ошибки', errors.getvalue().decode('utf-8'))
            fixer.close.assert_called_once()

    def test_cancel_language_selection_closes_resources_without_an_error(self):
        for interruption in (EOFError, KeyboardInterrupt):
            with self.subTest(interruption=interruption.__name__):
                fixer = mock.Mock()
                fixer.load_language.return_value = None
                with mock.patch.object(app, 'AndroidTVTimeFixer', return_value=fixer), \
                        mock.patch('builtins.input', side_effect=interruption) as read, \
                        contextlib.redirect_stdout(io.StringIO()), \
                        self.assertRaises((SystemExit, EOFError, KeyboardInterrupt)) as exit:
                    app.main()
                self.assertIsInstance(exit.exception, SystemExit)
                self.assertEqual(exit.exception.code, 0)
                read.assert_called_once()
                fixer.gen_keys.assert_not_called()
                fixer.close.assert_called_once()
                fixer.logger.error.assert_not_called()

    def test_eof_at_instructions_or_menu_is_a_clean_exit_on_both_platforms(self):
        for platform in ('linux', 'win32'):
            for answers in ([EOFError], ['', EOFError]):
                with self.subTest(platform=platform, answers=len(answers)):
                    fixer = mock.Mock()
                    fixer.load_language.return_value = 'en'
                    with mock.patch.object(app, 'AndroidTVTimeFixer', return_value=fixer), \
                            mock.patch.object(app, 'set_language'), \
                            mock.patch.object(app.sys, 'platform', platform), \
                            mock.patch('builtins.input', side_effect=answers) as read, \
                            contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as exit:
                        app.main()
                    self.assertEqual(exit.exception.code, 0)
                    self.assertEqual(read.call_count, len(answers))
                    fixer.close.assert_called_once()
                    fixer.show_network_check.assert_called_once()
                    fixer.logger.error.assert_not_called()

    def test_windows_startup_error_preserves_error_exit_when_pause_input_closes(self):
        with mock.patch.object(app, 'AndroidTVTimeFixer', side_effect=OSError('test startup failure')), \
                mock.patch.object(app, 'logger'), mock.patch.object(app.sys, 'platform', 'win32'), \
                mock.patch('builtins.input', side_effect=EOFError), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as exit:
            app.main()
        self.assertEqual(exit.exception.code, 1)

    def test_language_read_failure_still_cleans_up_and_preserves_error_exit(self):
        fixer = mock.Mock()
        fixer.load_language.side_effect = OSError('test read failure')
        with mock.patch.object(app, 'AndroidTVTimeFixer', return_value=fixer), \
                mock.patch.object(app.sys, 'platform', 'win32'), \
                mock.patch('builtins.input', side_effect=EOFError), \
                contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as exit:
            app.main()
        self.assertEqual(exit.exception.code, 1)
        fixer.close.assert_called_once()

    def test_network_check_keeps_internet_and_ntp_status_separate_in_both_languages(self):
        previous = app.locales.current_language
        try:
            for language in ('ru', 'en'):
                app.set_language(language)
                for https, ntp, expected in (
                        (2, 2, 'network_check_ok'), (2, 0, 'network_check_ntp_blocked'),
                        (0, 2, 'network_check_https_failed'), (0, 0, 'network_check_failed')):
                    with self.subTest(language=language, https=https, ntp=ntp):
                        fixer = object.__new__(app.AndroidTVTimeFixer)
                        output = io.StringIO()
                        result = NetworkCheckResult(https, ntp, .1, True)
                        with mock.patch.object(app, 'check_network', return_value=result) as probe, \
                                contextlib.redirect_stdout(output):
                            fixer.show_network_check()
                        probe.assert_called_once_with(timeout=4.0)
                        self.assertIn(app.locales.get(expected), output.getvalue())
                        self.assertIn(app.locales.get('network_check_no_target'), output.getvalue())
                        self.assertNotIn('{status}', output.getvalue())
        finally:
            app.locales.current_language = previous

    def test_network_check_does_not_probe_the_saved_device(self):
        fixer = object.__new__(app.AndroidTVTimeFixer)
        fixer.last_device_ip = '192.0.2.10:37105'
        output = io.StringIO()
        with mock.patch.object(app, 'check_network',
                               return_value=NetworkCheckResult(2, 2, .1, True)) as probe, \
                contextlib.redirect_stdout(output):
            fixer.show_network_check()
        probe.assert_called_once_with(timeout=4.0)
        self.assertNotIn('192.0.2.10', output.getvalue())

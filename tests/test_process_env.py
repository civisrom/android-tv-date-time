import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

from src.process_env import external_environment, external_program_environment


class ProcessEnvironmentTests(unittest.TestCase):
    def test_non_frozen_process_preserves_user_environment(self):
        with mock.patch.object(sys, 'frozen', False, create=True), \
                mock.patch.dict(os.environ, {'HOME': '/synthetic-user', 'PATH': '/usr/bin'}, clear=True):
            self.assertEqual(dict(os.environ), external_environment())

    def test_frozen_linux_restores_original_libraries_without_changing_parent(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, {
                'LD_LIBRARY_PATH': directory, 'LD_LIBRARY_PATH_ORIG': '/system/custom',
                'PATH': os.pathsep.join([directory, str(Path(directory) / 'lib'), '/usr/bin']),
                'HOME': '/original-user'}, clear=True), \
                mock.patch.object(sys, 'frozen', True, create=True), \
                mock.patch.object(sys, '_MEIPASS', directory, create=True), \
                mock.patch.object(sys, 'platform', 'linux'):
            with external_program_environment() as env:
                self.assertEqual('/system/custom', env['LD_LIBRARY_PATH'])
                self.assertEqual('/usr/bin', env['PATH'])
                self.assertEqual('/original-user', env['HOME'])
            self.assertEqual(directory, os.environ['LD_LIBRARY_PATH'])

    @unittest.skipUnless(sys.platform == 'win32', 'Native Windows DLL search path')
    def test_windows_DLL_search_path_is_restored_even_when_spawn_fails(self):
        import ctypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.GetDllDirectoryW.argtypes = [ctypes.c_uint, ctypes.c_wchar_p]
        kernel.SetDllDirectoryW.argtypes = [ctypes.c_wchar_p]

        def current():
            buffer = ctypes.create_unicode_buffer(32768)
            kernel.GetDllDirectoryW(len(buffer), buffer)
            return buffer.value

        previous = current()
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(sys, 'frozen', True, create=True):
            try:
                self.assertTrue(kernel.SetDllDirectoryW(directory))
                with self.assertRaises(OSError):
                    with external_program_environment():
                        self.assertEqual('', current())
                        raise OSError('synthetic launch failure')
                self.assertEqual(directory, current())
            finally:
                kernel.SetDllDirectoryW(previous or None)

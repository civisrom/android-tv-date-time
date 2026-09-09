"""Окружение системных программ, запускаемых из упакованного терминала."""
from contextlib import contextmanager
import os
from pathlib import Path
import sys
import threading

_DLL_LOCK = threading.RLock()


def external_environment():
    env = os.environ.copy()
    if not getattr(sys, 'frozen', False):
        return env
    if sys.platform.startswith('linux'):
        if 'LD_LIBRARY_PATH_ORIG' in env:
            env['LD_LIBRARY_PATH'] = env['LD_LIBRARY_PATH_ORIG']
        else:
            env.pop('LD_LIBRARY_PATH', None)
    bundled = getattr(sys, '_MEIPASS', None)
    if bundled:
        root = Path(bundled).resolve()
        for name in ('PATH', 'DYLD_LIBRARY_PATH'):
            if name in env:
                env[name] = os.pathsep.join(part for part in env[name].split(os.pathsep)
                                          if not part or not Path(part).resolve().is_relative_to(root))
    return env


@contextmanager
def external_program_environment():
    env = external_environment()
    if sys.platform != 'win32' or not getattr(sys, 'frozen', False):
        yield env
        return
    import ctypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.GetDllDirectoryW.argtypes = [ctypes.c_uint, ctypes.c_wchar_p]
    kernel.SetDllDirectoryW.argtypes = [ctypes.c_wchar_p]
    with _DLL_LOCK:
        size = kernel.GetDllDirectoryW(0, None)
        previous = ctypes.create_unicode_buffer(size + 1)
        kernel.GetDllDirectoryW(len(previous), previous)
        if not kernel.SetDllDirectoryW(None):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            yield env
        finally:
            if not kernel.SetDllDirectoryW(previous.value or None):
                raise ctypes.WinError(ctypes.get_last_error())

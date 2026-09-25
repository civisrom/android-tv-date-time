"""Настоящий ADB: обычный выход, закрытие окна Windows и авария владельца."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.android_time_fixer import adb_env
from src.adb_server import ADBServerLease


def listening(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=.2):
            return True
    except OSError:
        return False


def wait_for(condition):
    deadline = time.monotonic() + 15
    while not condition():
        if time.monotonic() >= deadline:
            raise AssertionError('ADB lifecycle check timed out')
        time.sleep(.05)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--worker', type=Path)
    parser.add_argument('--port', type=int)
    args = parser.parse_args()
    adb = str(args.adb.resolve())
    if args.worker:
        lease = ADBServerLease(adb, adb_env(args.worker, args.port), args.worker / 'leases')
        lease.ensure()
        window = 0
        if os.name == 'nt':
            api = ctypes.WinDLL('kernel32')
            api.GetConsoleWindow.restype = ctypes.c_void_p
            window = api.GetConsoleWindow() or 0
        print(json.dumps({'pid': lease.child.pid if lease.child else None, 'window': window}), flush=True)
        try:
            input()
        finally:
            lease.release()
        return
    cases = ['normal', 'foreign'] + (['terminate', 'close-window', 'two-owners'] if os.name == 'nt' else [])
    for case in cases:
        with tempfile.TemporaryDirectory(prefix='adb-cleanup-') as root:
            directory = Path(root)
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            workers = []
            foreign = None
            server = None

            def start():
                child = subprocess.Popen(
                    [sys.executable, __file__, '--adb', adb, '--worker', root, '--port', str(port)],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                    **({'creationflags': subprocess.CREATE_NEW_CONSOLE} if case == 'close-window' else {}),
                )
                workers.append(child)
                line = child.stdout.readline()
                if not line:
                    raise AssertionError(child.communicate(timeout=5)[1])
                return child, json.loads(line)

            try:
                if case == 'foreign':
                    foreign = subprocess.Popen([adb, 'server', 'nodaemon'], env=adb_env(directory, port),
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    wait_for(lambda: listening(port))
                first, info = start()
                if info['pid']:
                    server = psutil.Process(info['pid'])
                if case == 'two-owners':
                    second, _ = start()
                    first.kill(); first.communicate(timeout=10)
                    assert listening(port), 'First owner killed the ADB still used by the second'
                    second.kill(); second.communicate(timeout=10)
                elif case == 'terminate':
                    first.kill(); first.communicate(timeout=10)
                elif case == 'close-window':
                    from ctypes import wintypes
                    api = ctypes.WinDLL('user32', use_last_error=True)
                    api.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
                    api.PostMessageW.restype = wintypes.BOOL
                    assert info['window'] and api.PostMessageW(info['window'], 0x0010, 0, 0)
                    first.wait(timeout=15)  # stdin остаётся открытым: проверяем именно закрытие окна.
                    first.communicate()
                else:
                    first.communicate('\n', timeout=15)
                    assert first.returncode == 0
                if case == 'foreign':
                    assert listening(port) and foreign.poll() is None, 'Foreign ADB was terminated'
                else:
                    wait_for(lambda: not listening(port))
                    wait_for(lambda: not server.is_running() or server.status() == psutil.STATUS_ZOMBIE)
                print(f'{case}: passed')
            finally:
                for worker in workers:
                    if worker.poll() is None:
                        worker.kill(); worker.communicate(timeout=10)
                if foreign is not None:
                    foreign.terminate(); foreign.wait(timeout=5)
                if server is not None and server.is_running():
                    server.kill()


if __name__ == '__main__':
    main()

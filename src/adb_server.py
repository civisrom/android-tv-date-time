"""Владение локальным ADB-сервером между несколькими экземплярами программы."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import uuid

import psutil
from platformdirs import user_data_path


class ADBServerLease:
    def __init__(self, adb_path, env, directory=None):
        self.adb_path = adb_path
        self.env = env
        self.port = int(env.get('ANDROID_ADB_SERVER_PORT', '5038'))
        if not 1 <= self.port <= 65535 or self.port == 5037:
            raise ValueError('A dedicated local ADB port is required')
        self.directory = Path(directory) if directory else user_data_path('AndroidTVTimeFixer', appauthor=False) / 'adb-runtime'
        self.token = uuid.uuid4().hex
        self.registered = False
        self.child = None
        self.mutex = threading.RLock()

    @contextmanager
    def _locked(self):
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        path = self.directory / f'{self.port}.lock'
        fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
        locked = False
        try:
            if os.fstat(fd).st_size == 0:
                os.write(fd, b'0')
            deadline = time.monotonic() + 5
            while not locked:
                try:
                    os.lseek(fd, 0, os.SEEK_SET)
                    if os.name == 'nt':
                        import msvcrt
                        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    locked = True
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError('ADB ownership lock is busy')
                    time.sleep(.05)
            yield
        finally:
            if locked:
                if os.name == 'nt':
                    import msvcrt
                    os.lseek(fd, 0, os.SEEK_SET)
                    msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def _load(self):
        path = self.directory / f'{self.port}.json'
        try:
            with path.open(encoding='utf-8') as stream:
                state = json.loads(stream.read(65537))
            if not isinstance(state, dict) or not isinstance(state.get('leases'), dict):
                return {'leases': {}}
        except (OSError, ValueError):
            return {'leases': {}}
        # При повторном использовании PID предпочитаем лишнюю живую аренду
        # ошибочному завершению чужой сессии. Умершие процессы удаляются точно.
        state['leases'] = {key: pid for key, pid in state['leases'].items()
                           if type(pid) is int and pid > 0 and psutil.pid_exists(pid)}
        return state

    def _save(self, state):
        fd, name = tempfile.mkstemp(prefix=f'{self.port}.', dir=self.directory)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(state, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, self.directory / f'{self.port}.json')
        finally:
            if os.path.exists(name):
                os.unlink(name)

    def _listening(self):
        try:
            with socket.create_connection(('127.0.0.1', self.port), timeout=.2):
                return True
        except OSError:
            return False

    @staticmethod
    def _owned_process(record):
        try:
            process = psutil.Process(record['pid'])
            if (process.is_running() and process.create_time() == record['birth']
                    and process.exe() == record['exe'] and process.status() != psutil.STATUS_ZOMBIE):
                return process
        except (psutil.Error, TypeError, KeyError):
            pass
        return None

    def ensure(self):
        with self.mutex, self._locked():
            state = self._load()
            if not self._listening():
                flags = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {'start_new_session': True}
                child = subprocess.Popen([self.adb_path, 'server', 'nodaemon'], env=self.env,
                                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **flags)
                self.child = child
                deadline = time.monotonic() + 8
                while child.poll() is None and not self._listening() and time.monotonic() < deadline:
                    time.sleep(.05)
                if child.poll() is not None or not self._listening():
                    if child.poll() is None:
                        child.terminate()
                        try:
                            child.wait(timeout=2)
                        except subprocess.TimeoutExpired:
                            child.kill()
                            child.wait(timeout=2)
                    raise OSError('Could not start the application ADB server')
                process = psutil.Process(child.pid)
                state['server'] = {'pid': child.pid, 'birth': process.create_time(), 'exe': process.exe()}
            elif self._owned_process(state.get('server')) is None:
                # Порт уже занят сервером без доказанного владения: можно
                # использовать, но нельзя посылать ему kill-server/disconnect.
                state.pop('server', None)
            state['leases'][self.token] = os.getpid()
            self._save(state)
            self.registered = True

    def release(self):
        with self.mutex:
            if not self.registered:
                return
            with self._locked():
                state = self._load()
                state['leases'].pop(self.token, None)
                if not state['leases']:
                    process = self._owned_process(state.get('server'))
                    if process is not None:
                        process.terminate()
                        try:
                            process.wait(timeout=3)
                        except psutil.TimeoutExpired:
                            # Проверяем идентичность ещё раз перед принудительным завершением.
                            if self._owned_process(state.get('server')) is not None:
                                process.kill()
                                process.wait(timeout=3)
                    state.pop('server', None)
                self._save(state)
                self.registered = False
            if self.child is not None:
                self.child.poll()  # Забираем статус своего уже завершённого дочернего процесса.

import logging
import os
import socket
import subprocess
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.android_time_fixer import AndroidTVTimeFixer, AndroidTVTimeFixerError

ADB = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'tools/android-sdk'))) / 'platform-tools/adb')
address = '192.0.2.1:37105'
reply = ('failed to authenticate to ' + address).encode()
server = socket.socket()
server.bind(('127.0.0.1', 0))
server.listen()
stopped = threading.Event()

def read_exact(conn, n):
    data = b''
    while len(data) < n:
        part = conn.recv(n - len(data))
        if not part:
            raise EOFError()
        data += part
    return data

def serve():
    while not stopped.is_set():
        try:
            conn, _ = server.accept()
        except OSError:
            return
        with conn:
            conn.settimeout(5)
            request = read_exact(conn, int(read_exact(conn, 4), 16))
            if request == b'host:version':
                response = b'0029'
            elif request == ('host:connect:' + address).encode():
                response = reply
            else:
                raise AssertionError(request)
            conn.sendall(b'OKAY' + f'{len(response):04x}'.encode() + response)

threading.Thread(target=serve, daemon=True).start()
env = os.environ.copy()
env.pop('ADB_SERVER_SOCKET', None)
env.pop('ANDROID_ADB_SERVER_ADDRESS', None)
env['ANDROID_ADB_SERVER_PORT'] = str(server.getsockname()[1])
try:
    result = subprocess.run([ADB, 'connect', address], env=env,
                            capture_output=True, text=True, timeout=10)
    print('actual adb CLI:', result.returncode, result.stdout.strip())
    fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
    fixer.logger = logging.getLogger('wireless-audit')
    fixer.adb_env = env
    fixer.get_adb_path = lambda: ADB
    assert result.returncode == 0 and result.stdout.strip() == reply.decode(), result
    try:
        fixer._connect_via_platform_tools('192.0.2.1', 37105)
    except AndroidTVTimeFixerError:
        print('PASS: project rejects zero-exit authentication failure')
    else:
        raise AssertionError('Failed authentication was accepted')
finally:
    stopped.set()
    server.close()

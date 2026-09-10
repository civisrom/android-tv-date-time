"""Проверка USB discovery через настоящий ADB-сервер без физических устройств."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.android_time_fixer import adb_env, parse_usb_devices, read_adb_device_list
from src.adb_server import ADBServerLease


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    args = parser.parse_args()
    adb = str(Path(args.adb).resolve())
    with tempfile.TemporaryDirectory(prefix='usb-adb-probe-') as directory:
        with socket.socket() as port_reservation:
            port_reservation.bind(('127.0.0.1', 0))
            port = port_reservation.getsockname()[1]
        env = adb_env(Path(directory), port)
        lease = ADBServerLease(adb, env, Path(directory) / 'leases')
        try:
            lease.ensure()
            devices = parse_usb_devices(read_adb_device_list(port))
            version = subprocess.check_output([adb, 'version'], env=env, timeout=10, text=True).splitlines()[1]
            print(json.dumps({'platform': sys.platform, 'adb': version,
                              'usb_devices': len(devices), 'snapshot': 'ok'}))
        finally:
            lease.release()
        assert not lease._listening(), 'Owned ADB server was not released'


if __name__ == '__main__':
    main()

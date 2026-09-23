import multiprocessing
from pathlib import Path
import tempfile
import unittest

from src.android_time_fixer import AndroidTVTimeFixer
from device_time_settings import TimeSnapshotStore, device_identity, read_time_settings
from time_profiles import TimeProfileStore
from tests.test_device_time_settings import TimeDevice


def _write_store(directory, name, kind, gate, results):
    device = TimeDevice()
    device.globals['ntp_server'] = name + '.example'
    gate.wait(10)
    if kind == 'snapshot':
        saved = TimeSnapshotStore(directory, AndroidTVTimeFixer._atomic_write_json).save(device)
        results.put(saved['settings']['ntp_server'])
    else:
        TimeProfileStore(directory, AndroidTVTimeFixer._atomic_write_json).save(
            name, '192.0.2.1:5555', device_identity(device), read_time_settings(device))
        results.put(name)


class TimeStoreConcurrencyTests(unittest.TestCase):
    def run_writers(self, directory, kind):
        context = multiprocessing.get_context('spawn')
        gate, results = context.Event(), context.Queue()
        children = [context.Process(target=_write_store, args=(directory, name, kind, gate, results))
                    for name in ('first', 'second')]
        try:
            for child in children:
                child.start()
            gate.set()
            values = [results.get(timeout=20) for _ in children]
            for child in children:
                child.join(20)
                self.assertEqual(0, child.exitcode)
            return values
        finally:
            for child in children:
                if child.is_alive():
                    child.terminate()
                child.join(5)
            results.close()
            results.join_thread()

    def test_two_processes_share_the_first_snapshot_without_overwriting_it(self):
        with tempfile.TemporaryDirectory() as directory:
            values = self.run_writers(directory, 'snapshot')
            self.assertEqual(values[0], values[1])
            store = TimeSnapshotStore(directory, AndroidTVTimeFixer._atomic_write_json)
            saved = store.load(device_identity(TimeDevice()))
            self.assertEqual(values[0], saved['settings']['ntp_server'])
            self.assertEqual(1, len(list((Path(directory) / 'time-snapshots').glob('*.json'))))

    def test_two_processes_keep_both_new_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run_writers(directory, 'profile')
            profiles = TimeProfileStore(directory, AndroidTVTimeFixer._atomic_write_json).load()
            self.assertEqual({'first', 'second'}, {profile['name'] for profile in profiles})

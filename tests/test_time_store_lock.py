import multiprocessing
from pathlib import Path
import tempfile
import time
import unittest

from time_store_lock import time_store_lock


def _hold(path, ready, release):
    with time_store_lock(path):
        ready.set()
        release.wait(15)


def _increment(path, ready, count):
    ready.wait(15)
    target = Path(path).with_suffix('.txt')
    for _ in range(count):
        with time_store_lock(path):
            value = int(target.read_text()) if target.exists() else 0
            time.sleep(.005)
            target.write_text(str(value + 1))


class TimeStoreLockTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / 'state.lock'
        # Spawn exercises independent processes on every supported OS.
        self.context = multiprocessing.get_context('spawn')

    def child(self, function, *arguments):
        process = self.context.Process(target=function, args=arguments)
        process.start()

        def cleanup():
            if process.is_alive():
                process.terminate()
            process.join(5)
            process.close()
        self.addCleanup(cleanup)
        return process

    def test_contending_process_respects_deadline_then_can_acquire(self):
        ready, release = self.context.Event(), self.context.Event()
        process = self.child(_hold, str(self.path), ready, release)
        self.assertTrue(ready.wait(10))
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            with time_store_lock(self.path, timeout=.15):
                self.fail('Another process still owns the lock')
        self.assertLess(time.monotonic() - started, 1.5)
        release.set()
        process.join(5)
        self.assertEqual(0, process.exitcode)
        with time_store_lock(self.path):
            self.assertTrue(self.path.exists())

    def test_process_termination_does_not_leave_a_stale_lock(self):
        ready, release = self.context.Event(), self.context.Event()
        process = self.child(_hold, str(self.path), ready, release)
        self.assertTrue(ready.wait(10))
        process.terminate()
        process.join(5)
        with time_store_lock(self.path, timeout=2):
            self.assertTrue(self.path.exists())

    def test_exception_releases_lock_without_deleting_its_path(self):
        with self.assertRaises(KeyboardInterrupt):
            with time_store_lock(self.path):
                raise KeyboardInterrupt()
        with time_store_lock(self.path):
            self.assertTrue(self.path.exists())

    def test_multiple_writers_keep_every_read_modify_write(self):
        ready = self.context.Event()
        children = [self.child(_increment, str(self.path), ready, 10) for _ in range(3)]
        ready.set()
        for child in children:
            child.join(10)
            self.assertEqual(0, child.exitcode)
        self.assertEqual('30', self.path.with_suffix('.txt').read_text())

    def test_invalid_timeout_does_not_create_a_file(self):
        for value in (0, -1, float('nan'), float('inf')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                with time_store_lock(self.path, value):
                    pass
        self.assertFalse(self.path.exists())

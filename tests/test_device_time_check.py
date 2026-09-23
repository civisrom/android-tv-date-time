import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from adb_shell import constants
from adb_shell.adb_device import AdbDevice
from adb_shell.adb_message import AdbMessage
from adb_shell.transport.base_transport import BaseTransport

from src import device_time_check as clock_check


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def monotonic(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class FragmentedTransport(BaseTransport):
    def __init__(self, clock, reads, write_delay=0.0):
        self.clock, self.reads, self.write_delay = clock, list(reads), write_delay
        self.budgets = []
        self.closed = False

    def connect(self, transport_timeout_s):
        pass

    def close(self):
        self.closed = True

    def delay(self, delay, timeout):
        self.budgets.append(timeout)
        self.clock.advance(min(delay, timeout))
        if delay > timeout:
            raise TimeoutError('Synthetic transport deadline')

    def bulk_read(self, numbytes, transport_timeout_s):
        delay, fragment = self.reads.pop(0)
        self.delay(delay, transport_timeout_s)
        if len(fragment) > numbytes:
            self.reads.insert(0, (0, fragment[numbytes:]))
        return fragment[:numbytes]

    def bulk_write(self, data, transport_timeout_s):
        self.delay(self.write_delay, transport_timeout_s)
        return len(data)


class LegacyAdbDeadlineTests(unittest.TestCase):
    def run_shell(self, reads, write_delay=0.0):
        clock = FakeClock()
        transport = FragmentedTransport(clock, reads, write_delay)
        device = AdbDevice(transport)
        device._available = True  # The isolated unit fixture starts after authentication.
        return clock, transport, device

    def test_success_restores_transport_methods_and_keeps_connection_open(self):
        payload = b'1790000000\n'
        packets = [AdbMessage(constants.OKAY, 2, 1).pack(),
                   AdbMessage(constants.WRTE, 2, 1, payload).pack() + payload,
                   AdbMessage(constants.CLSE, 2, 1).pack()]
        clock, transport, device = self.run_shell([(.1, packet) for packet in packets])
        with patch.object(clock_check.time, 'monotonic', clock.monotonic):
            self.assertEqual(payload.decode(), clock_check.legacy_shell_with_timeout(device, 'date +%s', 1))
        self.assertFalse(transport.closed)
        self.assertNotIn('bulk_read', vars(transport))
        self.assertNotIn('bulk_write', vars(transport))
        self.assertLess(clock.now - 100, 1)

    def test_open_and_shell_reads_share_one_deadline(self):
        reads = [(.55, AdbMessage(constants.OKAY, 2, 1).pack()),
                 (.55, AdbMessage(constants.CLSE, 2, 1).pack())]
        clock, transport, device = self.run_shell(reads)
        with patch.object(clock_check.time, 'monotonic', clock.monotonic), self.assertRaises(TimeoutError):
            clock_check.legacy_shell_with_timeout(device, 'date +%s', 1)
        self.assertAlmostEqual(1, clock.now - 100)
        self.assertTrue(transport.closed)
        self.assertFalse(device.available)
        self.assertNotIn('bulk_read', vars(transport))

    def test_fragmented_open_cannot_extend_deadline_when_wall_clock_moves_backwards(self):
        packet = AdbMessage(constants.OKAY, 2, 1).pack()
        clock, transport, device = self.run_shell([(.4, packet[i:i + 8]) for i in range(0, 24, 8)])
        with patch.object(clock_check.time, 'monotonic', clock.monotonic), \
                patch.object(clock_check.time, 'time', side_effect=range(1000, 0, -1)), \
                self.assertRaises(TimeoutError):
            clock_check.legacy_shell_with_timeout(device, 'date +%s', 1)
        self.assertAlmostEqual(1, clock.now - 100)
        self.assertTrue(transport.closed)
        self.assertNotIn('bulk_write', vars(transport))

    def test_open_header_and_payload_writes_share_the_same_deadline(self):
        clock, transport, device = self.run_shell([], write_delay=.55)
        with patch.object(clock_check.time, 'monotonic', clock.monotonic), self.assertRaises(TimeoutError):
            clock_check.legacy_shell_with_timeout(device, 'date +%s', 1)
        self.assertAlmostEqual(1, clock.now - 100)
        self.assertTrue(transport.closed)


class DeviceTimeCheckTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.clock_patch = patch.object(clock_check.time, 'monotonic', self.clock.monotonic)
        self.clock_patch.start()
        self.addCleanup(self.clock_patch.stop)
        self.network = patch.object(clock_check, 'query_ntp', return_value=SimpleNamespace(
            tx_time=1_790_000_000.0, delay=0.02, received_monotonic=100.0))
        self.query = self.network.start()
        self.addCleanup(self.network.stop)

    def test_match_uses_remote_ntp_and_ignores_computer_wall_clock(self):
        shell = Mock(return_value='1790000000\n')
        with patch.object(clock_check.time, 'time', side_effect=AssertionError('wall clock read')):
            result = clock_check.verify_device_time(shell, ['time.example'])
        self.assertEqual('MATCH', result.status)
        self.assertAlmostEqual(0.49, result.difference_seconds, places=5)
        self.assertAlmostEqual(0.512, result.uncertainty_seconds)
        self.assertEqual('time.example', result.reference_server)
        self.assertTrue(result.measured_at_utc.endswith('+00:00'))
        self.assertEqual('date +%s', shell.call_args.args[0])
        self.assertLessEqual(shell.call_args.args[1], 3.0)

    def test_definite_mismatch_and_uncertain_boundary(self):
        mismatch = clock_check.verify_device_time(lambda *_: '1790000010', ['time.example'])
        boundary = clock_check.verify_device_time(lambda *_: '1790000005', ['time.example'])
        self.assertEqual('MISMATCH', mismatch.status)
        self.assertEqual('UNCERTAIN', boundary.status)

    def test_adb_latency_propagates_monotonic_reference_and_uncertainty(self):
        def shell(command, timeout):
            self.clock.advance(2)
            return '1790000001'
        result = clock_check.verify_device_time(shell, ['time.example'])
        self.assertEqual('MATCH', result.status)
        self.assertAlmostEqual(0.49, result.difference_seconds, places=5)
        self.assertAlmostEqual(1.512, result.uncertainty_seconds)

    def test_late_callback_never_reports_match(self):
        def shell(command, timeout):
            self.clock.advance(31)
            return '1790000015'
        result = clock_check.verify_device_time(shell, ['time.example'], timeout=40)
        self.assertEqual('UNCERTAIN', result.status)

    def test_exceeded_deadline_never_reports_match(self):
        def shell(command, timeout):
            self.clock.advance(timeout + 0.01)
            return '1790000001'
        result = clock_check.verify_device_time(shell, ['time.example'])
        self.assertEqual('UNCERTAIN', result.status)

    def test_delayed_delivery_of_ntp_sample_keeps_original_receive_age(self):
        def query(*_):
            self.clock.advance(31)
            return SimpleNamespace(tx_time=1_790_000_000.0, delay=0, received_monotonic=100.0)
        self.query.side_effect = query
        result = clock_check.verify_device_time(lambda *_: '1790000031', ['time.example'], timeout=40)
        self.assertEqual('UNCERTAIN', result.status)

    def test_date_errors_are_not_parsed_as_timestamps(self):
        for output in ('permission denied', '1790000000\nerror', '-1', '253402300800',
                       '\u0661\u0667\u0669', ''):
            with self.subTest(output=output):
                result = clock_check.verify_device_time(lambda *_: output, ['time.example'])
                self.assertEqual('DEVICE_UNAVAILABLE', result.status)

    def test_transport_timeout_is_device_unavailable(self):
        result = clock_check.verify_device_time(Mock(side_effect=TimeoutError), ['time.example'])
        self.assertEqual('DEVICE_UNAVAILABLE', result.status)

    def test_no_servers_does_not_touch_network_or_device(self):
        shell = Mock()
        result = clock_check.verify_device_time(shell, [])
        self.assertEqual('NO_SERVER', result.status)
        shell.assert_not_called()
        self.query.assert_not_called()

    def test_fallback_and_combined_deadline(self):
        def query(server, timeout, stopped):
            self.clock.advance(timeout)
            raise TimeoutError()
        self.query.side_effect = query
        shell = Mock()
        result = clock_check.verify_device_time(shell, ['a', 'b', 'c', 'd', 'ignored'])
        self.assertEqual('NTP_UNAVAILABLE', result.status)
        self.assertEqual(4, self.query.call_count)
        self.assertLessEqual(self.clock.now - 100, 3)
        shell.assert_not_called()
        self.query.side_effect = [TimeoutError(), SimpleNamespace(tx_time=1_790_000_000.0, delay=0, received_monotonic=self.clock.now)]
        result = clock_check.verify_device_time(lambda *_: '1790000000', ['a', 'b'])
        self.assertEqual('MATCH', result.status)
        self.assertEqual('b', result.reference_server)

    def test_invalid_reference_does_not_make_a_match(self):
        for sample in (SimpleNamespace(tx_time=float('nan'), delay=0, received_monotonic=100),
                       SimpleNamespace(tx_time=1_790_000_000.0, delay=float('inf'), received_monotonic=100),
                       SimpleNamespace(tx_time=1_790_000_000.0, delay=-10, received_monotonic=100)):
            with self.subTest(sample=sample):
                self.query.return_value = sample
                result = clock_check.verify_device_time(lambda *_: '1790000000', ['time.example'])
                self.assertEqual('NTP_UNAVAILABLE', result.status)

    def test_cached_result_expires_and_clock_reversal_is_not_fresh(self):
        result = clock_check.verify_device_time(lambda *_: '1790000000', ['time.example'])
        self.assertEqual('MATCH', result.status_at(130))
        self.assertEqual('STALE', result.status_at(130.01))
        self.assertEqual('STALE', result.status_at(99))

    def test_cancellation_stops_before_shell(self):
        stopped = threading.Event()
        def query(*_):
            stopped.set()
            return SimpleNamespace(tx_time=1_790_000_000.0, delay=0, received_monotonic=self.clock.now)
        self.query.side_effect = query
        shell = Mock()
        with self.assertRaises(clock_check.CancelledError):
            clock_check.verify_device_time(shell, ['time.example'], stopped=stopped)
        shell.assert_not_called()

    def test_monitor_bounds_duration_and_reports_failed_samples(self):
        class Stop:
            def is_set(inner):
                return False

            def wait(inner, seconds):
                self.clock.advance(seconds)
                return False

        outputs = []
        shell = Mock(side_effect=['1790000000', TimeoutError(), '1790000060'])
        self.query.side_effect = [SimpleNamespace(tx_time=1_790_000_000.0 + delta, delay=0, received_monotonic=100 + delta)
                                  for delta in (0, 30, 60)]
        result = clock_check.monitor_device_time(shell, ['time.example'], outputs.append,
                                                stop_event=Stop(), duration=70)
        self.assertEqual(['MATCH', 'DEVICE_UNAVAILABLE', 'MATCH'], [x.status for x in outputs])
        self.assertEqual(3, result.samples)
        self.assertEqual(1, result.skipped)
        self.assertEqual(70, result.elapsed_seconds)
        self.assertFalse(result.stopped)
        self.assertEqual('STALE', outputs[0].status_at())

    def test_monitor_stop_is_observed_during_wait(self):
        stopped = threading.Event()
        emitted = []
        def emit(sample):
            emitted.append(sample)
            stopped.set()
        result = clock_check.monitor_device_time(lambda *_: '1790000000', ['time.example'],
                                                emit, stop_event=stopped)
        self.assertTrue(result.stopped)
        self.assertEqual(1, result.samples)
        self.assertEqual(1, len(emitted))

    def test_monitor_does_not_burst_after_delayed_wakeup(self):
        starts = []
        class Stop:
            def is_set(inner):
                return False

            def wait(inner, seconds):
                self.clock.advance(seconds + (35 if len(starts) == 1 else 0))
                return False

        def verify(*_):
            starts.append(self.clock.now)
            return clock_check.TimeCheck('DEVICE_UNAVAILABLE')
        with patch.object(clock_check, 'verify_device_time', side_effect=verify):
            clock_check.monitor_device_time(Mock(), ['time.example'], Mock(),
                                           stop_event=Stop(), duration=100)
        self.assertEqual([100, 165, 195], starts)

    def test_monitor_rejects_unbounded_or_excessive_polling(self):
        for args in ({'duration': 601}, {'duration': 0}, {'interval': 1}, {'duration': float('nan')}):
            with self.subTest(args=args), self.assertRaises(ValueError):
                clock_check.monitor_device_time(Mock(), ['time.example'], Mock(), **args)


if __name__ == '__main__':
    unittest.main()

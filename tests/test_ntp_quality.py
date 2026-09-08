import contextlib
import io
import logging
import socket
import threading
from concurrent.futures import CancelledError
import unittest
from types import SimpleNamespace
from unittest import mock

import ntplib

from src.android_time_fixer import AndroidTVTimeFixer


class NtpQualityTests(unittest.TestCase):
    def test_cancelled_scan_does_not_send_more_queries(self):
        fixer = object.__new__(AndroidTVTimeFixer)
        stopped = threading.Event()

        def query(*args):
            stopped.set()
            return SimpleNamespace(delay=0.02, offset=0.0)

        with mock.patch.object(fixer, '_query_ntp_server', side_effect=query) as requested:
            with self.assertRaises(CancelledError):
                fixer._test_ntp_server('time.example', 5, 2, 1.0, stopped)
        self.assertEqual(1, requested.call_count)

    def test_query_rejects_invalid_protocol_and_preserves_large_clock_offset(self):
        class Socket:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def settimeout(self, timeout):
                self.timeout = timeout

            def connect(self, address):
                self.address = address

            def send(self, data):
                self.query = ntplib.NTPPacket()
                self.query.from_data(data)

            def recv(self, count):
                packet = ntplib.NTPPacket(version=4, mode=4)
                packet.stratum = 1
                packet.orig_timestamp = self.query.tx_timestamp
                packet.recv_timestamp = self.query.tx_timestamp + 31_536_000
                packet.tx_timestamp = packet.recv_timestamp + 0.001
                for key, value in changes.items():
                    setattr(packet, key, value)
                return packet.to_data()[:length]

        cases = [({}, 48), ({'mode': 3}, 48), ({'version': 2}, 48),
                 ({'leap': 3}, 48), ({'stratum': 0}, 48), ({'stratum': 16}, 48),
                 ({'orig_timestamp': 1}, 48), ({'recv_timestamp': 0}, 48),
                 ({'tx_timestamp': 1}, 48), ({}, 20)]
        for changes, length in cases:
            with self.subTest(changes=changes, length=length), \
                    mock.patch('src.android_time_fixer.socket.getaddrinfo',
                               return_value=[(socket.AF_INET, socket.SOCK_DGRAM, 17, '', ('192.0.2.1', 123))]), \
                    mock.patch('src.android_time_fixer.socket.socket', return_value=Socket()), \
                    mock.patch('src.android_time_fixer.time.monotonic', side_effect=[100, 100.05]):
                if changes or length != 48:
                    with self.assertRaises(ntplib.NTPException):
                        AndroidTVTimeFixer._query_ntp_server('time.example', 2)
                else:
                    result = AndroidTVTimeFixer._query_ntp_server('time.example', 2)
                    self.assertGreater(result.offset, 31_535_999)
                    self.assertAlmostEqual(0.049, result.delay, places=4)

    def test_five_samples_measure_losses_median_and_variation_with_pauses(self):
        fixer = object.__new__(AndroidTVTimeFixer)
        replies = [SimpleNamespace(delay=delay, offset=31_536_000.0)
                   for delay in (0.01, 0.02, 0.03, 0.1)] + [socket.timeout()]
        with mock.patch.object(fixer, '_query_ntp_server', side_effect=replies) as query, \
                mock.patch('src.android_time_fixer.time.sleep') as pause:
            result = fixer._test_ntp_server('time.example', 5, 2, 1.0)
        self.assertEqual(5, query.call_count)
        self.assertEqual([mock.call(1.0)] * 4, pause.call_args_list)
        self.assertEqual(80, result['success_rate'])
        self.assertAlmostEqual(25, result['median_rtt'])
        self.assertGreater(result['rtt_jitter'], 30)
        self.assertEqual(31_536_000.0, result['offset'])

    def test_ranking_prefers_complete_responses_then_stable_delay(self):
        def result(name, success, median, jitter):
            return dict(server=name, status='Reachable', success_rate=success,
                        median_rtt=median, rtt_jitter=jitter)
        steady = result('steady', 100, 25, 2)
        spiky = result('spiky', 100, 10, 100)
        lossy = result('lossy', 80, 1, 0)
        self.assertEqual(['steady', 'spiky', 'lossy'], [r['server'] for r in
                         sorted([lossy, spiky, steady], key=AndroidTVTimeFixer._ntp_rank)])

    def test_valid_server_is_not_rejected_because_computer_clock_is_wrong(self):
        fixer = object.__new__(AndroidTVTimeFixer)
        fixer.logger = logging.getLogger('ntp-quality-test')
        for offset in (-31_536_000.0, 31_536_000.0):
            with mock.patch.object(fixer, '_test_ntp_server', return_value=dict(
                    status='Reachable', offset=offset, avg_rtt=20, success_rate=100)), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertTrue(fixer.verify_ntp_server('time.example'))


if __name__ == '__main__':
    unittest.main()

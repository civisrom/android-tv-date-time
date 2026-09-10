import socket
import threading
import time
import unittest
from concurrent.futures import CancelledError
from unittest import mock

import ntplib
from src import ntp_network


class NtpNetworkTests(unittest.TestCase):
    def test_timestamp_era_before_after_and_across_2036(self):
        era = 2 ** 32
        for sent, received in [(era - 10, era - 9), (era + 10, era + 11), (era - .02, era - .01)]:
            request = ntplib.NTPPacket(version=4, mode=3, tx_timestamp=sent % era).to_data()
            reply = ntplib.NTPPacket(version=4, mode=4, tx_timestamp=(received + .015) % era)
            reply.stratum = 1
            reply.orig_timestamp = sent % era
            reply.recv_timestamp = received % era
            result = ntp_network.parse_response(reply.to_data(), request, sent, .05)
            self.assertAlmostEqual(received, result.recv_timestamp, places=5)
            self.assertAlmostEqual(received + .015, result.tx_timestamp, places=5)
            self.assertLess(abs(result.offset), 2)

    def test_fallback_uses_second_resolved_address_within_total_deadline(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as responder, \
                socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as silent:
            responder.bind(('127.0.0.1', 0))
            silent.bind(('127.0.0.1', 0))
            responder.settimeout(2)

            def serve():
                data, peer = responder.recvfrom(512)
                request = ntplib.NTPPacket()
                request.from_data(data)
                reply = ntplib.NTPPacket(mode=4, version=4, tx_timestamp=request.tx_timestamp)
                reply.stratum = 1
                reply.orig_timestamp = reply.recv_timestamp = request.tx_timestamp
                responder.sendto(reply.to_data(), peer)

            worker = threading.Thread(target=serve, daemon=True)
            worker.start()
            addresses = [(socket.AF_INET, silent.getsockname()), (socket.AF_INET, responder.getsockname())]
            started = time.monotonic()
            with mock.patch.object(ntp_network, 'resolve_addresses', return_value=addresses):
                self.assertLess(abs(ntp_network.query_ntp('test.example', .8).offset), 1)
            self.assertLess(time.monotonic() - started, 1.5)
            worker.join(2)
            self.assertFalse(worker.is_alive())

    def test_dns_deadline_and_cancellation_do_not_wait_for_blocked_resolver(self):
        release = threading.Event()

        def blocked(*args, **kwargs):
            release.wait(3)
            return []

        try:
            with mock.patch.object(ntp_network.socket, 'getaddrinfo', side_effect=blocked):
                started = time.monotonic()
                with self.assertRaises(socket.timeout):
                    ntp_network.query_ntp('test.example', .15)
                self.assertLess(time.monotonic() - started, 1)
                stopped = threading.Event()
                timer = threading.Timer(.05, stopped.set)
                timer.start()
                started = time.monotonic()
                with self.assertRaises(CancelledError):
                    ntp_network.query_ntp('test.example', 2, stopped)
                self.assertLess(time.monotonic() - started, 1)
                timer.join()
        finally:
            release.set()

    def test_receive_can_be_cancelled_without_waiting_for_full_timeout(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as silent:
            silent.bind(('127.0.0.1', 0))
            stopped = threading.Event()
            timer = threading.Timer(.05, stopped.set)
            timer.start()
            started = time.monotonic()
            with mock.patch.object(ntp_network, 'resolve_addresses', return_value=[(socket.AF_INET, silent.getsockname())]):
                with self.assertRaises(CancelledError):
                    ntp_network.query_ntp('test.example', 10, stopped)
            self.assertLess(time.monotonic() - started, 1)
            timer.join()

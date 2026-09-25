import contextlib
import io
import socket
import ssl
import threading
import time
import unittest
from types import SimpleNamespace
from unittest import mock
from urllib.error import HTTPError

import ntplib

from src import network_check


class NetworkCheckTests(unittest.TestCase):
    def test_full_partial_and_offline_results_do_not_confuse_https_and_ntp(self):
        for https, ntp in ((True, True), (True, False), (False, True), (False, False)):
            with self.subTest(https=https, ntp=ntp), \
                    mock.patch.object(network_check, '_probe_https', return_value=https) as web, \
                    mock.patch.object(network_check, '_probe_ntp', return_value=ntp) as clock:
                result = network_check.check_network()
            self.assertEqual(https, result.https_reachable)
            self.assertEqual(ntp, result.ntp_reachable)
            self.assertEqual(2 if https else 0, result.https_ok)
            self.assertEqual(2 if ntp else 0, result.ntp_ok)
            self.assertCountEqual(network_check.HTTPS_URLS, [call.args[0] for call in web.call_args_list])
            self.assertCountEqual(network_check.NTP_HOSTS, [call.args[0] for call in clock.call_args_list])

    def test_one_failed_provider_does_not_hide_working_connection(self):
        with mock.patch.object(network_check, '_probe_https', side_effect=[OSError('unreachable'), True]), \
                mock.patch.object(network_check, '_probe_ntp', side_effect=[True, OSError('unreachable')]):
            result = network_check.check_network()
        self.assertEqual((1, 1), (result.https_ok, result.ntp_ok))
        self.assertTrue(result.https_reachable and result.ntp_reachable)

    def test_blocked_dns_cannot_hold_startup_or_create_unbounded_workers(self):
        release = threading.Event()
        finished = threading.Event()
        count = 0
        lock = threading.Lock()

        def blocked(*args):
            nonlocal count
            try:
                release.wait(3)
                return True
            finally:
                with lock:
                    count += 1
                    if count == 6:
                        finished.set()

        try:
            with mock.patch.object(network_check, '_probe_https', side_effect=blocked) as web, \
                    mock.patch.object(network_check, '_probe_ntp', side_effect=blocked) as clock, \
                    mock.patch.object(network_check, '_probe_local_network', return_value=True):
                started = time.monotonic()
                first = network_check.check_network(timeout=.1)
                second = network_check.check_network(timeout=.1)
                self.assertLess(time.monotonic() - started, .8)
                self.assertEqual((0, 0), (first.https_ok, first.ntp_ok))
                self.assertEqual((0, 0), (second.https_ok, second.ntp_ok))
                self.assertEqual(6, web.call_count + clock.call_count)
        finally:
            release.set()
            self.assertTrue(finished.wait(2))
            # run() releases its slot just after the probe returns.
            for unused in range(6):
                self.assertTrue(network_check._PROBE_SLOTS.acquire(timeout=1))
            for unused in range(6):
                network_check._PROBE_SLOTS.release()

    def test_local_network_requires_an_active_non_loopback_address(self):
        cases = ((True, socket.AF_INET, '127.0.0.1', False),
                 (True, socket.AF_INET6, '::1', False),
                 (False, socket.AF_INET, '192.0.2.5', False),
                 (True, socket.AF_INET, '0.0.0.0', False),
                 (True, socket.AF_INET, '192.0.2.5', True),
                 (True, socket.AF_INET6, 'fe80::1%wlan0', False))
        for up, family, address, expected in cases:
            with self.subTest(address=address, up=up), \
                    mock.patch.object(network_check.psutil, 'net_if_stats', return_value={'net': SimpleNamespace(isup=up)}), \
                    mock.patch.object(network_check.psutil, 'net_if_addrs', return_value={
                        'net': [SimpleNamespace(family=family, address=address)]}):
                self.assertEqual(expected, network_check._probe_local_network(None, time.monotonic() + 2, threading.Event()))

    def test_local_inspection_failure_remains_unknown(self):
        with mock.patch.object(network_check, '_probe_local_network', side_effect=OSError('interface failure')), \
                mock.patch.object(network_check, '_probe_https', return_value=False), \
                mock.patch.object(network_check, '_probe_ntp', return_value=False):
            result = network_check.check_network()
        self.assertIsNone(result.local_network)

    def test_https_requires_http_response_over_verified_tls(self):
        for status, url, expected in ((204, 'https://example.test/', True),
                                      (500, 'https://example.test/', True),
                                      (200, 'http://example.test/', False)):
            response = SimpleNamespace(status=status, url=url)
            with self.subTest(status=status, url=url), \
                    mock.patch.object(network_check, 'urlopen', return_value=contextlib.nullcontext(response)) as opened:
                actual = network_check._probe_https('https://example.test/', time.monotonic() + 2, threading.Event())
                self.assertEqual(expected, actual)
                self.assertEqual('HEAD', opened.call_args.args[0].method)
                context = opened.call_args.kwargs['context']
                self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
                self.assertTrue(context.check_hostname)
                self.assertGreater(opened.call_args.kwargs['timeout'], 0)

    def test_https_error_response_still_proves_verified_network_access(self):
        for status in (403, 404, 500):
            with self.subTest(status=status), mock.patch.object(network_check, 'urlopen', side_effect=HTTPError(
                    'https://example.test/', status, 'synthetic HTTP failure', {}, None)):
                self.assertTrue(network_check._probe_https('https://example.test/', time.monotonic() + 2, threading.Event()))

    def test_ca_bundle_works_when_installed_python_ca_path_is_absent(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        self.assertEqual(0, context.cert_store_stats()['x509_ca'])
        response = SimpleNamespace(status=204, url='https://example.test/')
        with mock.patch.object(network_check.ssl, 'create_default_context', return_value=context), \
                mock.patch.object(network_check, 'urlopen', return_value=contextlib.nullcontext(response)):
            self.assertTrue(network_check._probe_https('https://example.test/', time.monotonic() + 2, threading.Event()))
        self.assertGreater(context.cert_store_stats()['x509_ca'], 0)
        self.assertTrue(context.check_hostname)
        self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)

    def test_invalid_ntp_and_tls_failure_are_not_success_or_secret_output(self):
        output = io.StringIO()
        with mock.patch.object(network_check, 'query_ntp', side_effect=ntplib.NTPException('invalid origin')), \
                mock.patch.object(network_check, 'urlopen', side_effect=ssl.SSLError('synthetic-proxy-secret')), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            result = network_check.check_network()
        self.assertEqual((0, 0), (result.https_ok, result.ntp_ok))
        self.assertEqual('', output.getvalue())

    def test_expired_probes_do_not_send_requests(self):
        with mock.patch.object(network_check, 'urlopen') as web, \
                mock.patch.object(network_check, 'query_ntp') as clock:
            deadline = time.monotonic() - 1
            self.assertFalse(network_check._probe_https('https://example.test', deadline, threading.Event()))
            self.assertFalse(network_check._probe_ntp('time.example.test', deadline, threading.Event()))
        web.assert_not_called()
        clock.assert_not_called()

    def test_cancellation_reaches_validating_ntp_client(self):
        stopped = threading.Event()
        with mock.patch.object(network_check, 'query_ntp') as query:
            self.assertTrue(network_check._probe_ntp('time.example.test', time.monotonic() + 2, stopped))
        host, timeout, event = query.call_args.args
        self.assertEqual('time.example.test', host)
        self.assertGreater(timeout, 0)
        self.assertIs(stopped, event)

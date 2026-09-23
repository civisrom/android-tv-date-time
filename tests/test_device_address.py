import contextlib
import io
import socket
import unittest
from unittest import mock

from src.android_time_fixer import AndroidTVTimeFixer, AndroidTVTimeFixerError, _MdnsCollector
from src.device_address import format_address, has_explicit_port, parse_address


class DeviceAddressTests(unittest.TestCase):
    def test_ipv6_ports_scopes_and_ipv4_round_trip(self):
        for host in ('127.0.0.1', '::1', '2001:db8::123', 'fe80::1%eth0', 'fe80::1%3', '::ffff:192.0.2.1'):
            with self.subTest(host=host):
                self.assertEqual((host, 37105), parse_address(format_address(host, 37105)))
                self.assertEqual((host, 5555), parse_address(host))
                self.assertTrue(has_explicit_port(format_address(host, 37105)))
                self.assertFalse(has_explicit_port(host))
                self.assertTrue(AndroidTVTimeFixer.validate_ip(format_address(host, 37105)))
        self.assertEqual(('::1', 5555), parse_address('[::1]'))
        self.assertFalse(has_explicit_port('[::1]'))

    def test_malformed_endpoints_and_ambiguous_ipv4_are_rejected(self):
        for value in ('a:b', '1:2:3', '1:::2', '[::1]:0', '[::1]:65536', '[::1]:', '[::1]:+123',
                      '[::1]:５５５５', '[::1]:000001', '[127.0.0.1]:5555', '[::1]garbage', '::1%',
                      '::1%bad/name', 'fe80::1%eth0%2', '::ffff:010.0.0.1', '::1;id', 'bad.example::1',
                      '010.0.0.1', '192.168.01.1:5555', None):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    parse_address(value)
                self.assertFalse(AndroidTVTimeFixer.validate_ip(value))

    def test_ntp_accepts_literal_ipv6_without_uri_or_interface_scope(self):
        for host in ('::1', '2001:db8::123', '::ffff:192.0.2.1'):
            self.assertTrue(AndroidTVTimeFixer.validate_ntp_server(host), host)
        for host in ('[::1]', '[::1]:123', 'fe80::1%eth0', '::ffff:010.0.0.1'):
            self.assertFalse(AndroidTVTimeFixer.validate_ntp_server(host), host)

    def test_mdns_parses_and_sorts_both_families_without_inventing_a_port(self):
        output = '\n'.join(('a _adb._tcp [2001:db8::2]:5555', 'b _adb._tcp 192.0.2.1:5566',
                            'c _adb._tcp [fe80::1%3]:37105', 'd _adb._tcp ::1',
                            'e _adb._tcp [::1]:0', 'f _adb._tcp 999.0.0.1:5555'))
        found = AndroidTVTimeFixer._parse_mdns_services(output, '_adb._tcp')
        self.assertEqual(['192.0.2.1:5566', '[2001:db8::2]:5555', '[fe80::1%3]:37105'],
                         AndroidTVTimeFixer._sort_addresses(found))

    def test_zeroconf_preserves_ipv6_interface_scope(self):
        info = mock.Mock(port=37105)
        info.parsed_scoped_addresses.return_value = ['fe80::1%eth0', '192.0.2.1']
        zc = mock.Mock()
        zc.get_service_info.return_value = info
        collector = _MdnsCollector(zc)
        collector.add_service(zc, '_adb._tcp.local.', 'test')
        self.assertEqual(['[fe80::1%eth0]:37105', '192.0.2.1:37105'], collector.found)

    def test_native_pairing_receives_bracketed_ipv6_endpoint(self):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer.logger = mock.Mock()
        fixer._run_adb = mock.Mock(return_value=(0, 'Successfully paired to [::1]:37105'))
        with contextlib.redirect_stdout(io.StringIO()):
            fixer.pair_device('[::1]:37105', '123456')
        self.assertEqual(['pair', '[::1]:37105'], fixer._run_adb.call_args.args[0])

    def test_pairing_requires_an_explicit_port_in_either_family(self):
        fixer = AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)
        fixer._run_adb = mock.Mock()
        for value in ('192.0.2.1', '::1', '[::1]'):
            with self.subTest(value=value), self.assertRaises(AndroidTVTimeFixerError):
                fixer.pair_device(value, '123456')
        fixer._run_adb.assert_not_called()

    def test_port_probe_connects_to_ipv6_when_platform_supports_it(self):
        try:
            listener = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            listener.bind(('::1', 0))
        except OSError as error:
            if 'listener' in locals():
                listener.close()
            self.skipTest(f'IPv6 loopback unavailable: {error}')
        with listener:
            listener.listen()
            listener.settimeout(2)
            self.assertTrue(AndroidTVTimeFixer._check_port_available('::1', listener.getsockname()[1], .5))
            with listener.accept()[0] as accepted:
                self.assertEqual(b'', accepted.recv(1))

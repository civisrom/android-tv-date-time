import contextlib
import io
import logging
import unittest
from unittest import mock

from src.android_time_fixer import AndroidTVTimeFixer


class AutoSetupDiscoveryTests(unittest.TestCase):
    def fixer(self, services):
        fixer = object.__new__(AndroidTVTimeFixer)
        fixer.device = None
        fixer.connected_ip = ''
        fixer.logger = logging.getLogger('auto-discovery-test')
        fixer.mdns_discover_all = mock.Mock(return_value=services)
        fixer.prompt_adb_port = mock.Mock(return_value=5555)
        fixer.scan_network_for_android_devices = mock.Mock(return_value=['192.168.1.20:5555'])
        fixer.connect_or_reuse = mock.Mock()
        fixer.save_last_ip = mock.Mock()
        fixer._detect_user_region = mock.Mock(return_value=([], None))
        fixer.ntp_servers = {'test': 'time.example'}
        fixer.custom_ntp_servers = []
        fixer._test_ntp_server = mock.Mock(return_value={'status': 'Unreachable'})
        return fixer

    def run_setup(self, fixer, answers):
        with mock.patch('builtins.input', side_effect=answers), contextlib.redirect_stdout(io.StringIO()):
            fixer.auto_setup_ntp()

    def test_one_advertised_address_uses_enter_without_asking_for_port(self):
        for kind, address in [('legacy', '192.168.1.20:5555'), ('connect', '192.168.1.20:37105')]:
            with self.subTest(kind=kind):
                fixer = self.fixer({kind: [address], 'pairing': ['192.168.1.20:42001']})
                self.run_setup(fixer, [''])
                fixer.connect_or_reuse.assert_called_once_with(address)
                fixer.save_last_ip.assert_called_once_with(address)
                fixer.prompt_adb_port.assert_not_called()
                fixer.scan_network_for_android_devices.assert_not_called()
                fixer._test_ntp_server.assert_called_once_with('time.example', 5, 2, 1.0, mock.ANY)

    def test_multiple_addresses_are_deduplicated_sorted_and_selected_explicitly(self):
        fixer = self.fixer({'connect': ['192.168.1.20:37105'],
                            'legacy': ['192.168.1.2:5555', '192.168.1.20:37105']})
        self.run_setup(fixer, ['2'])
        fixer.connect_or_reuse.assert_called_once_with('192.168.1.20:37105')
        fixer.prompt_adb_port.assert_not_called()
        fixer.scan_network_for_android_devices.assert_not_called()

    def test_discovery_missing_or_failed_uses_port_scan_and_never_a_pairing_port(self):
        for services in ({}, {'pairing': ['192.168.1.20:42001']}, RuntimeError('Discovery unavailable')):
            with self.subTest(services=services):
                fixer = self.fixer(services)
                if isinstance(services, Exception):
                    fixer.mdns_discover_all.side_effect = services
                self.run_setup(fixer, [''])
                fixer.prompt_adb_port.assert_called_once_with()
                fixer.scan_network_for_android_devices.assert_called_once_with(5555)
                fixer.connect_or_reuse.assert_called_once_with('192.168.1.20:5555')

    def test_cancel_or_invalid_confirmation_never_connects_or_probes_ntp(self):
        for answers in (['q'], ['oops']):
            fixer = self.fixer({'legacy': ['192.168.1.20:5555']})
            self.run_setup(fixer, answers)
            fixer.connect_or_reuse.assert_not_called()
            fixer._test_ntp_server.assert_not_called()

    def test_multiple_addresses_require_a_valid_number(self):
        for answer in ('', 'q', '0', '3', 'oops'):
            fixer = self.fixer({'legacy': ['192.168.1.2:5555', '192.168.1.20:5555']})
            self.run_setup(fixer, [answer])
            fixer.connect_or_reuse.assert_not_called()
            fixer._test_ntp_server.assert_not_called()

    def test_cancelling_fallback_does_not_scan_or_connect(self):
        fixer = self.fixer({})
        fixer.prompt_adb_port.return_value = None
        self.run_setup(fixer, [])
        fixer.scan_network_for_android_devices.assert_not_called()
        fixer.connect_or_reuse.assert_not_called()
        fixer._test_ntp_server.assert_not_called()


if __name__ == '__main__':
    unittest.main()

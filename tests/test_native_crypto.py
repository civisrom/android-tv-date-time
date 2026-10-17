import subprocess
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import verify_native_crypto as crypto


class NativeCryptoTests(unittest.TestCase):
    def test_backported_stdlib_cannot_excuse_old_embedded_cryptography(self):
        from cryptography.hazmat.backends.openssl.backend import backend
        with patch.object(crypto.sys, 'platform', 'linux'), \
                patch.object(crypto.ssl, 'OPENSSL_VERSION', 'OpenSSL 3.0.2'), \
                patch.object(backend, 'openssl_version_text', return_value='OpenSSL 4.0.1'), \
                patch.dict(crypto.os.environ, {'BUILD_PYTHON_STANDALONE': ''}), \
                patch.object(crypto, 'verify_ubuntu_backport', return_value=[]) as backport, \
                patch.object(Path, 'read_text', return_value='maps'), \
                patch.object(crypto.platform, 'freedesktop_os_release', return_value={}), \
                patch('sys.stdout', new_callable=io.StringIO), self.assertRaises(RuntimeError):
            crypto.main()
        backport.assert_called_once()

    def test_standalone_marker_requires_the_actual_pinned_interpreter(self):
        for marker, version, openssl in [(crypto.pinned.MARKER, (3, 11, 9), crypto.pinned.OPENSSL_VERSION),
                                         ('20260901-3.12.14', (3, 12, 14), crypto.pinned.OPENSSL_VERSION),
                                         (crypto.pinned.MARKER, (3, 12, 15), '3.5.8')]:
            with self.subTest(marker=marker, version=version, openssl=openssl), \
                    patch.dict(crypto.os.environ, {'BUILD_PYTHON_STANDALONE': marker}), \
                    patch.object(crypto.sys, 'version_info', version), \
                    patch.object(crypto.ssl, 'OPENSSL_VERSION', f'OpenSSL {openssl} 29 Sep 2026'), \
                    patch('sys.stdout', new_callable=io.StringIO), self.assertRaisesRegex(RuntimeError, 'interpreter'):
                crypto.main()

    def _audited_main(self, findings, ssl_version='OpenSSL 3.5.9 29 Sep 2026'):
        from cryptography.hazmat.backends.openssl.backend import backend
        with patch.dict(crypto.os.environ, {'BUILD_PYTHON_STANDALONE': ''}), \
                patch.object(crypto.ssl, 'OPENSSL_VERSION', ssl_version), \
                patch.object(crypto.platform, 'python_version', return_value='3.12.15'), \
                patch.object(backend, 'openssl_version_text', return_value='OpenSSL 4.0.3 29 Sep 2026'), \
                patch.object(crypto.osv, 'audit_native', return_value=findings) as audit, \
                patch('sys.stdout', new_callable=io.StringIO) as output:
            crypto.main()
        return audit.call_args.args[0], output.getvalue()

    def test_loaded_runtime_versions_are_audited_against_osv(self):
        components, output = self._audited_main([])
        self.assertEqual({crypto.osv.cpython('3.12.15', 'runtime'), crypto.osv.openssl('3.5.9', 'runtime'),
                          crypto.osv.openssl('4.0.3', 'runtime')}, set(components))
        self.assertIn('no unreviewed vulnerabilities', output)

    def test_only_reviewed_findings_in_bundled_libraries_are_accepted(self):
        reviewed = (crypto.osv.Dependency('GIT', crypto.osv.EXPAT_GIT, 'R_2_8_5', 'runtime'), 'CVE-2026-77214')
        _, output = self._audited_main([reviewed])
        self.assertIn('reviewed, not affected', output)
        other = (crypto.osv.Dependency('GIT', crypto.osv.EXPAT_GIT, 'R_2_8_5', 'runtime'), 'CVE-2099-0001')
        with self.assertRaisesRegex(RuntimeError, 'known vulnerabilities'):
            self._audited_main([reviewed, other])

    def test_expat_exemptions_require_xml_to_stay_out_of_the_executables(self):
        spec = (Path(__file__).resolve().parents[1] / 'pyinstaller.spec').read_text(encoding='utf-8')
        excludes = spec[spec.index('excludes=['):spec.index(']', spec.index('excludes=['))]
        for module in ['xml', 'pyexpat']:
            self.assertIn(f"'{module}',", excludes)
        self.assertTrue(any(name == crypto.osv.EXPAT_GIT for name, _ in crypto.NOT_AFFECTED))

    def _standalone_components(self, system):
        from cryptography.hazmat.backends.openssl.backend import backend
        with patch.dict(crypto.os.environ, {'BUILD_PYTHON_STANDALONE': crypto.pinned.MARKER}), \
                patch.object(crypto.sys, 'platform', system), \
                patch.object(crypto.sys, 'version_info', tuple(map(int, crypto.pinned.CPYTHON_VERSION.split('.')))), \
                patch.object(crypto.ssl, 'OPENSSL_VERSION', f'OpenSSL {crypto.pinned.OPENSSL_VERSION} 29 Sep 2026'), \
                patch.object(crypto.platform, 'python_version', return_value=crypto.pinned.CPYTHON_VERSION), \
                patch.object(backend, 'openssl_version_text', return_value='OpenSSL 4.0.3 29 Sep 2026'), \
                patch('pyexpat.EXPAT_VERSION', 'expat_2.9.0'), patch('zlib.ZLIB_RUNTIME_VERSION', '1.3.2'), \
                patch.object(crypto.osv, 'audit_native', return_value=[]) as audit, \
                patch('sys.stdout', new_callable=io.StringIO):
            crypto.main()
        return set(audit.call_args.args[0])

    def test_standalone_runtime_audits_bundled_expat_and_windows_zlib(self):
        expat = crypto.osv.Dependency('GIT', crypto.osv.EXPAT_GIT, 'R_2_9_0', 'runtime')
        zlib = crypto.osv.Dependency('GIT', crypto.osv.ZLIB_GIT, 'v1.3.2', 'runtime')
        self.assertLessEqual({expat, zlib}, self._standalone_components('win32'))
        # macOS links Apple's system libz, which is serviced with the OS.
        components = self._standalone_components('darwin')
        self.assertIn(expat, components)
        self.assertNotIn(zlib, components)

    def test_osv_finding_in_loaded_runtime_fails_even_above_offline_floor(self):
        finding = (crypto.osv.openssl('3.5.9', 'runtime'), 'CVE-2099-0001')
        with self.assertRaisesRegex(RuntimeError, 'known vulnerabilities'):
            self._audited_main([finding])

    def test_every_supported_branch_requires_its_fixed_patch(self):
        for (major, minor), floor in crypto.PATCH_FLOORS.items():
            with self.subTest(branch=(major, minor)):
                crypto.verify_upstream(f'OpenSSL {major}.{minor}.{floor} 25 Aug 2026')
                crypto.verify_upstream(f'OpenSSL {major}.{minor}.{floor + 1}')
                with self.assertRaises(RuntimeError):
                    crypto.verify_upstream(f'OpenSSL {major}.{minor}.{floor - 1}')

    def test_unknown_and_development_branches_require_review(self):
        for text in ['OpenSSL 3.5.8-dev', 'OpenSSL 4.0.2-alpha1', 'OpenSSL 3.3.99',
                     'OpenSSL 5.0.0', 'LibreSSL 4.2.0', 'OpenSSL 3.5.8\nextra']:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                crypto.verify_upstream(text)

    @unittest.skipUnless(os.name == 'posix', 'Linux maps use POSIX filesystem paths')
    def test_maps_require_both_real_libraries_and_ignore_other_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = [Path(temporary) / name for name in ['libssl.so.3', 'libcrypto.so.3']]
            for path in paths:
                path.touch()
            maps = '\n'.join(f'000-100 r-xp 0 00:00 1 {path}' for path in paths)
            self.assertEqual(sorted(path.resolve() for path in paths), crypto.loaded_openssl_paths(maps + '\n000-100 rw-p 0 00:00 0 [heap]'))
            with self.assertRaises(RuntimeError):
                crypto.loaded_openssl_paths(maps.splitlines()[0])
            with self.assertRaises(RuntimeError):
                crypto.loaded_openssl_paths(maps + ' (deleted)')

    def _backport(self, revision='3.0.2-0ubuntu1.30', status='installed'):
        paths = [Path('/usr/lib/test/libssl.so.3'), Path('/usr/lib/test/libcrypto.so.3')]
        calls = []

        def run(args):
            calls.append(args)
            if args[0] == 'dpkg-query':
                return f'libssl3:amd64\t{revision}\t{status}'
            if int(revision.rsplit('.', 1)[1]) < 30:
                raise subprocess.CalledProcessError(1, args)
            return ''

        with patch.object(crypto, 'loaded_openssl_paths', return_value=paths), \
                patch.object(crypto, '_package_owner', return_value='libssl3:amd64'), \
                patch.object(crypto, '_run', side_effect=run):
            result = crypto.verify_ubuntu_backport('OpenSSL 3.0.2 15 Mar 2022', '', {'ID': 'ubuntu', 'VERSION_ID': '22.04'})
        return result, calls

    def test_backport_requires_loaded_package_and_correct_revision(self):
        result, calls = self._backport()
        self.assertEqual(2, len(result))
        self.assertEqual(2, sum(call[0] == 'dpkg' for call in calls))
        self.assertEqual('3.0.2-0ubuntu1.31', self._backport('3.0.2-0ubuntu1.31')[0][0][2])
        with self.assertRaises(subprocess.CalledProcessError):
            self._backport('3.0.2-0ubuntu1.29')
        with self.assertRaises(RuntimeError):
            self._backport(status='config-files')

    def test_backport_does_not_trust_another_distribution_or_upstream_build(self):
        for version, distro in [('OpenSSL 3.0.13', {'ID': 'ubuntu', 'VERSION_ID': '22.04'}),
                                ('OpenSSL 3.0.2', {'ID': 'debian', 'VERSION_ID': '12'}),
                                ('OpenSSL 3.0.2', {'ID': 'ubuntu', 'VERSION_ID': '24.04'})]:
            with self.subTest(version=version, distro=distro), self.assertRaises(RuntimeError):
                crypto.verify_ubuntu_backport(version, '', distro)

    @unittest.skipUnless(os.name == 'posix', 'Linux usr-merge uses POSIX filesystem paths')
    def test_package_lookup_accepts_usr_merge_alias(self):
        path = Path('/usr/lib/test/libssl.so.3')
        with patch.object(Path, 'resolve', return_value=path), \
                patch.object(crypto, '_run', side_effect=[subprocess.CalledProcessError(1, []),
                                                         'libssl3:amd64: /lib/test/libssl.so.3']):
            self.assertEqual('libssl3:amd64', crypto._package_owner(path))

    def test_package_lookup_rejects_unowned_or_misidentified_library(self):
        path = Path('/custom/libssl.so.3')
        for output in ['other-package: /custom/libssl.so.3', 'libssl3:amd64: /other/libssl.so.3',
                       'libssl3:amd64: /custom/libssl.so.3\nother-package: /custom/libssl.so.3']:
            with self.subTest(output=output), patch.object(crypto, '_run', return_value=output), self.assertRaises(RuntimeError):
                crypto._package_owner(path)


if __name__ == '__main__':
    unittest.main()

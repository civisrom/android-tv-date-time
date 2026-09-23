import unittest

from scripts.verify_macos_crypto import openssl_dependencies, verify_openssl_version


class MacOSCryptoBuildTests(unittest.TestCase):
    def test_rejects_runner_openssl_with_known_fixed_vulnerabilities(self):
        for version in ['OpenSSL 3.6.3 9 Jun 2026', 'OpenSSL 3.6.4-dev', 'LibreSSL 4.2.0']:
            with self.subTest(version=version), self.assertRaises(RuntimeError):
                verify_openssl_version(version)

    def test_accepts_fixed_openssl_and_later_patch(self):
        for version in ['OpenSSL 3.6.4 25 Aug 2026', 'OpenSSL 3.6.5']:
            with self.subTest(version=version):
                verify_openssl_version(version)

    def test_rejects_homebrew_and_rpath_openssl_dependencies(self):
        output = '''/tmp/cryptography/_rust.abi3.so:
    /usr/local/opt/openssl@3/lib/libssl.3.dylib (compatibility version 3.0.0, current version 3.6.0)
    @rpath/libcrypto.3.dylib (compatibility version 3.0.0, current version 3.6.0)
    /usr/lib/libSystem.B.dylib (compatibility version 1.0.0, current version 1351.0.0)
'''
        self.assertEqual(['/usr/local/opt/openssl@3/lib/libssl.3.dylib', '@rpath/libcrypto.3.dylib'],
                         openssl_dependencies(output))

    def test_accepts_static_openssl_with_normal_macos_system_libraries(self):
        output = '''/tmp/libssl-folder/cryptography/_rust.abi3.so:
    /usr/lib/libSystem.B.dylib (compatibility version 1.0.0, current version 1351.0.0)
    /usr/lib/libiconv.2.dylib (compatibility version 7.0.0, current version 7.0.0)
'''
        self.assertEqual([], openssl_dependencies(output))

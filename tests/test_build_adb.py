import hashlib
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

from scripts import build_adb


class BuildAdbTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_download_rejects_a_checksum_mismatch(self):
        payload = b'android-tools source'
        with mock.patch('urllib.request.urlopen', return_value=io.BytesIO(payload)):
            build_adb.download('https://example.invalid/a.tar.xz', self.root / 'ok.tar.xz',
                               hashlib.sha256(payload).hexdigest())
        self.assertEqual(payload, (self.root / 'ok.tar.xz').read_bytes())
        with mock.patch('urllib.request.urlopen', return_value=io.BytesIO(payload)), \
                self.assertRaisesRegex(RuntimeError, 'SHA-256'):
            build_adb.download('https://example.invalid/a.tar.xz', self.root / 'bad.tar.xz', '0' * 64)

    def test_extraction_rejects_paths_outside_the_destination(self):
        archive = self.root / 'escape.tar.xz'
        with tarfile.open(archive, 'w:xz') as output:
            entry = tarfile.TarInfo('../escaped.txt')
            entry.size = 1
            output.addfile(entry, io.BytesIO(b'x'))
        with self.assertRaises(tarfile.FilterError):
            build_adb.extract(archive, self.root / 'sources')
        self.assertFalse((self.root / 'escaped.txt').exists())

    def test_vendored_libusb_is_replaced_by_the_release_sources(self):
        android_tools = self.root / 'android-tools'
        libusb = self.root / 'libusb-release'
        for path, text in [(android_tools / 'vendor/libusb/libusb/core.c', 'snapshot'),
                           (android_tools / 'vendor/libusb/libusb/stale.c', 'snapshot'),
                           (android_tools / 'vendor/libusb/linux/config.h', 'aosp config'),
                           (libusb / 'libusb/core.c', 'release'), (libusb / 'COPYING', 'LGPL')]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        build_adb.replace_libusb(android_tools, libusb)
        vendored = android_tools / 'vendor/libusb'
        self.assertEqual('release', (vendored / 'libusb/core.c').read_text())
        self.assertFalse((vendored / 'libusb/stale.c').exists())
        self.assertEqual('aosp config', (vendored / 'linux/config.h').read_text())
        self.assertEqual('LGPL', (vendored / 'COPYING').read_text())
        with self.assertRaisesRegex(RuntimeError, 'layout'):
            build_adb.replace_libusb(android_tools, self.root / 'missing')

    def test_built_adb_must_report_the_pinned_source_and_bundle_rpath(self):
        version = f'Android Debug Bridge version 1.0.41\nVersion {build_adb.SOURCE_MARKER}\n'

        def run(rpath):
            def fake(args, **kwargs):
                stdout = version if args[1] == 'version' else rpath + '\n'
                return subprocess.CompletedProcess(args, 0, stdout, '')
            return fake

        with mock.patch.object(build_adb.subprocess, 'run', side_effect=run(build_adb.RPATH)):
            self.assertEqual(f'Version {build_adb.SOURCE_MARKER}', build_adb.verify(Path('adb')))
        with mock.patch.object(build_adb.subprocess, 'run', side_effect=run('/usr/lib')), \
                self.assertRaisesRegex(RuntimeError, 'Unexpected adb build'):
            build_adb.verify(Path('adb'))

    def test_apt_package_list_is_printed_for_the_workflows(self):
        with mock.patch.object(sys, 'argv', ['build_adb.py', '--print-apt-packages']), \
                mock.patch('sys.stdout', new_callable=io.StringIO) as output:
            build_adb.main()
        self.assertEqual(' '.join(build_adb.APT_PACKAGES), output.getvalue().strip())
        self.assertIn('patchelf', build_adb.APT_PACKAGES)

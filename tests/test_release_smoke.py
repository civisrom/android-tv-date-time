import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
from zipfile import ZipFile

from scripts import release_archive, smoke_linux_systems, smoke_windows_arm


class ReleaseSmokeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def archive(self, names, digest=None):
        archive = self.root / 'AndroidTVTimeFixer-linux-arm64.zip'
        with ZipFile(archive, 'w') as output:
            for name in names:
                output.writestr(name, b'build')
        digest = digest or hashlib.sha256(archive.read_bytes()).hexdigest()
        Path(f'{archive}.sha256').write_text(f'{digest}  {archive.name}\n', encoding='ascii')
        return archive

    def test_archive_is_unpacked_only_with_a_matching_checksum_and_flat_names(self):
        archive = self.archive(['AndroidTVTimeFixer', 'LICENSE'])
        self.assertTrue((release_archive.unpack_verified(archive, self.root / 'ok') / 'LICENSE').is_file())
        with self.assertRaisesRegex(RuntimeError, 'SHA-256'):
            release_archive.unpack_verified(self.archive(['LICENSE'], '0' * 64), self.root / 'bad')
        with self.assertRaisesRegex(RuntimeError, 'Unexpected path'):
            release_archive.unpack_verified(self.archive(['../escaped']), self.root / 'escape')
        self.assertFalse((self.root / 'escaped').exists())

    def test_clean_system_run_needs_adb_menu_and_no_loader_errors(self):
        good = ('Main Menu\nAndroid Debug Bridge version 1.0.41\nVersion 37.0.0p1-android-tools\n'
                'List of devices attached\n')
        smoke_linux_systems.check('Debian 12', 0, good)
        for returncode, output in [(1, good), (0, 'Main Menu\n'), (0, good.replace('List of devices', '')),
                                   (0, good + 'adb: error while loading shared libraries: libprotobuf.so.23'),
                                   (0, good + 'Traceback (most recent call last)')]:
            with self.subTest(output=output[-40:]), self.assertRaises(AssertionError):
                smoke_linux_systems.check('Debian 12', returncode, output)

    def test_clean_system_images_are_pinned_by_digest(self):
        for image in smoke_linux_systems.IMAGES.values():
            self.assertRegex(image, r'^[a-z]+:[0-9.]+@sha256:[0-9a-f]{64}$')

    def test_pe_machine_distinguishes_x64_from_arm64(self):
        for machine in (0x8664, 0xAA64):
            image = self.root / f'{machine:x}.exe'
            header = bytearray(512)
            header[:2] = b'MZ'
            struct.pack_into('<I', header, 0x3C, 0x80)
            header[0x80:0x84] = b'PE\0\0'
            struct.pack_into('<H', header, 0x84, machine)
            image.write_bytes(bytes(header))
            self.assertEqual(machine, smoke_windows_arm.pe_machine(image))
        for name, content in [('text.exe', b'not an executable'), ('short.exe', b'MZ' + b'\0' * 10),
                              ('offset.exe', b'MZ' + b'\0' * 58 + b'\xff\xff\0\0')]:
            (self.root / name).write_bytes(content)
            with self.subTest(name=name), self.assertRaises(ValueError):
                smoke_windows_arm.pe_machine(self.root / name)

import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

from scripts.setup_build_python import build_target, extract_verified, install_python


class BuildPythonTests(unittest.TestCase):
    def test_platform_selection_keeps_linux_and_distinguishes_macos_architectures(self):
        self.assertIsNone(build_target('linux', 'x86_64'))
        self.assertEqual('aarch64-apple-darwin', build_target('darwin', 'arm64'))
        self.assertEqual('x86_64-apple-darwin', build_target('darwin', 'x86_64'))
        self.assertEqual('x86_64-pc-windows-msvc', build_target('win32', 'AMD64'))
        with self.assertRaises(RuntimeError):
            build_target('win32', 'ARM64')

    def archive(self, root, name='python/LICENSE.txt'):
        path = root / 'python.tar.gz'
        with tarfile.open(path, 'w:gz') as archive:
            entry = tarfile.TarInfo(name)
            content = b'test fixture'
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def test_checksum_failure_never_extracts_distribution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, _ = self.archive(root)
            with self.assertRaisesRegex(RuntimeError, 'SHA-256'), mock.patch('tarfile.open') as extract:
                extract_verified(archive, root / 'destination', '0' * 64)
            extract.assert_not_called()

    def test_verified_distribution_extracts_and_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, digest = self.archive(root)
            extract_verified(archive, root / 'destination', digest)
            self.assertEqual(b'test fixture', (root / 'destination/python/LICENSE.txt').read_bytes())
            archive, digest = self.archive(root, '../escaped.txt')
            with self.assertRaises(tarfile.FilterError):
                extract_verified(archive, root / 'destination', digest)
            self.assertFalse((root / 'escaped.txt').exists())

    def test_existing_destination_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / 'keep.txt'
            marker.write_text('existing')
            with self.assertRaises(FileExistsError), mock.patch('urllib.request.urlopen') as download:
                install_python(root, 'x86_64-apple-darwin')
            download.assert_not_called()
            self.assertEqual('existing', marker.read_text())

    def test_failed_download_removes_only_its_new_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / 'keep.txt'
            marker.write_text('existing')
            destination = root / 'runtime'
            with mock.patch('urllib.request.urlopen', side_effect=OSError('offline')):
                with self.assertRaises(OSError):
                    install_python(destination, 'x86_64-apple-darwin')
            self.assertFalse(destination.exists())
            self.assertEqual('existing', marker.read_text())

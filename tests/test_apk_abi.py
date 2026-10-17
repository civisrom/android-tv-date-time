import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from zipfile import ZIP_STORED, ZipFile

spec = importlib.util.spec_from_file_location("apk_abi", Path(__file__).resolve().parents[1] / "scripts/verify_android_apk.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def elf64(machine=183, alignment=16384, address=0):
    data = bytearray(64 + 56)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<II6Q", data, 64, 1, 5, 0, address, 0, 1, 1, alignment)
    return bytes(data)


class ApkAbiTests(unittest.TestCase):
    def test_arm64_16k_library_passes(self):
        module.verify_elf(elf64(), "arm64-v8a")

    def test_native_libraries_need_a_locked_runtime_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with ZipFile(apk, "w", ZIP_STORED) as archive:
                archive.writestr("lib/arm64-v8a/libunknown.so", elf64())
            with self.assertRaisesRegex(ValueError, "no locked runtime source"):
                module.verify_apk(apk, {"androidx.graphics:graphics-path"})

    def test_every_listed_native_source_is_locked_for_the_app_runtime(self):
        self.assertLessEqual(set(module.NATIVE_SOURCES.values()), module.locked_runtime_modules())

    def test_wrong_architecture_and_4k_segments_are_rejected(self):
        for data in (elf64(machine=62), elf64(alignment=4096), elf64(address=4096), elf64()[:70]):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                module.verify_elf(data, "arm64-v8a")

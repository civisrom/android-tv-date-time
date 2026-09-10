#!/usr/bin/env python3
"""Verify native ABI identity and 16 KiB ELF/ZIP alignment in the actual APK."""
import argparse
from pathlib import Path
import struct
from zipfile import ZIP_STORED, ZipFile

ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}
PAGE = 16384


def verify_elf(data, abi):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Not a supported little-endian ELF")
    bits, machine = ABIS[abi]
    if data[4] != bits or struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError("Native library does not match its ABI directory")
    if bits == 1:
        offset = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        layout = "<8I"
    else:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        layout = "<II6Q"
    if size != struct.calcsize(layout) or count == 0 or offset + count * size > len(data):
        raise ValueError("Invalid ELF program header table")
    loads = 0
    for index in range(count):
        header = struct.unpack_from(layout, data, offset + index * size)
        if header[0] != 1:
            continue
        loads += 1
        file_offset, address = header[1:3] if bits == 1 else header[2:4]
        alignment = header[-1]
        if alignment < PAGE or file_offset % PAGE != address % PAGE:
            raise ValueError("Native load segment does not support 16 KiB pages")
    if loads == 0:
        raise ValueError("ELF has no loadable segments")


def verify_apk(path):
    seen = set()
    count = 0
    with ZipFile(path) as archive, Path(path).open("rb") as raw:
        for item in archive.infolist():
            if not item.filename.startswith("lib/") or not item.filename.endswith(".so"):
                continue
            parts = item.filename.split("/")
            if len(parts) != 3 or parts[1] not in ABIS:
                raise ValueError("Unexpected native ABI")
            verify_elf(archive.read(item), parts[1])
            if item.compress_type != ZIP_STORED:
                raise ValueError("Native library is compressed; mmap alignment cannot be verified")
            raw.seek(item.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", raw.read(4))
            if (item.header_offset + 30 + name_size + extra_size) % PAGE:
                raise ValueError("Native ZIP entry is not aligned to 16 KiB")
            seen.add(parts[1])
            count += 1
    if seen != set(ABIS):
        raise ValueError("APK must include ARMv7, ARM64, x86 and x86_64")
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    count = verify_apk(args.apk)
    print(f"APK verified: {count} native libraries, 4 ABIs, ELF and ZIP alignment 16 KiB.")

"""Распаковывает архив сборки после проверки его файла .sha256."""
import hashlib
from pathlib import Path
from zipfile import ZipFile


def unpack_verified(archive, destination):
    archive = Path(archive)
    expected = Path(f'{archive}.sha256').read_text(encoding='ascii').split()
    if expected != [hashlib.sha256(archive.read_bytes()).hexdigest(), archive.name]:
        raise RuntimeError(f'{archive.name} does not match its SHA-256 file')
    destination = Path(destination)
    with ZipFile(archive) as source:
        for name in source.namelist():
            if Path(name).name != name:
                raise RuntimeError(f'Unexpected path in {archive.name}: {name}')
        source.extractall(destination)
    return destination

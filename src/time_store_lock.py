"""Межпроцессная блокировка изменений локальных снимков и профилей времени."""
from contextlib import contextmanager
import errno
import math
import os
from pathlib import Path
import time


@contextmanager
def time_store_lock(path, timeout=5.0):
    """Блокирует отдельный постоянный lock-файл до завершения read-modify-write.

    JSON заменяется атомарно, поэтому блокировать сам JSON нельзя. Lock-файл
    не удаляется: иначе два процесса могут заблокировать разные inode.
    ОС освобождает блокировку и при аварийном завершении владельца.
    """
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError('Lock timeout must be positive and finite')
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    acquired = False
    try:
        deadline = time.monotonic() + timeout
        while True:
            try:
                if os.name == 'nt':
                    import msvcrt
                    os.lseek(fd, 0, os.SEEK_SET)
                    # Byte ranges may extend beyond EOF; no content is needed.
                    msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
                break
            except OSError as error:
                if error.errno not in (errno.EACCES, errno.EAGAIN):
                    raise
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('Time settings storage is busy') from error
                time.sleep(min(.05, remaining))
        yield
    finally:
        try:
            if acquired:
                if os.name == 'nt':
                    import msvcrt
                    os.lseek(fd, 0, os.SEEK_SET)
                    msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)

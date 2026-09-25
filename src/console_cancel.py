"""Отмена ожидания ADB без фонового чтения последующих пунктов меню."""
import os
import queue
import select
import sys
import threading


def cancel_requested():
    if not sys.stdin.isatty():
        return False
    if os.name == 'nt':
        import msvcrt
        return msvcrt.kbhit() and msvcrt.getwch().lower() in ('q', '\x03')
    ready, _, _ = select.select([sys.stdin], [], [], 0)
    return bool(ready) and sys.stdin.readline().strip().lower() == 'q'


def cancellable_connect(device, **kwargs):
    completed = queue.Queue(maxsize=1)
    cancelled = threading.Event()

    def connect():
        try:
            completed.put((device.connect(**kwargs), None))
        except BaseException as error:
            completed.put((None, error))
        finally:
            if cancelled.is_set():
                try:
                    device.close()
                except Exception:
                    pass

    worker = threading.Thread(target=connect, name='adb-authorization', daemon=True)
    worker.start()
    pending = True
    try:
        while True:
            if cancel_requested():
                raise KeyboardInterrupt()
            try:
                result, error = completed.get(timeout=.1)
            except queue.Empty:
                continue
            pending = False
            if error is not None:
                raise error
            return result
    finally:
        if pending:
            cancelled.set()
            try:
                # AdbDevice.close ждёт lock, удерживаемый AUTH. Закрытие
                # собственного TCP-сокета разблокирует AUTH немедленно.
                manager = getattr(device, '_io_manager', None)
                if manager is not None:
                    manager._transport.close()
                else:
                    device.close()
            except Exception:
                pass
        worker.join(timeout=1)

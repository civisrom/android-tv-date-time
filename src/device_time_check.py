"""Чтение часов устройства и ограниченное наблюдение без изменения настроек.

shell(command, timeout) обязан соблюдать переданный общий таймаут команды.
Вызовы ADB выполняются последовательно, без оставляемых фоновых потоков.
Совпадение часов не устанавливает источник синхронизации Android.
"""
from concurrent.futures import CancelledError
from dataclasses import dataclass
from datetime import datetime, timezone
import math
import re
import threading
import time

try:
    from .ntp_network import query_ntp
except ImportError:
    from ntp_network import query_ntp


MAX_REFERENCE_AGE = 30.0
MAX_MONITOR_SECONDS = 600.0
MIN_MONITOR_INTERVAL = 30.0
_MAX_EPOCH = 253_402_300_799


@dataclass(frozen=True)
class TimeCheck:
    status: str
    difference_seconds: float | None = None
    uncertainty_seconds: float | None = None
    reference_server: str | None = None
    device_time_seconds: int | None = None
    measured_at_utc: str | None = None
    measured_at_monotonic: float | None = None

    def status_at(self, now=None):
        """Статус сохранённого измерения с явной отметкой устаревания."""
        if self.device_time_seconds is None or self.measured_at_monotonic is None:
            return self.status
        age = (time.monotonic() if now is None else now) - self.measured_at_monotonic
        return self.status if 0 <= age <= MAX_REFERENCE_AGE else 'STALE'


@dataclass(frozen=True)
class MonitorSummary:
    samples: int
    skipped: int
    elapsed_seconds: float
    stopped: bool


def legacy_shell_with_timeout(device, command, timeout):
    """Один monotonic budget на OPEN, пакеты и закрытие legacy shell.

    adb_shell 0.4.4 имеет отдельные таймауты этапов, основанные на time.time().
    CLI вызывает устройство последовательно; временно ограничиваем каждый
    I/O именно этого транспорта. После ошибки закрываем незавершённый поток.
    """
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError('ADB timeout must be positive and finite')
    deadline = time.monotonic() + timeout
    transport = device._io_manager._transport
    original_read, original_write = transport.bulk_read, transport.bulk_write
    missing = object()
    previous = {name: vars(transport).get(name, missing) for name in ('bulk_read', 'bulk_write')}

    def remaining(requested):
        budget = deadline - time.monotonic()
        if budget <= 0:
            raise TimeoutError('ADB command deadline exceeded')
        return budget if requested is None else min(budget, requested)

    def read(numbytes, transport_timeout_s=None):
        return original_read(numbytes, remaining(transport_timeout_s))

    def write(data, transport_timeout_s=None):
        return original_write(data, remaining(transport_timeout_s))

    try:
        transport.bulk_read, transport.bulk_write = read, write
        output = device.shell(command, transport_timeout_s=timeout, read_timeout_s=timeout, timeout_s=timeout)
        remaining(None)
        return output
    except BaseException:
        try:
            device.close()
        except Exception:
            pass
        raise
    finally:
        for name, value in previous.items():
            if value is missing:
                delattr(transport, name)
            else:
                setattr(transport, name, value)


def _cancelled(stopped):
    if stopped is not None and stopped.is_set():
        raise CancelledError()


def verify_device_time(shell, servers, timeout=3.0, stopped=None):
    """Сравнивает date +%s с первым доступным NTP из максимум четырёх адресов.

    timeout ограничивает всё измерение, включая DNS/NTP и ADB. Для ADB
    заранее оставляется доля бюджета. NTP привязывается к monotonic, поэтому
    изменение часов компьютера между запросами не искажает сравнение.
    """
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError('Clock check timeout must be positive and finite')
    _cancelled(stopped)
    deadline = time.monotonic() + timeout
    candidates = list(dict.fromkeys(server.strip() for server in servers if server.strip()))[:4]
    if not candidates:
        return TimeCheck('NO_SERVER')

    reference_server = None
    for index, server in enumerate(candidates):
        _cancelled(stopped)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        # Одна доля остаётся для ADB, остальные — ещё не проверенным адресам.
        budget = remaining / (len(candidates) - index + 1)
        try:
            sample = query_ntp(server, budget, stopped)
            reference_elapsed = sample.received_monotonic
            delay = sample.delay
            reference = sample.tx_time + max(0.0, delay) / 2
            if (not math.isfinite(reference_elapsed)
                    or not math.isfinite(reference) or not 0 < reference <= _MAX_EPOCH
                    or not math.isfinite(delay) or delay < -0.001):
                raise ValueError('Invalid NTP reference')
            reference_server = server
            break
        except CancelledError:
            raise
        except Exception:
            continue
    _cancelled(stopped)
    if reference_server is None:
        return TimeCheck('NTP_UNAVAILABLE')

    started = time.monotonic()
    remaining = deadline - started
    if remaining <= 0:
        return TimeCheck('NTP_UNAVAILABLE', reference_server=reference_server)
    try:
        output = shell('date +%s', remaining).strip()
    except Exception:
        _cancelled(stopped)
        return TimeCheck('DEVICE_UNAVAILABLE', reference_server=reference_server)
    finished = time.monotonic()
    _cancelled(stopped)
    if not re.fullmatch(r'[0-9]{1,12}', output) or int(output) > _MAX_EPOCH:
        return TimeCheck('DEVICE_UNAVAILABLE', reference_server=reference_server)
    seconds = int(output)
    if (finished > deadline or started < reference_elapsed or finished < started
            or finished - reference_elapsed > MAX_REFERENCE_AGE):
        return TimeCheck('UNCERTAIN', reference_server=reference_server,
                         device_time_seconds=seconds, measured_at_monotonic=finished)

    duration = finished - started
    reference_at_read = reference + (started - reference_elapsed) + duration / 2
    if not 0 < reference_at_read <= _MAX_EPOCH:
        return TimeCheck('UNCERTAIN', reference_server=reference_server,
                         device_time_seconds=seconds, measured_at_monotonic=finished)
    # date +%s округляет вниз. Берём середину секунды и обоих обменов.
    difference = seconds + 0.5 - reference_at_read
    uncertainty = 0.502 + duration / 2 + max(0.0, delay) / 2
    if abs(difference) + uncertainty <= 5.0:
        status = 'MATCH'
    elif abs(difference) - uncertainty > 5.0:
        status = 'MISMATCH'
    else:
        status = 'UNCERTAIN'
    measured_at = datetime.fromtimestamp(reference_at_read, timezone.utc).isoformat()
    return TimeCheck(status, difference, uncertainty, reference_server, seconds,
                     measured_at, finished)


def monitor_device_time(shell, servers, emit, stop_event=None, duration=600.0, interval=30.0):
    """Синхронная сессия до 10 минут, не чаще одного измерения в 30 секунд.

    emit получает каждую попытку, включая ошибки; прежний MATCH не повторяется
    при потере связи. skipped — попытки без прочитанных часов устройства.
    Ctrl+C передаётся вызывающему; stop_event прерывает ожидание между замерами.
    """
    if (not math.isfinite(duration) or not 0 < duration <= MAX_MONITOR_SECONDS
            or not math.isfinite(interval) or interval < MIN_MONITOR_INTERVAL):
        raise ValueError('Monitor requires 0 < duration <= 600 and interval >= 30')
    stop_event = stop_event if stop_event is not None else threading.Event()
    started = time.monotonic()
    deadline = started + duration
    scheduled = started
    samples = skipped = 0
    while not stop_event.is_set():
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        wait = min(scheduled - time.monotonic(), remaining)
        if wait > 0 and stop_event.wait(wait):
            break
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        # После сна компьютера/задержки планировщика не наверстываем пропущенные
        # интервалы серией запросов: считаем 30 секунд от реального старта.
        scheduled = time.monotonic() + interval
        try:
            sample = verify_device_time(shell, servers, min(3.0, remaining), stop_event)
        except CancelledError:
            break
        emit(sample)
        samples += 1
        skipped += sample.device_time_seconds is None
    return MonitorSummary(samples, skipped, time.monotonic() - started, stop_event.is_set())

"""Ограниченный по времени SNTP: все адреса DNS, отмена и смена эпохи в 2036 году."""
import math
import queue
import socket
import threading
import time
from concurrent.futures import CancelledError

import ntplib


_DNS_SLOTS = threading.BoundedSemaphore(4)
_ERA = 2 ** 32


def _remaining(deadline, stopped=None):
    if stopped is not None and stopped.is_set():
        raise CancelledError()
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise socket.timeout('NTP deadline exceeded')
    return remaining


def resolve_addresses(server, deadline, stopped=None):
    # getaddrinfo нельзя прервать переносимым способом. Не более четырёх
    # daemon-потоков могут ждать ОС; вызывающий ограничен общим дедлайном.
    while not _DNS_SLOTS.acquire(timeout=min(0.1, _remaining(deadline, stopped))):
        pass
    result = queue.Queue(maxsize=1)

    def resolve():
        try:
            result.put(socket.getaddrinfo(server, 123, type=socket.SOCK_DGRAM))
        except Exception as error:
            result.put(error)
        finally:
            _DNS_SLOTS.release()

    threading.Thread(target=resolve, name='ntp-dns', daemon=True).start()
    while True:
        try:
            value = result.get(timeout=min(0.1, _remaining(deadline, stopped)))
            if isinstance(value, Exception):
                raise value
            unique = list(dict.fromkeys((entry[0], entry[4]) for entry in value))
            if not unique:
                raise socket.gaierror('No NTP addresses')
            return sorted(unique, key=lambda entry: entry[0] != socket.AF_INET)
        except queue.Empty:
            pass


def parse_response(response, request, sent, elapsed):
    if len(response) < 48 or response[24:32] != request[40:48]:
        raise ntplib.NTPException('Invalid NTP response or origin timestamp')
    stats = ntplib.NTPStats()
    stats.from_data(response)
    if (stats.version not in (3, 4) or stats.mode != 4 or stats.leap == 3
            or not 1 <= stats.stratum <= 15
            or response[32:40] == bytes(8) or response[40:48] == bytes(8)):
        raise ntplib.NTPException('Invalid or unsynchronized NTP response')
    # NTP передаёт младшие 32 бита секунд. Эпоха выбирается относительно
    # часов клиента (необходим ориентир с точностью ±68 лет, как в RFC 5905).
    stats.orig_timestamp = sent
    stats.recv_timestamp += round((sent - stats.recv_timestamp) / _ERA) * _ERA
    stats.tx_timestamp += round((stats.recv_timestamp - stats.tx_timestamp) / _ERA) * _ERA
    stats.dest_timestamp = sent + elapsed
    if (elapsed < 0 or stats.tx_timestamp < stats.recv_timestamp
            or not math.isfinite(stats.offset) or not math.isfinite(stats.delay) or stats.delay < -0.001):
        raise ntplib.NTPException('Invalid NTP timestamps')
    return stats


def query_ntp(server, timeout, stopped=None):
    deadline = time.monotonic() + timeout
    addresses = resolve_addresses(server, deadline, stopped)
    error = socket.timeout('No NTP response')
    for index, (family, address) in enumerate(addresses):
        remaining = _remaining(deadline, stopped)
        # Оставляем время каждому адресу, включая IPv6, вместо исчерпания
        # общего таймаута первым молчащим узлом пула.
        address_deadline = time.monotonic() + remaining / (len(addresses) - index)
        try:
            with socket.socket(family, socket.SOCK_DGRAM) as client:
                client.connect(address)
                sent = ntplib.system_to_ntp_time(time.time())
                request = ntplib.NTPPacket(mode=3, version=4, tx_timestamp=sent % _ERA).to_data()
                started = time.monotonic()
                client.settimeout(min(0.1, _remaining(address_deadline, stopped)))
                client.send(request)
                while True:
                    client.settimeout(min(0.1, _remaining(address_deadline, stopped)))
                    try:
                        response = client.recv(512)
                        break
                    except socket.timeout:
                        _remaining(address_deadline, stopped)
                return parse_response(response, request, sent, time.monotonic() - started)
        except (OSError, ntplib.NTPException) as failure:
            error = failure
    raise error

"""Короткая проверка HTTPS и NTP перед запуском интерактивного меню."""
from dataclasses import dataclass
import ipaddress
import queue
import socket
import ssl
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

import psutil
import certifi

try:
    from .ntp_network import query_ntp
except ImportError:
    from ntp_network import query_ntp


HTTPS_URLS = (
    'https://www.google.com/generate_204',
    'https://www.cloudflare.com/cdn-cgi/trace',
)
NTP_HOSTS = ('time.cloudflare.com', 'ntp.msk-ix.ru')
# DNS внутри urllib может игнорировать сокетный таймаут. Проверка не ждёт
# такой поток; ограничение не позволяет повторным вызовам копить их без конца.
_PROBE_SLOTS = threading.BoundedSemaphore(6)


@dataclass(frozen=True)
class NetworkCheckResult:
    https_ok: int
    ntp_ok: int
    elapsed_seconds: float
    local_network: bool | None = None
    https_total: int = len(HTTPS_URLS)
    ntp_total: int = len(NTP_HOSTS)
    https_skipped: int = 0
    ntp_skipped: int = 0

    @property
    def https_reachable(self):
        return self.https_ok > 0

    @property
    def ntp_reachable(self):
        return self.ntp_ok > 0


def _probe_https(url, deadline, stopped):
    remaining = deadline - time.monotonic()
    if remaining <= 0 or stopped.is_set():
        return False
    # urlopen использует штатные настройки proxy и проверку сертификатов.
    # Исключения могут содержать пароль proxy, поэтому они не выводятся.
    request = Request(url, method='HEAD')
    context = ssl.create_default_context()
    # Системные/корпоративные roots сохраняются. Публичные CA едут с готовой
    # сборкой: путь OpenSSL на Mac может вести к отсутствующему Python.
    context.load_verify_locations(cafile=certifi.where())
    try:
        response = urlopen(request, timeout=remaining, context=context)
    except HTTPError as error:
        # 403/404/500 тоже подтверждают HTTPS-доступ: это проверка сети,
        # а не работоспособности конкретной страницы. Ошибка CONNECT proxy
        # или проверки TLS сюда не попадает.
        response = error
    with response as reply:
        return (not stopped.is_set() and urlsplit(reply.url).scheme == 'https'
                and 200 <= reply.status < 600)


def _probe_ntp(host, deadline, stopped):
    remaining = deadline - time.monotonic()
    if remaining <= 0 or stopped.is_set():
        return False
    # Общий NTP-клиент проверяет серверный режим, stratum, метки и источник.
    query_ntp(host, remaining, stopped)
    return not stopped.is_set()


def is_virtual_interface(name):
    name = name.lower()
    return (any(marker in name for marker in ('virtual', 'vbox', 'vmware', 'hyper-v', 'vethernet',
                                             'docker', 'wsl', 'npcap', 'loopback', 'vpn'))
            or name.startswith(('veth', 'br-', 'tun', 'tap', 'utun', 'wg', 'tailscale')))


def _probe_local_network(unused, deadline, stopped):
    states = psutil.net_if_stats()
    for name, addresses in psutil.net_if_addrs().items():
        if name not in states or not states[name].isup or is_virtual_interface(name):
            continue
        for address in addresses:
            if address.family in (socket.AF_INET, socket.AF_INET6):
                try:
                    ip = ipaddress.ip_address(address.address.split('%', 1)[0])
                except ValueError:
                    continue
                if not (ip.is_loopback or ip.is_unspecified or ip.is_link_local or ip.is_multicast):
                    return True
    return False


def check_network(timeout=4.0):
    """Не блокирует меню дольше общего таймаута, включая системный DNS."""
    if timeout <= 0:
        raise ValueError('Network check timeout must be positive')
    started = time.monotonic()
    deadline = started + timeout
    stopped = threading.Event()
    completed = queue.Queue()

    def run(protocol, probe, address):
        try:
            reachable = probe(address, deadline, stopped)
        except Exception:
            reachable = None if protocol == 'local' else False
        finally:
            _PROBE_SLOTS.release()
        completed.put((protocol, reachable))

    probes = [('local', _probe_local_network, None)]
    probes += [('https', _probe_https, url) for url in HTTPS_URLS]
    probes += [('ntp', _probe_ntp, host) for host in NTP_HOSTS]
    pending = 0
    skipped = {'local': 0, 'https': 0, 'ntp': 0}
    for protocol, probe, address in probes:
        if _PROBE_SLOTS.acquire(blocking=False):
            pending += 1
            threading.Thread(target=run, args=(protocol, probe, address),
                             name='startup-network', daemon=True).start()
        else:
            skipped[protocol] += 1

    successes = {'https': 0, 'ntp': 0}
    local_network = None
    try:
        while pending:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                protocol, reachable = completed.get(timeout=remaining)
            except queue.Empty:
                break
            if protocol in successes:
                successes[protocol] += bool(reachable)
            else:
                local_network = reachable
            pending -= 1
    finally:
        stopped.set()
    return NetworkCheckResult(successes['https'], successes['ntp'], time.monotonic() - started,
                              local_network, len(HTTPS_URLS), len(NTP_HOSTS),
                              skipped['https'], skipped['ntp'])

import os
import sys
import re
import socket
import shlex
import shutil
import stat
import struct
import time
import datetime
import ipaddress
import logging
import platform
import json
import psutil
import atexit
import signal
import threading
import tempfile
import subprocess
from subprocess import Popen, PIPE
from pathlib import Path
from typing import Any, Optional, Protocol, Tuple, List
from concurrent.futures import ThreadPoolExecutor, as_completed, wait, FIRST_COMPLETED
import ntplib
import pyperclip
from platformdirs import user_data_path
from colorama import Fore, init
from adb_shell import constants as adb_constants
from adb_shell.adb_message import AdbMessage
from adb_shell.auth.keygen import keygen, write_public_keyfile
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
sys.path.append(str(Path(__file__).parent))
from locales import locales, set_language, Language
init(autoreset=True)

# Настройка базового логгера (только консольный вывод на уровне модуля)
# FileHandler добавляется в AndroidTVTimeFixer._setup_logging()
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)
logger.propagate = False
APP_VERSION = '2.4.2'

#: Порт adbd для «отладки по сети» (adb tcpip). Беспроводная отладка
#: Android 11+ открывает случайный порт, поэтому порт везде параметризован.
DEFAULT_ADB_PORT = 5555

#: Порт собственного ADB-сервера. Не 5037: на нём живёт сервер пользователя и
#: Android Studio, а программа глушит свой сервер при выходе.
DEFAULT_ADB_SERVER_PORT = 5038

#: Файлы, которые adb хранит в своём каталоге <HOME>/.android.
ADB_HOME_FILES = ('adbkey', 'adbkey.pub', 'adbkey.known_hosts')

#: mDNS-сервисы, которыми Android анонсирует отладку.
#: pairing — на экране открыт диалог спаривания, connect — можно подключаться,
#: legacy — «отладка по сети» (adb tcpip), старый открытый протокол.
ADB_MDNS_SERVICES = {
    'pairing': '_adb-tls-pairing._tcp',
    'connect': '_adb-tls-connect._tcp',
    'legacy': '_adb._tcp',
}

#: 'ip:port' в выводе adb mdns services
_IP_PORT_RE = re.compile(r'^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$')

#: Ответ adbd, когда включена беспроводная отладка: дальше всё идёт через TLS.
#: В adb_shell.constants записи для STLS нет — adb_shell этот режим не умеет
#: и падает с "Unknown command: 1397511251" (это и есть A_STLS).
A_STLS = 0x534C5453

#: Признаки того, что до устройства не достучались (в отличие от ненулевого
#: кода возврата самой команды на устройстве).
ADB_CONNECTION_ERRORS = (
    "error: no devices/emulators found",
    "error: device not found",
    "error: device offline",
    "error: device unauthorized",
    "cannot connect",
    "failed to connect",
    "unable to connect",
    "connection refused",
    "no route to host",
    "timed out",
)


def _subprocess_encoding() -> str:
    """Кодировка вывода дочерних процессов (на Windows консоль отдаёт cp866)."""
    return 'utf-8' if sys.platform != 'win32' else 'cp866'


def adb_env(adb_home: Optional[Path], server_port: int) -> dict:
    """Окружение для дочерних процессов adb: свой сервер и свой каталог ключей.

    Свой порт сервера работает везде. С каталогом ключей сложнее — проверено
    прямым запуском platform-tools 37.0.1 на всех трёх ОС (workflow
    adb-home-probe.yml):

    * Linux и macOS уводит только HOME (ANDROID_USER_HOME и ANDROID_SDK_HOME
      этот бинарник игнорирует вопреки документации Android);
    * Windows не уводит НИ ОДНА переменная — ни HOME, ни USERPROFILE, ни
      ANDROID_USER_HOME, ни ANDROID_SDK_HOME, ни HOMEDRIVE+HOMEPATH: adb там
      берёт профиль через системный API и всегда пишет в профиль пользователя.

    Поэтому на Windows домашние переменные не трогаем вовсе: подменять их
    ради нулевого эффекта — лишний риск для дочернего процесса. HOME
    подменяется только в env дочернего процесса и никогда в os.environ, иначе
    поехали бы Path.home() и platformdirs у самой программы.
    """
    env = os.environ.copy()
    env['ANDROID_ADB_SERVER_PORT'] = str(server_port)
    if adb_home is not None and os.name != 'nt':
        env['HOME'] = str(adb_home)
        env['USERPROFILE'] = str(adb_home)
    return env


# zeroconf — запасной путь обнаружения, когда mDNS-бэкенд самого adb недоступен.
# Импорт необязательный: замороженная сборка, в которую библиотека не попала,
# должна продолжать работать, просто без этого запасного пути.
try:
    from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
except ImportError:  # pragma: no cover - зависит от окружения сборки
    ServiceBrowser = None
    ServiceListener = object
    Zeroconf = None


class _MdnsCollector(ServiceListener):  # type: ignore[misc,valid-type]
    """Складывает адреса найденных сервисов в список 'ip:port'."""

    def __init__(self, zeroconf_instance: Any) -> None:
        self.zeroconf = zeroconf_instance
        self.found: List[str] = []

    def add_service(self, zc: Any, type_: str, name: str) -> None:
        try:
            info = zc.get_service_info(type_, name, timeout=2000)
        except Exception:
            return
        if not info or not info.port:
            return
        for address in info.parsed_addresses():
            if ':' in address:  # IPv6 остальной код не поддерживает
                continue
            entry = f"{address}:{info.port}"
            if entry not in self.found:
                self.found.append(entry)

    def update_service(self, zc: Any, type_: str, name: str) -> None:
        self.add_service(zc, type_, name)

    def remove_service(self, zc: Any, type_: str, name: str) -> None:
        pass


class DeviceTransport(Protocol):
    """Минимальный контракт подключения к устройству.

    Ему удовлетворяют и adb_shell.AdbDeviceTcp (legacy-протокол), и
    PlatformToolsTransport (штатный adb, умеющий TLS). AdbDeviceTcp намеренно
    не оборачивается: он подходит под контракт как есть.
    """

    def shell(self, command: str) -> str: ...

    def close(self) -> None: ...


class PlatformToolsTransport:
    """Выполняет команды через встроенный бинарник adb.

    Нужен для устройств с беспроводной отладкой Android 11+: там adbd отвечает
    STLS, а adb_shell шифрование не поддерживает.
    """

    def __init__(
            self,
            adb_path: str,
            serial: str,
            timeout: int = 30,
            runner: Any = subprocess.run,
            env: Optional[dict] = None
    ) -> None:
        # adb_path и runner передаются снаружи, чтобы транспорт можно было
        # проверить без собранных resources/ и без живого устройства
        self.adb_path = adb_path
        self.serial = serial
        self.timeout = timeout
        self._runner = runner
        self.env = env

    def _run(self, args: List[str]) -> Tuple[int, str]:
        result = self._runner(
            [self.adb_path] + args,
            stdout=PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            encoding=_subprocess_encoding(),
            timeout=self.timeout,
            check=False,
            env=self.env
        )
        return result.returncode, result.stdout or ''

    def shell(self, command: str) -> str:
        try:
            _returncode, output = self._run(['-s', self.serial, 'shell', command])
        except Exception as e:
            raise AndroidTVTimeFixerError(
                locales.get('adb_shell_command_failed', error=str(e))
            )

        # Ненулевой код возврата — это код команды НА устройстве (adb его
        # пробрасывает), а не ошибка связи: `grep`, вернувший пусто, не повод
        # ронять получение информации об устройстве.
        lowered = output.lower()
        if any(error in lowered for error in ADB_CONNECTION_ERRORS):
            raise AndroidTVTimeFixerError(
                locales.get('adb_shell_command_failed', error=output.strip())
            )
        # Вывод разбирают построчно (getprop, dumpsys, /proc/meminfo), а adb
        # на Windows умеет отдавать хвосты '\r'. adb_shell их не оставляет —
        # приводим оба транспорта к одному виду.
        return output.replace('\r\n', '\n').replace('\r', '\n')

    def close(self) -> None:
        try:
            self._run(['disconnect', self.serial])
        except Exception:
            pass


class ADBProcessManager:
    """Аккуратно отключает только ADB-адрес, использованный приложением.

    Приложение не владеет глобальным ADB-сервером, поэтому cleanup не должен
    завершать сторонние процессы ADB или ломать сессии Android Studio.
    """

    def __init__(self, adb_path: str, device_ip: Optional[str] = None,
                 env: Optional[dict] = None) -> None:
        self.adb_path = adb_path
        self.device_ip = device_ip
        self.env = env
        self.logger = logging.getLogger(__name__)
        self.setup_process_termination()

    def setup_process_termination(self) -> None:
        """
        Настройка механизмов завершения процессов ADB
        при выходе из программы или закрытии терминала
        """
        try:
            # Регистрация обработчиков завершения
            atexit.register(self.cleanup)

            # SIGINT не перехватываем: стандартный KeyboardInterrupt обрабатывается
            # в terminal_mode() и main(), а atexit гарантирует очистку процессов.
            signal.signal(signal.SIGTERM, self.signal_handler)
        except Exception as e:
            self.logger.error(f"Error in setup_process_termination: {e}")

    def signal_handler(self, signum: int, frame: Any) -> None:
        """
        Обработчик SIGTERM: корректно завершает процессы ADB и выходит
        """
        try:
            self.logger.info(f"Received signal {signum}, shutting down")

            self.cleanup()

            sys.exit(0)
        except Exception as e:
            self.logger.error(f"Error in signal handler: {e}")
            sys.exit(1)

    def disconnect_device(self) -> None:
        """
        Отключение устройства через ADB перед завершением процессов
        """
        if not self.device_ip:
            return

        try:
            # Добавляем порт по умолчанию, если он не указан
            if ':' not in self.device_ip:
                device_address = f"{self.device_ip}:{DEFAULT_ADB_PORT}"
            else:
                device_address = self.device_ip

            self.logger.info(f"Executing 'adb disconnect {device_address}'")
            disconnect_process = subprocess.run(
                [self.adb_path, 'disconnect', device_address],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=5,
                env=self.env
            )
            
            if disconnect_process.returncode == 0:
                self.logger.info(f"Successfully disconnected from {device_address}")
            else:
                self.logger.warning(f"Failed to disconnect from {device_address}")
                
        except subprocess.TimeoutExpired:
            self.logger.warning("ADB disconnect timed out")
        except Exception as e:
            self.logger.error(f"Error during device disconnect: {e}")
        finally:
            self.device_ip = None

    def reset_adb_server(self) -> None:
        """Штатно завершает глобальный ADB-сервер перед terminal mode."""
        try:
            result = subprocess.run(
                [self.adb_path, 'kill-server'],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
                check=False,
                env=self.env
            )
            if result.returncode == 0:
                self.logger.info("ADB server stopped before terminal mode")
            else:
                self.logger.warning(f"ADB kill-server exited with code {result.returncode}")
        except subprocess.TimeoutExpired:
            self.logger.warning("ADB kill-server timed out")
        except Exception as e:
            self.logger.warning(f"ADB kill-server failed: {e}")

    def cleanup(self) -> None:
        """
        Метод для явного вызова очистки,
        который можно использовать при завершении программы
        """
        try:
            self.disconnect_device()
            # Сервер теперь свой (ANDROID_ADB_SERVER_PORT), а не общий, поэтому
            # его можно и нужно погасить за собой, не трогая чужие сессии
            self.reset_adb_server()
        except Exception as e:
            self.logger.error(f"Error during cleanup: {e}")

class AndroidTVTimeFixerError(Exception):
    """Базовый класс исключений для AndroidTVTimeFixer"""
    pass

class AndroidTVTimeFixer:
    MAX_SCAN_HOSTS = 65_534
    TERMINAL_COMMAND_TIMEOUT = 300

    @staticmethod
    def _program_dir() -> Path:
        """Каталог программы: рядом с .exe в собранной сборке, иначе рабочий каталог."""
        if getattr(sys, 'frozen', False):
            return Path(sys.executable).resolve().parent
        return Path.cwd()

    @classmethod
    def _resolve_data_dir(cls) -> Path:
        """
        Каталог для android_tv_fixer.log, settings.json, saved_servers.json и keys/.

        Программа переносимая, поэтому данные лежат рядом с ней. Каталог
        пользователя используется только когда в папку программы писать
        нельзя (Program Files, запуск из распакованного во временную папку
        архива) — иначе файлы просто не создавались бы.
        """
        program_dir = cls._program_dir()
        try:
            # Имя пробы уникально: с общим '.write_test' два одновременно
            # запущенных экземпляра удаляли файл друг у друга, проигравший
            # получал OSError и уходил в каталог пользователя — настройки и
            # ключи разъезжались по двум разным путям
            probe_fd, probe_name = tempfile.mkstemp(prefix='.write_test.', dir=program_dir)
            os.close(probe_fd)
            os.unlink(probe_name)
            return program_dir
        except OSError:
            return Path(user_data_path("AndroidTVTimeFixer", appauthor=False))

    def __init__(self) -> None:
        self.current_path = Path.cwd()
        self.data_dir = self._resolve_data_dir()
        self.data_dir.mkdir(parents=True, exist_ok=True)
        # 0700 ставим только на собственный каталог данных: сужать права
        # у папки программы (или у рабочего каталога исходников) нельзя
        if os.name != 'nt' and self.data_dir != self._program_dir():
            self.data_dir.chmod(0o700)
        self.keys_folder = self.data_dir / 'keys'
        self.servers_file = self.data_dir / 'saved_servers.json'
        self.settings_file = self.data_dir / 'settings.json'
        # HOME для дочерних adb: он создаст внутри свой .android. Отдельно от
        # keys_folder — там пара для adb_shell в плоской раскладке, adb её не читает
        self.adb_home = self.data_dir / 'adb'
        self._migrate_legacy_data()
        self._setup_logging()
        self.adb_server_port = self.load_adb_server_port()
        self._setup_adb_environment()
        self.adb_env = adb_env(self.adb_home, self.adb_server_port)
        self._adb_path: Optional[str] = None
        self._adb_path = self.get_adb_path()
        self.process_manager = ADBProcessManager(self._adb_path, env=self.adb_env)
        self.device: Optional[DeviceTransport] = None
        self.connected_ip = None
        self.connection_timeout = 120  # Таймаут ожидания подключения в секундах
        self.saved_servers = self.load_saved_servers()
        self.last_device_ip = self.load_last_ip()
        self.scan_port = self.load_scan_port()
        self.ntp_servers = {
            'at': 'at.pool.ntp.org',
            'ba': 'ba.pool.ntp.org',
            'be': 'be.pool.ntp.org',
            'bg': 'bg.pool.ntp.org',
            'by': 'by.pool.ntp.org',
            'ch': 'ch.pool.ntp.org',
            'cy': 'cy.pool.ntp.org',
            'cz': 'cz.pool.ntp.org',
            'de': 'de.pool.ntp.org',
            'dk': 'dk.pool.ntp.org',
            'ee': 'ee.pool.ntp.org',
            'es': 'es.pool.ntp.org',
            'fi': 'fi.pool.ntp.org',
            'fr': 'fr.pool.ntp.org',
            'gi': 'gi.pool.ntp.org',
            'gr': 'gr.pool.ntp.org',
            'hr': 'hr.pool.ntp.org',
            'hu': 'hu.pool.ntp.org',
            'ie': 'ie.pool.ntp.org',
            'is': 'is.pool.ntp.org',
            'it': 'it.pool.ntp.org',
            'li': 'li.pool.ntp.org',
            'lt': 'lt.pool.ntp.org',
            'lu': 'lu.pool.ntp.org',
            'lv': 'lv.pool.ntp.org',
            'md': 'md.pool.ntp.org',
            'mk': 'mk.pool.ntp.org',
            'nl': 'nl.pool.ntp.org',
            'no': 'no.pool.ntp.org',
            'pl': 'pl.pool.ntp.org',
            'pt': 'pt.pool.ntp.org',
            'ro': 'ro.pool.ntp.org',
            'rs': 'rs.pool.ntp.org',
            'ru': 'ru.pool.ntp.org',
            'se': 'se.pool.ntp.org',
            'si': 'si.pool.ntp.org',
            'sk': 'sk.pool.ntp.org',
            'tr': 'tr.pool.ntp.org',
            'uk': 'uk.pool.ntp.org',
            'us': 'us.pool.ntp.org',
            'ca': 'ca.pool.ntp.org',
            'br': 'br.pool.ntp.org',
            'au': 'au.pool.ntp.org',
            'jp': 'jp.pool.ntp.org',
            'kz': 'kz.pool.ntp.org',
            'ae': 'ae.pool.ntp.org',
            'am': 'am.pool.ntp.org',
            'az': 'az.pool.ntp.org',
            'bd': 'bd.pool.ntp.org',
            'bh': 'bh.pool.ntp.org',
            'cn': 'cn.pool.ntp.org',
            'ge': 'ge.pool.ntp.org',
            'hk': 'hk.pool.ntp.org',
            'id': 'id.pool.ntp.org',
            'il': 'il.pool.ntp.org',
            'in': 'in.pool.ntp.org',
            'ir': 'ir.pool.ntp.org',
            'kg': 'kg.pool.ntp.org',
            'kh': 'kh.pool.ntp.org',
            'kr': 'kr.pool.ntp.org',
            'lk': 'lk.pool.ntp.org',
            'mn': 'mn.pool.ntp.org',
            'mv': 'mv.pool.ntp.org',
            'my': 'my.pool.ntp.org',
            'np': 'np.pool.ntp.org',
            'ph': 'ph.pool.ntp.org',
            'pk': 'pk.pool.ntp.org',
            'ps': 'ps.pool.ntp.org',
            'qa': 'qa.pool.ntp.org',
            'sa': 'sa.pool.ntp.org',
            'sg': 'sg.pool.ntp.org',
            'th': 'th.pool.ntp.org',
            'tj': 'tj.pool.ntp.org',
            'tw': 'tw.pool.ntp.org',
            'uz': 'uz.pool.ntp.org',
            'ua': 'ua.pool.ntp.org',
            'vn': 'vn.pool.ntp.org'
        }
        # (en_name, ru_name) for each country code
        self.country_names = {
            'at': ('Austria', 'Австрия'),
            'ba': ('Bosnia and Herzegovina', 'Босния и Герцеговина'),
            'be': ('Belgium', 'Бельгия'),
            'bg': ('Bulgaria', 'Болгария'),
            'by': ('Belarus', 'Беларусь'),
            'ch': ('Switzerland', 'Швейцария'),
            'cy': ('Cyprus', 'Кипр'),
            'cz': ('Czech Republic', 'Чехия'),
            'de': ('Germany', 'Германия'),
            'dk': ('Denmark', 'Дания'),
            'ee': ('Estonia', 'Эстония'),
            'es': ('Spain', 'Испания'),
            'fi': ('Finland', 'Финляндия'),
            'fr': ('France', 'Франция'),
            'gi': ('Gibraltar', 'Гибралтар'),
            'gr': ('Greece', 'Греция'),
            'hr': ('Croatia', 'Хорватия'),
            'hu': ('Hungary', 'Венгрия'),
            'ie': ('Ireland', 'Ирландия'),
            'is': ('Iceland', 'Исландия'),
            'it': ('Italy', 'Италия'),
            'li': ('Liechtenstein', 'Лихтенштейн'),
            'lt': ('Lithuania', 'Литва'),
            'lu': ('Luxembourg', 'Люксембург'),
            'lv': ('Latvia', 'Латвия'),
            'md': ('Moldova', 'Молдова'),
            'mk': ('North Macedonia', 'Северная Македония'),
            'nl': ('Netherlands', 'Нидерланды'),
            'no': ('Norway', 'Норвегия'),
            'pl': ('Poland', 'Польша'),
            'pt': ('Portugal', 'Португалия'),
            'ro': ('Romania', 'Румыния'),
            'rs': ('Serbia', 'Сербия'),
            'ru': ('Russia', 'Россия'),
            'se': ('Sweden', 'Швеция'),
            'si': ('Slovenia', 'Словения'),
            'sk': ('Slovakia', 'Словакия'),
            'tr': ('Turkey', 'Турция'),
            'uk': ('United Kingdom', 'Великобритания'),
            'us': ('United States', 'США'),
            'ca': ('Canada', 'Канада'),
            'br': ('Brazil', 'Бразилия'),
            'au': ('Australia', 'Австралия'),
            'cn': ('China', 'Китай'),
            'jp': ('Japan', 'Япония'),
            'kz': ('Kazakhstan', 'Казахстан'),
            'ae': ('United Arab Emirates', 'ОАЭ'),
            'am': ('Armenia', 'Армения'),
            'az': ('Azerbaijan', 'Азербайджан'),
            'bd': ('Bangladesh', 'Бангладеш'),
            'bh': ('Bahrain', 'Бахрейн'),
            'ge': ('Georgia', 'Грузия'),
            'hk': ('Hong Kong', 'Гонконг'),
            'id': ('Indonesia', 'Индонезия'),
            'il': ('Israel', 'Израиль'),
            'in': ('India', 'Индия'),
            'ir': ('Iran', 'Иран'),
            'kg': ('Kyrgyzstan', 'Кыргызстан'),
            'kh': ('Cambodia', 'Камбоджа'),
            'kr': ('Korea', 'Корея'),
            'lk': ('Sri Lanka', 'Шри-Ланка'),
            'mn': ('Mongolia', 'Монголия'),
            'mv': ('Maldives', 'Мальдивы'),
            'my': ('Malaysia', 'Малайзия'),
            'np': ('Nepal', 'Непал'),
            'ph': ('Philippines', 'Филиппины'),
            'pk': ('Pakistan', 'Пакистан'),
            'ps': ('Palestinian Territory', 'Палестина'),
            'qa': ('Qatar', 'Катар'),
            'sa': ('Saudi Arabia', 'Саудовская Аравия'),
            'sg': ('Singapore', 'Сингапур'),
            'th': ('Thailand', 'Таиланд'),
            'tj': ('Tajikistan', 'Таджикистан'),
            'tw': ('Taiwan', 'Тайвань'),
            'uz': ('Uzbekistan', 'Узбекистан'),
            'ua': ('Ukraine', 'Украина'),
            'vn': ('Vietnam', 'Вьетнам'),
        }
        self.custom_ntp_servers = [
            'time.windows.com',
            'twc.trafficmanager.net',
            '0.europe.pool.ntp.org',
            '1.europe.pool.ntp.org',
            '2.europe.pool.ntp.org',
            '3.europe.pool.ntp.org',
            '0.north-america.pool.ntp.org',
            '1.north-america.pool.ntp.org',
            '2.north-america.pool.ntp.org',
            '3.north-america.pool.ntp.org',
            '0.asia.pool.ntp.org',
            '1.asia.pool.ntp.org',
            '2.asia.pool.ntp.org',
            '3.asia.pool.ntp.org',
            '0.africa.pool.ntp.org',
            '1.africa.pool.ntp.org',
            '2.africa.pool.ntp.org',
            '3.africa.pool.ntp.org',
            '0.oceania.pool.ntp.org',
            '1.oceania.pool.ntp.org',
            '2.oceania.pool.ntp.org',
            '3.oceania.pool.ntp.org',
            '0.south-america.pool.ntp.org',
            '1.south-america.pool.ntp.org',
            '2.south-america.pool.ntp.org',
            '3.south-america.pool.ntp.org',
            'time.cloudflare.com',
            'clock.isc.org',
            'ntp1.vniiftri.ru',
            'ntp2.vniiftri.ru',
            'ntp3.vniiftri.ru',
            'ntp4.vniiftri.ru',
            'ntp21.vniiftri.ru',
            'ntp1.niiftri.irkutsk.ru',
            'ntp2.niiftri.irkutsk.ru',
            'vniiftri.khv.ru',
            'vniiftri2.khv.ru',
            'ntp.sniim.ru',
            'ntp1.ntp-servers.net',
            'ntp0.ntp-servers.net',
            'time.nist.gov',
            'ntps1-1.cs.tu-berlin.de',
            'ntp.ix.ru',
            'time.google.com',
            'time.android.com'
        ]

    def _migrate_legacy_data(self) -> None:
        """Копирует portable-настройки в защищённый каталог данных один раз."""
        try:
            if self.current_path.resolve() == self.data_dir.resolve():
                return

            for name in ('settings.json', 'saved_servers.json'):
                source = self.current_path / name
                destination = self.data_dir / name
                if source.is_file() and not destination.exists():
                    shutil.copy2(source, destination)
                self._secure_file(destination)

            legacy_keys = self.current_path / 'keys'
            if legacy_keys.is_dir():
                self.keys_folder.mkdir(parents=True, exist_ok=True)
                if os.name != 'nt':
                    self.keys_folder.chmod(0o700)
                for name in ('adbkey', 'adbkey.pub'):
                    source = legacy_keys / name
                    destination = self.keys_folder / name
                    if source.is_file() and not destination.exists():
                        shutil.copy2(source, destination)
                    self._secure_file(destination)
        except OSError as e:
            logger.warning(f"Could not migrate legacy application data: {e}")

    @property
    def adb_dot_android(self) -> Path:
        """Каталог, который adb реально использует: <adb_home>/.android."""
        return self.adb_home / '.android'

    def _setup_adb_environment(self) -> None:
        """Готовит собственный каталог ключей adb и переносит в него старые.

        Само окружение строится в adb_env() и передаётся дочерним процессам
        через env=. В os.environ ничего не пишем: подмена HOME всему процессу
        сломала бы Path.home() и platformdirs у самой программы.
        """
        if os.name == 'nt':
            # На Windows adb всё равно пишет в профиль пользователя, так что
            # каталог остался бы пустым, а перенос ключей — копированием
            # приватного ключа во второе место без всякой пользы
            return

        try:
            self.adb_dot_android.mkdir(parents=True, exist_ok=True)
            self.adb_home.chmod(0o700)
            self.adb_dot_android.chmod(0o700)
        except OSError as e:
            logger.warning(f"Could not create the ADB home directory: {e}")
            return

        self._migrate_adb_home()

    def _migrate_adb_home(self) -> None:
        """Однократно переносит ключи adb из ~/.android в каталог программы.

        Без этого полная изоляция обесценила бы уже выполненное пользователем
        спаривание: устройство пришлось бы спаривать заново.
        """
        try:
            source_dir = Path.home() / '.android'
            if not source_dir.is_dir() or source_dir.resolve() == self.adb_dot_android.resolve():
                return

            for name in ADB_HOME_FILES:
                source = source_dir / name
                destination = self.adb_dot_android / name
                if source.is_file() and not destination.exists():
                    shutil.copy2(source, destination)
                    self._secure_file(destination)
        except OSError as e:
            logger.warning(f"Could not migrate ADB keys: {e}")

    @staticmethod
    def _secure_file(path: Path) -> None:
        """Ограничивает доступ к чувствительному файлу текущим пользователем."""
        if os.name != 'nt' and path.exists():
            path.chmod(stat.S_IRUSR | stat.S_IWUSR)

    @classmethod
    def _atomic_write_json(cls, path: Path, data: Any) -> None:
        """Атомарно сохраняет JSON и не оставляет обрезанный целевой файл."""
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, temp_name = tempfile.mkstemp(prefix=f'.{path.name}.', suffix='.tmp', dir=path.parent)
        temp_path = Path(temp_name)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as temp_file:
                json.dump(data, temp_file, ensure_ascii=False, indent=2)
                temp_file.flush()
                os.fsync(temp_file.fileno())
            cls._secure_file(temp_path)
            os.replace(temp_path, path)
            cls._secure_file(path)
        except Exception:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass
            raise

    def _setup_logging(self) -> None:
        """Настраивает логирование для класса с выводом в файл и консоль"""
        self.logger = logging.getLogger(__name__)
        self.logger.setLevel(logging.INFO)
        self.logger.propagate = False

        # Очищаем существующие обработчики чтобы избежать дублирования
        if self.logger.handlers:
            self.logger.handlers.clear()

        # Формат сообщений
        formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')

        # Обработчик для вывода в консоль
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(logging.INFO)
        console_handler.setFormatter(formatter)
        self.logger.addHandler(console_handler)

        # Обработчик для записи в файл
        try:
            log_file = self.data_dir / 'android_tv_fixer.log'
            file_handler = logging.FileHandler(log_file, encoding='utf-8', mode='a')
            self._secure_file(log_file)
            file_handler.setLevel(logging.INFO)
            file_handler.setFormatter(formatter)
            self.logger.addHandler(file_handler)
        except Exception as e:
            self.logger.warning(f"Could not create log file: {e}")

    def get_adb_path(self) -> str:
        """
        Получает путь к ADB из runtime hook или ресурсов
        
        Returns:
            str: Полный путь к исполняемому файлу ADB
            
        Raises:
            FileNotFoundError: Если файл ADB не найден
        """
        if self._adb_path:
            return self._adb_path

        try:
            # Пытаемся импортировать платформенный путь из runtime hook'ов
            if sys.platform == 'win32':
                from hooks.win_hook import ADB_PATH
                self._adb_path = ADB_PATH
            elif sys.platform == 'darwin':
                from hooks.macos_hook import ADB_PATH
                self._adb_path = ADB_PATH
            else:
                from hooks.linux_hook import ADB_PATH
                self._adb_path = ADB_PATH
        except ImportError:
            # Fallback для разработки
            if getattr(sys, 'frozen', False):
                base_path = sys._MEIPASS
            else:
                base_path = os.path.abspath(os.path.dirname(__file__))
            
            self._adb_path = os.path.join(
                base_path, 
                'resources', 
                'adb.exe' if sys.platform == 'win32' else 'adb'
            )

        if not os.path.exists(self._adb_path):
            raise FileNotFoundError(f"ADB не найден по пути: {self._adb_path}")

        self.logger.info(f"Используется ADB по пути: {self._adb_path}")
        return self._adb_path

    def _process_command_output(
            self,
            process: Popen,
            timeout: int = TERMINAL_COMMAND_TIMEOUT
    ) -> Tuple[int, str, str]:
        """
        Обрабатывает вывод команды и возвращает результат
        
        Args:
            process (Popen): Процесс для обработки
            
        Returns:
            Tuple[int, str, str]: (код возврата, stdout, stderr)
        """
        stdout_lines = []
        stderr_lines = []

        def _drain_stream(stream: Any, output: List[str], display: bool = False) -> None:
            try:
                for line in stream:
                    output.append(line)
                    if display:
                        print(Fore.GREEN + line.rstrip('\r\n'))
            except Exception:
                pass

        if process.stdout is None or process.stderr is None:
            raise RuntimeError("Command output pipes are not configured")

        stdout_thread = threading.Thread(
            target=_drain_stream, args=(process.stdout, stdout_lines, True), daemon=True
        )
        stderr_thread = threading.Thread(
            target=_drain_stream, args=(process.stderr, stderr_lines), daemon=True
        )
        stdout_thread.start()
        stderr_thread.start()

        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self._terminate_process_tree(process)
            raise TimeoutError(f"Command execution timeout exceeded ({timeout} sec.)")
        except BaseException:
            if process.poll() is None:
                self._terminate_process_tree(process)
            raise
        finally:
            stdout_thread.join(timeout=5)
            stderr_thread.join(timeout=5)
            process.stdout.close()
            process.stderr.close()

        return process.returncode, ''.join(stdout_lines), ''.join(stderr_lines)

    @staticmethod
    def _popen_group_options() -> dict:
        """Изолирует команду, чтобы timeout мог завершить всё дерево процессов."""
        if os.name == 'nt':
            return {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP}
        return {'start_new_session': True}

    @staticmethod
    def _terminate_process_tree(process: Popen) -> None:
        """Завершает принадлежащее terminal mode дерево по PID/группе."""
        if process.poll() is not None:
            return

        try:
            if os.name == 'nt':
                result = subprocess.run(
                    ['taskkill', '/PID', str(process.pid), '/T', '/F'],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=5,
                    check=False
                )
                if result.returncode != 0 and process.poll() is None:
                    process.kill()
            else:
                process_group = os.getpgid(process.pid)
                if process_group == os.getpgrp():
                    process.kill()
                else:
                    os.killpg(process_group, signal.SIGKILL)
        except (OSError, subprocess.SubprocessError):
            if process.poll() is None:
                process.kill()

        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    @staticmethod
    def _split_terminal_command(command: str) -> List[str]:
        """Разбирает команду, сохраняя обратные слеши Windows-путей."""
        args = shlex.split(command, posix=os.name != 'nt')
        if os.name == 'nt':
            return [
                arg[1:-1] if len(arg) >= 2 and arg[0] == arg[-1] == '"' else arg
                for arg in args
            ]
        return args

    def _retry_adb_connection(self, command: str, max_retries: int = 5, delay: int = 2) -> bool:
        """
        Пытается переподключиться к устройству несколько раз. На поздних
        попытках отключает только конкретный адрес, не перезапуская глобальный
        ADB-сервер. Использует DEFAULT_ADB_PORT, если порт не указан.
    
        Args:
            command (str): Выполняемая команда.
            max_retries (int): Максимальное количество попыток (по умолчанию 5).
            delay (int): Задержка между попытками в секундах (по умолчанию 2).
    
        Returns:
            bool: True, если подключение успешно, False в противном случае.
        """
        # Определяем кодировку текущей системы
        encoding = _subprocess_encoding()
    
        # Извлекаем IP-адрес из команды
        ip_match = re.search(r'(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::(\d+))?', command)
        if ip_match:
            ip = ip_match.group(1)
            # Используем порт по умолчанию, если порт не указан
            port = ip_match.group(2) if ip_match.group(2) else str(DEFAULT_ADB_PORT)
            device_ip = f"{ip}:{port}"
        else:
            device_ip = None
    
        for attempt in range(max_retries):
            try:
                if attempt >= 2 and device_ip:
                    self.logger.info(f"Попытка {attempt + 1}: Execute 'adb disconnect {device_ip}'")
                    disconnect_process = subprocess.run(
                        [self.get_adb_path(), 'disconnect', device_ip],
                        stdout=PIPE,
                        stderr=PIPE,
                        universal_newlines=True,
                        encoding=encoding,
                        timeout=10,
                        check=False,
                        env=self.adb_env
                    )
                    if disconnect_process.returncode != 0:
                        self.logger.warning(
                            f"Error while executing 'adb disconnect': "
                            f"{disconnect_process.stderr.strip()}"
                        )
    
                # Выполнение основной команды подключения
                args = self._split_terminal_command(command)
                if not args:
                    return False
    
                if os.path.basename(args[0]).lower() in ('adb', 'adb.exe'):
                    args[0] = self.get_adb_path()
    
                process = Popen(
                    args,
                    stdout=PIPE,
                    stderr=PIPE,
                    universal_newlines=True,
                    encoding=encoding,
                    bufsize=1,
                    env=self.adb_env,
                    **self._popen_group_options()
                )
    
                return_code, stdout, stderr = self._process_command_output(process)
    
                # Проверяем наличие ошибок подключения
                combined_output = f"{stdout}\n{stderr}".lower()

                if any(error in combined_output for error in ADB_CONNECTION_ERRORS):
                    if attempt < max_retries - 1:
                        self.logger.warning(f"Connection attempt {attempt + 1} failed. Retrying in {delay} sec...")
                        print(f"\033[33mConnection attempt {attempt + 1} failed. Retrying in {delay} sec...\033[0m")
                        time.sleep(delay)
                        continue
                    else:
                        self.logger.error("All connection attempts failed.")
                        print("\033[31mAll connection attempts failed.\033[0m")
                        return False

                if return_code == 0:
                    return True
                else:
                    # Если ошибка не связана с подключением, прекращаем попытки
                    if stderr:
                        self.logger.error(f"STDERR: {stderr.strip()}")
                        print(f"\033[31m{stderr.strip()}\033[0m")
                    return False
    
            except Exception as e:
                self.logger.error(f"Error while trying to connect: {str(e)}", exc_info=True)
                if attempt < max_retries - 1:
                    time.sleep(delay)
                    continue
                return False
    
        return False
    
    def execute_terminal_command(self, command: str) -> None:
        """
        Выполняет команду в терминале и выводит результат
        
        Args:
            command (str): Команда для выполнения
        """
        if not command:
            return
    
        try:
            args = self._split_terminal_command(command)
            if not args:
                return

            # Логику ADB-переподключения применяем только когда команда
            # действительно начинается с adb, а не просто содержит подстроку 'adb'
            first_token = os.path.basename(args[0]).lower()
            if (
                    first_token in ('adb', 'adb.exe') and
                    len(args) > 1 and args[1].lower() == 'connect'
            ):
                connection_success = self._retry_adb_connection(command)
                if not connection_success:
                    return

            else:
                if first_token in ('adb', 'adb.exe'):
                    args[0] = self.get_adb_path()
                self.logger.debug(f"The command is being executed: {' '.join(args)}")
                
                process = Popen(
                    args,
                    stdout=PIPE,
                    stderr=PIPE,
                    universal_newlines=True,
                    encoding='utf-8' if sys.platform != 'win32' else 'cp866',
                    bufsize=1,
                    env=self.adb_env,
                    **self._popen_group_options()
                )
                
                return_code, stdout, stderr = self._process_command_output(process)
                
                if return_code != 0:
                    self.logger.error(f"Command execution error. Code: {return_code}")
                    print(Fore.RED + locales.get("command_error"))
                    if stderr:
                        self.logger.error(f"STDERR: {stderr}")
                        print(Fore.RED + stderr)
    
        except FileNotFoundError as e:
            error_msg = f"Command not found: {e}"
            self.logger.error(error_msg)
            print(Fore.RED + locales.get("command_execution_error", error=error_msg))
        except TimeoutError as e:
            error_msg = f"Command execution timeout: {e}"
            self.logger.error(error_msg)
            print(Fore.RED + locales.get("command_execution_error", error=error_msg))
        except Exception as e:
            error_msg = f"Command execution error: {str(e)}"
            self.logger.error(error_msg, exc_info=True)
            print(Fore.RED + locales.get("command_execution_error", error=error_msg))

    @staticmethod
    def _console_codepage() -> Optional[int]:
        """Текущая кодовая страница консоли Windows или None"""
        try:
            import ctypes
            return int(ctypes.windll.kernel32.GetConsoleOutputCP())
        except Exception:
            return None

    def terminal_mode(self) -> None:
        """Режим терминала для выполнения команд"""
        # Установка кодировки для Windows. Прежнюю кодовую страницу
        # запоминаем: лаунчер выставляет UTF-8, и без восстановления
        # весь дальнейший вывод меню на кириллице ломался.
        previous_codepage = None
        if sys.platform == 'win32':
            previous_codepage = self._console_codepage()
            os.system('chcp 866 >nul')
    
        self.logger.info("Terminal mode started")
        print(Fore.GREEN + locales.get("terminal_mode_welcome"))
        print(Fore.YELLOW + locales.get("terminal_mode_help"))

        # Освобождаем transport библиотеки и, согласно ожидаемому поведению,
        # штатно завершаем ADB-сервер перед ручным управлением.
        self._close_device()
        self.process_manager.reset_adb_server()

        try:
            while True:
                try:
                    command = input(Fore.CYAN + "terminal> " + Fore.WHITE).strip()
                    
                    # Проверяем специальные команды
                    if command.lower() in ['exit', 'quit', 'q']:
                        self.logger.info("Exit terminal mode")
                        self.process_manager.cleanup()
                        break
                    elif command.lower() in ['help', '?']:
                        print(Fore.YELLOW + locales.get("terminal_mode_commands"))
                        continue
                    elif command.lower() == 'clear':
                        os.system('cls' if platform.system() == 'Windows' else 'clear')
                        continue
                    elif not command:
                        continue
                    
                    # Выполняем команду без завершения процессов ADB
                    self.execute_terminal_command(command)
                    
                except KeyboardInterrupt:
                    # Обработка Ctrl+C без завершения ADB процессов
                    self.logger.info(locales.get_en("terminal_mode_exit_ctrl_c"))
                    print("\n" + Fore.YELLOW + locales.get("terminal_mode_exit_ctrl_c"))
                    continue
                except Exception as e:
                    self.logger.error(f"Error in terminal mode: {str(e)}", exc_info=True)
                    print(Fore.RED + locales.get("terminal_mode_error", error=str(e)))
        
        except Exception as e:
            self.logger.error(f"Critical error in terminal mode: {str(e)}", exc_info=True)
            print(Fore.RED + locales.get("terminal_mode_critical_error", error=str(e)))
        finally:
            self.process_manager.cleanup()
            if previous_codepage:
                os.system(f'chcp {previous_codepage} >nul')
	
    def _test_ntp_server(self, server: str, count: int = 2, timeout: int = 2) -> dict:
        """Проверка NTP-сервера с несколькими попытками и детальной диагностикой ошибок.
        Используется и в ping_ntp_servers (пункт 6), и в auto_setup_ntp (пункт 9).
        Возвращает dict с метриками и статусом."""
        rtts = []
        offsets = []
        last_error = None
        ntp_client = ntplib.NTPClient()

        for _ in range(count):
            try:
                start_time = time.time()
                ntp_response = ntp_client.request(server, version=3, timeout=timeout)
                rtt = (time.time() - start_time) * 1000
                rtts.append(rtt)
                offsets.append(ntp_response.offset)
            except ntplib.NTPException as e:
                last_error = f"NTP Protocol Error: {e}"
            except socket.gaierror:
                last_error = "DNS Resolution Error"
            except socket.timeout:
                last_error = "Timeout"
            except Exception as e:
                last_error = str(e)

        if not rtts:
            return {
                'server': server,
                'status': 'Unreachable',
                'avg_rtt': None,
                'min_rtt': None,
                'max_rtt': None,
                'success_rate': 0,
                'offset': None,
                'error': last_error or 'Unknown',
                'color': Fore.RED
            }

        success_rate = (len(rtts) / count) * 100
        avg_rtt = sum(rtts) / len(rtts)
        avg_offset = sum(offsets) / len(offsets)

        return {
            'server': server,
            'status': 'Reachable',
            'avg_rtt': avg_rtt,
            'min_rtt': min(rtts),
            'max_rtt': max(rtts),
            'success_rate': success_rate,
            'offset': avg_offset,
            'error': None,
            'color': Fore.GREEN if success_rate > 66 else Fore.YELLOW
        }

    def ping_ntp_servers(self, timeout=2, count=3):
        """
        Check NTP servers reliability using ntplib with enhanced error handling

        Args:
            timeout (int): Timeout for NTP server connection in seconds
            count (int): Number of attempts to connect to each server
        """
        self.logger.info("Starting NTP servers ping test")
        print(Fore.GREEN + locales.get("ping_ntp_servers_start"))

        # Combine country NTP servers and custom NTP servers, removing duplicates
        all_servers = list(dict.fromkeys(
            list(self.ntp_servers.values()) + self.custom_ntp_servers
        ))

        total_servers = len(all_servers)
        self.logger.info(f"Total NTP servers to check: {total_servers}")

        server_ping_results = []
        reachable_count = 0
        unreachable_count = 0

        for idx, server in enumerate(all_servers, 1):
            progress = f"[{idx}/{total_servers}]"
            print(Fore.CYAN + f"\r{progress} Checking: {server:<40}", end="", flush=True)

            result = self._test_ntp_server(server, count=count, timeout=timeout)
            server_ping_results.append(result)
            if result['status'] == 'Reachable':
                reachable_count += 1
                self.logger.debug(f"Server {server}: Reachable, avg RTT={result['avg_rtt']:.2f}ms, success={result['success_rate']:.0f}%")
            else:
                unreachable_count += 1
                self.logger.debug(f"Server {server}: Unreachable, error={result.get('error')}")

        # Clear progress line
        print("\r" + " " * 60 + "\r", end="")

        # Sort results: reachable servers first, sorted by success rate and avg RTT
        server_ping_results.sort(
            key=lambda x: (x['status'] != 'Reachable', -x['success_rate'], x['avg_rtt'] or float('inf'))
        )

        # Display summary
        print(Fore.GREEN + f"\n{locales.get('ping_results_summary')}")
        print(Fore.WHITE + f"  {locales.get('total_servers')}: {total_servers}")
        print(Fore.GREEN + f"  {locales.get('reachable_servers')}: {reachable_count}")
        print(Fore.RED + f"  {locales.get('unreachable_servers')}: {unreachable_count}")
        print()

        # Display results table
        print(Fore.YELLOW + f"{'Server':<35} {'Status':<12} {'Avg RTT':<12} {'Min/Max RTT':<15} {'Success':<10}")
        print("-" * 85)

        for result in server_ping_results:
            server_display = result['server'][:33] + '..' if len(result['server']) > 35 else result['server']

            if result['avg_rtt'] is not None:
                rtt_display = f"{result['avg_rtt']:.1f}ms"
                minmax_display = f"{result['min_rtt']:.1f}/{result['max_rtt']:.1f}ms"
            else:
                rtt_display = "N/A"
                minmax_display = "N/A"

            success_display = f"{result['success_rate']:.0f}%"

            print(
                result['color'] +
                f"{server_display:<35} {result['status']:<12} {rtt_display:<12} {minmax_display:<15} {success_display:<10}"
            )

        self.logger.info(f"NTP ping test completed: {reachable_count} reachable, {unreachable_count} unreachable")
	
    @classmethod
    def _normalize_saved_servers(cls, data: Any) -> dict:
        """Проверяет схему пользовательского списка NTP-серверов."""
        if not isinstance(data, dict):
            raise ValueError("saved_servers must be an object")

        normalized = {'favorite_servers': [], 'custom_servers': []}
        for key in normalized:
            values = data.get(key, [])
            if not isinstance(values, list):
                raise ValueError(f"{key} must be a list")
            for value in values:
                if not isinstance(value, str) or not cls.validate_ntp_server(value.strip()):
                    raise ValueError(f"Invalid NTP server in {key}")
                server = value.strip()
                if server not in normalized[key]:
                    normalized[key].append(server)
        return normalized

    def load_saved_servers(self) -> dict:
        """Загружает сохраненные серверы из файла"""
        if self.servers_file.exists():
            try:
                with open(self.servers_file, 'r', encoding='utf-8') as f:
                    return self._normalize_saved_servers(json.load(f))
            except Exception as e:
                self.logger.warning(locales.get_en('logger_warning', error=str(e)))
        return {'favorite_servers': [], 'custom_servers': []}

    def save_servers(self) -> bool:
        """Сохраняет серверы в файл"""
        try:
            self.saved_servers = self._normalize_saved_servers(self.saved_servers)
            self._atomic_write_json(self.servers_file, self.saved_servers)
            return True
        except Exception as e:
            self.logger.warning(locales.get_en('logger_warning_2', error=str(e)))
            return False

    def _load_setting(self, key: str) -> str:
        """Читает одно значение из файла настроек"""
        if self.settings_file.exists():
            try:
                with open(self.settings_file, 'r', encoding='utf-8') as f:
                    settings = json.load(f)
                if not isinstance(settings, dict):
                    raise ValueError("settings must be an object")
                value = settings.get(key, '')
                return value if isinstance(value, str) else ''
            except Exception as e:
                self.logger.warning(locales.get_en('settings_load_error', error=str(e)))
        return ''

    def _save_settings(self, values: dict) -> bool:
        """Записывает значения в файл настроек одной атомарной записью"""
        try:
            settings = {}
            if self.settings_file.exists():
                with open(self.settings_file, 'r', encoding='utf-8') as f:
                    settings = json.load(f)
                if not isinstance(settings, dict):
                    settings = {}
            settings.update(values)
            self._atomic_write_json(self.settings_file, settings)
            return True
        except Exception as e:
            self.logger.warning(locales.get_en('settings_save_error', error=str(e)))
            return False

    def _save_setting(self, key: str, value: str) -> bool:
        """Записывает одно значение в файл настроек, сохраняя остальные"""
        return self._save_settings({key: value})

    def load_last_ip(self) -> str:
        """Загружает последний использованный IP адрес из файла настроек"""
        return self._load_setting('last_device_ip')

    def save_last_ip(self, ip: str) -> bool:
        """Сохраняет последний использованный IP адрес в файл настроек"""
        if self.validate_ip(ip) and self._save_setting('last_device_ip', ip):
            self.last_device_ip = ip
            return True
        return False

    def load_scan_port(self) -> int:
        """Загружает порт сканирования из настроек (по умолчанию DEFAULT_ADB_PORT)"""
        raw = self._load_setting('scan_port')
        try:
            port = int(raw)
        except ValueError:
            return DEFAULT_ADB_PORT
        return port if 1 <= port <= 65535 else DEFAULT_ADB_PORT

    def save_scan_port(self, port: int) -> bool:
        """Сохраняет порт сканирования в настройки"""
        if not (1 <= port <= 65535):
            return False
        if self._save_setting('scan_port', str(port)):
            self.scan_port = port
            return True
        return False

    def load_adb_server_port(self) -> int:
        """Порт собственного ADB-сервера из настроек (по умолчанию 5038)"""
        raw = self._load_setting('adb_server_port')
        try:
            port = int(raw)
        except ValueError:
            return DEFAULT_ADB_SERVER_PORT
        return port if 1 <= port <= 65535 else DEFAULT_ADB_SERVER_PORT

    def prompt_adb_port(self, default: Optional[int] = None, persist: bool = True) -> Optional[int]:
        """Спрашивает ADB-порт. Enter — значение по умолчанию, 'q' — отмена.

        Единая точка ввода порта для всех меню: иначе валидация расползлась бы
        по восьми разным input(). persist=False нужен одноразовым портам
        (порт спаривания), чтобы они не затирали сохранённый порт сканирования.
        """
        default = default or self.scan_port
        while True:
            raw = input(
                Fore.GREEN + locales.get("enter_scan_port", default=default) + Fore.WHITE
            ).strip()
            if not raw:
                return default
            if raw.lower() == 'q':
                return None
            try:
                port = int(raw)
            except ValueError:
                print(Fore.RED + locales.get("invalid_port"))
                continue
            if not (1 <= port <= 65535):
                print(Fore.RED + locales.get("invalid_port"))
                continue
            if persist:
                self.save_scan_port(port)
            return port

    def load_language(self) -> str:
        """Загружает сохранённый язык из файла настроек"""
        return self._load_setting('language')

    def save_language(self, language: str) -> bool:
        """Сохраняет выбранный язык в файл настроек"""
        if language in ('en', 'ru'):
            return self._save_setting('language', language)
        return False

    def get_device_ip_input(self) -> str:
        """Получает IP адрес устройства: сохранённый, ручной ввод или авто-сканирование сети"""
        while True:
            if self.last_device_ip:
                print(Fore.GREEN + locales.get('enter_device_ip_scan',
                                               saved_ip=self.last_device_ip), end="")
            else:
                print(Fore.GREEN + locales.get('enter_device_ip_scan_no_saved'), end="")

            ip = input(Fore.WHITE).strip()

            # Enter без ввода → сохранённый IP, иначе спрашиваем заново
            if not ip:
                if self.last_device_ip:
                    return self.last_device_ip
                continue

            # 'q' → выход в меню
            if ip.lower() == 'q':
                return ''

            # 's' → авто-сканирование сети, CIDR → сканирование указанной подсети
            if ip.lower() == 's' or '/' in ip:
                # Порт в записи 'CIDR:порт' уже задан явно — не переспрашиваем
                if '/' in ip and ':' in ip:
                    found = self.scan_custom_network(ip)
                else:
                    port = self.prompt_adb_port()
                    if port is None:
                        continue
                    found = (
                        self.scan_custom_network(ip, port)
                        if '/' in ip
                        else self.scan_network_for_android_devices(port)
                    )
                selected_ip = self._select_scanned_device(found)
                if selected_ip:
                    self.save_last_ip(selected_ip)
                    return selected_ip
                continue

            return ip

    @staticmethod
    def validate_ntp_server(server: str) -> bool:
        """
        Проверяет валидность NTP сервера (доменное имя или IP адрес)

        Args:
            server: Строка с адресом NTP сервера

        Returns:
            bool: True если формат валидный, False в противном случае
        """
        if not server:
            return False

        # Проверка на IP адрес
        ip_pattern = r'^(\d{1,3}\.){3}\d{1,3}$'
        if re.match(ip_pattern, server):
            octets = server.split('.')
            return all(0 <= int(octet) <= 255 for octet in octets)

        # Проверка на валидное доменное имя
        # Доменное имя может содержать буквы, цифры, дефисы и точки
        # Каждая часть должна начинаться и заканчиваться буквой или цифрой
        domain_pattern = r'^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\.[A-Za-z0-9-]{1,63})*\.[A-Za-z]{2,}$'
        return bool(re.match(domain_pattern, server))

    def copy_server_to_clipboard(self, server: str) -> bool:
        """Копирует адрес сервера в буфер обмена"""
        try:
            pyperclip.copy(server)
            return True
        except Exception as e:
            self.logger.warning(locales.get_en('copy_to_clipboard', error=str(e)))
            return False

    def paste_server_from_clipboard(self) -> str:
        """Получает адрес сервера из буфера обмена"""
        try:
            return pyperclip.paste()
        except Exception as e:
            self.logger.warning(locales.get_en('copy_to_clipboard_2', error=str(e)))
            return ""

    def add_to_favorites(self, server: str) -> bool:
        """Добавляет сервер в избранное. Отклоняет некорректный адрес."""
        # Без проверки прочитанное с устройства мусорное значение попадало в
        # список, после чего _normalize_saved_servers валил каждое следующее
        # сохранение и файл избранного переставал обновляться вообще
        if not self.validate_ntp_server(server):
            return False
        if server in self.saved_servers['favorite_servers']:
            return True
        self.saved_servers['favorite_servers'].append(server)
        if self.save_servers():
            return True
        self.saved_servers['favorite_servers'].remove(server)
        return False

    def remove_from_favorites(self, server: str):
        """Удаляет сервер из избранного"""
        if server in self.saved_servers['favorite_servers']:
            self.saved_servers['favorite_servers'].remove(server)
            self.save_servers()

    def server_management_menu(self) -> None:
        """Подменю управления серверами"""
        while True:
            print(Fore.GREEN + "\n" + locales.get("server_management"))
            print(Fore.YELLOW + "1. " + locales.get("show_favorite_servers"))
            print(Fore.YELLOW + "2. " + locales.get("add_current_server_to_favorites"))
            print(Fore.YELLOW + "3. " + locales.get("copy_server_to_clipboard"))
            print(Fore.YELLOW + "4. " + locales.get("paste_server_from_clipboard"))
            print(Fore.YELLOW + "5. " + locales.get("remove_server_from_favorites"))
            print(Fore.YELLOW + "6. " + locales.get("ping_ntp_menu"))
            print(Fore.YELLOW + "7. " + locales.get("export_import_menu"))
            print(Fore.YELLOW + "8. " + locales.get("return_to_main_menu"))

            choice = input(Fore.GREEN + locales.get("select_action") + " " + Fore.WHITE).strip()

            if choice == '1':
                favorites = self.saved_servers.get('favorite_servers', [])
                if favorites:
                    print(Fore.GREEN + locales.get("favorite_servers_list"))
                    for i, server in enumerate(favorites, 1):
                        print(Fore.WHITE + f"  {i}. {server}")
                else:
                    print(Fore.YELLOW + locales.get("no_favorite_servers"))

            elif choice == '2':
                if not self.device:
                    print(Fore.RED + locales.get("connect_device_first"))
                    continue
                try:
                    current_ntp = self.get_current_ntp()
                    if not current_ntp or current_ntp == 'null':
                        print(Fore.RED + locales.get("no_device_connected"))
                    elif self.add_to_favorites(current_ntp):
                        print(Fore.GREEN + locales.get("server_added_to_favorites", server=current_ntp))
                    else:
                        print(Fore.RED + locales.get("invalid_ntp_server_format"))
                except AndroidTVTimeFixerError as e:
                    print(Fore.RED + locales.get("error_message", error=str(e)))

            elif choice == '3':
                if not self.device:
                    print(Fore.RED + locales.get("connect_device_first"))
                    continue
                try:
                    current_ntp = self.get_current_ntp()
                    if self.copy_server_to_clipboard(current_ntp):
                        print(Fore.GREEN + locales.get("server_copied_to_clipboard", server=current_ntp))
                    else:
                        print(Fore.RED + locales.get("failed_to_copy_server"))
                except AndroidTVTimeFixerError as e:
                    print(Fore.RED + locales.get("error_message", error=str(e)))

            elif choice == '4':
                try:
                    server = self.paste_server_from_clipboard()
                    if server and server.strip():
                        server = server.strip()
                        if self.validate_ntp_server(server):
                            print(Fore.GREEN + locales.get("server_set_from_clipboard", server=server))
                            if not self.device:
                                print(Fore.RED + locales.get("connect_device_first"))
                            elif self.fix_time(server):
                                print(Fore.GREEN + locales.get("ntp_server_set", ntp_server=server))
                        else:
                            print(Fore.RED + locales.get("invalid_ntp_server_format"))
                    else:
                        print(Fore.YELLOW + locales.get("clipboard_empty_or_unavailable"))
                except Exception as e:
                    print(Fore.RED + locales.get("error_occurred", error=str(e)))

            elif choice == '5':
                favorites = self.saved_servers.get('favorite_servers', [])
                if not favorites:
                    print(Fore.YELLOW + locales.get("no_favorite_servers"))
                    continue
                print(Fore.GREEN + locales.get("choose_server_to_remove"))
                for i, server in enumerate(favorites, 1):
                    print(Fore.WHITE + f"  {i}. {server}")
                try:
                    num = int(input(Fore.GREEN + locales.get("enter_server_number") + " " + Fore.WHITE).strip())
                    if 1 <= num <= len(favorites):
                        removed = favorites[num - 1]
                        self.remove_from_favorites(removed)
                        print(Fore.GREEN + locales.get("server_removed_from_favorites", server=removed))
                    else:
                        print(Fore.RED + locales.get("invalid_number"))
                except ValueError:
                    print(Fore.RED + locales.get("enter_valid_number"))

            elif choice == '6':
                self.logger.info("Submenu: Ping NTP servers")
                self.ping_ntp_servers()

            elif choice == '7':
                self.export_import_menu()

            elif choice == '8':
                break
            else:
                print(Fore.RED + locales.get("invalid_choice"))

    @staticmethod
    def parse_ip_port(address: str) -> Tuple[str, int]:
        """Разбирает адрес вида 'ip' или 'ip:port'.

        Возвращает (ip, port); при отсутствующем или некорректном порте —
        DEFAULT_ADB_PORT.
        """
        if ':' in address:
            parts = address.rsplit(':', 1)
            ip = parts[0]
            try:
                port = int(parts[1])
                if not (1 <= port <= 65535):
                    port = DEFAULT_ADB_PORT
            except ValueError:
                port = DEFAULT_ADB_PORT
        else:
            ip = address
            port = DEFAULT_ADB_PORT
        return ip.strip(), port

    @staticmethod
    def validate_ip(ip: str) -> bool:
        """Проверяет IP-адрес, допускает формат ip или ip:port"""
        # Отделяем порт если есть и проверяем его явно,
        # чтобы некорректный порт не подменялся молча на порт по умолчанию
        if ':' in ip:
            ip, port_str = ip.rsplit(':', 1)
            try:
                if not (1 <= int(port_str) <= 65535):
                    return False
            except ValueError:
                return False
        pattern = r'^(\d{1,3}\.){3}\d{1,3}$'
        if not re.match(pattern, ip):
            return False
        octets = ip.split('.')
        return all(0 <= int(octet) <= 255 for octet in octets)

    @staticmethod
    def validate_country_code(code: str) -> bool:
        return bool(re.match(r'^[a-zA-Z]{2}$', code))

    def gen_keys(self) -> None:
        try:
            priv_key = self.keys_folder / 'adbkey'
            pub_key = self.keys_folder / 'adbkey.pub'

            if priv_key.exists() and pub_key.exists():
                self.logger.info(locales.get_en('existing_adb_keys'))
            elif priv_key.exists():
                # Потерян только публичный ключ — восстанавливаем его из
                # приватного: перегенерация пары сбросила бы авторизацию
                # программы на всех ранее подключённых устройствах
                write_public_keyfile(str(priv_key), str(pub_key))
                self.logger.info(locales.get_en('adb_pubkey_restored'))
            else:
                self.keys_folder.mkdir(parents=True, exist_ok=True, mode=0o700)
                keygen(str(priv_key))
                self.logger.info(locales.get_en('gen_keys'))
            if os.name != 'nt':
                self.keys_folder.chmod(0o700)
            self._secure_file(priv_key)
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get('key_generation_error', error=str(e)))

    def load_keys(self):
        try:
            with open(self.keys_folder / 'adbkey.pub', 'rb') as f:
                pub = f.read()
            with open(self.keys_folder / 'adbkey', 'rb') as f:
                priv = f.read()
            return pub, priv
        except FileNotFoundError:
            raise AndroidTVTimeFixerError(locales.get("adb_keys_not_found"))
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("key_loading_error", error=str(e)))

    def _close_device(self) -> None:
        """Закрывает активный adb-shell transport и очищает состояние."""
        device = self.device
        self.device = None
        self.connected_ip = None
        process_manager = getattr(self, 'process_manager', None)
        if process_manager is not None:
            process_manager.device_ip = None
        if device is not None:
            try:
                device.close()
            except Exception as e:
                self.logger.debug(f"Could not close ADB transport: {e}")

    def close(self) -> None:
        """Освобождает только ресурсы, принадлежащие этому экземпляру."""
        self._close_device()
        self.process_manager.cleanup()
    
    def connect_or_reuse(self, ip: str) -> None:
        """Подключается к устройству или переиспользует существующее соединение"""
        host, port = self.parse_ip_port(ip)
        normalized = f"{host}:{port}"
        if self.device and self.connected_ip == normalized:
            try:
                # Проверяем, что соединение ещё активно
                self.device.shell('echo ok')
                self.logger.info(f"Reusing existing connection to {normalized}")
                print(Fore.GREEN + locales.get("connection_reused", ip=normalized))
                return
            except Exception:
                # Соединение потеряно, переподключаемся
                self._close_device()
        elif self.device:
            self._close_device()
        self.connect(ip)

    def verify_ntp_server(self, server: str, count: int = 3, timeout: int = 3) -> bool:
        """Проверяет что NTP-сервер действительно синхронизирует время (не просто доступен)"""
        print(Fore.CYAN + locales.get("ntp_verify_before_apply"))
        result = self._test_ntp_server(server, count=count, timeout=timeout)

        if result['status'] != 'Reachable':
            print(Fore.RED + locales.get("ntp_verify_failed", server=server))
            self.logger.warning(
                f"NTP server {server} rejected: unavailable as NTP server "
                f"({result.get('error') or 'unknown error'})"
            )
            return False

        avg_offset = result['offset']

        # Проверяем что offset адекватный (сервер реально синхронизирует время)
        if avg_offset is None:
            print(Fore.RED + locales.get("ntp_verify_failed", server=server))
            self.logger.warning(f"NTP server {server} rejected: missing offset")
            return False

        if abs(avg_offset) > 60:
            print(Fore.RED + locales.get("ntp_verify_bad_offset", server=server, offset=avg_offset))
            self.logger.warning(
                f"NTP server {server} rejected: bad offset {avg_offset}"
            )
            return False

        print(Fore.GREEN + locales.get("ntp_verify_detailed",
                                       server=server, rtt=result['avg_rtt'],
                                       success=result['success_rate'], offset=avg_offset))
        return True

    def connect(self, ip: str) -> None:
        """Улучшенная версия метода подключения с ожиданием разрешения"""
        if not self.validate_ip(ip):
            raise AndroidTVTimeFixerError(locales.get("invalid_ip_format", port=DEFAULT_ADB_PORT))

        host, port = self.parse_ip_port(ip)

        # Проверяем доступность порта перед попыткой подключения
        print(Fore.CYAN + locales.get("checking_port", ip=host, port=port))
        if not self._wait_for_port(host, port):
            raise AndroidTVTimeFixerError(locales.get("port_not_available", ip=host, port=port))

        # Порт открыт — выясняем, каким протоколом говорит adbd. Без этого
        # устройство с беспроводной отладкой молча выедало весь таймаут
        # ожидания подтверждения: adb_shell не понимает STLS.
        protocol = self._detect_adb_protocol(host, port)
        if protocol is None:
            raise AndroidTVTimeFixerError(locales.get("adb_probe_failed", ip=host, port=port))
        if protocol == 'tls':
            print(Fore.YELLOW + locales.get("adb_protocol_tls_detected", ip=f"{host}:{port}"))
            print(Fore.CYAN + locales.get("adb_tls_connect_try"))
            transport = self._connect_via_platform_tools(host, port)
            self._close_device()
            self.device = transport
            self.connected_ip = f"{host}:{port}"
            self.process_manager.device_ip = f"{host}:{port}"
            print(Fore.GREEN + locales.get("adb_tls_connect_ok", ip=f"{host}:{port}"))
            self.logger.info(locales.get_en('connection_success', ip=host, port=port))
            return

        pub, priv = self.load_keys()
        signer = PythonRSASigner(pub, priv)

        start_time = time.time()
        connection_established = False
        last_error = None

        print(locales.get("waiting_for_connection", remaining_time=self.connection_timeout))
        print(locales.get("confirm_connection"))

        attempt = 0

        def announce_prompt(_device: Any) -> None:
            # adb_shell зовёт это ровно перед отправкой публичного ключа —
            # в этот момент запрос и появляется на экране устройства
            print()
            print(Fore.YELLOW + locales.get("connection_prompt_sent", attempt=attempt))

        while True:
            remaining_time = int(self.connection_timeout - (time.time() - start_time))
            if remaining_time <= 0:
                break
            attempt += 1
            device = None
            try:
                device = AdbDeviceTcp(host, port, default_transport_timeout_s=9.)
                device.connect(
                    rsa_keys=[signer],
                    auth_timeout_s=min(15, remaining_time),
                    auth_callback=announce_prompt
                )
                connection_established = True
            except Exception as e:
                last_error = str(e)
            finally:
                # Сокет неудачной попытки закрываем и при Ctrl+C: иначе при
                # минутном ожидании подтверждения накапливаются открытые
                # соединения, а прерывание оставляло сессию висеть на устройстве
                if device is not None and not connection_established:
                    try:
                        device.close()
                    except Exception:
                        pass

            if connection_established:
                self._close_device()
                self.device = device
                self.connected_ip = f"{host}:{port}"
                # Без этого disconnect_device() всегда выходил вхолостую:
                # адрес, который приложение должно отключить, не сохранялся
                self.process_manager.device_ip = f"{host}:{port}"
                self.logger.info(locales.get_en('connection_success', ip=host, port=port))
                break

            remaining_time = max(0, int(self.connection_timeout - (time.time() - start_time)))
            # ljust затирает хвост предыдущего значения счётчика, flush нужен
            # потому что строка без перевода не выталкивается из буфера сама
            print(
                locales.get("waiting_for_connection", remaining_time=remaining_time).ljust(50),
                end='', flush=True
            )
            if remaining_time > 0:
                time.sleep(1)

        print()  # Новая строка после завершения ожидания

        if not connection_established:
            # Не оставляем недоподключённый объект: иначе проверки
            # "if not self.device" дальше по коду пройдут ложно-успешно
            self._close_device()
            raise AndroidTVTimeFixerError(
                locales.get("connection_failed", timeout=self.connection_timeout) + "\n" +
                locales.get("ensure_steps") + "\n" +
                locales.get("last_error", error=last_error)
            )

    def get_current_ntp(self) -> str:
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        try:
            current_ntp = self.device.shell('settings get global ntp_server')
            return current_ntp.strip()
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get('failed_to_get_ntp_server', error=str(e)))

    def set_ntp_server(self, ntp_server: str) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get('no_device_connected'))
        if not self.validate_ntp_server(ntp_server):
            raise AndroidTVTimeFixerError(locales.get("invalid_ntp_server_format"))
        if not self.verify_ntp_server(ntp_server):
            raise AndroidTVTimeFixerError(locales.get("ntp_server_not_added", server=ntp_server))
    
        try:
            self.device.shell(f'settings put global ntp_server {shlex.quote(ntp_server)}')
            self.logger.info(locales.get_en('ntp_server_set', ntp_server=ntp_server))
    
            # Проверяем изменение
            new_ntp = self.get_current_ntp()
            if new_ntp != ntp_server:
                raise AndroidTVTimeFixerError(locales.get("ntp_server_confirmation_failed"))
        except AndroidTVTimeFixerError:
            raise
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("ntp_server_update_failed", error=str(e)))
	
    def fix_time(self, ntp_server: str) -> bool:
        """Проверяет и устанавливает NTP-сервер. Возвращает True если установлен."""
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        self.set_ntp_server(ntp_server)
        return True

    # ──────────────────────────────────────────────────────────
    # Network scan
    # ──────────────────────────────────────────────────────────

    @staticmethod
    def _check_port_available(ip: str, port: int, timeout: float = 2.0) -> bool:
        """Проверяет, открыт ли указанный порт на IP-адресе"""
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(timeout)
                return sock.connect_ex((ip, port)) == 0
        except Exception:
            return False

    def _wait_for_port(self, host: str, port: int, attempts: int = 3, timeout: float = 3.0) -> bool:
        """
        Проверяет доступность порта несколькими попытками.

        Одиночная проверка с таймаутом 2 с не переживала холодный ARP и
        энергосбережение Wi-Fi: устройство отвечало позже, и подключение
        обрывалось ещё до входа в цикл ожидания подтверждения на устройстве.
        """
        for attempt in range(attempts):
            if self._check_port_available(host, port, timeout=timeout):
                return True
            if attempt < attempts - 1:
                time.sleep(1)
        return False

    def _detect_adb_protocol(self, host: str, port: int, timeout: float = 3.0) -> Optional[str]:
        """Определяет, каким протоколом говорит adbd на host:port.

        Отправляет CNXN — тот же, что шлёт adb_shell, — и смотрит на команду в
        24-байтном заголовке ответа:

        * AUTH — «отладка по сети», открытый протокол, работает adb_shell;
        * STLS — беспроводная отладка Android 11+, дальше только TLS;
        * CNXN — устройство уже авторизовало этот ключ.

        Возвращает 'legacy' / 'tls' / 'authorized', либо None, если устройство
        не ответило или ответило чем-то неожиданным.
        """
        message = AdbMessage(
            adb_constants.CNXN,
            adb_constants.VERSION,
            adb_constants.MAX_ADB_DATA,
            b'host::androidtvtimefixer\0'
        )
        try:
            with socket.create_connection((host, port), timeout=timeout) as sock:
                sock.settimeout(timeout)
                sock.sendall(message.pack() + message.data)
                header = b''
                while len(header) < adb_constants.MESSAGE_SIZE:
                    chunk = sock.recv(adb_constants.MESSAGE_SIZE - len(header))
                    if not chunk:
                        return None
                    header += chunk
        except Exception as e:
            self.logger.debug(f"ADB protocol probe failed for {host}:{port}: {e}")
            return None

        command = struct.unpack(adb_constants.MESSAGE_FORMAT, header)[0]
        return {
            adb_constants.ID_TO_WIRE[adb_constants.AUTH]: 'legacy',
            adb_constants.ID_TO_WIRE[adb_constants.CNXN]: 'authorized',
            A_STLS: 'tls',
        }.get(command)

    def _connect_via_platform_tools(self, host: str, port: int) -> PlatformToolsTransport:
        """Подключается к TLS-устройству встроенным adb.

        Сработает только если этот компьютер уже спарен с устройством штатным
        adb: спаривание по коду — отдельный шаг, здесь его нет.
        """
        serial = f"{host}:{port}"
        try:
            result = subprocess.run(
                [self.get_adb_path(), 'connect', serial],
                stdout=PIPE,
                stderr=subprocess.STDOUT,
                universal_newlines=True,
                encoding=_subprocess_encoding(),
                timeout=30,
                check=False,
                env=self.adb_env
            )
        except Exception as e:
            raise AndroidTVTimeFixerError(
                locales.get('adb_tls_pairing_required', ip=serial, error=str(e))
            )

        output = (result.stdout or '').strip()
        lowered = output.lower()
        if result.returncode != 0 or any(err in lowered for err in ADB_CONNECTION_ERRORS):
            raise AndroidTVTimeFixerError(
                locales.get('adb_tls_pairing_required', ip=serial, error=output)
            )

        self.logger.info(locales.get_en('adb_tls_connect_ok', ip=serial))
        return PlatformToolsTransport(self.get_adb_path(), serial, env=self.adb_env)

    # ──────────────────────────────────────────────────────────
    # mDNS discovery
    # ──────────────────────────────────────────────────────────

    def _run_adb(self, args: List[str], timeout: int = 15) -> Tuple[int, str]:
        """Запускает встроенный adb и возвращает (код возврата, вывод)."""
        result = subprocess.run(
            [self.get_adb_path()] + args,
            stdout=PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            encoding=_subprocess_encoding(),
            timeout=timeout,
            check=False,
            env=self.adb_env
        )
        return result.returncode, (result.stdout or '')

    def mdns_available(self) -> bool:
        """Проверяет, работает ли mDNS-бэкенд самого adb.

        На части сборок platform-tools демон обнаружения недоступен, и тогда
        'adb mdns services' молча возвращает пустой список вместо ошибки.
        """
        try:
            returncode, output = self._run_adb(['mdns', 'check'])
        except Exception as e:
            self.logger.debug(f"adb mdns check failed: {e}")
            return False
        if returncode != 0:
            return False
        # Успешный ответ вида "mdns daemon version [Openscreen discovery 0.0.0]"
        return 'unavailable' not in output.lower() and 'mdns' in output.lower()

    @staticmethod
    def _parse_mdns_services(output: str, service: str) -> List[str]:
        """Разбирает вывод 'adb mdns services'.

        Формат — имя, тип сервиса и адрес через табуляции, но заголовок и
        точность разметки от версии к версии плавают, поэтому разбираем
        свободно: строка годится, только если в ней есть и нужный тип сервиса,
        и токен вида ip:port. Всё остальное молча пропускаем.
        """
        found: List[str] = []
        for line in output.splitlines():
            if service not in line:
                continue
            for token in line.split():
                match = _IP_PORT_RE.match(token.strip())
                if not match:
                    continue
                port = int(match.group(2))
                if not (1 <= port <= 65535):
                    continue
                entry = f"{match.group(1)}:{port}"
                if entry not in found:
                    found.append(entry)
        return found

    def _mdns_via_adb(self, service: str) -> List[str]:
        """Обнаружение через встроенный adb."""
        if not self.mdns_available():
            return []
        try:
            _returncode, output = self._run_adb(['mdns', 'services'])
        except Exception as e:
            self.logger.debug(f"adb mdns services failed: {e}")
            return []
        return self._parse_mdns_services(output, service)

    def _mdns_via_zeroconf(self, service: str, timeout: float) -> List[str]:
        """Запасное обнаружение через библиотеку zeroconf."""
        if Zeroconf is None or ServiceBrowser is None:
            return []

        zeroconf_instance = None
        try:
            zeroconf_instance = Zeroconf()
            collector = _MdnsCollector(zeroconf_instance)
            ServiceBrowser(zeroconf_instance, f"{service}.local.", collector)
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                time.sleep(0.2)
            return list(collector.found)
        except Exception as e:
            self.logger.debug(f"zeroconf discovery failed: {e}")
            return []
        finally:
            if zeroconf_instance is not None:
                try:
                    zeroconf_instance.close()
                except Exception:
                    pass

    def mdns_discover(self, kind: str = 'connect', timeout: float = 5.0) -> List[str]:
        """Находит устройства через mDNS. Возвращает адреса 'ip:port'.

        Тот же контракт, что у scan_network_for_android_devices, поэтому
        результат взаимозаменяем в меню и в connect_or_reuse.
        """
        service = ADB_MDNS_SERVICES[kind]
        found = self._mdns_via_adb(service)
        if not found:
            found = self._mdns_via_zeroconf(service, timeout)
        return sorted(found, key=lambda item: ipaddress.IPv4Address(item.split(':')[0]))

    # ──────────────────────────────────────────────────────────
    # Wireless debugging (Android 11+): pairing
    # ──────────────────────────────────────────────────────────

    @staticmethod
    def validate_pairing_code(code: str) -> bool:
        """Код спаривания — ровно шесть цифр."""
        return bool(re.fullmatch(r'\d{6}', code.strip()))

    def pair_device(self, address: str, code: str) -> None:
        """Спаривает компьютер с устройством по коду с экрана TV.

        Успехом считаем только явное подтверждение в выводе: adb умеет
        завершаться с нулевым кодом, ничего при этом не спарив.
        """
        if not self.validate_ip(address):
            raise AndroidTVTimeFixerError(locales.get("invalid_ip_format", port=DEFAULT_ADB_PORT))
        if not self.validate_pairing_code(code):
            raise AndroidTVTimeFixerError(locales.get("invalid_pairing_code"))

        host, port = self.parse_ip_port(address)
        serial = f"{host}:{port}"
        print(Fore.CYAN + locales.get("pairing_in_progress", ip=serial))
        try:
            returncode, output = self._run_adb(['pair', serial, code.strip()], timeout=60)
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("pairing_failed", ip=serial, error=str(e)))

        output = output.strip()
        if returncode != 0 or 'successfully paired' not in output.lower():
            raise AndroidTVTimeFixerError(
                locales.get("pairing_failed", ip=serial, error=output or str(returncode))
            )
        self.logger.info(locales.get_en('pairing_success', ip=serial))

    def _check_adb_port(self, ip: str, timeout: float, port: int = DEFAULT_ADB_PORT) -> Optional[str]:
        """Проверяет, открыт ли ADB-порт на указанном IP"""
        return ip if self._check_port_available(ip, port, timeout=timeout) else None

    # Второй проход имеет смысл только для подсети обозримого размера
    MAX_RETRY_HOSTS = 1024

    @staticmethod
    def _scan_limits(total: int) -> Tuple[float, int]:
        """
        Подбирает таймаут и число потоков первого прохода.

        Одного прохода недостаточно: пока адрес не разрешён в MAC, SYN стоит
        в очереди ARP, а Android TV по Wi-Fi в энергосбережении отвечает на
        ARP с задержкой. Хуже того, при наплыве одновременных ARP-запросов
        Windows возвращает "host unreachable" сразу, вообще не дожидаясь
        таймаута — поэтому увеличение таймаута само по себе не помогает,
        нужен именно повторный проход по не ответившим адресам.
        """
        if total <= 1024:
            return 1.5, min(64, total)
        return 0.6, min(512, total)

    def _select_scanned_device(self, found: List[str]) -> str:
        """Выбор устройства из результатов сканирования."""
        if not found:
            return ''

        raw = input(Fore.GREEN + locales.get("scan_select_device") + Fore.WHITE).strip()
        if not raw:
            return ''
        try:
            idx = int(raw)
            if 1 <= idx <= len(found):
                return found[idx - 1]
        except ValueError:
            pass
        print(Fore.RED + locales.get("invalid_input"))
        return ''

    @classmethod
    def _get_local_interface_networks(cls) -> List[Tuple[str, str, ipaddress.IPv4Network, bool]]:
        """Возвращает scannable private IPv4 сети локальных интерфейсов."""
        interfaces = []
        seen_networks = set()
        try:
            stats = psutil.net_if_stats()
            for iface_name, addrs in psutil.net_if_addrs().items():
                iface_stats = stats.get(iface_name)
                if iface_stats and not iface_stats.isup:
                    continue
                for addr in addrs:
                    if addr.family != socket.AF_INET:
                        continue
                    ip = addr.address
                    if not cls._is_scannable_local_ip(ip):
                        continue
                    if addr.netmask:
                        network = ipaddress.IPv4Network(f"{ip}/{addr.netmask}", strict=False)
                    else:
                        networks = cls._get_local_scan_networks(ip)
                        if not networks:
                            continue
                        network = networks[0]
                    if network in seen_networks:
                        continue
                    seen_networks.add(network)
                    interfaces.append((
                        iface_name,
                        ip,
                        network,
                        cls._is_virtual_interface_name(iface_name),
                    ))
        except Exception:
            pass
        return interfaces

    @classmethod
    def _get_default_route_local_ips(cls) -> List[str]:
        """Определяет local IP интерфейса основного маршрута без подключения к внешнему хосту."""
        detected = []
        try:
            if sys.platform == 'win32':
                detected.extend(cls._get_windows_default_route_ips())
            elif sys.platform == 'darwin':
                detected.extend(cls._get_macos_default_route_ips())
            else:
                detected.extend(cls._get_linux_default_route_ips())
        except Exception:
            pass
        return [ip for ip in dict.fromkeys(detected) if cls._is_scannable_local_ip(ip)]

    @classmethod
    def _get_linux_default_route_ips(cls) -> List[str]:
        result = subprocess.run(
            ['ip', '-4', 'route', 'show', 'default'],
            capture_output=True, text=True, timeout=3
        )
        ips = []
        for line in result.stdout.splitlines():
            src_match = re.search(r'\bsrc\s+(\d{1,3}(?:\.\d{1,3}){3})', line)
            if src_match:
                ips.append(src_match.group(1))
                continue
            dev_match = re.search(r'\bdev\s+(\S+)', line)
            if dev_match:
                ip = cls._get_interface_ipv4(dev_match.group(1))
                if ip:
                    ips.append(ip)
        return ips

    @classmethod
    def _get_macos_default_route_ips(cls) -> List[str]:
        result = subprocess.run(
            ['route', '-n', 'get', 'default'],
            capture_output=True, text=True, timeout=3
        )
        iface = ''
        for line in result.stdout.splitlines():
            stripped = line.strip()
            if stripped.startswith('interface:'):
                iface = stripped.split(':', 1)[1].strip()
                break
        ip = cls._get_interface_ipv4(iface) if iface else ''
        return [ip] if ip else []

    @staticmethod
    def _get_windows_default_route_ips() -> List[str]:
        result = subprocess.run(
            ['route', 'PRINT', '-4', '0.0.0.0'],
            capture_output=True, text=True, timeout=5
        )
        routes = []
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) < 5:
                continue
            if parts[0] != '0.0.0.0' or parts[1] != '0.0.0.0':
                continue
            try:
                metric = int(parts[4])
            except ValueError:
                metric = 0
            routes.append((metric, parts[3]))
        return [ip for _metric, ip in sorted(routes)]

    @staticmethod
    def _get_interface_ipv4(iface_name: str) -> str:
        try:
            for addr in psutil.net_if_addrs().get(iface_name, []):
                if addr.family == socket.AF_INET:
                    return addr.address
        except Exception:
            pass
        return ''

    @classmethod
    def _is_scannable_local_ip(cls, ip: str) -> bool:
        try:
            parsed = ipaddress.IPv4Address(ip)
        except ValueError:
            return False
        return not parsed.is_loopback and cls._is_private_ip(ip)

    @staticmethod
    def _is_virtual_interface_name(iface_name: str) -> bool:
        name = iface_name.lower()
        virtual_markers = (
            'virtual', 'vbox', 'vmware', 'hyper-v', 'vethernet',
            'docker', 'wsl', 'npcap', 'loopback'
        )
        return any(marker in name for marker in virtual_markers)

    @staticmethod
    def _is_private_ip(ip_str: str) -> bool:
        """Проверяет, является ли IP-адрес локальным (192.168.x.x, 10.x.x.x или 172.16-31.x.x)"""
        try:
            if ip_str.startswith('192.168.') or ip_str.startswith('10.'):
                return True
            if ip_str.startswith('172.'):
                second_octet = int(ip_str.split('.')[1])
                return 16 <= second_octet <= 31
            return False
        except Exception:
            return False

    @staticmethod
    def _detect_interface_network(local_ip: str) -> Optional[ipaddress.IPv4Network]:
        """
        Определяет реальную подсеть интерфейса через psutil.
        Возвращает точную сеть (например /24) или None если не удалось определить.
        """
        try:
            for iface_name, addrs in psutil.net_if_addrs().items():
                for addr in addrs:
                    if addr.family != socket.AF_INET:
                        continue
                    if addr.address != local_ip:
                        continue
                    if addr.netmask:
                        return ipaddress.IPv4Network(
                            f"{local_ip}/{addr.netmask}", strict=False
                        )
        except Exception:
            pass
        return None

    @classmethod
    def _get_local_scan_networks(cls, local_ip: str) -> List[ipaddress.IPv4Network]:
        """
        Определяет сети для сканирования на основе локального IP.
        Сначала пытается определить реальную подсеть интерфейса через psutil.
        Если не удалось — использует запасной диапазон:
          192.168.x.x → 192.168.0.0/16
          10.x.x.x    → текущая /16 + 10.1.0.0/16
        """
        try:
            addr = ipaddress.IPv4Address(local_ip)
        except ValueError:
            return []

        if addr.is_loopback:
            return []

        networks = []

        # Пытаемся определить реальную подсеть через интерфейс
        detected = cls._detect_interface_network(local_ip)

        if detected:
            networks.append(detected)
        elif local_ip.startswith('192.168.'):
            # Fallback: домашние сети — вся 192.168.0.0/16
            networks.append(ipaddress.IPv4Network('192.168.0.0/16', strict=False))
        elif local_ip.startswith('10.'):
            # Fallback: /16 от текущего IP
            current_net = ipaddress.IPv4Network(f"{local_ip}/16", strict=False)
            networks.append(current_net)
            # Дополнительно 10.1.0.0/16
            extra_net = ipaddress.IPv4Network('10.1.0.0/16', strict=False)
            if extra_net != current_net:
                networks.append(extra_net)
        elif local_ip.startswith('172.'):
            # Fallback: 172.16.0.0/12 private range
            try:
                second_octet = int(local_ip.split('.')[1])
                if 16 <= second_octet <= 31:
                    current_net = ipaddress.IPv4Network(f"{local_ip}/16", strict=False)
                    networks.append(current_net)
            except (ValueError, IndexError):
                pass

        return networks

    def _scan_networks(
            self,
            networks: List[ipaddress.IPv4Network],
            port: int = DEFAULT_ADB_PORT
    ) -> List[str]:
        """Сканирует список сетей на наличие устройств с открытым ADB-портом.

        Возвращает адреса в виде 'ip:port': порт больше не подразумевается,
        и вызывающий код (connect_or_reuse, batch_set_ntp) разбирает его через
        parse_ip_port.
        """
        collapsed_networks = list(ipaddress.collapse_addresses(networks))
        total = sum(self._network_hosts_count(network) for network in collapsed_networks)

        if total > self.MAX_SCAN_HOSTS:
            print(Fore.RED + locales.get(
                "scan_too_large", hosts=total, limit=self.MAX_SCAN_HOSTS
            ))
            return []

        net_names = ", ".join(str(n) for n in collapsed_networks)
        print(Fore.CYAN + locales.get("scan_start", network=net_names, port=port))

        if total == 0:
            print(Fore.YELLOW + locales.get("scan_complete", count=0))
            return []

        hosts = [
            str(host)
            for network in collapsed_networks
            for host in network.hosts()
        ]

        timeout, workers = self._scan_limits(len(hosts))
        found = self._probe_hosts(hosts, timeout, workers, port)

        # Первый проход прогревает ARP-кэш, но сам по себе теряет узлы,
        # ответившие с задержкой или отброшенные при наплыве запросов.
        # Поэтому не ответившие адреса перепроверяем ещё раз — дольше и
        # меньшим числом потоков, уже по прогретому ARP.
        found_set = set(found)
        remaining = [ip for ip in hosts if ip not in found_set]
        if 0 < len(remaining) <= self.MAX_RETRY_HOSTS:
            print(Fore.CYAN + locales.get("scan_retry", count=len(remaining)))
            found.extend(self._probe_hosts(
                remaining, timeout=3.0, workers=min(32, len(remaining)), port=port
            ))

        # Порядок завершения задач произволен, а найденное на втором проходе
        # иначе всегда оказывалось в хвосте: пользователь выбирает по номеру.
        # Сортируем по голому адресу и только потом приписываем порт: у
        # IPv4Address строка 'ip:port' вызывает AddressValueError.
        return [f"{ip}:{port}" for ip in sorted(found, key=ipaddress.IPv4Address)]

    def _probe_hosts(
            self,
            hosts: List[str],
            timeout: float,
            workers: int,
            port: int = DEFAULT_ADB_PORT
    ) -> List[str]:
        """Проверяет ADB-порт на списке адресов, печатая прогресс."""
        total = len(hosts)
        found: List[str] = []
        checked = 0
        host_iter = iter(hosts)

        with ThreadPoolExecutor(max_workers=workers) as executor:
            pending = {}

            def submit_next() -> None:
                try:
                    ip = next(host_iter)
                except StopIteration:
                    return
                pending[executor.submit(self._check_adb_port, ip, timeout, port)] = ip

            for _ in range(workers):
                submit_next()

            while pending:
                done, _ = wait(pending, return_when=FIRST_COMPLETED)
                for future in done:
                    pending.pop(future, None)
                    try:
                        result = future.result()
                    except Exception:
                        result = None
                    checked += 1
                    if result:
                        found.append(result)
                    if checked % 50 == 0 or checked == total:
                        print(
                            Fore.CYAN + "\r  " +
                            locales.get("scan_progress", checked=checked, total=total, found=len(found)),
                            end="", flush=True
                        )
                    submit_next()
        print()  # новая строка после прогресса
        return found

    @staticmethod
    def _unique_networks(networks: List[ipaddress.IPv4Network]) -> List[ipaddress.IPv4Network]:
        unique = []
        for network in networks:
            if network not in unique:
                unique.append(network)
        return unique

    @staticmethod
    def _network_hosts_count(network: ipaddress.IPv4Network) -> int:
        return network.num_addresses - 2 if network.prefixlen < 31 else network.num_addresses

    @staticmethod
    def _make_wide_network(ip: str) -> Optional[ipaddress.IPv4Network]:
        if ip.startswith('192.168.'):
            return ipaddress.IPv4Network('192.168.0.0/16', strict=False)
        if ip.startswith('10.'):
            return ipaddress.IPv4Network(f"{ip}/16", strict=False)
        if ip.startswith('172.'):
            try:
                second_octet = int(ip.split('.')[1])
                if 16 <= second_octet <= 31:
                    return ipaddress.IPv4Network(f"{ip}/16", strict=False)
            except (ValueError, IndexError):
                return None
        return None

    @classmethod
    def _get_wide_candidates(
            cls,
            interfaces: List[Tuple[str, str, ipaddress.IPv4Network, bool]],
            scanned_networks: List[ipaddress.IPv4Network]
    ) -> List[ipaddress.IPv4Network]:
        candidates = []
        for _iface_name, ip, network, _is_virtual in interfaces:
            if network not in scanned_networks or network.prefixlen <= 16:
                continue
            wide = cls._make_wide_network(ip)
            if wide and wide != network and wide not in candidates:
                candidates.append(wide)
        return candidates

    @staticmethod
    def _split_cidr_port(raw: str) -> Tuple[str, Optional[int]]:
        """Разбирает 'CIDR' или 'CIDR:порт'.

        В записи IPv4-подсети двоеточий нет, поэтому хвост после последнего ':'
        однозначно является портом. Некорректный порт — ValueError, чтобы он не
        подменялся молча на значение по умолчанию.
        """
        raw = raw.strip()
        if ':' not in raw:
            return raw, None
        cidr, port_str = raw.rsplit(':', 1)
        port = int(port_str)
        if not (1 <= port <= 65535):
            raise ValueError(port_str)
        return cidr.strip(), port

    def scan_custom_network(self, cidr: str, port: Optional[int] = None) -> List[str]:
        """Сканирует подсеть, введённую пользователем вручную.

        Принимает как 'CIDR', так и 'CIDR:порт'; порт из строки приоритетнее
        аргумента.
        """
        try:
            cidr, inline_port = self._split_cidr_port(cidr)
        except ValueError:
            print(Fore.RED + locales.get("invalid_port"))
            return []

        scan_port = inline_port or port or DEFAULT_ADB_PORT

        try:
            network = ipaddress.IPv4Network(cidr.strip(), strict=False)
        except ValueError:
            print(Fore.RED + locales.get("scan_invalid_cidr", cidr=cidr))
            return []

        if network.version != 4 or not network.is_private:
            print(Fore.RED + locales.get("scan_invalid_cidr", cidr=cidr))
            return []

        hosts_count = self._network_hosts_count(network)
        if hosts_count > self.MAX_SCAN_HOSTS:
            print(Fore.RED + locales.get(
                "scan_too_large", hosts=hosts_count, limit=self.MAX_SCAN_HOSTS
            ))
            return []
        if hosts_count > 4096:
            answer = input(
                Fore.YELLOW +
                locales.get("scan_large_custom_offer", network=str(network), hosts=hosts_count) +
                Fore.WHITE
            ).strip().lower()
            if answer not in ('y', 'yes', 'д', 'да'):
                return []

        found = self._scan_networks([network], scan_port)
        if found:
            print(Fore.GREEN + locales.get("scan_found", count=len(found)))
            for i, ip in enumerate(found, 1):
                print(Fore.WHITE + f"  {i}. {ip}")
        else:
            print(Fore.YELLOW + locales.get("scan_none"))
            print(Fore.YELLOW + locales.get("scan_firewall_hint"))
        return found

    def _choose_additional_networks(
            self,
            additional: List[Tuple[str, str, ipaddress.IPv4Network, bool]]
    ) -> List[ipaddress.IPv4Network]:
        print(Fore.CYAN + locales.get("scan_additional_available"))
        for idx, (iface_name, ip, network, is_virtual) in enumerate(additional, 1):
            marker = locales.get("scan_virtual_marker") if is_virtual else locales.get("scan_physical_marker")
            print(Fore.WHITE + f"  {idx}. {network}  {iface_name} ({ip}) {marker}")

        answer = input(Fore.GREEN + locales.get("scan_additional_prompt") + Fore.WHITE).strip().lower()
        if answer in ('', 'n', 'no', 'н', 'нет'):
            return []
        if answer in ('all', 'a', 'все'):
            return self._unique_networks([network for _iface, _ip, network, _virt in additional])

        selected = []
        for raw in re.split(r'[\s,]+', answer):
            if not raw:
                continue
            try:
                idx = int(raw)
            except ValueError:
                continue
            if 1 <= idx <= len(additional):
                network = additional[idx - 1][2]
                if network not in selected:
                    selected.append(network)
        if not selected:
            print(Fore.RED + locales.get("invalid_input"))
        return selected

    def scan_network_for_android_devices(self, port: Optional[int] = None) -> List[str]:
        """Сканирует локальные подсети в поисках устройств с открытым ADB-портом.
        Автоматически определяет подсеть через psutil, fallback на /16."""
        scan_port = port or DEFAULT_ADB_PORT
        interfaces = self._get_local_interface_networks()
        if not interfaces:
            print(Fore.RED + locales.get("scan_local_ip_error"))
            return []

        default_ips = self._get_default_route_local_ips()
        primary = [item for item in interfaces if item[1] in default_ips]
        if not primary:
            primary = [item for item in interfaces if not item[3]]
        if not primary:
            primary = interfaces[:1]

        primary_networks = self._unique_networks([network for _iface, _ip, network, _virt in primary])
        additional = [
            item for item in interfaces
            if item[2] not in primary_networks
        ]

        if not primary_networks:
            print(Fore.RED + locales.get("scan_local_ip_error"))
            return []

        for network in primary_networks:
            hosts_count = self._network_hosts_count(network)
            print(Fore.GREEN + locales.get("scan_net_detected", network=str(network), hosts=hosts_count))

        found = self._scan_networks(primary_networks, scan_port)
        scanned_networks = list(primary_networks)

        if not found and additional:
            print(Fore.YELLOW + locales.get("scan_none"))
            selected_additional = self._choose_additional_networks(additional)
            if selected_additional:
                found = self._scan_networks(selected_additional, scan_port)
                scanned_networks.extend(selected_additional)

        wide_scan_offered = False
        wide_candidates = self._get_wide_candidates(interfaces, scanned_networks)
        if not found and wide_candidates:
            wide_scan_offered = True
            # scan_none здесь не печатаем: scan_wide_offer уже начинается
            # с "В подсети ... устройства не найдены"
            print(Fore.CYAN + locales.get(
                "scan_wide_offer",
                narrow=", ".join(str(n) for n in scanned_networks),
                wide=", ".join(str(n) for n in wide_candidates)
            ))
            answer = input(Fore.WHITE).strip().lower()
            if answer in ('y', 'yes', 'д', 'да'):
                found = self._scan_networks(wide_candidates, scan_port)

        if found:
            print(Fore.GREEN + locales.get("scan_found", count=len(found)))
            for i, ip in enumerate(found, 1):
                print(Fore.WHITE + f"  {i}. {ip}")
        elif not wide_scan_offered:
            print(Fore.YELLOW + locales.get("scan_none"))
            print(Fore.YELLOW + locales.get("scan_firewall_hint"))
        else:
            print(Fore.YELLOW + locales.get("scan_firewall_hint"))

        return found

    # ──────────────────────────────────────────────────────────
    # Batch NTP update
    # ──────────────────────────────────────────────────────────

    def batch_set_ntp(self, ntp_server: str, ip_list: List[str]) -> None:
        """Устанавливает NTP-сервер на нескольких устройствах одновременно"""
        if not self.validate_ntp_server(ntp_server):
            print(Fore.RED + locales.get("invalid_ntp_server_format"))
            return
        if not self.verify_ntp_server(ntp_server):
            print(Fore.RED + locales.get("ntp_server_not_added", server=ntp_server))
            return

        try:
            pub, priv = self.load_keys()
            signer = PythonRSASigner(pub, priv)
        except AndroidTVTimeFixerError as e:
            print(Fore.RED + locales.get("error_message", error=str(e)))
            return

        success = 0
        failed = 0
        total = len(ip_list)

        for idx, ip in enumerate(ip_list, 1):
            print(Fore.CYAN + locales.get("batch_connecting", idx=idx, total=total, ip=ip))
            device = None
            reused = False
            try:
                host, port = self.parse_ip_port(ip)
                if not self.validate_ip(ip):
                    raise AndroidTVTimeFixerError(locales.get("invalid_ip_format", port=DEFAULT_ADB_PORT))

                # Если это устройство уже подключено интерактивно, работаем через
                # тот же transport: второе соединение к тому же adbd конфликтует
                # с первым, а закрытие его в finally обрывало бы активную сессию
                if self.device is not None and self.connected_ip == f"{host}:{port}":
                    device = self.device
                    reused = True
                else:
                    if not self._wait_for_port(host, port):
                        raise AndroidTVTimeFixerError(
                            locales.get("port_not_available", ip=host, port=port)
                        )

                    protocol = self._detect_adb_protocol(host, port)
                    if protocol is None:
                        raise AndroidTVTimeFixerError(
                            locales.get("adb_probe_failed", ip=host, port=port)
                        )

                    if protocol == 'tls':
                        # Понятная причина вместо таймаута в общем except ниже
                        print(Fore.YELLOW + locales.get(
                            "adb_protocol_tls_detected", ip=f"{host}:{port}"
                        ))
                        device = self._connect_via_platform_tools(host, port)
                    else:
                        # Коллбэк срабатывает только когда ключ ещё не авторизован:
                        # иначе групповой прогон молча висел 15 секунд на устройстве,
                        # ждущем подтверждения, без единой подсказки пользователю
                        def announce_prompt(_device: Any) -> None:
                            print(Fore.YELLOW + locales.get("batch_prompt_sent", ip=ip))

                        device = AdbDeviceTcp(host, port, default_transport_timeout_s=9.)
                        device.connect(
                            rsa_keys=[signer],
                            auth_timeout_s=15,
                            auth_callback=announce_prompt
                        )

                device.shell(f'settings put global ntp_server {shlex.quote(ntp_server)}')
                confirmed = device.shell('settings get global ntp_server').strip()
                if confirmed == ntp_server:
                    print(Fore.GREEN + locales.get("batch_success", ip=ip, server=ntp_server))
                    success += 1
                else:
                    print(Fore.YELLOW + locales.get("batch_failed", ip=ip, error="verification failed"))
                    failed += 1
            except Exception as e:
                print(Fore.RED + locales.get("batch_failed", ip=ip, error=str(e)))
                failed += 1
            finally:
                if device is not None and not reused:
                    try:
                        device.close()
                    except Exception:
                        pass

        print(Fore.CYAN + locales.get("batch_summary", success=success, failed=failed, total=total))

    # ──────────────────────────────────────────────────────────
    # Device time synchronization
    # ──────────────────────────────────────────────────────────

    def show_device_time(self) -> None:
        """Показывает время устройства и сравнивает с временем ПК"""
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        print(Fore.CYAN + locales.get("device_time_title"))
        try:
            timestamp_str = self.device.shell('date +%s').strip()
            device_timestamp = int(timestamp_str)
            device_time = datetime.datetime.fromtimestamp(device_timestamp)
            pc_time = datetime.datetime.now()
            diff = abs((pc_time - device_time).total_seconds())

            print(Fore.WHITE + locales.get("device_time", time=device_time.strftime("%Y-%m-%d %H:%M:%S")))
            print(Fore.WHITE + locales.get("pc_time",     time=pc_time.strftime("%Y-%m-%d %H:%M:%S")))

            if diff < 60:
                print(Fore.GREEN + locales.get("time_in_sync"))
            else:
                hours   = int(diff // 3600)
                minutes = int((diff % 3600) // 60)
                seconds = int(diff % 60)
                diff_str = (f"{hours}h {minutes}m {seconds}s" if hours > 0
                            else f"{minutes}m {seconds}s")
                print(Fore.RED + locales.get("time_out_of_sync", diff=diff_str))
        except Exception as e:
            print(Fore.YELLOW + locales.get("device_time_error", error=str(e)))

    # ──────────────────────────────────────────────────────────
    # Export / Import settings
    # ──────────────────────────────────────────────────────────

    def export_settings(self, path: Optional[str] = None) -> None:
        """Экспортирует все настройки в JSON-файл"""
        if path is None:
            path = str(self.data_dir / 'backup.json')
        export_data = {
            'version': '1.0.0',
            'exported_at': datetime.datetime.now().isoformat(),
            'language': self.load_language(),
            'last_ip': self.load_last_ip(),
            'saved_servers': self.saved_servers,
        }
        try:
            self._atomic_write_json(Path(path).expanduser(), export_data)
            print(Fore.GREEN + locales.get("export_success", path=path))
            self.logger.info(f"Settings exported to: {path}")
        except Exception as e:
            print(Fore.RED + locales.get("export_failed", error=str(e)))
            self.logger.error(f"Export failed: {e}")

    def import_settings(self, path: str) -> None:
        """Импортирует настройки из JSON-файла"""
        if not os.path.exists(path):
            print(Fore.RED + locales.get("import_not_found", path=path))
            return
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            if not isinstance(data, dict):
                raise ValueError("Backup root must be an object")

            saved_servers = self.saved_servers
            if 'saved_servers' in data:
                saved_servers = self._normalize_saved_servers(data['saved_servers'])

            language = data.get('language', '')
            if language and language not in ('en', 'ru'):
                raise ValueError("Invalid language in backup")

            last_ip = data.get('last_ip', '')
            if last_ip and (not isinstance(last_ip, str) or not self.validate_ip(last_ip)):
                raise ValueError("Invalid device IP in backup")

            # Настройки лежат в двух файлах, и раньше сбой на втором оставлял
            # язык из бэкапа при старом списке серверов. Теперь язык и адрес
            # пишутся одной записью, а неудача откатывает уже записанное
            previous_servers = self.saved_servers
            new_settings = {
                'language': language or self.load_language(),
                'last_device_ip': last_ip or self.load_last_ip(),
            }

            self.saved_servers = saved_servers
            if not self.save_servers():
                self.saved_servers = previous_servers
                raise OSError("Could not save imported server settings")
            if not self._save_settings(new_settings):
                self.saved_servers = previous_servers
                self.save_servers()
                raise OSError("Could not save imported settings")

            self.last_device_ip = new_settings['last_device_ip']
            if language:
                set_language(language)
            print(Fore.GREEN + locales.get("import_success", path=path))
            self.logger.info(f"Settings imported from: {path}")
        except Exception as e:
            print(Fore.RED + locales.get("import_failed", error=str(e)))
            self.logger.error(f"Import failed: {e}")

    def export_import_menu(self) -> None:
        """Подменю экспорта/импорта настроек"""
        while True:
            print(Fore.GREEN + "\n" + locales.get("export_import_menu"))
            print(Fore.YELLOW + locales.get("choice_export"))
            print(Fore.YELLOW + locales.get("choice_import"))
            print(Fore.YELLOW + locales.get("choice_back"))

            choice = input(Fore.GREEN + locales.get("select_action") + " " + Fore.WHITE).strip()

            if choice == '1':
                raw = input(Fore.GREEN + locales.get("export_path_prompt") + Fore.WHITE).strip()
                self.export_settings(raw if raw else None)
            elif choice == '2':
                raw = input(Fore.GREEN + locales.get("import_path_prompt") + Fore.WHITE).strip()
                if raw:
                    self.import_settings(raw)
                else:
                    print(Fore.RED + locales.get("invalid_input"))
            elif choice == '3':
                break
            else:
                print(Fore.RED + locales.get("invalid_choice"))

    # ──────────────────────────────────────────────────────────
    # Network scan & batch submenu
    # ──────────────────────────────────────────────────────────

    def _select_from_list(self, items: List[str], prompt_key: str) -> str:
        """Печатает нумерованный список и возвращает выбранный элемент."""
        for index, item in enumerate(items, 1):
            print(Fore.WHITE + f"  {index}. {item}")
        raw = input(Fore.GREEN + locales.get(prompt_key) + " " + Fore.WHITE).strip()
        try:
            index = int(raw)
        except ValueError:
            print(Fore.RED + locales.get("invalid_input"))
            return ''
        if 1 <= index <= len(items):
            return items[index - 1]
        print(Fore.RED + locales.get("invalid_device_number"))
        return ''

    def _pick_wireless_address(self, kind: str) -> str:
        """Адрес устройства для беспроводной отладки: из mDNS или вручную.

        Порты спаривания и подключения на устройстве разные и случайные, так
        что mDNS здесь не украшение, а единственный способ не переписывать их
        с экрана вручную.
        """
        print(Fore.CYAN + locales.get("mdns_searching"))
        found = self.mdns_discover(kind)
        if found:
            print(Fore.GREEN + locales.get("mdns_found", count=len(found)))
            selected = self._select_from_list(found, "enter_device_number")
            if selected:
                return selected
        else:
            print(Fore.YELLOW + locales.get("mdns_none"))

        prompt = "enter_pairing_host" if kind == 'pairing' else "enter_connect_host"
        raw = input(Fore.GREEN + locales.get(prompt) + Fore.WHITE).strip()
        if not raw or raw.lower() == 'q':
            return ''
        if ':' in raw:
            return raw
        port = self.prompt_adb_port(default=DEFAULT_ADB_PORT, persist=False)
        return f"{raw}:{port}" if port else ''

    def wireless_menu(self) -> None:
        """Подменю беспроводной отладки Android 11+"""
        while True:
            print(Fore.GREEN + locales.get("wireless_menu"))
            print(Fore.YELLOW + "1. " + locales.get("wireless_pair_device"))
            print(Fore.YELLOW + "2. " + locales.get("wireless_mdns_scan"))
            print(Fore.YELLOW + "3. " + locales.get("return_to_main_menu"))

            choice = input(Fore.GREEN + locales.get("select_action") + " " + Fore.WHITE).strip()

            if choice == '1':
                try:
                    address = self._pick_wireless_address('pairing')
                    if not address:
                        continue
                    code = input(Fore.GREEN + locales.get("enter_pairing_code") + Fore.WHITE).strip()
                    if not code or code.lower() == 'q':
                        continue
                    self.pair_device(address, code)
                    print(Fore.GREEN + locales.get("pairing_success", ip=address))

                    # Спаривание прошло, но подключаться надо на другой порт
                    connect_address = self._pick_wireless_address('connect')
                    if not connect_address:
                        continue
                    self.connect_or_reuse(connect_address)
                    self.save_last_ip(connect_address)
                except AndroidTVTimeFixerError as e:
                    print(Fore.RED + locales.get("error_message", error=str(e)))

            elif choice == '2':
                print(Fore.CYAN + locales.get("mdns_searching"))
                connectable = self.mdns_discover('connect')
                pairable = self.mdns_discover('pairing')

                if pairable:
                    print(Fore.YELLOW + locales.get("mdns_service_pairing"))
                    for index, item in enumerate(pairable, 1):
                        print(Fore.WHITE + f"  {index}. {item}")

                if connectable:
                    print(Fore.GREEN + locales.get("mdns_service_connect"))
                    selected = self._select_from_list(connectable, "enter_device_number")
                    if selected:
                        try:
                            self.connect_or_reuse(selected)
                            self.save_last_ip(selected)
                        except AndroidTVTimeFixerError as e:
                            print(Fore.RED + locales.get("error_message", error=str(e)))
                elif not pairable:
                    print(Fore.YELLOW + locales.get("mdns_none"))
                    if Zeroconf is None:
                        print(Fore.YELLOW + locales.get("mdns_unavailable"))

            elif choice == '3':
                break
            else:
                print(Fore.RED + locales.get("invalid_choice"))

    def scan_batch_menu(self) -> None:
        """Подменю: сканирование сети и групповые операции"""
        discovered: List[str] = []

        while True:
            print(Fore.GREEN + locales.get("submenu_scan_batch"))
            print(Fore.YELLOW + locales.get("submenu_scan"))
            print(Fore.YELLOW + locales.get("submenu_connect_discovered"))
            print(Fore.YELLOW + locales.get("submenu_batch"))
            print(Fore.YELLOW + locales.get("submenu_time_sync"))
            print(Fore.YELLOW + locales.get("submenu_back"))

            choice = input(Fore.GREEN + locales.get("select_action") + " " + Fore.WHITE).strip()

            if choice == '1':
                port = self.prompt_adb_port()
                if port is None:
                    continue
                discovered = self.scan_network_for_android_devices(port)

            elif choice == '2':
                if not discovered:
                    print(Fore.RED + locales.get("no_discovered_devices"))
                    continue
                print(Fore.GREEN + locales.get("scan_found", count=len(discovered)))
                for i, ip in enumerate(discovered, 1):
                    print(Fore.WHITE + f"  {i}. {ip}")
                try:
                    num = int(input(Fore.GREEN + locales.get("enter_device_number") + " " + Fore.WHITE).strip())
                    if 1 <= num <= len(discovered):
                        ip = discovered[num - 1]
                        self.connect_or_reuse(ip)
                        self.save_last_ip(ip)
                    else:
                        print(Fore.RED + locales.get("invalid_device_number"))
                except ValueError:
                    print(Fore.RED + locales.get("invalid_input"))
                except AndroidTVTimeFixerError as e:
                    print(Fore.RED + locales.get("error_message", error=str(e)))

            elif choice == '3':
                print(Fore.GREEN + locales.get("batch_ntp_title"))
                print(Fore.CYAN + locales.get("ntp_format_hint"))
                ntp_server = input(Fore.GREEN + locales.get("batch_enter_ntp") + Fore.WHITE).strip()
                if not ntp_server or ntp_server.lower() == 'q':
                    continue
                if not self.validate_ntp_server(ntp_server):
                    print(Fore.RED + locales.get("invalid_ntp_server_format"))
                    continue

                ip_raw = input(
                    Fore.GREEN + locales.get("batch_enter_ips", count=len(discovered)) + Fore.WHITE
                ).strip()

                if ip_raw:
                    ip_list = [ip.strip() for ip in ip_raw.split(',') if ip.strip()]
                elif discovered:
                    ip_list = discovered
                else:
                    print(Fore.RED + locales.get("batch_no_targets"))
                    continue

                self.batch_set_ntp(ntp_server, ip_list)

            elif choice == '4':
                if not self.device:
                    ip = self.get_device_ip_input()
                    if not ip:      # отмена по 'q'
                        continue
                    if not self.validate_ip(ip):
                        print(Fore.RED + locales.get("invalid_ip_format", port=DEFAULT_ADB_PORT))
                        continue
                    try:
                        self.connect_or_reuse(ip)
                        self.save_last_ip(ip)
                    except AndroidTVTimeFixerError as e:
                        print(Fore.RED + locales.get("error_message", error=str(e)))
                        continue
                try:
                    self.show_device_time()
                except AndroidTVTimeFixerError as e:
                    print(Fore.RED + locales.get("error_message", error=str(e)))

            elif choice == '5':
                break
            else:
                print(Fore.RED + locales.get("invalid_choice"))

    # ──────────────────────────────────────────────────────────
    # Auto-setup NTP (experimental)
    # ──────────────────────────────────────────────────────────

    # Маппинг Windows-имён таймзон (time.tzname) → IANA timezone
    _win_tz_to_iana = {
        # Windows RTZ (Russia Time Zones)
        'RTZ 1': 'Europe/Kaliningrad',
        'RTZ 2': 'Europe/Moscow',
        'RTZ 3': 'Europe/Samara',
        'RTZ 4': 'Asia/Yekaterinburg',
        'RTZ 5': 'Asia/Omsk',
        'RTZ 6': 'Asia/Novosibirsk',
        'RTZ 7': 'Asia/Krasnoyarsk',
        'RTZ 8': 'Asia/Irkutsk',
        'RTZ 9': 'Asia/Yakutsk',
        'RTZ 10': 'Asia/Vladivostok',
        'RTZ 11': 'Asia/Kamchatka',
        # Windows standard names (English)
        'Eastern Standard Time': 'America/New_York',
        'Central Standard Time': 'America/Chicago',
        'Mountain Standard Time': 'America/Denver',
        'Pacific Standard Time': 'America/Los_Angeles',
        'Central European Standard Time': 'Europe/Warsaw',
        'W. Europe Standard Time': 'Europe/Berlin',
        'Romance Standard Time': 'Europe/Paris',
        'GMT Standard Time': 'Europe/London',
        'FLE Standard Time': 'Europe/Kiev',
        'GTB Standard Time': 'Europe/Bucharest',
        'Turkey Standard Time': 'Europe/Istanbul',
        'China Standard Time': 'Asia/Shanghai',
        'Tokyo Standard Time': 'Asia/Tokyo',
        'Korea Standard Time': 'Asia/Seoul',
        'India Standard Time': 'Asia/Kolkata',
        'AUS Eastern Standard Time': 'Australia/Sydney',
        'E. South America Standard Time': 'America/Sao_Paulo',
        'Arab Standard Time': 'Asia/Riyadh',
        'Arabian Standard Time': 'Asia/Dubai',
        'Israel Standard Time': 'Asia/Jerusalem',
        'Singapore Standard Time': 'Asia/Singapore',
        'Taipei Standard Time': 'Asia/Taipei',
        'SE Asia Standard Time': 'Asia/Bangkok',
        'Belarus Standard Time': 'Europe/Minsk',
        'Georgian Standard Time': 'Asia/Tbilisi',
        'Azerbaijan Standard Time': 'Asia/Baku',
        'Caucasus Standard Time': 'Asia/Yerevan',
        'Iran Standard Time': 'Asia/Tehran',
        'Pakistan Standard Time': 'Asia/Karachi',
        'Central Asia Standard Time': 'Asia/Almaty',
        'Bangladesh Standard Time': 'Asia/Dhaka',
        'Nepal Standard Time': 'Asia/Kathmandu',
        'N. Central Asia Standard Time': 'Asia/Novosibirsk',
        'North Asia Standard Time': 'Asia/Krasnoyarsk',
        'North Asia East Standard Time': 'Asia/Irkutsk',
        'Vladivostok Standard Time': 'Asia/Vladivostok',
        'Yakutsk Standard Time': 'Asia/Yakutsk',
        'Ekaterinburg Standard Time': 'Asia/Yekaterinburg',
        'Russian Standard Time': 'Europe/Moscow',
        'Kaliningrad Standard Time': 'Europe/Kaliningrad',
    }

    # Маппинг UTC-офсета (часы) → IANA timezone (фолбэк)
    _utc_offset_to_iana = {
        -10: 'Pacific/Honolulu', -9: 'America/Anchorage',
        -8: 'America/Los_Angeles', -7: 'America/Denver',
        -6: 'America/Chicago', -5: 'America/New_York',
        -4: 'America/Sao_Paulo', -3: 'America/Sao_Paulo',
        0: 'Europe/London', 1: 'Europe/Berlin', 2: 'Europe/Kiev',
        3: 'Europe/Moscow', 4: 'Asia/Dubai', 5: 'Asia/Yekaterinburg',
        6: 'Asia/Omsk', 7: 'Asia/Krasnoyarsk', 8: 'Asia/Shanghai',
        9: 'Asia/Tokyo', 10: 'Australia/Sydney', 11: 'Asia/Vladivostok',
        12: 'Asia/Kamchatka',
    }

    # Маппинг timezone-префиксов на коды стран и региональные пулы
    _tz_to_countries = {
        'Europe/Moscow': ['ru'], 'Europe/Kaliningrad': ['ru'], 'Europe/Samara': ['ru'],
        'Europe/Volgograd': ['ru'], 'Asia/Yekaterinburg': ['ru'], 'Asia/Omsk': ['ru'],
        'Asia/Novosibirsk': ['ru'], 'Asia/Krasnoyarsk': ['ru'], 'Asia/Irkutsk': ['ru'],
        'Asia/Yakutsk': ['ru'], 'Asia/Vladivostok': ['ru'], 'Asia/Kamchatka': ['ru'],
        'Europe/Kiev': ['ua'], 'Europe/Kyiv': ['ua'],
        'Europe/Minsk': ['by'],
        'Asia/Almaty': ['kz'], 'Asia/Aqtau': ['kz'], 'Asia/Aqtobe': ['kz'],
        'Asia/Tashkent': ['uz'], 'Asia/Samarkand': ['uz'],
        'Asia/Tbilisi': ['ge'],
        'Asia/Baku': ['az'],
        'Asia/Yerevan': ['am'],
        'Europe/Berlin': ['de'], 'Europe/Vienna': ['at'], 'Europe/Zurich': ['ch'],
        'Europe/Paris': ['fr'], 'Europe/London': ['uk'],
        'Europe/Rome': ['it'], 'Europe/Madrid': ['es'],
        'Europe/Amsterdam': ['nl'], 'Europe/Brussels': ['be'],
        'Europe/Warsaw': ['pl'], 'Europe/Prague': ['cz'],
        'Europe/Budapest': ['hu'], 'Europe/Bucharest': ['ro'],
        'Europe/Sofia': ['bg'], 'Europe/Helsinki': ['fi'],
        'Europe/Stockholm': ['se'], 'Europe/Oslo': ['no'],
        'Europe/Copenhagen': ['dk'], 'Europe/Lisbon': ['pt'],
        'Europe/Athens': ['gr'], 'Europe/Istanbul': ['tr'],
        'Europe/Belgrade': ['rs'], 'Europe/Zagreb': ['hr'],
        'Europe/Ljubljana': ['si'], 'Europe/Bratislava': ['sk'],
        'Europe/Vilnius': ['lt'], 'Europe/Riga': ['lv'],
        'Europe/Tallinn': ['ee'], 'Europe/Chisinau': ['md'],
        'Europe/Dublin': ['ie'], 'Europe/Reykjavik': ['is'],
        'Europe/Luxembourg': ['lu'],
        'America/New_York': ['us'], 'America/Chicago': ['us'],
        'America/Denver': ['us'], 'America/Los_Angeles': ['us'],
        'America/Toronto': ['ca'], 'America/Vancouver': ['ca'],
        'America/Sao_Paulo': ['br'], 'America/Argentina/Buenos_Aires': ['ar'],
        'Australia/Sydney': ['au'], 'Australia/Melbourne': ['au'],
        'Asia/Tokyo': ['jp'], 'Asia/Seoul': ['kr'],
        'Asia/Shanghai': ['cn'], 'Asia/Hong_Kong': ['hk'],
        'Asia/Taipei': ['tw'], 'Asia/Singapore': ['sg'],
        'Asia/Bangkok': ['th'], 'Asia/Jakarta': ['id'],
        'Asia/Kolkata': ['in'], 'Asia/Karachi': ['pk'],
        'Asia/Dubai': ['ae'], 'Asia/Riyadh': ['sa'],
        'Asia/Tehran': ['ir'], 'Asia/Jerusalem': ['il'],
        'Asia/Dhaka': ['bd'], 'Asia/Colombo': ['lk'],
        'Asia/Kuala_Lumpur': ['my'], 'Asia/Manila': ['ph'],
        'Asia/Phnom_Penh': ['kh'], 'Asia/Ulaanbaatar': ['mn'],
        'Asia/Kathmandu': ['np'], 'Asia/Bishkek': ['kg'],
        'Asia/Dushanbe': ['tj'], 'Asia/Ho_Chi_Minh': ['vn'],
        'Asia/Bahrain': ['bh'], 'Asia/Qatar': ['qa'],
    }

    # Маппинг континентов из timezone на региональные пулы
    _tz_region_pools = {
        'Europe': ['0.europe.pool.ntp.org', '1.europe.pool.ntp.org',
                    '2.europe.pool.ntp.org', '3.europe.pool.ntp.org'],
        'America': ['0.north-america.pool.ntp.org', '1.north-america.pool.ntp.org',
                     '2.north-america.pool.ntp.org', '3.north-america.pool.ntp.org',
                     '0.south-america.pool.ntp.org', '1.south-america.pool.ntp.org',
                     '2.south-america.pool.ntp.org', '3.south-america.pool.ntp.org'],
        'Asia': ['0.asia.pool.ntp.org', '1.asia.pool.ntp.org',
                  '2.asia.pool.ntp.org', '3.asia.pool.ntp.org'],
        'Australia': ['0.oceania.pool.ntp.org', '1.oceania.pool.ntp.org',
                       '2.oceania.pool.ntp.org', '3.oceania.pool.ntp.org'],
        'Pacific': ['0.oceania.pool.ntp.org', '1.oceania.pool.ntp.org',
                      '2.oceania.pool.ntp.org', '3.oceania.pool.ntp.org'],
        'Africa': ['0.africa.pool.ntp.org', '1.africa.pool.ntp.org',
                    '2.africa.pool.ntp.org', '3.africa.pool.ntp.org'],
    }

    def _detect_user_region(self) -> Tuple[List[str], List[str]]:
        """
        Определяет локацию пользователя по системному timezone.
        Возвращает (priority_servers, region_name_parts) — серверы для приоритетной проверки.
        """
        try:
            tz_name = time.tzname[0] if time.tzname else ''
            # Пытаемся получить IANA timezone
            try:
                tz_key = str(datetime.datetime.now().astimezone().tzinfo)
            except Exception:
                tz_key = ''

            # Пробуем через datetime
            if not tz_key or tz_key in ('UTC', 'GMT'):
                try:
                    tz_key = str(datetime.datetime.now(datetime.timezone.utc).astimezone().tzinfo)
                except Exception:
                    pass

            # Определяем timezone через /etc/timezone или /etc/localtime (Linux/macOS)
            if not tz_key or '/' not in tz_key:
                try:
                    with open('/etc/timezone', 'r') as f:
                        tz_key = f.read().strip()
                except Exception:
                    try:
                        link = os.readlink('/etc/localtime')
                        # /usr/share/zoneinfo/Europe/Moscow -> Europe/Moscow
                        if 'zoneinfo/' in link:
                            tz_key = link.split('zoneinfo/')[-1]
                    except Exception:
                        pass

            # Если tz_key — не IANA (нет '/'), пробуем конвертировать Windows-имя → IANA
            if tz_key and '/' not in tz_key:
                # Проверяем точное совпадение Windows-имени
                iana = self._win_tz_to_iana.get(tz_key)
                if not iana:
                    # Проверяем tz_name (time.tzname[0]), например "RTZ 2 (зима)" → "RTZ 2"
                    for win_name, iana_name in self._win_tz_to_iana.items():
                        if tz_name.startswith(win_name) or tz_key.startswith(win_name):
                            iana = iana_name
                            break
                if not iana:
                    # Фолбэк: определяем IANA по UTC-офсету
                    try:
                        utc_offset_sec = datetime.datetime.now(datetime.timezone.utc).astimezone().utcoffset().total_seconds()
                        utc_offset_hours = int(utc_offset_sec / 3600)
                        iana = self._utc_offset_to_iana.get(utc_offset_hours)
                    except Exception:
                        pass
                if iana:
                    tz_key = iana

            # Без IANA-имени ни страну, ни континент не определить: раньше
            # это печаталось как пустой регион с пустым списком приоритетов
            if not tz_key or '/' not in tz_key:
                return [], []

            priority = []

            # 1. Точное совпадение timezone -> страна
            for tz, codes in self._tz_to_countries.items():
                if tz_key == tz or tz_key.endswith(tz):
                    for code in codes:
                        if code in self.ntp_servers:
                            priority.append(self.ntp_servers[code])
                    break

            # 2. Региональные пулы по континенту из timezone
            continent = tz_key.split('/')[0]
            region_pools = self._tz_region_pools.get(continent, [])
            for pool in region_pools:
                if pool not in priority:
                    priority.append(pool)

            # 3. Соседние страны того же континента
            for tz, codes in self._tz_to_countries.items():
                if tz.startswith(continent + '/'):
                    for code in codes:
                        srv = self.ntp_servers.get(code, '')
                        if srv and srv not in priority:
                            priority.append(srv)

            return priority, [tz_key, continent]

        except Exception:
            return [], []

    def auto_setup_ntp(self) -> None:
        """Полная автоматизация: сканирование → подключение → выбор лучшего NTP → установка"""
        # Шаг 1: Сканирование сети
        port = self.prompt_adb_port()
        if port is None:
            return
        print(Fore.CYAN + locales.get("auto_scanning_network"))
        found = self.scan_network_for_android_devices(port)

        if not found:
            print(Fore.RED + locales.get("auto_no_devices"))
            return

        # Шаг 2: Выбор устройства
        if len(found) == 1:
            target_ip = found[0]
            print(Fore.GREEN + locales.get("auto_found_device", count=1, ip=target_ip))
        else:
            print(Fore.GREEN + locales.get("scan_found", count=len(found)))
            for i, ip in enumerate(found, 1):
                print(Fore.WHITE + f"  {i}. {ip}")
            raw = input(Fore.GREEN + locales.get("auto_select_device") + Fore.WHITE).strip()
            try:
                idx = int(raw)
                if 1 <= idx <= len(found):
                    target_ip = found[idx - 1]
                else:
                    print(Fore.RED + locales.get("invalid_input"))
                    return
            except ValueError:
                print(Fore.RED + locales.get("invalid_input"))
                return

        # Шаг 3: Подключение к устройству
        print(Fore.CYAN + locales.get("auto_confirm_tv"))
        try:
            self.connect_or_reuse(target_ip)
            self.save_last_ip(target_ip)
        except AndroidTVTimeFixerError as e:
            print(Fore.RED + locales.get("error_message", error=str(e)))
            return

        # Шаг 4: Определение локации и проверка NTP-серверов
        priority_servers, region_info = self._detect_user_region()
        if region_info:
            print(Fore.GREEN + locales.get("auto_region_detected",
                                           timezone=region_info[0], region=region_info[1]))
            print(Fore.CYAN + locales.get("auto_priority_count", count=len(priority_servers)))

        print(Fore.CYAN + locales.get("auto_checking_ntp"))
        # Региональные серверы ставим в начало очереди, чтобы они реально
        # проверялись первыми, как обещает сообщение auto_priority_count
        all_servers = list(dict.fromkeys(
            priority_servers +
            list(self.ntp_servers.values()) + self.custom_ntp_servers
        ))

        results: List[dict] = []
        total = len(all_servers)

        with ThreadPoolExecutor(max_workers=50) as executor:
            futures = {executor.submit(self._test_ntp_server, s, 2, 2): s for s in all_servers}
            checked = 0
            for future in as_completed(futures):
                result = future.result()
                checked += 1
                # Фильтруем: только доступные с адекватным offset (<60 сек)
                if result['status'] == 'Reachable' and abs(result['offset']) <= 60:
                    results.append(result)
                if checked % 10 == 0 or checked == total:
                    print(
                        Fore.CYAN + "\r" +
                        locales.get("auto_checking_progress",
                                    checked=checked, total=total, found=len(results)),
                        end="", flush=True
                    )
        print()  # новая строка

        if not results:
            print(Fore.RED + locales.get("auto_no_reachable_servers"))
            return

        # Сортировка: success_rate (убыв.) → avg_rtt (возр.)
        # Региональные серверы получают лёгкий бонус: -10% к RTT при равном success_rate
        priority_set = set(priority_servers)
        results.sort(key=lambda x: (
            -x['success_rate'],
            x['avg_rtt'] * (0.9 if x['server'] in priority_set else 1.0)
        ))

        # Шаг 5: Показать топ-5
        print(Fore.GREEN + locales.get("auto_top_servers"))
        top5 = results[:5]
        for i, r in enumerate(top5, 1):
            marker = " <<< " if i == 1 else ""
            color = Fore.GREEN if r['success_rate'] > 66 else Fore.YELLOW
            print(
                color +
                f"  {i}. {r['server']:<40} "
                f"RTT: {r['avg_rtt']:.1f}ms  "
                f"{locales.get('auto_server_success')}: {r['success_rate']:.0f}%  "
                f"Offset: {r['offset']:.3f}s{marker}"
            )

        best = top5[0]
        print(Fore.GREEN + locales.get("auto_best_server", server=best['server'], rtt=best['avg_rtt']))

        # Шаг 6: Выбор из топа или подтверждение рекомендации
        raw = input(Fore.GREEN + locales.get("auto_choose_from_top") + Fore.WHITE).strip()
        best_server = best['server']
        if raw:
            try:
                idx = int(raw)
                if 1 <= idx <= len(top5):
                    best_server = top5[idx - 1]['server']
            except ValueError:
                pass

        # Шаг 7: Подтверждение и установка
        confirm = input(
            Fore.GREEN + locales.get("auto_confirm_install", server=best_server) + Fore.WHITE
        ).strip()
        if confirm.lower() in ('y', 'yes', 'д', 'да', ''):
            try:
                self.set_ntp_server(best_server)
                print(Fore.GREEN + locales.get("auto_installed", server=best_server))
                self.show_device_time()
            except AndroidTVTimeFixerError as e:
                print(Fore.RED + locales.get("error_message", error=str(e)))
        else:
            print(Fore.YELLOW + locales.get("auto_cancelled"))

    def show_country_codes(self) -> None:
        is_ru = locales.current_language == Language.RU
        print(Fore.YELLOW + locales.get("available_country_codes_full"))
        for code, server in self.ntp_servers.items():
            names = self.country_names.get(code, ('', ''))
            name = names[1] if is_ru else names[0]
            print(Fore.WHITE + f"  {code.upper()}: {name:<28} -> {server}")

    def show_country_hints(self, partial: str) -> None:
        """Показывает подходящие коды стран по частичному вводу"""
        is_ru = locales.current_language == Language.RU
        partial = partial.strip().lower()
        matches = []
        for code, names in self.country_names.items():
            name = names[1] if is_ru else names[0]
            if (code.startswith(partial) or
                    name.lower().startswith(partial) or
                    (partial and partial in name.lower())):
                matches.append((code, name, self.ntp_servers.get(code, '')))
        if matches:
            print(Fore.YELLOW + locales.get("hint_matching"))
            for code, name, server in sorted(matches)[:10]:
                print(Fore.WHITE + f"  {code}: {name:<28} -> {server}")
        else:
            print(Fore.RED + locales.get("hint_no_match"))

    def show_custom_ntp_servers(self) -> None:
        print(Fore.YELLOW + locales.get("available_alternative_ntp_servers"))
        for server in self.custom_ntp_servers:
            print(Fore.WHITE + locales.get("custom_ntp_server", server=server))

    def set_custom_ntp(self) -> None:
        print(Fore.CYAN + locales.get("ntp_format_hint"))
        while True:
            ntp_server = input(Fore.GREEN + locales.get("enter_ntp_server") + Fore.WHITE).strip()
            self.logger.info(f"User entered custom NTP server: {ntp_server}")
            if ntp_server.lower() == 'q':
                self.logger.info("User cancelled custom NTP input")
                return
            # Проверяем валидность формата NTP сервера
            if not self.validate_ntp_server(ntp_server):
                self.logger.warning(f"Invalid NTP server format: {ntp_server}")
                print(Fore.RED + locales.get("invalid_ntp_server_format"))
                continue
            try:
                if self.fix_time(ntp_server):
                    self.logger.info(f"Custom NTP server set successfully: {ntp_server}")
                    print(Fore.GREEN + locales.get("ntp_server_set", ntp_server=ntp_server))
                else:
                    self.logger.info(f"User declined NTP server: {ntp_server}")
                return
            except AndroidTVTimeFixerError as e:
                self.logger.error(f"Failed to set custom NTP server: {e}")
                print(locales.get("error_message", error=str(e)))

    def _get_all_props(self) -> dict:
        """Читает все системные свойства одним вызовом getprop"""
        raw = self.device.shell('getprop')
        props = {}
        # Разбираем построчно и жадно до последней ']': значения свойств
        # (например ro.build.description) сами могут содержать ']'
        for line in raw.splitlines():
            match = re.match(r'^\[([^\]]+)\]:\s*\[(.*)\]$', line.strip())
            if match:
                props[match.group(1)] = match.group(2)
        return props

    def get_device_info(self) -> dict:
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        try:
            ip_address, mac_address = self._get_device_network_info()

            # Один вызов getprop вместо отдельного round-trip на каждое свойство
            props = self._get_all_props()

            battery_raw = self.device.shell('dumpsys battery').splitlines()
            battery_level = next((l.strip() for l in battery_raw if 'level' in l), '')
            battery_status = next((l.strip() for l in battery_raw if 'status' in l), '')

            meminfo = self.device.shell('cat /proc/meminfo').splitlines()
            total_ram = next((l.strip() for l in meminfo if l.startswith('MemTotal')), '')
            available_ram = next((l.strip() for l in meminfo if l.startswith('MemAvailable')), '')

            device_info = {
                'model': props.get('ro.product.model', ''),
                'brand': props.get('ro.product.brand', ''),
                'name': props.get('ro.product.name', ''),
                'android_version': props.get('ro.build.version.release', ''),
                'api_level': props.get('ro.build.version.sdk', ''),
                'serial': props.get('ro.serialno', ''),
                'boot_serial': props.get('ro.boot.serialno', ''),
                'cpu_arch': props.get('ro.product.cpu.abi', ''),
                'hardware': props.get('ro.hardware', ''),
                'ip_address': ip_address,
                'mac_address': mac_address,
                # Дополнительные сетевые параметры
                'network_type': props.get('gsm.network.type', ''),
                'cellular_operator': props.get('gsm.operator.alpha', ''),
                # Информация о подключениях
                'battery_level': battery_level,
                'battery_status': battery_status,
                'manufacturer': props.get('ro.product.manufacturer', ''),
                'device': props.get('ro.product.device', ''),
                'build_id': props.get('ro.build.id', ''),
                'build_fingerprint': props.get('ro.build.fingerprint', ''),
                'uptime': self.device.shell('cat /proc/uptime').strip(),
                'total_ram': total_ram,
                'available_ram': available_ram,
                'screen_resolution': self.device.shell('wm size').strip(),
                'screen_density': self.device.shell('wm density').strip(),
                'timezone': props.get('persist.sys.timezone', ''),
                'locale': props.get('persist.sys.locale', ''),
                'cpu_cores': self.device.shell('cat /proc/cpuinfo | grep "^processor" | wc -l').strip(),
                'bootloader_version': props.get('ro.bootloader', ''),
                'baseband_version': props.get('gsm.version.baseband', ''),
                'kernel_version': self.device.shell('uname -r').strip(),
                'secure_boot_status': props.get('ro.boot.secureboot', '')
            }
            return device_info
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))

    def _get_device_network_info(self) -> Tuple[str, str]:
        """Получает сетевую информацию без жесткой привязки к wlan0."""
        iface = ""
        try:
            default_route = self.device.shell('ip route show default 2>/dev/null | head -n 1').strip()
            match = re.search(r'\bdev\s+([A-Za-z0-9_.:-]+)', default_route)
            if match:
                iface = match.group(1)
        except Exception:
            pass

        if not iface:
            try:
                addr_output = self.device.shell('ip -o addr show scope global 2>/dev/null').strip()
                match = re.search(r'^\d+:\s+([A-Za-z0-9_.:-]+)\s+', addr_output)
                if match:
                    iface = match.group(1)
            except Exception:
                pass

        if iface and re.match(r'^[A-Za-z0-9_.:-]+$', iface):
            ip_address = self.device.shell(f'ip addr show {shlex.quote(iface)}').strip()
            mac_address = self.device.shell(
                f'cat /sys/class/net/{shlex.quote(iface)}/address 2>/dev/null'
            ).strip()
            return ip_address, mac_address

        ip_address = self.device.shell('ip addr show').strip()
        return ip_address, ''
            
    def show_current_settings(self) -> None:
        """Показывает только текущий сервер NTP"""
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        try:
            current_ntp = self.get_current_ntp()
            print(Fore.GREEN + locales.get("current_ntp_server"), end="")
            print(Fore.RED + f"{current_ntp}")
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("ntp_server_info_error", error=str(e)))

    def show_device_info(self) -> None:
        """Показывает полную информацию об устройстве, включая NTP-сервер"""
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        try:
            self.logger.info("Retrieving device information")
            current_ntp = self.get_current_ntp()
            device_info = self.get_device_info()
            self.logger.info(f"Device model: {device_info.get('model', 'Unknown')}")
            self.logger.info(f"Current NTP server: {current_ntp}")
            print(Fore.GREEN + locales.get("current_device_info"))
            print(Fore.GREEN + locales.get("current_ntp_server") + " ", end="")
            print(Fore.RED + "{}".format(current_ntp))
            print(Fore.YELLOW + locales.get("device_info"))
            for key, value in device_info.items():
                print(f"  {key.replace('_', ' ').capitalize()}: {value}")
        except Exception as e:
            self.logger.error(f"Failed to retrieve device info: {e}")
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))

def main():
    try:
        fixer = AndroidTVTimeFixer()
    except Exception as e:
        # Конструктор падает, например, когда не найден adb: без этого
        # обработчика окно закрывалось с голым traceback
        logger.error(f"Startup failed: {e}", exc_info=True)
        print(Fore.RED + f"Startup failed: {e}")
        if sys.platform == 'win32':
            input("Press Enter to exit...")
        sys.exit(1)

    fixer.logger.info("=" * 50)
    fixer.logger.info("Application started")

    # Попытка загрузить сохранённый язык
    saved_language = fixer.load_language()

    if saved_language in ('en', 'ru'):
        # Автоматически устанавливаем сохранённый язык
        set_language(saved_language)
        fixer.logger.info(f"Language loaded from settings: {saved_language.upper()}")
        if saved_language == 'ru':
            print(locales.get("language_loaded_ru"))
        else:
            print(locales.get("language_loaded_en"))
    else:
        # Запрашиваем выбор языка у пользователя
        print(locales.get("select_language"))  # Выводим сообщение для выбора языка
        print("1. " + locales.get("english"))  # Выбор для английского
        print("2. " + locales.get("russian"))  # Выбор для русского
        # Ввод пользователя
        lang_choice = input(locales.get("enter_number")).strip()
        # Назначение языка на основе выбора
        if lang_choice == "2":
            set_language("ru")
            fixer.save_language("ru")
            fixer.logger.info("User selected language: Russian")
            print(locales.get("language_set_ru"))  # Подтверждение выбора
        else:
            set_language("en")
            fixer.save_language("en")
            fixer.logger.info("User selected language: English")
            print(locales.get("language_set_en"))  # Подтверждение выбора

    try:
        # Показываем дисклеймер
        print(Fore.RED + locales.get("disclaimer"))

        # Показываем начальные инструкции
        print(Fore.GREEN + locales.get("program_title"))
        print(Fore.WHITE + locales.get("please_ensure"))
        print(Fore.YELLOW + locales.get("adb_setup"))
        print(Fore.YELLOW + locales.get("adb_steps"))
        print(Fore.YELLOW + locales.get("adb_network"))
        print(Fore.YELLOW + locales.get("auto_time_date"))
        print(Fore.YELLOW + locales.get("network_requirement"))
        print(Fore.YELLOW + locales.get("reboot_device"))
        print(Fore.YELLOW + locales.get("firewall_notice"))
        input(Fore.WHITE + locales.get("press_enter_to_continue"))

        # Генерируем ключи ADB
        fixer.gen_keys()

        while True:
            print(Fore.GREEN + locales.get("main_menu"))
            print(Fore.YELLOW + locales.get("menu_item_1"))
            print(Fore.YELLOW + locales.get("menu_item_2"))
            print(Fore.YELLOW + locales.get("menu_item_3"))
            print(Fore.YELLOW + locales.get("menu_item_4"))
            print(Fore.YELLOW + locales.get("menu_item_5"))
            print(Fore.YELLOW + locales.get("menu_item_6"))
            print(Fore.YELLOW + locales.get("menu_item_7"))
            print(Fore.YELLOW + locales.get("menu_item_8"))
            print(Fore.YELLOW + locales.get("menu_item_9"))
            print(Fore.YELLOW + locales.get("menu_item_10"))
            print(Fore.YELLOW + locales.get("menu_item_wireless"))
            print(Fore.YELLOW + locales.get("menu_item_11"))

            choice = input(Fore.GREEN + locales.get("menu_prompt")).strip()
            fixer.logger.info(f"User selected menu option: {choice}")

            if choice == '1':
                fixer.logger.info("Menu: Change NTP server by country code")
                ip = fixer.get_device_ip_input()
                if not ip:          # отмена по 'q'
                    continue
                fixer.logger.info(f"User entered IP: {ip}")
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect_or_reuse(ip)
                        fixer.save_last_ip(ip)
                        fixer.logger.info(f"Successfully connected to device: {ip}")
                        fixer.show_current_settings()
                        # ── Country code input with interactive hints ──
                        print(Fore.CYAN + locales.get("country_code_format_hint"))
                        print(Fore.CYAN + locales.get("hint_type_hint"))
                        while True:
                            raw = input(Fore.GREEN + locales.get("enter_country_code") + Fore.WHITE).strip()
                            if raw.lower() == 'q':
                                break
                            # Search mode: "?<text>"
                            if raw.startswith('?'):
                                fixer.show_country_hints(raw[1:])
                                continue
                            code = raw.lower()
                            # Detect if user entered full NTP address instead of code
                            if '.' in code:
                                print(Fore.RED + locales.get('country_code_wrong_format'))
                                continue
                            if not fixer.validate_country_code(code):
                                fixer.show_country_hints(code)
                                print(Fore.RED + locales.get('invalid_country_code'))
                                continue
                            if code not in fixer.ntp_servers:
                                fixer.show_country_hints(code)
                                print(Fore.RED + locales.get('invalid_country_code'))
                                continue
                            try:
                                ntp_server = fixer.ntp_servers[code]
                                if fixer.fix_time(ntp_server):
                                    fixer.logger.info(f"NTP server changed to: {ntp_server} (country: {code.upper()})")
                                    print(Fore.GREEN + locales.get('ntp_server_set', ntp_server=ntp_server))
                                else:
                                    fixer.logger.info(f"User declined NTP server: {ntp_server}")
                            except AndroidTVTimeFixerError as e:
                                fixer.logger.error(f"Error setting NTP server: {e}")
                                print(Fore.RED + locales.get('error_message', error=str(e)))
                            break
                    except AndroidTVTimeFixerError as e:
                        fixer.logger.debug(f"Connection error: {e}")
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    print(Fore.RED + locales.get('invalid_ip_format', port=DEFAULT_ADB_PORT))

            elif choice == '2':
                fixer.logger.info("Menu: Change NTP server to custom")
                ip = fixer.get_device_ip_input()
                if not ip:          # отмена по 'q'
                    continue
                fixer.logger.info(f"User entered IP: {ip}")
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect_or_reuse(ip)
                        fixer.save_last_ip(ip)
                        fixer.logger.info(f"Successfully connected to device: {ip}")
                        fixer.show_current_settings()
                        fixer.set_custom_ntp()
                    except AndroidTVTimeFixerError as e:
                        fixer.logger.debug(f"Connection error: {e}")
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    print(Fore.RED + locales.get('invalid_ip_format', port=DEFAULT_ADB_PORT))

            elif choice == '3':
                fixer.logger.info("Menu: Show country codes with names")
                fixer.show_country_codes()

            elif choice == '4':
                fixer.logger.info("Menu: Show custom NTP servers")
                fixer.show_custom_ntp_servers()

            elif choice == '5':
                fixer.logger.info("Menu: Show device information")
                ip = fixer.get_device_ip_input()
                if not ip:          # отмена по 'q'
                    continue
                fixer.logger.info(f"User entered IP: {ip}")
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect_or_reuse(ip)
                        fixer.save_last_ip(ip)
                        fixer.logger.info(f"Successfully connected to device: {ip}")
                        fixer.show_device_info()
                        fixer.show_device_time()
                    except AndroidTVTimeFixerError as e:
                        fixer.logger.debug(f"Connection error: {e}")
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    print(Fore.RED + locales.get('invalid_ip_format', port=DEFAULT_ADB_PORT))

            elif choice == '6':
                fixer.logger.info("Menu: Ping NTP servers")
                fixer.ping_ntp_servers()

            elif choice == '7':
                fixer.logger.info("Menu: Server management")
                fixer.server_management_menu()

            elif choice == '8':
                fixer.logger.info("Menu: Network scan & batch operations")
                fixer.scan_batch_menu()

            elif choice == '9':
                fixer.logger.info("Menu: Auto-setup NTP (experimental)")
                fixer.auto_setup_ntp()

            elif choice == '10':
                fixer.logger.info("Menu: Terminal mode activated")
                fixer.terminal_mode()
                fixer.logger.info("Menu: Terminal mode deactivated")

            elif choice == '11':
                fixer.logger.info("Menu: Wireless debugging menu")
                fixer.wireless_menu()

            elif choice == '0':
                fixer.logger.info("User selected exit")
                print(Fore.GREEN + locales.get('exit_message'))
                fixer.logger.info("Application closed normally")
                if sys.platform == 'win32':
                    input(locales.get('windows_press_enter'))
                sys.exit(0)

            elif choice.lower() == 'b':
                fixer.logger.info("User pressed back")
                continue
            else:
                fixer.logger.warning(f"Invalid menu choice: {choice}")
                print(Fore.RED + locales.get('invalid_choice'))

    except AndroidTVTimeFixerError as e:
        fixer.logger.error(f"Application error: {e}")
        print(Fore.RED + locales.get('error_message', error=str(e)))
        if sys.platform == 'win32':
            input(locales.get('windows_press_enter'))
        sys.exit(1)
    except KeyboardInterrupt:
        fixer.logger.info("Application interrupted by user (Ctrl+C)")
        print(Fore.RED + locales.get('operation_aborted'))
        sys.exit(0)
    except Exception as e:
        fixer.logger.error(f"Unexpected error: {e}", exc_info=True)
        print(Fore.RED + locales.get('unexpected_error', error=str(e)))
        if sys.platform == 'win32':
            input(locales.get('windows_press_enter'))
        sys.exit(1)

    finally:
        fixer.logger.info("Application cleanup started")
        fixer.close()
        fixer.logger.info("Application cleanup completed")

if __name__ == '__main__':
    if '--version' in sys.argv:
        print(f"AndroidTVTimeFixer {APP_VERSION}")
    else:
        main()

import os
import sys
import re
import socket
import shlex
import time
import datetime
import ipaddress
import logging
import platform
import json
import psutil
import atexit
import signal
import subprocess
from subprocess import Popen, PIPE
from pathlib import Path
from typing import Optional, Tuple, List
from concurrent.futures import ThreadPoolExecutor, as_completed
import ntplib
import pyperclip
import colorama
from colorama import Fore, Style, init
from adb_shell.auth.keygen import keygen
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
sys.path.append(str(Path(__file__).parent))
from locales import locales, set_language
init(autoreset=True)

try:
    import wmi
except ImportError:
    wmi = None

# Настройка базового логгера (только консольный вывод на уровне модуля)
# FileHandler добавляется в AndroidTVTimeFixer._setup_logging()
logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)

class ADBProcessManager:
    def __init__(self, adb_path, device_ip=None):
        self.adb_path = adb_path
        self.device_ip = device_ip
        self.logger = logging.getLogger(__name__)
        self.setup_process_termination()

    def setup_process_termination(self):
        """
        Настройка механизмов завершения процессов ADB
        при выходе из программы или закрытии терминала
        """
        try:
            # Регистрация обработчиков завершения
            atexit.register(self.terminate_adb_processes)
            
            # Настройка обработчиков сигналов
            signal.signal(signal.SIGINT, self.signal_handler)
            signal.signal(signal.SIGTERM, self.signal_handler)
        except Exception as e:
            self.logger.error(f"Error in setup_process_termination: {e}")

    def signal_handler(self, signum, frame):
        """
        Обработчик системных сигналов для завершения процессов
        """
        try:
            self.logger.info(locales.get_en("terminal_mode_exit_ctrl_c"))
            print("\n" + Fore.YELLOW + locales.get("terminal_mode_exit_ctrl_c"))
            
            # Сначала отключаем устройство
            self.disconnect_device()
            
            # Затем завершаем процессы
            self.terminate_adb_processes()
            
            sys.exit(0)
        except Exception as e:
            self.logger.error(f"Error in signal handler: {e}")
            sys.exit(1)

    def disconnect_device(self):
        """
        Отключение устройства через ADB перед завершением процессов
        """
        if not self.device_ip:
            return

        try:
            # Добавляем порт 5555 по умолчанию, если он не указан
            if ':' not in self.device_ip:
                device_address = f"{self.device_ip}:5555"
            else:
                device_address = self.device_ip

            self.logger.info(f"Executing 'adb disconnect {device_address}'")
            disconnect_process = subprocess.run(
                [self.adb_path, 'disconnect', device_address],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=5
            )
            
            if disconnect_process.returncode == 0:
                self.logger.info(f"Successfully disconnected from {device_address}")
            else:
                self.logger.warning(f"Failed to disconnect from {device_address}")
                
        except subprocess.TimeoutExpired:
            self.logger.warning("ADB disconnect timed out")
        except Exception as e:
            self.logger.error(f"Error during device disconnect: {e}")

    def terminate_adb_processes(self):
        """
        Комплексный метод завершения всех процессов ADB
        с использованием нескольких подходов
        """
        try:
            # Сначала отключаем устройство
            self.disconnect_device()

            # 1. Штатное завершение через ADB
            subprocess.run([self.adb_path, 'kill-server'], 
                           stdout=subprocess.DEVNULL, 
                           stderr=subprocess.DEVNULL, 
                           timeout=5)
            self.logger.info("ADB kill-server executed successfully")
        except subprocess.TimeoutExpired:
            self.logger.warning("ADB kill-server timed out")

        # 2. Завершение через psutil
        psutil_terminated = self._terminate_via_psutil()

        # 3. Завершение через platform-специфичные методы
        if sys.platform == 'win32':
            self._terminate_windows_processes()
        else:
            self._terminate_unix_processes()

    def _terminate_via_psutil(self):
        """
        Завершение процессов через psutil

        Returns:
            bool: Успешность завершения
        """
        terminated = False
        try:
            for proc in psutil.process_iter(['name', 'exe']):
                try:
                    if (proc.info['name'] == 'adb.exe' or 
                        (proc.info['exe'] and self.adb_path in proc.info['exe'])):
                        
                        # Мягкое завершение
                        proc.terminate()
                        
                        # Если не завершился - принудительно
                        try:
                            proc.wait(timeout=3)
                            terminated = True
                        except psutil.TimeoutExpired:
                            proc.kill()
                            terminated = True
                except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                    pass
            
            if terminated:
                self.logger.info("Processes terminated via psutil")
        except Exception as e:
            self.logger.error(f"Error terminating via psutil: {e}")
        
        return terminated

    def _terminate_windows_processes(self):
        """Расширенное завершение процессов ADB в Windows"""
        try:
            # 1. Завершение через taskkill
            subprocess.run(['taskkill', '/F', '/IM', 'adb.exe'], 
                           stdout=subprocess.DEVNULL, 
                           stderr=subprocess.DEVNULL, 
                           timeout=5)
            
            # 2. Завершение через WMI (если доступно)
            if wmi is not None:
                self._terminate_via_wmi()
            
            self.logger.info("Windows ADB processes terminated")
        except subprocess.TimeoutExpired:
            self.logger.warning("taskkill timed out")
        except Exception as e:
            self.logger.error(f"Error terminating Windows processes: {e}")

    def _terminate_via_wmi(self):
        """
        Завершение процессов ADB с использованием WMI
        Работает только в Windows
        """
        if wmi is None:
            self.logger.warning("WMI module not available")
            return

        try:
            # Создаем WMI объект
            c = wmi.WMI()
            
            # Находим процессы ADB по имени
            processes = c.Win32_Process(name='adb.exe')
            
            for process in processes:
                try:
                    # Немедленное завершение процесса
                    process.Terminate()
                    self.logger.info(f"Terminated ADB process with PID {process.ProcessId}")
                except Exception as e:
                    self.logger.error(f"Error terminating process via WMI: {e}")
            
            # Дополнительный поиск по пути
            processes_by_path = c.Win32_Process(ExecutablePath=self.adb_path)
            for process in processes_by_path:
                try:
                    process.Terminate()
                    self.logger.info(f"Terminated ADB process with PID {process.ProcessId}")
                except Exception as e:
                    self.logger.error(f"Error terminating process by path via WMI: {e}")
        
        except Exception as e:
            self.logger.error(f"WMI termination error: {e}")

    def _terminate_unix_processes(self):
        """Завершение процессов ADB в Unix-системах"""
        try:
            subprocess.run(['pkill', '-9', 'adb'], 
                           stdout=subprocess.DEVNULL, 
                           stderr=subprocess.DEVNULL, 
                           timeout=5)
            self.logger.info("Unix ADB processes terminated")
        except subprocess.TimeoutExpired:
            self.logger.warning("pkill timed out")
        except Exception as e:
            self.logger.error(f"Error terminating Unix processes: {e}")

    def cleanup(self):
        """
        Метод для явного вызова очистки,
        который можно использовать при завершении программы
        """
        try:
            # Сначала отключаем устройство
            self.disconnect_device()
            # Затем завершаем процессы
            self.terminate_adb_processes()
        except Exception as e:
            self.logger.error(f"Error during cleanup: {e}")

class AndroidTVTimeFixerError(Exception):
    """Базовый класс исключений для AndroidTVTimeFixer"""
    pass

class AndroidTVTimeFixer:
    def __init__(self):
        self.current_path = Path.cwd()
        self.keys_folder = self.current_path / 'keys'
        self._setup_logging()
        self._adb_path: Optional[str] = None
        self._adb_path = self.get_adb_path()
        self.process_manager = ADBProcessManager(self._adb_path)
        self.device = None
        self.connected_ip = None
        self.max_connection_retries = 5
        self.connection_retry_delay = 5
        self.connection_timeout = 120  # Таймаут ожидания подключения в секундах
        self.servers_file = self.current_path / 'saved_servers.json'
        self.saved_servers = self.load_saved_servers()
        self.settings_file = self.current_path / 'settings.json'
        self.last_device_ip = self.load_last_ip()
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

    def _setup_logging(self) -> None:
        """Настраивает логирование для класса с выводом в файл и консоль"""
        self.logger = logging.getLogger(__name__)
        self.logger.setLevel(logging.INFO)

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
            log_file = self.current_path / 'android_tv_fixer.log'
            file_handler = logging.FileHandler(log_file, encoding='utf-8', mode='a')
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
            # Пытаемся импортировать из hook'ов
            try:
                from hooks.win_hook import ADB_PATH
                self._adb_path = ADB_PATH
            except ImportError:
                try:
                    from hooks.linux_hook import ADB_PATH
                    self._adb_path = ADB_PATH
                except ImportError:
                    from hooks.macos_hook import ADB_PATH
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

    def _process_command_output(self, process: Popen) -> Tuple[int, str, str]:
        """
        Обрабатывает вывод команды и возвращает результат
        
        Args:
            process (Popen): Процесс для обработки
            
        Returns:
            Tuple[int, str, str]: (код возврата, stdout, stderr)
        """
        stdout_lines = []
        try:
            while True:
                output = process.stdout.readline()
                if output == '' and process.poll() is not None:
                    break
                if output:
                    clean_output = output.strip()
                    stdout_lines.append(clean_output)
                    print(Fore.GREEN + clean_output)

            return_code = process.poll()
            _, stderr = process.communicate(timeout=5)
            return return_code, '\n'.join(stdout_lines), stderr

        except TimeoutError:
            process.kill()
            raise TimeoutError("Command execution timeout exceeded")

    def _retry_adb_connection(self, command: str, max_retries: int = 5, delay: int = 2) -> bool:
        """
        Пытается переподключиться к устройству несколько раз, выполняя 'adb kill-server' и 'adb disconnect'
        только на 3-й, 4-й и 5-й попытке. Использует порт 5555 по умолчанию.
    
        Args:
            command (str): Выполняемая команда.
            max_retries (int): Максимальное количество попыток (по умолчанию 5).
            delay (int): Задержка между попытками в секундах (по умолчанию 2).
    
        Returns:
            bool: True, если подключение успешно, False в противном случае.
        """
        # Определяем кодировку текущей системы
        encoding = 'utf-8' if sys.platform != 'win32' else 'cp866'
    
        # Извлекаем IP-адрес из команды
        ip_match = re.search(r'(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::(\d+))?', command)
        if ip_match:
            ip = ip_match.group(1)
            # Используем порт 5555 по умолчанию, если порт не указан
            port = ip_match.group(2) if ip_match.group(2) else '5555'
            device_ip = f"{ip}:{port}"
        else:
            device_ip = None
    
        for attempt in range(max_retries):
            try:
                # Выполнение команд adb kill-server и adb disconnect только на 3-й, 4-й и 5-й попытке
                if attempt >= 2:
                    self.logger.info(f"Попытка {attempt + 1}: Execute 'adb kill-server' to restart the ADB server.")
                    
                    # Выполняем adb kill-server
                    kill_server_command = [self.get_adb_path(), 'kill-server']
                    kill_server_process = Popen(
                        kill_server_command,
                        stdout=PIPE,
                        stderr=PIPE,
                        universal_newlines=True,
                        encoding=encoding,
                        bufsize=1
                    )
                    _, kill_server_stderr = kill_server_process.communicate()
    
                    if kill_server_process.returncode != 0:
                        self.logger.warning(f"Error while executing 'adb kill-server': {kill_server_stderr.strip()}")
                    else:
                        self.logger.info("'adb kill-server' completed successfully.")
    
                    # Выполняем adb disconnect для конкретного IP, если он есть
                    if device_ip:
                        self.logger.info(f"Попытка {attempt + 1}: Execute 'adb disconnect {device_ip}'")
                        disconnect_command = [self.get_adb_path(), 'disconnect', device_ip]
                        disconnect_process = Popen(
                            disconnect_command,
                            stdout=PIPE,
                            stderr=PIPE,
                            universal_newlines=True,
                            encoding=encoding,
                            bufsize=1
                        )
                        _, disconnect_stderr = disconnect_process.communicate()
    
                        if disconnect_process.returncode != 0:
                            self.logger.warning(f"Error while executing 'adb disconnect': {disconnect_stderr.strip()}")
                        else:
                            self.logger.info("'adb disconnect' completed successfully.")
    
                # Выполнение основной команды подключения
                args = shlex.split(command)
                if not args:
                    return False
    
                if args[0] == 'adb':
                    args[0] = self.get_adb_path()
    
                process = Popen(
                    args,
                    stdout=PIPE,
                    stderr=PIPE,
                    universal_newlines=True,
                    encoding=encoding,
                    bufsize=1
                )
    
                return_code, stdout, stderr = self._process_command_output(process)
    
                # Проверяем наличие ошибок подключения
                connection_errors = [
                    "error: no devices/emulators found",
                    "error: device not found",
                    "error: device offline",
                    "error: device unauthorized",
                    "cannot connect"
                ]
    
                if return_code == 0:
                    return True
    
                if any(error in stderr.lower() for error in connection_errors):
                    if attempt < max_retries - 1:
                        self.logger.warning(f"Connection attempt {attempt + 1} failed. Retrying in {delay} sec...")
                        print(f"\033[33mConnection attempt {attempt + 1} failed. Retrying in {delay} sec...\033[0m")
                        time.sleep(delay)
                        continue
                    else:
                        self.logger.error("All connection attempts failed.")
                        print(f"\033[31mAll connection attempts failed.\033[0m")
                        return False
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
            # Пробуем выполнить команду с автоматическими попытками переподключения
            if 'adb' in command:
                connection_success = self._retry_adb_connection(command)
                if not connection_success:
                    return
    
            else:
                args = shlex.split(command)
                if not args:
                    return
    
                self.logger.debug(f"The command is being executed: {' '.join(args)}")
                
                process = Popen(
                    args,
                    stdout=PIPE,
                    stderr=PIPE,
                    universal_newlines=True,
                    encoding='utf-8' if sys.platform != 'win32' else 'cp866',
                    bufsize=1
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

    def terminal_mode(self) -> None:
        """Режим терминала для выполнения команд"""
        # Установка кодировки для Windows
        if sys.platform == 'win32':
            os.system('chcp 866')
    
        self.logger.info("Terminal mode started")
        print(Fore.GREEN + locales.get("terminal_mode_welcome"))
        print(Fore.YELLOW + locales.get("terminal_mode_help"))

        # Завершаем процессы ADB при входе в терминальный режим,
        # чтобы пользователь мог управлять ADB-сервером вручную
        self.process_manager.terminate_adb_processes()

        try:
            while True:
                try:
                    command = input(Fore.CYAN + "terminal> " + Fore.WHITE).strip()
                    
                    # Проверяем специальные команды
                    if command.lower() in ['exit', 'quit', 'q']:
                        self.logger.info("Exit terminal mode")
                        # Завершаем процессы ADB только при выходе
                        self.process_manager.terminate_adb_processes()
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
            # Дополнительная страховка - очистка процессов при любом выходе
            # Хотя основное завершение происходит при командах exit/quit/q
            self.process_manager.cleanup()
	
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
            # Show progress
            progress = f"[{idx}/{total_servers}]"
            print(Fore.CYAN + f"\r{progress} Checking: {server:<40}", end="", flush=True)

            server_attempts = []
            rtts = []

            for attempt_num in range(count):
                try:
                    # Create NTP client
                    ntp_client = ntplib.NTPClient()

                    # Attempt to retrieve NTP time
                    start_time = time.time()
                    ntp_response = ntp_client.request(server, version=3, timeout=timeout)
                    end_time = time.time()

                    # Calculate round trip time
                    rtt = (end_time - start_time) * 1000  # Convert to milliseconds
                    rtts.append(rtt)

                    server_attempts.append({
                        'status': 'Successful',
                        'rtt': rtt,
                        'offset': ntp_response.offset
                    })

                except ntplib.NTPException as e:
                    server_attempts.append({
                        'status': 'NTP Protocol Error',
                        'error': str(e)
                    })
                except socket.gaierror:
                    server_attempts.append({
                        'status': 'DNS Resolution Error',
                        'error': 'Could not resolve server hostname'
                    })
                except socket.timeout:
                    server_attempts.append({
                        'status': 'Timeout',
                        'error': 'Connection timed out'
                    })
                except Exception as e:
                    server_attempts.append({
                        'status': 'Unexpected Error',
                        'error': str(e)
                    })

            # Analyze server performance
            successful_attempts = [attempt for attempt in server_attempts if attempt['status'] == 'Successful']

            if successful_attempts:
                avg_rtt = sum(rtts) / len(rtts)
                min_rtt = min(rtts)
                max_rtt = max(rtts)
                success_rate = (len(successful_attempts) / count) * 100
                reachable_count += 1

                server_ping_results.append({
                    'server': server,
                    'status': 'Reachable',
                    'avg_rtt': avg_rtt,
                    'min_rtt': min_rtt,
                    'max_rtt': max_rtt,
                    'success_rate': success_rate,
                    'color': Fore.GREEN if success_rate > 66 else Fore.YELLOW
                })
                self.logger.debug(f"Server {server}: Reachable, avg RTT={avg_rtt:.2f}ms, success={success_rate:.0f}%")
            else:
                unreachable_count += 1
                # Get the error from the last attempt
                last_error = server_attempts[-1].get('error', 'Unknown') if server_attempts else 'No attempts'
                server_ping_results.append({
                    'server': server,
                    'status': 'Unreachable',
                    'avg_rtt': None,
                    'min_rtt': None,
                    'max_rtt': None,
                    'success_rate': 0,
                    'error': last_error,
                    'color': Fore.RED
                })
                self.logger.debug(f"Server {server}: Unreachable, error={last_error}")

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
	
    def load_saved_servers(self) -> dict:
        """Загружает сохраненные серверы из файла"""
        if self.servers_file.exists():
            try:
                with open(self.servers_file, 'r') as f:
                    return json.load(f)
            except Exception as e:
                self.logger.warning(locales.get_en('logger_warning', error=str(e)))
        return {'favorite_servers': [], 'custom_servers': []}

    def save_servers(self):
        """Сохраняет серверы в файл"""
        try:
            with open(self.servers_file, 'w') as f:
                json.dump(self.saved_servers, f, indent=2)
        except Exception as e:
            self.logger.warning(locales.get_en('logger_warning_2', error=str(e)))

    def load_last_ip(self) -> str:
        """Загружает последний использованный IP адрес из файла настроек"""
        if self.settings_file.exists():
            try:
                with open(self.settings_file, 'r') as f:
                    settings = json.load(f)
                    return settings.get('last_device_ip', '')
            except Exception as e:
                self.logger.warning(locales.get_en('settings_load_error', error=str(e)))
        return ''

    def save_last_ip(self, ip: str) -> None:
        """Сохраняет последний использованный IP адрес в файл настроек"""
        try:
            settings = {}
            if self.settings_file.exists():
                with open(self.settings_file, 'r') as f:
                    settings = json.load(f)
            settings['last_device_ip'] = ip
            with open(self.settings_file, 'w') as f:
                json.dump(settings, f, indent=2)
            self.last_device_ip = ip
        except Exception as e:
            self.logger.warning(locales.get_en('settings_save_error', error=str(e)))

    def load_language(self) -> str:
        """Загружает сохранённый язык из файла настроек"""
        if self.settings_file.exists():
            try:
                with open(self.settings_file, 'r') as f:
                    settings = json.load(f)
                    return settings.get('language', '')
            except Exception as e:
                self.logger.warning(locales.get_en('settings_load_error', error=str(e)))
        return ''

    def save_language(self, language: str) -> None:
        """Сохраняет выбранный язык в файл настроек"""
        try:
            settings = {}
            if self.settings_file.exists():
                with open(self.settings_file, 'r') as f:
                    settings = json.load(f)
            settings['language'] = language
            with open(self.settings_file, 'w') as f:
                json.dump(settings, f, indent=2)
        except Exception as e:
            self.logger.warning(locales.get_en('settings_save_error', error=str(e)))

    def get_device_ip_input(self) -> str:
        """Получает IP адрес устройства: сохранённый, ручной ввод или авто-сканирование сети"""
        if self.last_device_ip:
            print(Fore.GREEN + locales.get('enter_device_ip_scan',
                                           saved_ip=self.last_device_ip), end="")
        else:
            print(Fore.GREEN + locales.get('enter_device_ip_scan_no_saved'), end="")

        ip = input(Fore.WHITE).strip()

        # Enter без ввода → сохранённый IP
        if not ip and self.last_device_ip:
            return self.last_device_ip

        # 's' → авто-сканирование сети
        if ip.lower() == 's':
            found = self.scan_network_for_android_devices()
            if not found:
                return self.get_device_ip_input()  # повторный запрос
            raw = input(Fore.GREEN + locales.get("scan_select_device") + Fore.WHITE).strip()
            if not raw:
                return self.get_device_ip_input()
            try:
                idx = int(raw)
                if 1 <= idx <= len(found):
                    selected_ip = found[idx - 1]
                    self.save_last_ip(selected_ip)
                    return selected_ip
            except ValueError:
                pass
            print(Fore.RED + locales.get("invalid_input"))
            return self.get_device_ip_input()

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

    def add_to_favorites(self, server: str):
        """Добавляет сервер в избранное"""
        if server not in self.saved_servers['favorite_servers']:
            self.saved_servers['favorite_servers'].append(server)
            self.save_servers()

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
                    if current_ntp and current_ntp != 'null':
                        self.add_to_favorites(current_ntp)
                        print(Fore.GREEN + locales.get("server_added_to_favorites", server=current_ntp))
                    else:
                        print(Fore.RED + locales.get("no_device_connected"))
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
                            if self.device:
                                self.fix_time(server)
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
    def validate_ip(ip: str) -> bool:
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
            if not self.keys_folder.exists():
                self.keys_folder.mkdir(parents=True)
                priv_key = self.keys_folder / 'adbkey'
                keygen(str(priv_key))
                self.logger.info(locales.get_en('gen_keys'))
            else:
                self.logger.info(locales.get_en('existing_adb_keys'))
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

    def list_devices(self):
        """Получить список подключенных устройств через adb."""
        result = subprocess.run([self.get_adb_path(), 'devices'], capture_output=True, text=True)
        lines = result.stdout.splitlines()

        devices = [line.split()[0] for line in lines[1:] if line.strip()]

        if len(devices) == 0:
            print(Fore.RED + locales.get("no_connected_devices"))
            return None

        return devices

    def select_device(self, devices):
        """Выбор устройства из списка для подключения."""
        print(Fore.GREEN + locales.get("choose_device_to_connect"))
        for i, device in enumerate(devices, 1):
            print(Fore.YELLOW + f"{i}. {device}")

        choice = input(Fore.WHITE + locales.get("enter_device_number"))

        try:
            choice = int(choice)
            if 1 <= choice <= len(devices):
                return devices[choice - 1]
            else:
                print(Fore.RED + locales.get("invalid_device_number"))
                return None
        except ValueError:
            print(Fore.RED + locales.get("invalid_input"))
            return None

    def connect_to_device(self, device):
        """Подключение к выбранному устройству через adb."""
        print(Fore.GREEN + locales.get("connecting_to_device", device_id=device))

        subprocess.run([self.get_adb_path(), '-s', device, 'shell'], check=True)

    def show_device_info_adb(self):
        """Получение информации о текущем подключенном устройстве через adb shell."""
        result = subprocess.run([self.get_adb_path(), 'shell', 'getprop'], capture_output=True, text=True)
        print(Fore.GREEN + locales.get("current_device_info"))
        print(result.stdout)
    
    def connect_or_reuse(self, ip: str) -> None:
        """Подключается к устройству или переиспользует существующее соединение"""
        if self.device and self.connected_ip == ip:
            try:
                # Проверяем, что соединение ещё активно
                self.device.shell('echo ok')
                self.logger.info(f"Reusing existing connection to {ip}")
                print(Fore.GREEN + locales.get("connection_reused", ip=ip))
                return
            except Exception:
                # Соединение потеряно, переподключаемся
                self.device = None
                self.connected_ip = None
        self.connect(ip)

    def verify_ntp_server(self, server: str, count: int = 3, timeout: int = 3) -> bool:
        """Проверяет что NTP-сервер действительно синхронизирует время (не просто доступен)"""
        print(Fore.CYAN + locales.get("ntp_verify_before_apply"))
        ntp_client = ntplib.NTPClient()
        rtts = []
        offsets = []

        for i in range(count):
            try:
                start_time = time.time()
                response = ntp_client.request(server, version=3, timeout=timeout)
                rtt = (time.time() - start_time) * 1000
                rtts.append(rtt)
                offsets.append(response.offset)
            except Exception:
                pass

        if not rtts:
            response = input(Fore.YELLOW + locales.get("ntp_verify_failed", server=server) + Fore.WHITE).strip()
            return response.lower() in ('y', 'yes', 'д', 'да')

        success_rate = (len(rtts) / count) * 100
        avg_rtt = sum(rtts) / len(rtts)
        avg_offset = sum(offsets) / len(offsets)

        # Проверяем что offset адекватный (сервер реально синхронизирует время)
        if abs(avg_offset) > 60:
            print(Fore.RED + locales.get("ntp_verify_bad_offset", server=server, offset=avg_offset))
            response = input(Fore.YELLOW + locales.get("ntp_verify_force_apply") + Fore.WHITE).strip()
            return response.lower() in ('y', 'yes', 'д', 'да')

        print(Fore.GREEN + locales.get("ntp_verify_detailed",
                                       server=server, rtt=avg_rtt,
                                       success=success_rate, offset=avg_offset))
        return True

    def connect(self, ip: str) -> None:
        """Улучшенная версия метода подключения с ожиданием разрешения"""
        if not self.validate_ip(ip):
            raise AndroidTVTimeFixerError(locales.get("invalid_ip_format"))

        pub, priv = self.load_keys()
        signer = PythonRSASigner(pub, priv)
        
        start_time = time.time()
        connection_established = False
        last_error = None
        
        print(locales.get("waiting_for_connection", remaining_time=self.connection_timeout))
        print(locales.get("confirm_connection"))
        
        while time.time() - start_time < self.connection_timeout:
            try:
                self.device = AdbDeviceTcp(ip.strip(), 5555, default_transport_timeout_s=9.)
                self.device.connect(rsa_keys=[signer], auth_timeout_s=15)
                connection_established = True
                self.connected_ip = ip
                self.process_manager.device_ip = ip
                self.logger.info(locales.get_en('connection_success', ip=ip))
                break
            except Exception as e:
                last_error = str(e)
                remaining_time = int(self.connection_timeout - (time.time() - start_time))
                print(locales.get("waiting_for_connection", remaining_time=remaining_time), end='')
                time.sleep(1)

        print()  # Новая строка после завершения ожидания
        
        if not connection_established:
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
    
        try:
            self.device.shell(f'settings put global ntp_server {ntp_server}')
            self.logger.info(locales.get_en('ntp_server_set', ntp_server=ntp_server))
    
            # Проверяем изменение
            new_ntp = self.get_current_ntp()
            if ntp_server not in new_ntp:
                raise AndroidTVTimeFixerError(locales.get("ntp_server_confirmation_failed"))
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("ntp_server_update_failed", error=str(e)))
	
    def fix_time(self, ntp_server: str) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        if not self.verify_ntp_server(ntp_server):
            return

        self.set_ntp_server(ntp_server)

    # ──────────────────────────────────────────────────────────
    # Network scan
    # ──────────────────────────────────────────────────────────

    def _check_adb_port(self, ip: str) -> Optional[str]:
        """Проверяет, открыт ли ADB-порт 5555 на указанном IP"""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.2)
            result = sock.connect_ex((ip, 5555))
            sock.close()
            return ip if result == 0 else None
        except Exception:
            return None

    @staticmethod
    def _is_private_ip(ip_str: str) -> bool:
        """Проверяет, является ли IP-адрес локальным (192.168.x.x или 10.x.x.x)"""
        try:
            return ip_str.startswith('192.168.') or ip_str.startswith('10.')
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

        return networks

    def scan_network_for_android_devices(self) -> List[str]:
        """Сканирует локальные подсети в поисках устройств с открытым ADB-портом 5555.
        Автоматически определяет подсеть через psutil, fallback на /16."""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
        except Exception:
            print(Fore.RED + locales.get("scan_local_ip_error"))
            return []

        if not self._is_private_ip(local_ip):
            print(Fore.YELLOW + locales.get("scan_not_private", ip=local_ip))
            return []

        # Определяем реальную подсеть
        detected = self._detect_interface_network(local_ip)
        if detected:
            hosts_count = detected.num_addresses - 2 if detected.prefixlen < 31 else detected.num_addresses
            print(Fore.GREEN + locales.get("scan_net_detected", network=str(detected), hosts=hosts_count))
        else:
            fallback_net = "192.168.0.0/16" if local_ip.startswith('192.168.') else f"{local_ip}/16"
            print(Fore.YELLOW + locales.get("scan_net_fallback", network=fallback_net))

        networks = self._get_local_scan_networks(local_ip)
        if not networks:
            print(Fore.YELLOW + locales.get("scan_not_private", ip=local_ip))
            return []

        # Собираем все хосты из всех подсетей, без дублей
        hosts_set: set = set()
        for net in networks:
            for h in net.hosts():
                hosts_set.add(str(h))
        hosts = sorted(hosts_set, key=lambda ip: tuple(int(o) for o in ip.split('.')))
        total = len(hosts)

        net_names = ", ".join(str(n) for n in networks)
        print(Fore.CYAN + locales.get("scan_start", network=net_names))

        found: List[str] = []
        checked = 0

        workers = min(500, total)
        with ThreadPoolExecutor(max_workers=workers) as executor:
            futures = {executor.submit(self._check_adb_port, ip): ip for ip in hosts}
            for future in as_completed(futures):
                result = future.result()
                checked += 1
                if result:
                    found.append(result)
                if checked % 200 == 0 or checked == total:
                    print(
                        Fore.CYAN + "\r  " +
                        locales.get("scan_progress", checked=checked, total=total, found=len(found)),
                        end="", flush=True
                    )
        print()  # новая строка после прогресса

        if found:
            print(Fore.GREEN + locales.get("scan_found", count=len(found)))
            for i, ip in enumerate(found, 1):
                print(Fore.WHITE + f"  {i}. {ip}")
        else:
            print(Fore.YELLOW + locales.get("scan_none"))

        return found

    # ──────────────────────────────────────────────────────────
    # Batch NTP update
    # ──────────────────────────────────────────────────────────

    def batch_set_ntp(self, ntp_server: str, ip_list: List[str]) -> None:
        """Устанавливает NTP-сервер на нескольких устройствах одновременно"""
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
            try:
                device = AdbDeviceTcp(ip.strip(), 5555, default_transport_timeout_s=9.)
                device.connect(rsa_keys=[signer], auth_timeout_s=15)
                device.shell(f'settings put global ntp_server {ntp_server}')
                confirmed = device.shell('settings get global ntp_server').strip()
                if ntp_server in confirmed:
                    print(Fore.GREEN + locales.get("batch_success", ip=ip, server=ntp_server))
                    success += 1
                else:
                    print(Fore.YELLOW + locales.get("batch_failed", ip=ip, error="verification failed"))
                    failed += 1
            except Exception as e:
                print(Fore.RED + locales.get("batch_failed", ip=ip, error=str(e)))
                failed += 1

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
            path = str(self.current_path / 'backup.json')
        export_data = {
            'version': '1.0.0',
            'exported_at': datetime.datetime.now().isoformat(),
            'language': self.load_language(),
            'last_ip': self.load_last_ip(),
            'saved_servers': self.saved_servers,
        }
        try:
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(export_data, f, ensure_ascii=False, indent=2)
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
            if 'saved_servers' in data:
                self.saved_servers = data['saved_servers']
                self.save_servers()
            if 'language' in data and data['language'] in ('en', 'ru'):
                self.save_language(data['language'])
                set_language(data['language'])
            if 'last_ip' in data and data['last_ip']:
                self.save_last_ip(data['last_ip'])
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
                discovered = self.scan_network_for_android_devices()

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
                    if not self.validate_ip(ip):
                        print(Fore.RED + locales.get("invalid_ip_format"))
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

    def _test_ntp_server_quick(self, server: str, count: int = 3, timeout: int = 2) -> Optional[dict]:
        """Проверка NTP-сервера с несколькими попытками и валидацией offset.
        Возвращает dict с метриками или None если сервер недоступен."""
        ntp_client = ntplib.NTPClient()
        rtts = []
        offsets = []

        for _ in range(count):
            try:
                start = time.time()
                response = ntp_client.request(server, version=3, timeout=timeout)
                rtt = (time.time() - start) * 1000
                rtts.append(rtt)
                offsets.append(response.offset)
            except Exception:
                pass

        if not rtts:
            return None

        success_rate = (len(rtts) / count) * 100
        avg_rtt = sum(rtts) / len(rtts)
        avg_offset = sum(offsets) / len(offsets)

        # Отбраковываем серверы с аномально большим offset (>60 секунд)
        # — они "пингуются" но время некорректно
        if abs(avg_offset) > 60:
            return None

        return {
            'server': server,
            'avg_rtt': avg_rtt,
            'min_rtt': min(rtts),
            'max_rtt': max(rtts),
            'success_rate': success_rate,
            'offset': avg_offset
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
        'America/Sao_Paulo': ['br'], 'America/Argentina/Buenos_Aires': ['br'],
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
                import zoneinfo
                tz_key = str(datetime.datetime.now().astimezone().tzinfo)
            except Exception:
                tz_key = ''

            # Пробуем через datetime
            if not tz_key or tz_key in ('UTC', 'GMT'):
                try:
                    tz_key = str(datetime.datetime.now(datetime.timezone.utc).astimezone().tzinfo)
                except Exception:
                    pass

            # Определяем timezone через /etc/timezone или /etc/localtime
            if not tz_key or len(tz_key) < 4:
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

            if not tz_key:
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
            continent = tz_key.split('/')[0] if '/' in tz_key else ''
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
        print(Fore.CYAN + locales.get("auto_scanning_network"))
        found = self.scan_network_for_android_devices()

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
        all_servers = list(dict.fromkeys(
            list(self.ntp_servers.values()) + self.custom_ntp_servers
        ))

        results: List[dict] = []
        total = len(all_servers)

        with ThreadPoolExecutor(max_workers=50) as executor:
            futures = {executor.submit(self._test_ntp_server_quick, s): s for s in all_servers}
            checked = 0
            for future in as_completed(futures):
                result = future.result()
                checked += 1
                if result is not None:
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

        # Сортировка: приоритет локальным серверам, затем success_rate (убыв.) и avg_rtt (возр.)
        priority_set = set(priority_servers)
        results.sort(key=lambda x: (
            x['server'] not in priority_set,  # False (0) для приоритетных — они первые
            -x['success_rate'],
            x['avg_rtt']
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
        from locales import Language
        is_ru = locales.current_language == Language.RU
        print(Fore.YELLOW + locales.get("available_country_codes_full"))
        for code, server in self.ntp_servers.items():
            names = self.country_names.get(code, ('', ''))
            name = names[1] if is_ru else names[0]
            print(Fore.WHITE + f"  {code.upper()}: {name:<28} -> {server}")

    def show_country_hints(self, partial: str) -> None:
        """Показывает подходящие коды стран по частичному вводу"""
        from locales import Language
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
                self.fix_time(ntp_server)
                self.logger.info(f"Custom NTP server set successfully: {ntp_server}")
                print(Fore.GREEN + locales.get("ntp_server_set", ntp_server=ntp_server))
                return
            except AndroidTVTimeFixerError as e:
                self.logger.error(f"Failed to set custom NTP server: {e}")
                print(locales.get("error_message", error=str(e)))

    def get_device_info(self) -> dict:
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))

        try:
            device_info = {
                'model': self.device.shell('getprop ro.product.model').strip(),
                'brand': self.device.shell('getprop ro.product.brand').strip(),
                'name': self.device.shell('getprop ro.product.name').strip(),
                'android_version': self.device.shell('getprop ro.build.version.release').strip(),
                'api_level': self.device.shell('getprop ro.build.version.sdk').strip(),
                'serial': self.device.shell('getprop ro.serialno').strip(),
                'boot_serial': self.device.shell('getprop ro.boot.serialno').strip(),
                'cpu_arch': self.device.shell('getprop ro.product.cpu.abi').strip(),
                'hardware': self.device.shell('getprop ro.hardware').strip(),
                'ip_address': self.device.shell('ip addr show wlan0').strip(),
                'mac_address': self.device.shell('cat /sys/class/net/wlan0/address').strip(),
                # Дополнительные сетевые параметры
                'network_type': self.device.shell('getprop gsm.network.type').strip(),
                'cellular_operator': self.device.shell('getprop gsm.operator.alpha').strip(),
                # Информация о подключениях
                'battery_level': self.device.shell('dumpsys battery | grep level').strip(),
                'battery_status': self.device.shell('dumpsys battery | grep status').strip(),
                'manufacturer': self.device.shell('getprop ro.product.manufacturer').strip(),
                'device': self.device.shell('getprop ro.product.device').strip(),
                'build_id': self.device.shell('getprop ro.build.id').strip(),
                'build_fingerprint': self.device.shell('getprop ro.build.fingerprint').strip(),
                'uptime': self.device.shell('cat /proc/uptime').strip(),
                'total_ram': self.device.shell("cat /proc/meminfo | grep 'MemTotal'").strip(),
                'available_ram': self.device.shell("cat /proc/meminfo | grep 'MemAvailable'").strip(),
                'screen_resolution': self.device.shell('wm size').strip(),
                'screen_density': self.device.shell('wm density').strip(),
                'timezone': self.device.shell('getprop persist.sys.timezone').strip(),
                'locale': self.device.shell('getprop persist.sys.locale').strip(),
                'cpu_cores': self.device.shell('cat /proc/cpuinfo | grep "^processor" | wc -l').strip(),
                'bootloader_version': self.device.shell('getprop ro.bootloader').strip(),  # Версия загрузчика
                'baseband_version': self.device.shell('getprop gsm.version.baseband').strip(),
                'kernel_version': self.device.shell('uname -r').strip(),
                'secure_boot_status': self.device.shell('getprop ro.boot.secureboot').strip()
            }
            return device_info
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))
            
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
                print(f"  {key.capitalize()}: {value}")
        except Exception as e:
            self.logger.error(f"Failed to retrieve device info: {e}")
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))

def main():
    fixer = AndroidTVTimeFixer()
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
            print(Fore.YELLOW + locales.get("menu_item_11"))

            choice = input(Fore.GREEN + locales.get("menu_prompt")).strip()
            fixer.logger.info(f"User selected menu option: {choice}")

            if choice == '1':
                fixer.logger.info("Menu: Change NTP server by country code")
                ip = fixer.get_device_ip_input()
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
                                fixer.fix_time(ntp_server)
                                fixer.logger.info(f"NTP server changed to: {ntp_server} (country: {code.upper()})")
                                print(Fore.GREEN + locales.get('ntp_server_set', ntp_server=ntp_server))
                            except AndroidTVTimeFixerError as e:
                                fixer.logger.error(f"Error setting NTP server: {e}")
                                print(Fore.RED + locales.get('error_message', error=str(e)))
                            break
                    except AndroidTVTimeFixerError as e:
                        fixer.logger.error(f"Connection error: {e}")
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    fixer.logger.warning(f"Invalid IP format entered: {ip}")
                    print(Fore.RED + locales.get('invalid_ip_format'))

            elif choice == '2':
                fixer.logger.info("Menu: Change NTP server to custom")
                ip = fixer.get_device_ip_input()
                fixer.logger.info(f"User entered IP: {ip}")
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect_or_reuse(ip)
                        fixer.save_last_ip(ip)
                        fixer.logger.info(f"Successfully connected to device: {ip}")
                        fixer.show_current_settings()
                        fixer.set_custom_ntp()
                    except AndroidTVTimeFixerError as e:
                        fixer.logger.error(f"Connection error: {e}")
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    fixer.logger.warning(f"Invalid IP format entered: {ip}")
                    print(Fore.RED + locales.get('invalid_ip_format'))

            elif choice == '3':
                fixer.logger.info("Menu: Show country codes with names")
                fixer.show_country_codes()

            elif choice == '4':
                fixer.logger.info("Menu: Show custom NTP servers")
                fixer.show_custom_ntp_servers()

            elif choice == '5':
                fixer.logger.info("Menu: Show device information")
                ip = fixer.get_device_ip_input()
                fixer.logger.info(f"User entered IP: {ip}")
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect_or_reuse(ip)
                        fixer.save_last_ip(ip)
                        fixer.logger.info(f"Successfully connected to device: {ip}")
                        fixer.show_device_info()
                        fixer.show_device_time()
                    except AndroidTVTimeFixerError as e:
                        fixer.logger.error(f"Connection error: {e}")
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    fixer.logger.warning(f"Invalid IP format entered: {ip}")
                    print(Fore.RED + locales.get('invalid_ip_format'))

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
        # Явная очистка при завершении программы
        fixer.process_manager.cleanup()
        fixer.logger.info("Application cleanup completed")

if __name__ == '__main__':
    main()

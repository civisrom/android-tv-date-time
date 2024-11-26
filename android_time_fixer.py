import os
import sys
import re
import socket
import shlex
import time
import logging
import platform
import json
import psutil
import ctypes
import atexit
import signal
import threading
import subprocess
from subprocess import Popen, PIPE
from pathlib import Path
from typing import Optional, Tuple
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

# Настройка логирования
#logging.basicConfig(
#    level=logging.INFO,
#    format='%(asctime)s - %(levelname)s - %(message)s',
#    handlers=[logging.StreamHandler(sys.stdout)]
#)
#logger = logging.getLogger(__name__)

# Настройка логгера
logging.basicConfig(
    level=logging.INFO,  # Уровень логирования
    format='%(asctime)s - %(levelname)s - %(message)s',  # Формат сообщений
    handlers=[
        logging.StreamHandler(sys.stdout)  # Вывод в консоль
    ]
)

# Установка кодировки для обработчика
for handler in logging.getLogger().handlers:
    if isinstance(handler, logging.StreamHandler):
        handler.setStream(sys.stdout)
        handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(message)s'))
logger = logging.getLogger(__name__)

class AndroidTVTimeFixerError(Exception):
    """Базовый класс исключений для AndroidTVTimeFixer"""
    pass

class AndroidTVTimeFixer:
    def __init__(self):
        self.current_path = Path.cwd()
        self.keys_folder = self.current_path / 'keys'
        self._setup_logging()
        self.current_path = Path.cwd()
        self._adb_path = None
        self.adb_path = self.get_adb_path()
        self._kill_event = threading.Event()
        self.device = None
        self.max_connection_retries = 5
        self.connection_retry_delay = 5
        self.connection_timeout = 120  # Таймаут ожидания подключения в секундах
        self.servers_file = self.current_path / 'saved_servers.json'
        self.saved_servers = self.load_saved_servers()
        atexit.register(self.kill_adb_processes)
        signal.signal(signal.SIGTERM, self._handle_signal)
        signal.signal(signal.SIGINT, self._handle_signal)
        self._monitor_thread.start()
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
            'ua': 'ua.pool.ntp.org',
            'uk': 'uk.pool.ntp.org',
            'us': 'us.pool.ntp.org',
            'ca': 'ca.pool.ntp.org',
            'br': 'br.pool.ntp.org',
            'au': 'au.pool.ntp.org',
            'cn': 'cn.pool.ntp.org',
            'jp': 'jp.pool.ntp.org',
            'kz': 'kz.pool.ntp.org'
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
            'time.cloudflare.com',
            'clock.isc.org',
            'ntp2.vniiftri.ru',
            'ntps1-1.cs.tu-berlin.de',
            'ntp.ix.ru',
            'time.android.com'
        ]

    def _setup_logging(self) -> None:
        """Настраивает логирование для класса"""
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(levelname)s - %(message)s',
            handlers=[
                logging.FileHandler('android_tv_fixer.log', encoding='utf-8'),
                logging.StreamHandler()
            ]
        )
        self.logger = logging.getLogger(__name__)



    def _handle_signal(self, signum, frame):
        """Обработчик системных сигналов"""
        self.logger.info(f"Получен сигнал завершения: {signum}")
        self._kill_event.set()
        self.kill_adb_processes()
        sys.exit(0)

    def _adb_process_monitor(self):
        """
        Фоновый поток для мониторинга и завершения процессов ADB
        """
        while not self._kill_event.is_set():
            try:
                import psutil
                for proc in psutil.process_iter(['name', 'pid']):
                    if proc.info['name'] and 'adb.exe' in proc.info['name'].lower():
                        try:
                            self.logger.info(f"Обнаружен процесс ADB: {proc.info['pid']}")
                            proc.terminate()
                            proc.wait(timeout=1)
                        except Exception as e:
                            self.logger.warning(f"Не удалось завершить процесс {proc.info['pid']}: {e}")
            except ImportError:
                # Резервный механизм без psutil
                self._kill_adb_without_psutil()
            
            time.sleep(2)

    def kill_adb_processes(self) -> None:
        """
        Комплексный метод завершения процессов ADB с максимальной надежностью
        """
        self.logger.info("Начало процедуры завершения ADB процессов")
    
        # Список методов завершения ADB процессов
        kill_methods = [
            # 1. Штатное завершение через ADB kill-server
            self._kill_adb_server,
            
            # 2. Завершение через psutil (если установлен)
            self._kill_adb_with_psutil,
            
            # 3. Системные утилиты (taskkill для Windows, pkill для Unix)
            self._kill_adb_with_system_tools
        ]
    
        # Последовательное применение методов завершения
        for method in kill_methods:
            try:
                method()
            except Exception as e:
                self.logger.warning(f"Метод завершения не сработал: {e}")
    
        self.logger.info("Процедура завершения ADB процессов завершена")
    
    def _kill_adb_server(self):
        """Завершение ADB сервера штатным способом"""
        try:
            subprocess.run(
                [self.adb_path, 'kill-server'], 
                stdout=subprocess.PIPE, 
                stderr=subprocess.PIPE, 
                timeout=3,
                check=True
            )
            self.logger.info("ADB сервер успешно остановлен через kill-server")
        except subprocess.CalledProcessError as e:
            self.logger.error(f"Ошибка kill-server: {e.stderr.decode('utf-8', errors='ignore')}")
        except Exception as e:
            self.logger.error(f"Неожиданная ошибка при остановке ADB сервера: {e}")
    
    def _kill_adb_with_psutil(self):
        """Завершение ADB процессов через psutil"""
        try:
            import psutil
            
            # Список найденных процессов ADB
            adb_processes = []
            
            for proc in psutil.process_iter(['name', 'pid']):
                try:
                    # Проверка имени процесса с учетом регистра
                    if proc.info['name'] and 'adb' in proc.info['name'].lower():
                        adb_processes.append(proc)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            
            # Завершение найденных процессов
            for proc in adb_processes:
                try:
                    self.logger.info(f"Завершение процесса ADB: PID={proc.pid}, Name={proc.info['name']}")
                    proc.terminate()
                    
                    # Ожидание завершения с таймаутом
                    try:
                        proc.wait(timeout=2)
                    except psutil.TimeoutExpired:
                        proc.kill()  # Принудительное закрытие
                
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            
            if adb_processes:
                self.logger.info(f"Завершено процессов ADB через psutil: {len(adb_processes)}")
            
        except ImportError:
            self.logger.warning("Библиотека psutil не установлена, пропуск метода")
        except Exception as e:
            self.logger.error(f"Ошибка при использовании psutil: {e}")
    
    def _kill_adb_with_system_tools(self):
        """Завершение ADB процессов системными утилитами"""
        if sys.platform == 'win32':
            # Методы завершения для Windows
            kill_commands = [
                ['taskkill', '/F', '/IM', 'adb.exe'],
                ['taskkill', '/F', '/T', '/IM', 'adb.exe']
            ]
            
            for cmd in kill_commands:
                try:
                    subprocess.run(
                        cmd, 
                        stdout=subprocess.PIPE, 
                        stderr=subprocess.PIPE, 
                        timeout=3,
                        text=True
                    )
                    self.logger.info(f"Завершение ADB через команду: {' '.join(cmd)}")
                except subprocess.CalledProcessError as e:
                    self.logger.error(f"Ошибка выполнения {cmd}: {e.stderr}")
                except Exception as e:
                    self.logger.error(f"Неожиданная ошибка при завершении ADB: {e}")
        
        else:
            # Методы завершения для Unix-подобных систем
            kill_commands = [
                ['pkill', '-9', '-f', 'adb'],
                ['killall', '-9', 'adb']
            ]
            
            for cmd in kill_commands:
                try:
                    subprocess.run(
                        cmd, 
                        stdout=subprocess.PIPE, 
                        stderr=subprocess.PIPE, 
                        timeout=3,
                        text=True
                    )
                    self.logger.info(f"Завершение ADB через команду: {' '.join(cmd)}")
                except subprocess.CalledProcessError as e:
                    self.logger.error(f"Ошибка выполнения {cmd}: {e.stderr}")
                except Exception as e:
                    self.logger.error(f"Неожиданная ошибка при завершении ADB: {e}")

    def _kill_with_ctypes(self):
        """Низкоуровневое завершение процессов через ctypes (Windows)"""
        try:
            import ctypes
            import psutil

            kernel32 = ctypes.windll.kernel32
            for pid in psutil.pids():
                try:
                    process = psutil.Process(pid)
                    if process.name().lower() == 'adb.exe':
                        handle = kernel32.OpenProcess(1, False, pid)
                        if handle:
                            kernel32.TerminateProcess(handle, 0)
                            kernel32.CloseHandle(handle)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
        except Exception as e:
            self.logger.error(f"Ошибка при низкоуровневом завершении: {e}")

    def get_adb_path(self) -> str:
        """
        Получает путь к ADB из runtime hook или ресурсов
        
        Returns:
            str: Полный путь к исполняемому файлу ADB
            
        Raises:
            FileNotFoundError: Если файл ADB не найден
        """
        # Проверяем, если путь уже установлен
        if self._adb_path:
            return self._adb_path
    
        try:
            # Пытаемся импортировать из hook'ов
            try:
                from hooks.win_hook import ADB_PATH
                self._adb_path = ADB_PATH
            except ImportError:
                from hooks.linux_hook import ADB_PATH
                self._adb_path = ADB_PATH
        except ImportError:
            # Fallback для разработки
            if getattr(sys, 'frozen', False):  # Если программа упакована
                base_path = sys._MEIPASS
            else:  # В случае обычного запуска
                base_path = os.path.abspath(os.path.dirname(__file__))
    
            self._adb_path = os.path.join(
                base_path, 
                'resources', 
                'adb.exe' if sys.platform == 'win32' else 'adb'
            )
    
        # Проверяем, существует ли файл
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
                    if sys.platform == 'win32':
                        try:
                            # Пробуем декодировать как cp866 (кодировка командной строки Windows)
                            clean_output = clean_output.encode('cp866').decode('cp866')
                        except UnicodeEncodeError:
                            # Если не получилось, оставляем как есть
                            pass
                    stdout_lines.append(clean_output)
                    print(Fore.GREEN + clean_output)

            return_code = process.poll()
            _, stderr = process.communicate(timeout=5)
            return return_code, '\n'.join(stdout_lines), stderr

        except TimeoutError:
            process.kill()
            raise TimeoutError("Превышено время ожидания выполнения команды")

    def _retry_adb_connection(self, command: str, max_retries: int = 3, delay: int = 2) -> bool:
        """
        Пытается переподключиться к устройству несколько раз
        
        Args:
            command (str): Выполняемая команда
            max_retries (int): Максимальное количество попыток
            delay (int): Задержка между попытками в секундах
            
        Returns:
            bool: True если подключение успешно, False в противном случае
        """
        import time
        
        for attempt in range(max_retries):
            try:
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
                    encoding='utf-8' if sys.platform != 'win32' else 'cp866',
                    bufsize=1
                )
                
                return_code, stdout, stderr = self._process_command_output(process)
                
                # Проверяем наличие ошибок подключения
                connection_errors = [
                    "error: no devices/emulators found",
                    "error: device not found",
                    "error: device offline",
                    "error: device unauthorized"
                ]
                
                if return_code == 0:
                    return True
                    
                if any(error in stderr.lower() for error in connection_errors):
                    if attempt < max_retries - 1:
                        self.logger.warning(f"Попытка подключения {attempt + 1} не удалась. Повторная попытка через {delay} сек...")
                        print(Fore.YELLOW + f"Попытка подключения {attempt + 1} не удалась. Повторная попытка через {delay} сек...")
                        time.sleep(delay)
                        continue
                    else:
                        self.logger.error("Все попытки подключения не удались")
                        print(Fore.RED + "Все попытки подключения не удались")
                        return False
                else:
                    # Если ошибка не связана с подключением, прекращаем попытки
                    if stderr:
                        self.logger.error(f"STDERR: {stderr}")
                        print(Fore.RED + stderr)
                    return False
                    
            except Exception as e:
                self.logger.error(f"Ошибка при попытке подключения: {str(e)}", exc_info=True)
                if attempt < max_retries - 1:
                    time.sleep(delay)
                    continue
                return False
                
        return False
    
    def execute_terminal_command(self, command: str) -> None:
        """
        Выполняет команду в терминале и выводит результат.
    
        Args:
            command (str): Команда для выполнения
        """
        if not command:
            return
    
        try:
            # Обрабатываем специальную команду adb kill-server
            if 'adb kill-server' in command.lower():
                self.logger.info("Команда 'adb kill-server' обработана через встроенный метод.")
                self.kill_adb_processes()
                return
    
            # Пробуем выполнить команду
            args = shlex.split(command)
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
                self.logger.error(f"Ошибка выполнения команды. Код: {return_code}")
                print(Fore.RED + "Ошибка выполнения команды.")
                if stderr:
                    self.logger.error(f"STDERR: {stderr}")
                    print(Fore.RED + stderr)
    
        except FileNotFoundError as e:
            error_msg = f"Команда не найдена: {e}"
            self.logger.error(error_msg)
            print(Fore.RED + f"Ошибка: {error_msg}")
        except TimeoutError as e:
            error_msg = f"Таймаут выполнения команды: {e}"
            self.logger.error(error_msg)
            print(Fore.RED + f"Ошибка: {error_msg}")
        except Exception as e:
            error_msg = f"Ошибка выполнения команды: {str(e)}"
            self.logger.error(error_msg, exc_info=True)
            print(Fore.RED + f"Ошибка: {error_msg}")

    def terminal_mode(self) -> None:
        """Режим терминала для выполнения команд"""
        # Установка кодировки для Windows
        if sys.platform == 'win32':
            os.system('chcp 866')
    
        self.logger.info("Запущен режим терминала")
        print(Fore.GREEN + locales.get("terminal_mode_welcome"))
        print(Fore.YELLOW + locales.get("terminal_mode_help"))
        
        try:
            while True:
                command = input(Fore.CYAN + "terminal> " + Fore.WHITE).strip()
                
                # Проверяем специальные команды
                if command.lower() in ['exit', 'quit', 'q']:
                    self.logger.info("Выход из режима терминала")
                    break
                elif command.lower() in ['help', '?']:
                    print(Fore.YELLOW + locales.get("terminal_mode_commands"))
                    continue
                elif command.lower() == 'clear':
                    os.system('cls' if sys.platform == 'win32' else 'clear')
                    continue
                elif not command:
                    continue
                
                # Выполняем команду
                self.execute_terminal_command(command)
        except KeyboardInterrupt:
            self.logger.info("Прерывание работы терминала (Ctrl+C)")
            print("\n" + Fore.YELLOW + locales.get("terminal_mode_exit_ctrl_c"))
            return
        except Exception as e:
            self.logger.error(f"Ошибка в режиме терминала: {str(e)}", exc_info=True)
            print(Fore.RED + locales.get("terminal_mode_error", error=str(e)))
        finally:
            # Завершаем ADB процессы при выходе
            self.kill_adb_processes()
	
    def ping_ntp_servers(self, timeout=2, count=3):
        """
        Check NTP servers reliability using ntplib with enhanced error handling
        
        Args:
            timeout (int): Timeout for NTP server connection in seconds
            count (int): Number of attempts to connect to each server
        """
        print(Fore.GREEN + locales.get("ping_ntp_servers_start"))
        
        # Combine country NTP servers and custom NTP servers
        all_servers = list(self.ntp_servers.values()) + self.custom_ntp_servers
        
        server_ping_results = []
        
        for server in all_servers:
            server_attempts = []
            
            for _ in range(count):
                try:
                    # Create NTP client
                    ntp_client = ntplib.NTPClient()
                    
                    # Attempt to retrieve NTP time
                    start_time = time.time()
                    ntp_response = ntp_client.request(server, version=3, timeout=timeout)
                    end_time = time.time()
                    
                    # Calculate round trip time
                    rtt = (end_time - start_time) * 1000  # Convert to milliseconds
                    
                    server_attempts.append({
                        'status': 'Successful',
                        'rtt': rtt
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
                avg_rtt = sum(attempt['rtt'] for attempt in successful_attempts) / len(successful_attempts)
                success_rate = (len(successful_attempts) / count) * 100
                
                server_ping_results.append({
                    'server': server,
                    'status': 'Reachable',
                    'avg_rtt': avg_rtt,
                    'success_rate': success_rate,
                    'color': Fore.GREEN if success_rate > 66 else Fore.YELLOW
                })
            else:
                server_ping_results.append({
                    'server': server,
                    'status': 'Unreachable',
                    'avg_rtt': None,
                    'success_rate': 0,
                    'color': Fore.RED
                })
        
        # Sort results: reachable servers first, sorted by success rate and avg RTT
        server_ping_results.sort(
            key=lambda x: (x['status'] != 'Reachable', -x['success_rate'], x['avg_rtt'] or float('inf'))
        )
        
        # Display results
        print(Fore.YELLOW + f"{'Server':<25} {'Status':<15} {'Avg RTT (ms)':<15} {'Success Rate':<15}")
        print("-" * 70)
        
        for result in server_ping_results:
            rtt_display = f"{result['avg_rtt']:.2f}" if result['avg_rtt'] is not None else "N/A"
            success_rate_display = f"{result['success_rate']:.2f}%" if result['success_rate'] is not None else "N/A"
            
            print(
                result['color'] + 
                f"{result['server']:<25} {result['status']:<15} {rtt_display:<15} {success_rate_display:<15}"
            )
	
    def load_saved_servers(self) -> dict:
        """Загружает сохраненные серверы из файла"""
        if self.servers_file.exists():
            try:
                with open(self.servers_file, 'r') as f:
                    return json.load(f)
            except Exception as e:
                logger.warning(locales.get('logger_warning', error=str(e)))
        return {'favorite_servers': [], 'custom_servers': []}

    def save_servers(self):
        """Сохраняет серверы в файл"""
        try:
            with open(self.servers_file, 'w') as f:
                json.dump(self.saved_servers, f, indent=2)
        except Exception as e:
            logger.warning(locales.get('logger_warning_2', error=str(e)))

    def copy_server_to_clipboard(self, server: str) -> bool:
        """Копирует адрес сервера в буфер обмена"""
        try:
            pyperclip.copy(server)
            return True
        except Exception as e:
            logger.warning(locales.get('copy_to_clipboard', error=str(e)))
            return False

    def paste_server_from_clipboard(self) -> str:
        """Получает адрес сервера из буфера обмена"""
        try:
            return pyperclip.paste()
        except Exception as e:
            logger.warning(locales.get('copy_to_clipboard_2', error=str(e)))
            return ""

    def add_to_favorites(self, server: str):
        """Добавляет сервер в избранное"""
        if server not in self.saved_servers['favorite_servers']:
            self.saved_servers['favorite_servers'].append(server)
            self.save_servers()

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
                logger.info(locales.get('gen_keys'))
            else:
                logger.info(locales.get('existing_adb_keys'))
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

    def list_devices():
        """Получить список подключенных устройств через adb."""
        result = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
        lines = result.stdout.splitlines()
        
        devices = [line.split()[0] for line in lines[1:] if line.strip()]
        
        if len(devices) == 0:
            print(Fore.RED + locales.get("no_connected_devices"))
            return None
        
        return devices
    
    def select_device(devices):
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
    
    def connect_to_device(device):
        """Подключение к выбранному устройству через adb."""
        print(Fore.GREEN + locales.get("connecting_to_device", device_id=device))
        
        subprocess.run(['adb', '-s', device, 'shell'], check=True)
    
    def show_device_info():
        """Получение информации о текущем подключенном устройстве."""
        result = subprocess.run(['adb', 'shell', 'getprop'], capture_output=True, text=True)
        print(Fore.GREEN + locales.get("current_device_info"))
        print(result.stdout)
    
    def connect(self, ip: str) -> None:
        """Улучшенная версия метода подключения с ожиданием разрешения"""
        if not self.validate_ip(ip):
            raise AndroidTVTimeFixerError(locales.get("invalid_ip_format"))

        pub, priv = self.load_keys()
        signer = PythonRSASigner(pub, priv)
        
        start_time = time.time()
        connection_established = False
        last_error = None
        
        print(locales.get("waiting_for_connection"))
        print(locales.get("confirm_connection"))
        
        while time.time() - start_time < self.connection_timeout:
            try:
                self.device = AdbDeviceTcp(ip.strip(), 5555, default_transport_timeout_s=9.)
                self.device.connect(rsa_keys=[signer], auth_timeout_s=15)
                connection_established = True
                logger.info(locales.get('connection_success', ip=ip))
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
            logger.info(locales.get('ntp_server_set', ntp_server=ntp_server))
    
            # Проверяем изменение
            new_ntp = self.get_current_ntp()
            if ntp_server not in new_ntp:
                raise AndroidTVTimeFixerError(locales.get("ntp_server_confirmation_failed"))
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("ntp_server_update_failed", error=str(e)))
	
    def fix_time(self, ntp_server: str) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        self.set_ntp_server(ntp_server)

    def show_country_codes(self) -> None:
        print(Fore.YELLOW + locales.get("available_country_codes"))
        for code, server in self.ntp_servers.items():
            print(locales.get("country_code_server", code=code.upper(), server=server))

    def show_custom_ntp_servers(self) -> None:
        print(Fore.YELLOW + locales.get("available_alternative_ntp_servers"))
        for server in self.custom_ntp_servers:
            print(locales.get("custom_ntp_server", server=server))

    def set_custom_ntp(self) -> None:
        while True:
            ntp_server = input(Fore.GREEN + locales.get("enter_ntp_server") + Fore.WHITE).strip()
            if ntp_server.lower() == 'q':
                return
            try:
                self.fix_time(ntp_server)
                print(Fore.GREEN + locales.get("ntp_server_set", ntp_server=ntp_server))
                return
            except AndroidTVTimeFixerError as e:
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
                'serial': self.device.shell('getprop ro.boot.serialno').strip(),
                'cpu_arch': self.device.shell('getprop ro.product.cpu.abi').strip(),
                'hardware': self.device.shell('getprop ro.hardware').strip(),
                #'ip_address': self.device.shell('ip addr show wlan0 | grep "inet "').strip(),
                #'ip_address': self.device.shell("ip -f inet addr show wlan0 | awk '/inet / {print $2}' | cut -d'/' -f1").strip(),
                'ip_address': self.device.shell('ip addr show wlan0').strip(),
                'mac_address': self.device.shell('cat /sys/class/net/wlan0/address').strip(),
                #'wifi_ssid': self.device.shell('dumpsys wifi | grep "mWifiInfo"').strip(),
                # Дополнительные сетевые параметры
                'network_type': self.device.shell('getprop gsm.network.type').strip(),
                'cellular_operator': self.device.shell('getprop gsm.operator.alpha').strip(),
                # Информация о подключениях
                #'active_connections': self.device.shell('netstat -tuln').strip(),
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
            current_ntp = self.get_current_ntp()
            device_info = self.get_device_info()
            print(Fore.GREEN + locales.get("current_device_info"))
            print(Fore.GREEN + locales.get("current_ntp_server") + " ", end="")
            print(Fore.RED + "{}".format(current_ntp))
            print(Fore.YELLOW + locales.get("device_info"))
            for key, value in device_info.items():
                print(f"  {key.capitalize()}: {value}")
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))
            
    def manage_servers(self):
        """Управление сохраненными серверами"""
        while True:
            print(locales.get("server_management"))
            print("1. " + locales.get("show_favorite_servers"))
            print("2. " + locales.get("add_current_server_to_favorites"))
            print("3. " + locales.get("copy_server_to_clipboard"))
            print("4. " + locales.get("paste_server_from_clipboard"))
            print("5. " + locales.get("remove_server_from_favorites"))
            print("6. " + locales.get("ping_servers"))
            print("7. " + locales.get("return_to_main_menu"))

            choice = input(locales.get("select_action")).strip()

            if choice == '1':
                if self.saved_servers['favorite_servers']:
                    print(locales.get("favorite_servers_list"))
                    for i, server in enumerate(self.saved_servers['favorite_servers'], 1):
                        print(f"{i}. {server}")
                else:
                    print(locales.get("no_favorite_servers"))

            elif choice == '2':
                if self.device:
                    current_ntp = self.get_current_ntp()
                    self.add_to_favorites(current_ntp)
                    print(locales.get("server_added_to_favorites", server=current_ntp))
                else:
                    print(locales.get("connect_device_first"))

            elif choice == '3':
                if self.device:
                    current_ntp = self.get_current_ntp()
                    if self.copy_server_to_clipboard(current_ntp):
                        print(locales.get("server_copied_to_clipboard", server=current_ntp))
                    else:
                        print(locales.get("failed_to_copy_server"))
                else:
                    print(locales.get("connect_device_first"))

            elif choice == '4':
                server = self.paste_server_from_clipboard()
                if server:
                    try:
                        if self.device:
                            self.fix_time(server)
                            print(locales.get("server_set_from_clipboard", server=server))
                        else:
                            print(locales.get("connect_device_first"))
                    except AndroidTVTimeFixerError as e:
                        print(locales.get("error_occurred", error=str(e)))
                else:
                    print(locales.get("clipboard_empty_or_unavailable"))

            elif choice == '5':
                if self.saved_servers['favorite_servers']:
                    print(locales.get("choose_server_to_remove"))
                    for i, server in enumerate(self.saved_servers['favorite_servers'], 1):
                        print(f"{i}. {server}")
                    try:
                        idx = int(input(locales.get("enter_server_number"))) - 1
                        if 0 <= idx < len(self.saved_servers['favorite_servers']):
                            removed = self.saved_servers['favorite_servers'].pop(idx)
                            self.save_servers()
                            print(locales.get("server_removed_from_favorites", server=removed))
                        else:
                            print(locales.get("invalid_number"))
                    except ValueError:
                        print(locales.get("enter_valid_number"))
                else:
                    print(locales.get("no_favorite_servers"))

            elif choice == '6':
                self.ping_ntp_servers()
            
            elif choice == '7':
                break

            else:
                print(locales.get("invalid_choice"))

def main():
    fixer = AndroidTVTimeFixer()
    print(locales.get("select_language"))  # Выводим сообщение для выбора языка
    print("1. " + locales.get("english"))  # Выбор для английского
    print("2. " + locales.get("russian"))  # Выбор для русского
    # Ввод пользователя
    lang_choice = input(locales.get("enter_number")).strip()
    # Назначение языка на основе выбора
    if lang_choice == "2":
        set_language("ru")
        print(locales.get("language_set_ru"))  # Подтверждение выбора
    else:
        set_language("en")
        print(locales.get("language_set_en"))  # Подтверждение выбора
        
    try:
        # Показываем начальные инструкции
        print(Fore.GREEN + locales.get("program_title"))
        print(Fore.WHITE + locales.get("please_ensure"))
        print(Fore.YELLOW + locales.get("adb_setup"))
        print(Fore.YELLOW + locales.get("adb_steps"))
        print(Fore.YELLOW + locales.get("adb_network"))
        print(Fore.YELLOW + locales.get("auto_time_date"))
        print(Fore.YELLOW + locales.get("network_requirement"))
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
            print(Fore.YELLOW + locales.get("menu_item_8"))
            print(Fore.YELLOW + locales.get("menu_item_9"))
            print(Fore.YELLOW + locales.get("menu_item_10"))

            choice = input(Fore.WHITE + locales.get("menu_prompt")).strip()

            if choice == '1':
                print(Fore.GREEN + locales.get('enter_device_ip'), end="")
                ip = input(Fore.WHITE).strip()
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect(ip)
                        fixer.show_current_settings()
                        print(Fore.GREEN + locales.get('enter_country_code'), end="")
                        code = input(Fore.WHITE).strip()
                        if fixer.validate_country_code(code):
                            try:
                                ntp_server = fixer.ntp_servers[code.lower()]
                                fixer.fix_time(ntp_server)
                                print(Fore.GREEN + locales.get('ntp_server_set', ntp_server=ntp_server))
                            except KeyError:
                                print(Fore.RED + locales.get('invalid_country_code'))
                            except AndroidTVTimeFixerError as e:
                                print(Fore.RED + locales.get('error_message', error=str(e)))
                        else:
                            print(Fore.RED + locales.get('invalid_country_code'))
                    except AndroidTVTimeFixerError as e:
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    print(Fore.RED + locales.get('invalid_ip_format'))

            elif choice == '2':
                print(Fore.GREEN + locales.get('enter_device_ip'), end="")
                ip = input(Fore.WHITE).strip()
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect(ip)
                        fixer.show_current_settings()
                        fixer.set_custom_ntp()
                    except AndroidTVTimeFixerError as e:
                        print(Fore.RED + locales.get('error_message', error=str(e)))
                else:
                    print(Fore.RED + locales.get('invalid_ip_format'))

            elif choice == '3':
                fixer.show_country_codes()

            elif choice == '4':
                fixer.show_custom_ntp_servers()

            elif choice == '5':
                print(Fore.GREEN + locales.get('enter_device_ip'), end="")
                ip = input(Fore.WHITE).strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.show_device_info()
                else:
                    print(Fore.RED + locales.get('invalid_ip_format'))
            
            elif choice == '6':
                fixer.manage_servers()
                    
            elif choice == '7':
                print(Fore.GREEN + locales.get('country_codes_description'))
                print(locales.get('country_codes'))
		    
            elif choice == '8':
                fixer.terminal_mode()
		    
            elif choice == '9':
                print(Fore.GREEN + locales.get('exit_message'))
                sys.exit(0)
            
            elif choice.lower() == 'b':
                continue
            else:
                print(Fore.RED + locales.get('invalid_choice'))
        
    except AndroidTVTimeFixerError as e:
        print(Fore.RED + locales.get('error_message').format(str(e)))
        sys.exit(1)
    except KeyboardInterrupt:
        print(Fore.RED + locales.get('operation_aborted'))
        sys.exit(0)
    except Exception as e:
        print(Fore.RED + locales.get('unexpected_error').format(str(e)))
        sys.exit(1)

if __name__ == '__main__':
    main()

import os
import sys
import re
import socket
import shlex
import time
import logging
import platform
from platform import system
from time import sleep
from typing import Optional, List, Dict, Union
from datetime import datetime
import threading
import queue
import signal
import json
import subprocess
from subprocess import Popen, PIPE
from pathlib import Path
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
        self.logger = self._setup_logger()
        self.device = None
        self.max_connection_retries = 5
        self.connection_retry_delay = 5
        self.connection_timeout = 120  # Таймаут ожидания подключения в секундах
        self.servers_file = self.current_path / 'saved_servers.json'
        self.saved_servers = self.load_saved_servers()
        self.history_index = 0
        self.command_queue = queue.Queue()
        self.is_running = True
        self.last_command_output = ""
        self.aliases: Dict[str, str] = {}
        self._load_aliases()
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
            'ntp0.ntp-servers.net',
            'ntp1.ntp-servers.net',
            'ntp2.ntp-servers.net',
            'ntp3.ntp-servers.net',
            'ntp4.ntp-servers.net',
            'ntp5.ntp-servers.net',
            'ntp6.ntp-servers.net',
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


      init()

        # Initialize locales
        self.locales = self._load_locales()

    def _setup_logger(self) -> logging.Logger:
        """Configure logging with proper format and handlers."""
        logger = logging.getLogger('AndroidTVTimeFixer')
        logger.setLevel(logging.INFO)
        
        os.makedirs('logs', exist_ok=True)
        
        file_handler = logging.FileHandler(
            f'logs/androidtv_{datetime.now().strftime("%Y%m%d_%H%M%S")}.log'
        )
        
        console_handler = logging.StreamHandler()
        
        formatter = logging.Formatter(
            '%(asctime)s - %(levelname)s - %(message)s'
        )
        
        file_handler.setFormatter(formatter)
        console_handler.setFormatter(formatter)
        
        logger.addHandler(file_handler)
        logger.addHandler(console_handler)
        
        return logger

    def _load_locales(self):
        """Load localization strings."""
        try:
            # Implement your locales loading logic here
            pass
        except Exception as e:
            self.logger.error(f"Failed to load locales: {e}")
            return {}

    def _load_aliases(self) -> None:
        """Load command aliases from config file."""
        try:
            if os.path.exists('aliases.conf'):
                with open('aliases.conf', 'r') as f:
                    for line in f:
                        if '=' in line:
                            alias, command = line.strip().split('=', 1)
                            self.aliases[alias.strip()] = command.strip()
        except Exception as e:
            self.logger.error(f"Failed to load aliases: {e}")

    def _save_aliases(self) -> None:
        """Save command aliases to config file."""
        try:
            with open('aliases.conf', 'w') as f:
                for alias, command in self.aliases.items():
                    f.write(f"{alias}={command}\n")
        except Exception as e:
            self.logger.error(f"Failed to save aliases: {e}")

    def get_adb_path(self):
        """Gets the path to ADB based on the platform with enhanced error handling."""
        try:
            # Try to get platform-specific ADB path
            if system() == 'Windows':
                from hooks.win_hook import ADB_PATH
                if not os.path.exists(ADB_PATH):
                    raise FileNotFoundError(f"ADB not found at {ADB_PATH}")
                return ADB_PATH
            else:
                from hooks.linux_hook import ADB_PATH
                if not os.path.exists(ADB_PATH):
                    raise FileNotFoundError(f"ADB not found at {ADB_PATH}")
                return ADB_PATH
        except ImportError:
            # Fallback to local resources
            try:
                base_path = sys._MEIPASS if getattr(sys, 'frozen', False) else os.path.abspath(os.path.dirname(__file__))
                adb_name = 'adb.exe' if system() == 'Windows' else 'adb'
                adb_path = os.path.join(base_path, 'resources', adb_name)
                
                if not os.path.exists(adb_path):
                    raise FileNotFoundError(f"ADB not found at {adb_path}")
                
                return adb_path
            except Exception as e:
                self.logger.error(f"Failed to locate ADB: {e}")
                raise

    def execute_terminal_command(self, command: str, retries: int = 3) -> Optional[str]:
        """
        Executes a command in the terminal with enhanced retry logic and output capture.
        """
        if not command:
            return None

        # Check for aliases
        if command in self.aliases:
            command = self.aliases[command]

        self.logger.info(f"Executing command: {command}")
        output_lines = []
        attempt = 0

        while attempt < retries:
            try:
                args = shlex.split(command)
                if args[0] == 'adb':
                    args[0] = self.get_adb_path()
                
                process = Popen(
                    args,
                    stdout=PIPE,
                    stderr=PIPE,
                    universal_newlines=True,
                    bufsize=1
                )

                def read_output(pipe, queue):
                    for line in iter(pipe.readline, ''):
                        queue.put(line.strip())
                    pipe.close()

                output_queue = queue.Queue()
                error_queue = queue.Queue()

                output_thread = threading.Thread(
                    target=read_output,
                    args=(process.stdout, output_queue)
                )
                error_thread = threading.Thread(
                    target=read_output,
                    args=(process.stderr, error_queue)
                )

                output_thread.daemon = True
                error_thread.daemon = True
                output_thread.start()
                error_thread.start()

                while process.poll() is None:
                    # Handle stdout
                    try:
                        while True:
                            line = output_queue.get_nowait()
                            print(Fore.GREEN + line + Style.RESET_ALL)
                            output_lines.append(line)
                    except queue.Empty:
                        pass

                    # Handle stderr
                    try:
                        while True:
                            line = error_queue.get_nowait()
                            print(Fore.RED + line + Style.RESET_ALL)
                            output_lines.append(f"ERROR: {line}")
                    except queue.Empty:
                        pass

                    sleep(0.1)

                output_thread.join(timeout=1)
                error_thread.join(timeout=1)

                if process.returncode == 0:
                    self.last_command_output = '\n'.join(output_lines)
                    return self.last_command_output
                else:
                    attempt += 1
                    if attempt < retries:
                        self.logger.warning(f"Command failed, retrying ({attempt}/{retries})...")
                        sleep(self.connection_retry_delay)
                    else:
                        self.logger.error(f"Command failed after {retries} attempts")
                        return None

            except Exception as e:
                self.logger.error(f"Command execution error: {e}")
                attempt += 1
                if attempt < retries:
                    sleep(self.connection_retry_delay)
                else:
                    return None

    def terminal_mode(self):
        """Enhanced terminal mode with additional features."""
        print(Fore.CYAN + """
╔═══════════════════════════════════════════╗
║           Enhanced Terminal Mode           ║
║     Type 'help' to see available commands  ║
╚═══════════════════════════════════════════╝
""" + Style.RESET_ALL)

        def signal_handler(signum, frame):
            print("\nGracefully shutting down terminal mode...")
            self.is_running = False
        
        original_handler = signal.signal(signal.SIGINT, signal_handler)

        try:
            while self.is_running:
                try:
                    command = input(Fore.YELLOW + "terminal> " + Style.RESET_ALL).strip()
                    
                    if not command:
                        continue

                    self.command_history.append(command)
                    self.history_index = len(self.command_history)

                    if command.lower() in ['exit', 'quit', 'q', 'back']:
                        print(self.locales.get("terminal_exit_message", "Exiting terminal mode..."))
                        break
                    
                    elif command.lower() in ['help', '?']:
                        self._show_terminal_help()
                        continue
                    
                    elif command.lower() == 'clear':
                        os.system('cls' if system() == 'Windows' else 'clear')
                        continue
                    
                    elif command.lower() == 'history':
                        self._show_history()
                        continue
                    
                    elif command.startswith('alias '):
                        self._handle_alias(command[6:])
                        continue
                    
                    elif command.lower() == 'aliases':
                        self._show_aliases()
                        continue
                    
                    elif command.lower() == 'save':
                        if self.last_command_output:
                            self._save_output()
                        else:
                            print(self.locales.get("no_output_to_save", "No output to save"))
                        continue

                    elif command.lower() == 'devices':
                        self.check_device_connection()
                        continue

                    # Execute the command
                    self.execute_terminal_command(command)

                except EOFError:
                    print("\n" + self.locales.get("terminal_exit_message", "Exiting terminal mode..."))
                    break
                except Exception as e:
                    self.logger.error(f"Error in terminal mode: {e}")
                    print(Fore.RED + f"Error: {str(e)}" + Style.RESET_ALL)

        finally:
            # Restore original signal handler
            signal.signal(signal.SIGINT, original_handler)

    def _show_terminal_help(self):
        """Display available terminal commands and their descriptions."""
        help_text = f"""
{self.locales.get("available_commands", "Available Commands")}:
------------------
help, ?          : {self.locales.get("help_command_desc", "Show this help message")}
exit, quit, q    : {self.locales.get("exit_command_desc", "Exit terminal mode")}
clear            : {self.locales.get("clear_command_desc", "Clear the screen")}
history          : {self.locales.get("history_command_desc", "Show command history")}
alias name=cmd   : {self.locales.get("alias_command_desc", "Create command alias")}
aliases          : {self.locales.get("aliases_command_desc", "List all aliases")}
save             : {self.locales.get("save_command_desc", "Save last command output to file")}
devices          : {self.locales.get("devices_command_desc", "Check connected devices")}
adb commands     : {self.locales.get("adb_commands_desc", "Standard ADB commands")}
</command>       : {self.locales.get("system_command_desc", "Execute system command")}
"""
        print(Fore.CYAN + help_text + Style.RESET_ALL)

    def _show_history(self):
        """Display command history."""
        for i, cmd in enumerate(self.command_history, 1):
            print(f"{i:3d}: {cmd}")

    def _handle_alias(self, alias_str: str):
        """Handle alias creation and management."""
        try:
            name, command = alias_str.split('=', 1)
            name = name.strip()
            command = command.strip()
            
            if name and command:
                self.aliases[name] = command
                self._save_aliases()
                print(self.locales.get("alias_created").format(name=name, command=command))
            else:
                print(self.locales.get("invalid_alias_format"))
        except ValueError:
            print(self.locales.get("invalid_alias_format"))

    def _show_aliases(self):
        """Display all defined aliases."""
        if self.aliases:
            print("\n" + self.locales.get("defined_aliases", "Defined Aliases:"))
            print("--------------")
            for alias, command in self.aliases.items():
                print(f"{alias} = {command}")
        else:
            print(self.locales.get("no_aliases_defined", "No aliases defined"))

    def _save_output(self):
        """Save the last command output to a file."""
        if not self.last_command_output:
            return
        
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"output_{timestamp}.txt"
        
        try:
            with open(filename, 'w') as f:
                f.write(self.last_command_output)
            print(self.locales.get("output_saved").format(filename=filename))
        except Exception as e:
            self.logger.error(f"Failed to save output: {e}")
            print(self.locales.get("output_save_error").format(error=str(e)))

    def check_device_connection(self):
        """Checks if the Android device is connected via ADB."""
        print(self.locales.get("checking_device_connection", "Checking device connection..."))
        self.execute_terminal_command('adb devices')

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
            #print(Fore.YELLOW + locales.get("menu_item_7"))
            print(Fore.YELLOW + locales.get("menu_item_8"))
            print(Fore.YELLOW + fixer.locales.get("menu_item_9", "9. Terminal Mode"))
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
                print(Fore.YELLOW + "\n" + locales.get('menu_item_7'))
                devices = list_devices()
                if devices:
                    selected_device = select_device(devices)
                    if selected_device:
                        connect_to_device(selected_device)
                    
            elif choice == '8':
                print(Fore.GREEN + locales.get('country_codes_description'))
                print(locales.get('country_codes'))
		    
            choice = input(fixer.locales.get("enter_choice", "Enter your choice: "))
            
            if choice == '9':
                fixer.terminal_mode()
		    
            elif choice == '10':
                print(Fore.GREEN + locales.get('exit_message'))
                sys.exit(0)
            
            elif choice.lower() == 'b':
                continue
            else:
                print(Fore.RED + locales.get('invalid_choice'))
		    
            except KeyboardInterrupt:
                # Обработка Ctrl+C внутри цикла меню
                print("\n" + fixer.locales.get("menu_interrupted"))
                if input(fixer.locales.get("confirm_exit", "Exit? (y/n): ")).lower() == 'y':
                    raise KeyboardInterrupt  # Пробрасываем исключение для выхода из программы
                continue  # Возвращаемся к меню если пользователь не хочет выходить
            
            except Exception as e:
                # Обработка ошибок внутри цикла меню
                fixer.logger.error(f"Menu operation error: {e}")
                print(Fore.RED + fixer.locales.get("menu_error").format(error=str(e)) + Style.RESET_ALL)
                # Даем пользователю время прочитать сообщение об ошибке
                sleep(2)
                continue  # Возвращаемся к меню
                
    except AndroidTVTimeFixerError as e:
        # Обработка специфических ошибок приложения
        fixer.logger.error(f"Application error: {e}")
        print(Fore.RED + fixer.locales.get("error_message").format(error=str(e)) + Style.RESET_ALL)
        sys.exit(1)
        
    except KeyboardInterrupt:
        # Финальная обработка Ctrl+C для выхода из программы
        print("\n" + fixer.locales.get("operation_aborted"))
        sys.exit(0)
        
    except Exception as e:
        # Обработка всех остальных непредвиденных ошибок
        fixer.logger.critical(f"Unexpected error: {e}")
        print(Fore.RED + fixer.locales.get("unexpected_error").format(error=str(e)) + Style.RESET_ALL)
        sys.exit(1)
        
    finally:
        # Очистка ресурсов при выходе
        try:
            fixer.cleanup()  # Если у вас есть метод очистки
        except Exception as e:
            fixer.logger.error(f"Cleanup error: {e}")

if __name__ == '__main__':
    main()

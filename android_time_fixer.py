import os
import sys
import re
import time
import logging
import platform
import pyperclip
import json
import colorama
import subprocess
from pathlib import Path
from locales import locales, set_language
from adb_shell.auth.keygen import keygen
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
from locales import locales, set_language
from colorama import Fore, Style, init
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
        self.device = None
        self.max_connection_retries = 5
        self.connection_retry_delay = 7
        self.connection_timeout = 80  # Таймаут ожидания подключения в секундах
        self.servers_file = self.current_path / 'saved_servers.json'
        self.saved_servers = self.load_saved_servers()
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
        print(Fore.GREEN + locales.get("connecting_to_device").format(device))
        
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
                locales.get("last_error").format(last_error)
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
                print(Fore.GREEN + locales.get("ntp_server_set").format(ntp_server) + Fore.RED + ntp_server)
                return
            except AndroidTVTimeFixerError as e:
                print(locales.get("error_message").format(error=str(e)))

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
            print("6. " + locales.get("return_to_main_menu"))

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
            print(Fore.YELLOW + locales.get("menu_item_9"))

            choice = input(Fore.WHITE + locales.get("menu_prompt")).strip()

            if choice == '1':
                print(Fore.GREEN + locales.get('enter_device_ip'), end="")
                ip = input(Fore.WHITE).strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.show_current_settings()
                    print(Fore.GREEN + locales.get('enter_country_code'), end="")
                    code = input(Fore.WHITE).strip()
                    if fixer.validate_country_code(code):
                        ntp_server = fixer.ntp_servers[code.lower()]
                        fixer.fix_time(ntp_server)
                        print(Fore.YELLOW + locales.get('time_settings_updated'))
                else:
                    print(Fore.RED + locales.get('invalid_ip_format'))

            elif choice == '2':
                print(Fore.GREEN + locales.get('enter_device_ip'), end="")
                ip = input(Fore.WHITE).strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.show_current_settings()
                    fixer.set_custom_ntp()
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
import os
import sys
import re
import time
import logging
import platform
import pyperclip
import json
from pathlib import Path
from adb_shell.auth.keygen import keygen
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

def show_disclaimer():
    disclaimer = """
    ==========================================
    ВНИМАНИЕ: Эта программа предоставляется на условиях «как есть» (as is).
    Автор(ы) не несут ответственности за любые возможные убытки или ущерб,
    возникшие в результате использования данной программы.
    ==========================================
    """
    print(disclaimer)

# Вызов функции при запуске программы
if __name__ == "__main__":
    show_disclaimer()

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)

class AndroidTVTimeFixerError(Exception):
    """Базовый класс исключений для AndroidTVTimeFixer"""
    pass

class AndroidTVTimeFixer:
    def __init__(self):
        self.current_path = Path.cwd()
        self.keys_folder = self.current_path / 'keys'
        self.device = None
        self.max_connection_retries = 3
        self.connection_retry_delay = 7
        self.connection_timeout = 60  # Таймаут ожидания подключения в секундах
        self.servers_file = self.current_path / 'saved_servers.json'
        self.saved_servers = self.load_saved_servers()
        self.ntp_servers = {
            'ad': 'ad.pool.ntp.org',
            'al': 'al.pool.ntp.org',
            'at': 'at.pool.ntp.org',
            'ax': 'ax.pool.ntp.org',
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
            'fo': 'fo.pool.ntp.org',
            'fr': 'fr.pool.ntp.org',
            'gg': 'gg.pool.ntp.org',
            'gi': 'gi.pool.ntp.org',
            'gr': 'gr.pool.ntp.org',
            'hr': 'hr.pool.ntp.org',
            'hu': 'hu.pool.ntp.org',
            'ie': 'ie.pool.ntp.org',
            'im': 'im.pool.ntp.org',
            'is': 'is.pool.ntp.org',
            'it': 'it.pool.ntp.org',
            'je': 'je.pool.ntp.org',
            'li': 'li.pool.ntp.org',
            'lt': 'lt.pool.ntp.org',
            'lu': 'lu.pool.ntp.org',
            'lv': 'lv.pool.ntp.org',
            'mc': 'mc.pool.ntp.org',
            'md': 'md.pool.ntp.org',
            'me': 'me.pool.ntp.org',
            'mk': 'mk.pool.ntp.org',
            'mt': 'mt.pool.ntp.org',
            'nl': 'nl.pool.ntp.org',
            'no': 'no.pool.ntp.org',
            'pl': 'pl.pool.ntp.org',
            'pt': 'pt.pool.ntp.org',
            'ro': 'ro.pool.ntp.org',
            'rs': 'rs.pool.ntp.org',
            'ru': 'ru.pool.ntp.org',
            'se': 'se.pool.ntp.org',
            'si': 'si.pool.ntp.org',
            'sj': 'sj.pool.ntp.org',
            'sk': 'sk.pool.ntp.org',
            'sm': 'sm.pool.ntp.org',
            'tr': 'tr.pool.ntp.org',
            'ua': 'ua.pool.ntp.org',
            'uk': 'uk.pool.ntp.org',
            'va': 'va.pool.ntp.org',
            'xk': 'xk.pool.ntp.org',
            'yu': 'yu.pool.ntp.org',
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
            'time.android.com'
        ]

    def load_saved_servers(self) -> dict:
        """Загружает сохраненные серверы из файла"""
        if self.servers_file.exists():
            try:
                with open(self.servers_file, 'r') as f:
                    return json.load(f)
            except Exception as e:
                logger.warning(f"Не удалось загрузить сохраненные серверы: {e}")
        return {'favorite_servers': [], 'custom_servers': []}

    def save_servers(self):
        """Сохраняет серверы в файл"""
        try:
            with open(self.servers_file, 'w') as f:
                json.dump(self.saved_servers, f, indent=2)
        except Exception as e:
            logger.warning(f"Не удалось сохранить серверы: {e}")

    def copy_server_to_clipboard(self, server: str) -> bool:
        """Копирует адрес сервера в буфер обмена"""
        try:
            pyperclip.copy(server)
            return True
        except Exception as e:
            logger.warning(f"Не удалось скопировать в буфер обмена: {e}")
            return False

    def paste_server_from_clipboard(self) -> str:
        """Получает адрес сервера из буфера обмена"""
        try:
            return pyperclip.paste()
        except Exception as e:
            logger.warning(f"Не удалось вставить из буфера обмена: {e}")
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
                logger.info('Ключи ADB сгенерированы успешно')
            else:
                logger.info('Используются существующие ключи ADB')
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось сгенерировать ключи: {str(e)}")

    def load_keys(self):
        try:
            with open(self.keys_folder / 'adbkey.pub', 'rb') as f:
                pub = f.read()
            with open(self.keys_folder / 'adbkey', 'rb') as f:
                priv = f.read()
            return pub, priv
        except FileNotFoundError:
            raise AndroidTVTimeFixerError("Ключи ADB не найдены. Пожалуйста, сначала сгенерируйте их.")
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось загрузить ключи: {str(e)}")

    def connect(self, ip: str) -> None:
        """Улучшенная версия метода подключения с ожиданием разрешения"""
        if not self.validate_ip(ip):
            raise AndroidTVTimeFixerError("Неверный формат IP-адреса")

        pub, priv = self.load_keys()
        signer = PythonRSASigner(pub, priv)
        
        start_time = time.time()
        connection_established = False
        last_error = None
        
        print("\nОжидание подключения и разрешения на устройстве...")
        print("Пожалуйста, подтвердите подключение на экране ТВ, если появится запрос.")
        
        while time.time() - start_time < self.connection_timeout:
            try:
                self.device = AdbDeviceTcp(ip.strip(), 5555, default_transport_timeout_s=9.)
                self.device.connect(rsa_keys=[signer], auth_timeout_s=15)
                connection_established = True
                logger.info(f'Подключение к {ip}:5555 выполнено успешно')
                break
            except Exception as e:
                last_error = str(e)
                remaining_time = int(self.connection_timeout - (time.time() - start_time))
                print(f"\rОжидание подключения... {remaining_time} сек.", end='')
                time.sleep(1)

        print()  # Новая строка после завершения ожидания
        
        if not connection_established:
            raise AndroidTVTimeFixerError(
                f"Не удалось подключиться в течение {self.connection_timeout} секунд.\n"
                "Убедитесь, что:\n"
                "1. На вашем ТВ включен отладчик ADB\n"
                "2. Ваш ТВ и ПК находятся в одной сети\n"
                "3. IP-адрес введен правильно\n"
                "4. Вы предоставили доступ устройству при появлении запроса на ТВ\n"
                f"Последняя ошибка: {last_error}"
            )

    def get_current_ntp(self) -> str:
        if not self.device:
            raise AndroidTVTimeFixerError("Не подключено ни к одному устройству")

        try:
            current_ntp = self.device.shell('settings get global ntp_server')
            return current_ntp.strip()
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось получить текущий сервер NTP: {str(e)}")

    def set_ntp_server(self, ntp_server: str) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError("Не подключено ни к одному устройству")

        try:
            self.device.shell(f'settings put global ntp_server {ntp_server}')
            logger.info(f'Сервер NTP установлен на {ntp_server}')

            # Проверяем изменение
            new_ntp = self.get_current_ntp()
            if ntp_server not in new_ntp:
                raise AndroidTVTimeFixerError("Не удалось подтвердить изменение сервера NTP")
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось обновить сервер NTP: {str(e)}")

    def fix_time(self, ntp_server: str) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError("Не подключено ни к одному устройству")

        self.set_ntp_server(ntp_server)

    def show_country_codes(self) -> None:
        print("\nДоступные коды стран:")
        for code, server in self.ntp_servers.items():
            print(f"{code.upper()} — {server}")

    def show_custom_ntp_servers(self) -> None:
        print("\nДоступные альтернативные серверы NTP:")
        for server in self.custom_ntp_servers:
            print(f"- {server}")

    def set_custom_ntp(self) -> None:
        while True:
            ntp_server = input("\nВведите свой NTP-сервер (или 'q' для выхода): ").strip()
            if ntp_server.lower() == 'q':
                return
            try:
                self.fix_time(ntp_server)
                print(f"Сервер NTP установлен на {ntp_server}")
                return
            except AndroidTVTimeFixerError as e:
                print(f"Ошибка: {str(e)}")

    def get_device_info(self) -> dict:
        if not self.device:
            raise AndroidTVTimeFixerError("Не подключено ни к одному устройству")

        try:
            device_info = {
                'model': self.device.shell('getprop ro.product.model').strip(),
                'brand': self.device.shell('getprop ro.product.brand').strip(),
                'name': self.device.shell('getprop ro.product.name').strip(),
                'android_version': self.device.shell('getprop ro.build.version.release').strip(),
                'api_level': self.device.shell('getprop ro.build.version.sdk').strip(),
                #'serial': self.device.shell('getprop ro.serialno').strip(),
                'serial': self.device.shell('getprop ro.boot.serialno').strip(),
                'cpu_arch': self.device.shell('getprop ro.product.cpu.abi').strip(),
                'hardware': self.device.shell('getprop ro.hardware').strip(),
                #'ip_address': self.device.shell('ip addr show wlan0 | grep "inet "').strip(),
                'ip_address': self.device.shell("ip -f inet addr show wlan0 | awk '/inet / {print $2}' | cut -d'/' -f1").strip(),
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
            raise AndroidTVTimeFixerError(f"Не удалось получить информацию об устройстве: {str(e)}")

    def show_current_settings(self) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError("Не подключено ни к одному устройству")

        try:
            current_ntp = self.get_current_ntp()
            device_info = self.get_device_info()
            print(f"\nТекущие настройки:")
            print(f"- Текущий сервер NTP, установленный на устройстве: {current_ntp}")
            print(f"- Устройство (информация):")
            for key, value in device_info.items():
                print(f"  {key.capitalize()}: {value}")
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось получить информацию об устройстве: {str(e)}")

    def manage_servers(self):
        """Управление сохраненными серверами"""
        while True:
            print("\nУправление серверами:")
            print("1. Показать избранные серверы")
            print("2. Добавить текущий сервер в избранное")
            print("3. Копировать сервер в буфер обмена")
            print("4. Вставить сервер из буфера обмена")
            print("5. Удалить сервер из избранного")
            print("6. Вернуться в главное меню")

            choice = input("Выберите действие: ").strip()

            if choice == '1':
                if self.saved_servers['favorite_servers']:
                    print("\nИзбранные серверы:")
                    for i, server in enumerate(self.saved_servers['favorite_servers'], 1):
                        print(f"{i}. {server}")
                else:
                    print("Список избранных серверов пуст")

            elif choice == '2':
                if self.device:
                    current_ntp = self.get_current_ntp()
                    self.add_to_favorites(current_ntp)
                    print(f"Сервер {current_ntp} добавлен в избранное")
                else:
                    print("Сначала подключитесь к устройству")

            elif choice == '3':
                if self.device:
                    current_ntp = self.get_current_ntp()
                    if self.copy_server_to_clipboard(current_ntp):
                        print(f"Сервер {current_ntp} скопирован в буфер обмена")
                    else:
                        print("Не удалось скопировать сервер")
                else:
                    print("Сначала подключитесь к устройству")

            elif choice == '4':
                server = self.paste_server_from_clipboard()
                if server:
                    try:
                        if self.device:
                            self.fix_time(server)
                            print(f"Установлен сервер из буфера обмена: {server}")
                        else:
                            print("Сначала подключитесь к устройству")
                    except AndroidTVTimeFixerError as e:
                        print(f"Ошибка: {str(e)}")
                else:
                    print("Буфер обмена пуст или недоступен")

            elif choice == '5':
                if self.saved_servers['favorite_servers']:
                    print("\nВыберите сервер для удаления:")
                    for i, server in enumerate(self.saved_servers['favorite_servers'], 1):
                        print(f"{i}. {server}")
                    try:
                        idx = int(input("Введите номер сервера: ")) - 1
                        if 0 <= idx < len(self.saved_servers['favorite_servers']):
                            removed = self.saved_servers['favorite_servers'].pop(idx)
                            self.save_servers()
                            print(f"Сервер {removed} удален из избранного")
                        else:
                            print("Неверный номер")
                    except ValueError:
                        print("Введите корректный номер")
                else:
                    print("Список избранных серверов пуст")

            elif choice == '6':
                break

            else:
                print("Неверный выбор")

def main():
    fixer = AndroidTVTimeFixer()
    
    try:
        # Показываем начальные инструкции
        print("\nКорректировка сервера времени для Android TV")
        print("\nПожалуйста, убедитесь, что следующее сделано:")
        print("1. Включите отладку ADB на вашем ТВ или Nvidia Shield:")
        print("   Настройки > Настройки устройства > Об устройстве > Сборка (нажмите 7 раз или более)")
        print("   Затем: Настройки устройства > Для разработчиков > Отладка по сети (Включить)")
        print("2. Установите время и дату в автоматический режим:")
        print("   Настройки >  Настройки устройства > Дата и время > Автонастройка даты и времени > Использовать время сети")
        print("3. Ваш ТВ, Nvidia Shield и ПК должны быть подключены к одной сети")
        input("\nНажмите Enter, чтобы продолжить...")

        # Генерируем ключи ADB
        fixer.gen_keys()

        while True:
            print("\nГлавное меню:")
            print("1. Изменить сервер времени NTP по коду страны")
            print("2. Изменить сервер времени NTP на пользовательский")
            print("3. Показать доступные коды стран и серверов NTP,(можно копировать в буфер обмена)")
            print("4. Показать доступные альтернативные сервера времени NTP,(можно копировать в буфер обмена)")
            print("5. Показать текущие настройки устройства")
           # print("6. Управление серверами")
            print("6. Расшифровка кодов стран,(можно копировать в буфер обмена)")
            print("7. Выход")

            choice = input("Введите номер пункта меню: ").strip()

            if choice == '1':
                ip = input('\nВведите IP-адрес вашего ТВ (найдите в Настройки > Сеть и интернет): ').strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.show_current_settings()
                    code = input('\nВведите код вашей страны (например, ru для России, by для Беларусь, смотри в меню коды стран, для возврата q): ').strip()
                    if fixer.validate_country_code(code):
                        ntp_server = fixer.ntp_servers[code.lower()]
                        fixer.fix_time(ntp_server)
                        print("\nНастройки времени успешно обновлены!")
                else:
                    print("Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx")

            elif choice == '2':
                ip = input('\nВведите IP-адрес вашего ТВ (найдите в Настройки > Сеть и интернет): ').strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.set_custom_ntp()
                else:
                    print("Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx")

            elif choice == '3':
                fixer.show_country_codes()

            elif choice == '4':
                fixer.show_custom_ntp_servers()

            elif choice == '5':
                ip = input('\nВведите IP-адрес вашего ТВ (найдите в Настройки > Сеть и интернет): ').strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.show_current_settings()
                else:
                    print("Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx")

            elif choice == '8':
                fixer.manage_servers()

            elif choice == '6':
                print("\nРасшифровка кодов стран (можно копировать в буфер обмена):")
                print("ad: Андорра")
                print("al: Албания")
                print("at: Австрия")
                print("ax: Аландские острова")
                print("ba: Босния и Герцеговина")
                print("be: Бельгия")
                print("bg: Болгария")
                print("by: Беларусь")
                print("ch: Швейцария")
                print("cy: Кипр")
                print("cz: Чехия")
                print("de: Германия")
                print("dk: Дания")
                print("ee: Эстония")
                print("es: Испания")
                print("fi: Финляндия")
                print("fo: Фарерские острова")
                print("fr: Франция")
                print("gg: Гернси")
                print("gi: Гибралтар")
                print("gr: Греция")
                print("hr: Хорватия")
                print("hu: Венгрия")
                print("ie: Ирландия")
                print("im: Остров Мэн")
                print("is: Исландия")
                print("it: Италия")
                print("je: Джерси")
                print("li: Лихтенштейн")
                print("lt: Литва")
                print("lu: Люксембург")
                print("lv: Латвия")
                print("mc: Монако")
                print("md: Молдова")
                print("me: Черногория")
                print("mk: Северная Македония")
                print("mt: Мальта")
                print("nl: Нидерланды")
                print("no: Норвегия")
                print("pl: Польша")
                print("pt: Португалия")
                print("ro: Румыния")
                print("rs: Сербия")
                print("ru: Россия")
                print("se: Швеция")
                print("si: Словения")
                print("sj: Шпицберген и Ян-Майен")
                print("sk: Словакия")
                print("sm: Сан-Марино")
                print("tr: Турция")
                print("ua: Украина")
                print("uk: Великобритания")
                print("va: Ватикан")
                print("xk: Косово")
                print("yu: Югославия")
                print("us: США")
                print("ca: Канада")
                print("br: Бразилия")
                print("au: Австралия")
                print("cn: Китай")
                print("jp: Япония")
                print("kz: Казахстан")

            elif choice == '7':
                print("\nВыход из программы...")
                sys.exit(0)
            
            elif choice.lower() == 'b':
                continue
            else:
                print("Неверный выбор. Пожалуйста, попробуйте еще раз.")
        
    except AndroidTVTimeFixerError as e:
        print(f"\nОшибка: {str(e)}")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nОперация отменена пользователем")
        sys.exit(0)
    except Exception as e:
        print(f"\nНепредвиденная ошибка: {str(e)}")
        sys.exit(1)

if __name__ == '__main__':
    main()

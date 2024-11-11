import os
import sys
import re
import time
import logging
import platform
from pathlib import Path
from adb_shell.auth.keygen import keygen
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

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
        self.connection_retry_delay = 2
        self.ntp_servers = {
            'ru': 'ru.pool.ntp.org',
            'us': 'us.pool.ntp.org',
            'gb': 'uk.pool.ntp.org',
            'de': 'de.pool.ntp.org',
            'fr': 'fr.pool.ntp.org',
            'cn': 'cn.pool.ntp.org',
            'jp': 'jp.pool.ntp.org',
            'br': 'br.pool.ntp.org',
            'au': 'au.pool.ntp.org',
            'ca': 'ca.pool.ntp.org'
        }
        self.custom_ntp_servers = [
            'ntp0.ntp-servers.net',
            'ntp1.ntp-servers.net',
            'ntp2.ntp-servers.net',
            'ntp3.ntp-servers.net',
            'ntp4.ntp-servers.net',
            'ntp5.ntp-servers.net',
            'ntp6.ntp-servers.net',
            'time.windows.com'
        ]

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
        if not self.validate_ip(ip):
            raise AndroidTVTimeFixerError("Неверный формат IP-адреса")

        pub, priv = self.load_keys()
        signer = PythonRSASigner(pub, priv)
        
        for attempt in range(self.max_connection_retries):
            try:
                self.device = AdbDeviceTcp(ip.strip(), 5555, default_transport_timeout_s=9.)
                self.device.connect(rsa_keys=[signer], auth_timeout_s=0.1)
                logger.info(f'Подключение к {ip}:5555 выполнено успешно')
                return
            except Exception as e:
                if attempt == self.max_connection_retries - 1:
                    raise AndroidTVTimeFixerError(
                        f"Не удалось подключиться после {self.max_connection_retries} попыток.\n"
                        "Пожалуйста, убедитесь в следующем:\n"
                        "1. На вашем ТВ включен отладчик ADB\n"
                        "2. Ваш ТВ и ПК находятся в одной сети\n"
                        "3. IP-адрес введен правильно\n"
                        "4. Вы предоставили доступ устройству при появлении запроса на ТВ"
                    )
                logger.warning(f"Попытка подключения {attempt + 1} не удалась, повторная попытка...")
                time.sleep(self.connection_retry_delay)

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
            print(f"{code.upper()} - {server}")

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

    def show_current_settings(self) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError("Не подключено ни к одному устройству")

        try:
            current_ntp = self.get_current_ntp()
            print(f"\nТекущие настройки:")
            print(f"- Сервер NTP: {current_ntp}")
            print(f"- Устройство: {self.device.serial}")
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось получить информацию об устройстве: {str(e)}")

def main():
    fixer = AndroidTVTimeFixer()
    
    try:
        # Показываем начальные инструкции
        print("\nИсправитель времени и даты для Android TV")
        print("\nПожалуйста, убедитесь, что следующее сделано:")
        print("1. Включите отладку ADB на вашем ТВ:")
        print("   Настройки > Параметры устройства > О устройстве > Сборка (нажмите 7 раз)")
        print("   Затем: Параметры для разработчиков > Отладка по USB")
        print("2. Установите время и дату в автоматический режим:")
        print("   Настройки > Параметры устройства > Дата и время > Использовать время, предоставляемое сетью")
        print("3. Подключите ТВ и ПК к одной сети")
        input("\nНажмите Enter, чтобы продолжить...")

        # Генерируем ключи ADB
        fixer.gen_keys()

        while True:
            print("\nГлавное меню:")
            print("1. Изменить сервер NTP по коду страны")
            print("2. Изменить сервер NTP на пользовательский")
            print("3. Показать доступные коды стран")
            print("4. Показать доступные альтернативные серверы NTP")
            print("5. Показать текущие настройки")
            print("6. Выход")
            print("\nДля возврата к предыдущему меню введите 'b'")

            choice = input("Введите номер пункта меню: ").strip()

            if choice == '1':
                ip = input('\nВведите IP-адрес вашего ТВ (найдите в Настройки > Сеть и интернет): ').strip()
                if fixer.validate_ip(ip):
                    fixer.connect(ip)
                    fixer.show_current_settings()
                    code = input('\nВведите код вашей страны (например, ru для России, us для США): ').strip()
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

            elif choice == '6':
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

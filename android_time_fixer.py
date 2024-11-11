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
                        "3. IP-адрес введен правильно"
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
            new_ntp = self.device.shell('settings get global ntp_server')
            if ntp_server not in new_ntp:
                raise AndroidTVTimeFixerError("Не удалось подтвердить изменение сервера NTP")
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Не удалось обновить сервер NTP: {str(e)}")

    def fix_time(self, country_code: str) -> None:
        if not self.validate_country_code(country_code):
            raise AndroidTVTimeFixerError("Неверный формат кода страны. Используйте два буквенных символа (например, 'ru' для России, 'us' для США)")

        if country_code.lower() not in self.ntp_servers:
            raise AndroidTVTimeFixerError(f"Не найден сервер NTP для кода страны '{country_code.upper()}'")

        ntp_server = self.ntp_servers[country_code.lower()]
        self.set_ntp_server(ntp_server)

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

        # Получаем IP-адрес ТВ
        while True:
            ip = input('\nВведите IP-адрес вашего ТВ (найдите в Настройки > Сеть и интернет): ').strip()
            if fixer.validate_ip(ip):
                break
            print("Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx")

        # Подключаемся к устройству
        fixer.connect(ip)

        # Получаем текущий сервер NTP
        current_ntp = fixer.get_current_ntp()
        print(f"\nТекущий сервер NTP: {current_ntp}")

        # Получаем код страны и исправляем время
        while True:
            code = input('\nВведите код вашей страны (например, ru для России, us для США): ').strip()
            if fixer.validate_country_code(code):
                break
            print("Неверный код страны. Используйте два буквенных символа (например, 'ru', 'us')")

        fixer.fix_time(code)

        print("\nНастройки времени успешно обновлены!")
        print("Убедитесь, что на вашем ТВ время и дата установлены в автоматический режим.")
        print("\nСоздано Civis Romanuss (civisrom)")
        
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

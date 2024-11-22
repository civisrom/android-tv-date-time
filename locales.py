# locales.py - Module for handling multiple languages in the Android TV Time Fixer application
from typing import Dict, Any
from dataclasses import dataclass
from enum import Enum, auto

# Define supported languages
class Language(Enum):
    EN = auto()  # English
    RU = auto()  # Russian

# Data class to store translations for each string
@dataclass
class Translation:
    en: str  # English translation
    ru: str  # Russian translation

class Locales:
    def __init__(self):
        # Default language is English
        self.current_language: Language = Language.RU
        
        # Dictionary containing all translations
        self.translations: Dict[str, Translation] = {
            # Disclaimer text
            "disclaimer": Translation(
                en="""
==========================================
WARNING: This program is provided "as is".
The author(s) are not responsible for any possible losses or damages
arising from the use of this program.
==========================================
""",
                ru="""
==========================================
ВНИМАНИЕ: Эта программа предоставляется на условиях «как есть» (as is).
Автор(ы) не несут ответственности за любые возможные убытки или ущерб,
возникшие в результате использования данной программы.
==========================================
"""
            ),
            "program_title": Translation(
                en="\nAndroid TV Time Server Correction",
                ru="\nКорректировка сервера времени для Android TV"
            ),
            "please_ensure": Translation(
                en="\nPlease ensure the following is done:",
                ru="\nПожалуйста, убедитесь, что следующее сделано:"
            ),
            "adb_setup": Translation(
                en="1. Enable ADB debugging on your TV or Nvidia Shield:",
                ru="1. Включите отладку ADB на вашем ТВ или Nvidia Shield:"
            ),
            "adb_steps": Translation(
                en="   Settings > Device Preferences > About > Build (press 7 times or more)",
                ru="   Настройки > Настройки устройства > Об устройстве > Сборка (нажмите 7 раз или более)"
            ),
            "adb_network": Translation(
                en="   Then: Device Preferences > Developer options > Network debugging (Enable)",
                ru="   Затем: Настройки устройства > Для разработчиков > Отладка по сети (Включить)"
            ),
            "auto_time_date": Translation(
                en="2. Set time and date to automatic mode:",
                ru="2. Установите время и дату в автоматический режим: Настройки > Настройки устройства > Дата и Время > Автонастройка доты и времени > Использовать время сити"
            ),
            "network_requirement": Translation(
                en="3. Your TV, Nvidia Shield, and PC must be connected to the same network.",
                ru="3. Ваш ТВ, Nvidia Shield и ПК должны быть подключены к одной сети"
            ),
            "press_enter_to_continue": Translation(
                en="\nPress Enter to continue...",
                ru="\nНажмите Enter, чтобы продолжить..."
            ),
            # logger-warning
            "logger_warning": Translation(
                en="Failed to load the saved servers: {error}",
                ru="Не удалось загрузить сохраненные серверы: {error}"
            ),
            # logger-warning_2
            "logger_warning_2": Translation(
                en="Failed to save servers: {error}",
                ru="Не удалось сохранить серверы: {error}"
            ),
            # copy_server_to_clipboard
            "copy_to_clipboard": Translation(
                en="Failed to copy to clipboard: {error}",
                ru="Не удалось скопировать в буфер обмена: {error}"
            ),            
            # copy_server_to_clipboard2
            "copy_to_clipboard_2": Translation(
                en="Failed to paste from clipboard: {error}",
                ru="Не удалось вставить из буфера обмена: {error}"
            ),
            # gen_keys
            "gen_keys": Translation(
                en="ADB keys generated successfully",
                ru="Ключи ADB сгенерированы успешно"
            ),
            "existing_adb_keys": Translation(
            en="Existing ADB keys are being used",
            ru="Используются существующие ключи ADB"
            ),

            "key_generation_error": Translation(
            en="Failed to generate keys: {error}",
            ru="Не удалось сгенерировать ключи: {error}"
            ),
            
            "adb_keys_not_found": Translation(
            en="ADB keys not found. Please generate them first.",
            ru="Ключи ADB не найдены. Пожалуйста, сначала сгенерируйте их."
            ),
            
            "key_loading_error": Translation(
            en="Failed to load keys: {error}",
            ru="Не удалось загрузить ключи: {error}"
            ),
            
            "no_connected_devices": Translation(
            en="No connected devices.",
            ru="Нет подключенных устройств."
            ),
            
            "choose_device_to_connect": Translation(
                en="Select a device to connect:",
                ru="Выберите устройство для подключения:"
            ),
            "enter_device_number": Translation(
                en="Enter the device number: ",
                ru="Введите номер устройства: "
            ),
            "invalid_device_number": Translation(
                en="Invalid device number.",
                ru="Неверный номер устройства."
            ),
            "invalid_input": Translation(
                en="Invalid input.",
                ru="Некорректный ввод."
            ),
            "connecting_to_device": Translation(
                en="Connecting to device {device_id}...",
                ru="Подключение к устройству {device_id}..."
            ),
            
            "current_device_info": Translation(
                en="\nCurrent device information:\n",
                ru="\nТекущая информация об устройстве:\n"
            ),
            "waiting_for_connection": Translation(
                en="\nWaiting for connection and permission on the device...",
                ru="\nОжидание подключения и разрешения на устройстве..."
            ),
            "confirm_connection": Translation(
                en="Please confirm the connection on the TV screen if prompted.",
                ru="Пожалуйста, подтвердите подключение на экране ТВ, если появится запрос."
            ),
            "connection_success": Translation(
                en="Successfully connected to {ip}:5555",
                ru="Подключение к {ip}:5555 выполнено успешно"
            ),
            "waiting_for_connection": Translation(
                en="\rWaiting for connection... {remaining_time} sec.",
                ru="\rОжидание подключения... {remaining_time} сек."
            ),
            "connection_failed": Translation(
                en="Failed to connect within {timeout} seconds.",
                ru="Не удалось подключиться в течение {timeout} секунд."
            ),
            "ensure_steps": Translation(
                en="Make sure that:\n"
                "1. ADB debugging is enabled on your TV\n"
                "2. Your TV and PC are on the same network\n"
                "3. The IP address is entered correctly\n"
                "4. You have granted access to the device when prompted on the TV",
                ru="Убедитесь, что:\n"
                "1. На вашем ТВ включен отладчик ADB\n"
                "2. Ваш ТВ и ПК находятся в одной сети\n"
                "3. IP-адрес введен правильно\n"
                "4. Вы предоставили доступ устройству при появлении запроса на ТВ"
            ),
            "last_error": Translation(
                en="Last error: {error}",
                ru="Последняя ошибка: {error}"
            ),
            "no_device_connected": Translation(
                en="No device connected",
                ru="Не подключено ни к одному устройству"
            ),
            "failed_to_get_ntp_server": Translation(
                en="Failed to get current NTP server: {error}",
                ru="Не удалось получить текущий сервер NTP: {error}"
            ),
            "ntp_server_set": Translation(
                en="NTP server set to {ntp_server}",
                ru="Сервер NTP установлен на {ntp_server}"
            ),
            "ntp_server_confirmation_failed": Translation(
                en="Failed to confirm NTP server change.",
                ru="Не удалось подтвердить изменение сервера NTP"
            ),
            "ntp_server_update_failed": Translation(
                en="Failed to update NTP server: {error}",
                ru="Не удалось обновить сервер NTP: {error}"
            ),
            "available_country_codes": Translation(
                en="\nAvailable country codes:",
                ru="\nДоступные коды стран (копируем в буфер обмена наприм. ru или by и вставляем в пункте 1 глав. меню):"
            ),
            "country_code_server": Translation(
                en="{code} — {server}",
                ru="{code} — {server}"
            ),
            "available_alternative_ntp_servers": Translation(
                en="\nAvailable alternative NTP servers:",
                ru="\nДоступные альтернативные серверы времени NTP (копируем в буфер обмена наприм. 1.asia.pool.ntp.org и вставляем в пункте 2 глав. меню):"
            ),
            "custom_ntp_server": Translation(
                en="- {server}",
                ru="- {server}"
            ),
            "enter_ntp_server": Translation(
                en="\nEnter your NTP server (or 'q' to quit): ",
                ru="\nВведите свой NTP-сервер (или 'q' для выхода): "
            ),
            "device_info_error": Translation(
                en="Failed to retrieve device information: {error}",
                ru="Не удалось получить информацию об устройстве: {error}"
            ),
            "current_ntp_server": Translation(
                en="- Current NTP time server set on the device: ",
                ru="- Текущий сервер времени, установленный на устройстве: "
            ),
            "ntp_server_info_error": Translation(
                en="Failed to retrieve NTP server information: {error}",
                ru="Не удалось получить информацию о сервере NTP: {error}"
            ),
            "select_language": Translation(
                en="Select language:",
                ru="Выберите язык:"
            ),
            "english": Translation(
                en="English",
                ru="Английский"
            ),
            "russian": Translation(
                en="Russian",
                ru="Русский"
            ),
            "enter_number": Translation(
                en="Enter number:",
                ru="Введите номер:"
            ),
            "language_set_en": Translation(
                en="Language set to English.",
                ru="Язык установлен на английский."
            ),
            "language_set_ru": Translation(
                en="Language set to Russian.",
                ru="Язык установлен русский."
            ),
            "server_management": Translation(
                en="Server Management:",
                ru="Управление серверами:"
            ),
            "show_favorite_servers": Translation(
                en="Show favorite servers",
                ru="Показать избранные серверы"
            ),
            "add_current_server_to_favorites": Translation(
                en="Add current server to favorites",
                ru="Добавить текущий сервер в избранное"
            ),
            "copy_server_to_clipboard": Translation(
                en="Copy server to clipboard",
                ru="Копировать сервер в буфер обмена"
            ),
            "paste_server_from_clipboard": Translation(
                en="Paste server from clipboard",
                ru="Вставить сервер из буфера обмена"
            ),
            "remove_server_from_favorites": Translation(
                en="Remove server from favorites",
                ru="Удалить сервер из избранного"
            ),
            "return_to_main_menu": Translation(
                en="Return to main menu",
                ru="Вернуться в главное меню"
            ),
            "select_action": Translation(
                en="Select action:",
                ru="Выберите действие:"
            ),
            "favorite_servers_list": Translation(
                en="Favorite servers:",
                ru="Избранные серверы:"
            ),
            "no_favorite_servers": Translation(
                en="No favorite servers in the list.",
                ru="Список избранных серверов пуст."
            ),
            "server_added_to_favorites": Translation(
                en="Server {server} added to favorites",
                ru="Сервер {server} добавлен в избранное"
            ),
            "connect_device_first": Translation(
                en="Please connect to a device first",
                ru="Сначала подключитесь к устройству"
            ),
            "server_copied_to_clipboard": Translation(
                en="Server {server} copied to clipboard",
                ru="Сервер {server} скопирован в буфер обмена"
            ),
            "failed_to_copy_server": Translation(
                en="Failed to copy server",
                ru="Не удалось скопировать сервер"
            ),
            "server_set_from_clipboard": Translation(
                en="Server {server} set from clipboard",
                ru="Установлен сервер из буфера обмена: {server}"
            ),
            "error_occurred": Translation(
                en="Error: {error}",
                ru="Ошибка: {error}"
            ),
            "clipboard_empty_or_unavailable": Translation(
                en="Clipboard is empty or unavailable",
                ru="Буфер обмена пуст или недоступен"
            ),
            "choose_server_to_remove": Translation(
                en="Choose a server to remove:",
                ru="Выберите сервер для удаления:"
            ),
            "enter_server_number": Translation(
                en="Enter server number:",
                ru="Введите номер сервера:"
            ),
            "server_removed_from_favorites": Translation(
                en="Server {server} removed from favorites",
                ru="Сервер {server} удален из избранного"
            ),
            "invalid_number": Translation(
                en="Invalid number",
                ru="Неверный номер"
            ),
            "enter_valid_number": Translation(
                en="Please enter a valid number",
                ru="Введите корректный номер"
            ),
            "no_favorite_servers": Translation(
                en="No favorite servers in the list",
                ru="Список избранных серверов пуст"
            ),
            "invalid_choice": Translation(
                en="Invalid choice",
                ru="Неверный выбор"
            ),
            "menu_item_9": Translation(
                 en="9. Terminal mode (ADB and system commands)",
                 ru="9. Режим терминала (команды ADB и системные)"
            ),
            "terminal_mode_welcome": Translation(
                 en="Terminal mode activated. Type 'help' for available commands or 'exit' to quit.",
                 ru="Режим терминала активирован. Введите 'help' для списка команд или 'exit' для выхода."
            ),
            "terminal_mode_help": Translation(
                 en="You can execute any ADB or system commands.",
                 ru="Вы можете выполнять любые команды ADB или системные команды."
            ),
            "terminal_mode_commands": Translation(
                 en="""Available commands:
        - Any ADB command (e.g., 'adb devices', 'adb shell')
        - System commands
        - 'help' or '?' - Show this help
        - 'clear' - Clear screen
        - 'exit', 'quit', or 'q' - Exit terminal mode""",
                 ru="""Доступные команды:
        - Любые команды ADB (например, 'adb devices', 'adb shell')
        - Системные команды
        - 'help' или '?' - Показать эту справку
        - 'clear' - Очистить экран
        - 'exit', 'quit' или 'q' - Выйти из режима терминала"""
            ),
            "terminal_mode_exit_ctrl_c": Translation(
                 en="Terminal mode deactivated.",
                 ru="Режим терминала деактивирован."
            ),
            "terminal_mode_error": Translation(
                 en="Error executing command: {error}",
                 ru="Ошибка выполнения команды: {error}"
            ),
            "command_error": Translation(
                 en="Command execution failed",
                 ru="Ошибка выполнения команды"
            ),
            "command_execution_error": Translation(
                 en="Error executing command: {error}",
                 ru=""Ошибка выполнения команды: {error}"
            ),
                
            # Main menu items
            "main_menu": Translation(
                en="\nMain Menu:",
                ru="\nГлавное меню:"
            ),
            "menu_item_1": Translation(
                en="1. Change NTP time server by country code",
                ru="1. Изменить сервер времени NTP по коду страны"
            ),
            "menu_item_2": Translation(
                en="2. Change NTP time server to custom",
                ru="2. Изменить сервер времени NTP на пользовательский"
            ),
            "menu_item_3": Translation(
                en="3. Show available country codes and NTP servers (can be copied to clipboard)",
                ru="3. Показать доступные коды стран и серверов NTP (можно копировать в буфер обмена)"
            ),
            "menu_item_4": Translation(
                en="4. Show available alternative NTP servers (can be copied to clipboard)",
                ru="4. Показать доступные альтернативные сервера времени NTP (можно копировать в буфер обмена)"
            ),
            "menu_item_5": Translation(
                en="5. Show current device information",
                ru="5. Показать текущую информацию об устройстве"
            ),
            "menu_item_6": Translation(
                en="6. Server management",
                ru="6. Управление серверами"
            ),
            "menu_item_7": Translation(
                en="7. Connect to device via USB",
                ru="7. Подключиться к устройству через USB"
            ),
            "menu_item_8": Translation(
                en="8. Country codes explanation (can be copied to clipboard)",
                ru="8. Расшифровка кодов стран (можно копировать в буфер обмена)"
            ),
            "menu_item_10": Translation(
                en="10. Exit",
                ru="10. Выход"
            ),
            "menu_prompt": Translation(
                en="Enter menu option number:",
                ru="Введите номер пункта меню:"
            ),
            "enter_device_ip": Translation(
                en="Enter the IP address of your device (TV, Nvidia Shield) (find it in Settings > Network and Internet): ",
                ru="Введите IP-адрес вашего устройства (ТВ, Nvidia Shield) (найдите в Настройки > Сеть и интернет): "
            ),
            "invalid_ip_format": Translation(
                en="Invalid IP address format. Use the format: xxx.xxx.xxx.xxx",
                ru="Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx"
            ),
            "enter_country_code": Translation(
                en="Enter your country code (e.g. ru for Russia, uk for United Kingdom, see country codes menu, q to exit): ",
                ru="Введите код вашей страны (например, ru для России, by для Беларуси, смотри в меню коды стран, для возврата q): "
            ),
            "time_settings_updated": Translation(
                en="Time settings updated successfully!",
                ru="Настройки времени успешно обновлены!"
            ),
            "invalid_country_code": Translation(
                en="Invalid country code",
                ru="Недействительный код страны"
            ),

            "ping_ntp_servers_start": Translation(
                en="Starting NTP server connectivity check...",
                ru="Начинаю проверку связи с NTP-серверами..."
            ),
            
            "ntp_server_reachable": Translation(
                en="NTP server is reachable",
                ru="NTP-сервер доступен"
            ),
            
            "ntp_server_unreachable": Translation(
                en="NTP server is unreachable",
                ru="NTP-сервер недоступен"
            ),

            "connection_error": Translation(
                en="Connection error occurred",
                ru="Произошла ошибка подключения"
            ),

            "ping_servers": Translation(
                en="Ping NTP Servers",
                ru="Пинговать NTP-серверы"
            ),

            "ping_ntp_servers_start": Translation(
                en="Checking NTP server connectivity...",
                ru="Проверка доступности NTP-серверов..."
            ),
            
            # Setup instructions
            "adb_setup": Translation(
                en="1. Enable ADB debugging on your TV or Nvidia Shield:",
                ru="1. Включите отладку ADB на вашем ТВ или Nvidia Shield:"
            ),
            "adb_steps": Translation(
                en="   Settings > Device Preferences > About > Build (press 7 times or more)",
                ru="   Настройки > Настройки устройства > Об устройстве > Сборка (нажмите 7 раз или более)"
            ),
            "adb_network": Translation(
                en="   Then: Device Preferences > Developer options > Network debugging (Enable)",
                ru="   Затем: Настройки устройства > Для разработчиков > Отладка по сети (Включить)"
            ),

            "country_codes_description": Translation(
                en="\nCountry code decryption (can be copied to clipboard):",
                ru="\nРасшифровка кодов стран (копируем в буфер обмена наприм. ru и вставляем в пункте 1 глав. меню):"
            ),
            "country_codes": Translation(
                en="""
at: Austria
ba: Bosnia and Herzegovina
be: Belgium
bg: Bulgaria
by: Belarus
ch: Switzerland
cy: Cyprus
cz: Czech Republic
de: Germany
dk: Denmark
ee: Estonia
es: Spain
fi: Finland
fr: France
gi: Gibraltar
gr: Greece
hr: Croatia
hu: Hungary
ie: Ireland
is: Iceland
it: Italy
li: Liechtenstein
lt: Lithuania
lu: Luxembourg
lv: Latvia
md: Moldova
mk: North Macedonia
nl: Netherlands
no: Norway
pl: Poland
pt: Portugal
ro: Romania
rs: Serbia
ru: Russia
se: Sweden
si: Slovenia
sk: Slovakia
tr: Turkey
ua: Ukraine
uk: United Kingdom
us: United States
ca: Canada
br: Brazil
au: Australia
cn: China
jp: Japan
kz: Kazakhstan
uk: United Kingdom
""",
                ru="""
at: Австрия
ba: Босния и Герцеговина
be: Бельгия
bg: Болгария
by: Беларусь
ch: Швейцария
cy: Кипр
cz: Чехия
de: Германия
dk: Дания
ee: Эстония
es: Испания
fi: Финляндия
fr: Франция
gi: Гибралтар
gr: Греция
hr: Хорватия
hu: Венгрия
ie: Ирландия
is: Исландия
it: Италия
li: Лихтенштейн
lt: Литва
lu: Люксембург
lv: Латвия
md: Молдова
mk: Северная Македония
nl: Нидерланды
no: Норвегия
pl: Польша
pt: Португалия
ro: Румыния
rs: Сербия
ru: Россия
se: Швеция
si: Словения
sk: Словакия
tr: Турция
ua: Украина
uk: Великобритания
us: США
ca: Канада
br: Бразилия
au: Австралия
cn: Китай
jp: Япония
kz: Казахстан
uk: Великобритания
"""
            ),
            "exit_message": Translation(
                en="\nExiting the program...",
                ru="\nВыход из программы..."
            ),
            "invalid_choice": Translation(
                en="Invalid choice. Please try again.",
                ru="Неверный выбор. Пожалуйста, попробуйте еще раз."
            ),
            "error_message": Translation(
                en="Error: {}",
                ru="Ошибка: {}"
            ),
            "unexpected_error": Translation(
                en="\nUnexpected error: {}",
                ru="\nНепредвиденная ошибка: {}"
            ),
            "operation_aborted": Translation(
                en="\nOperation aborted by user",
                ru="\nОперация отменена пользователем"
            ),
        }

    def set_language(self, language: Language) -> None:
        """Set the current language for translations"""
        self.current_language = language

    def get(self, key: str, **kwargs: Any) -> str:
        """
        Get translation for the given key in current language
        Supports format string parameters through kwargs
        """
        translation = self.translations.get(key)
        if not translation:
            return f"Missing translation: {key}"
        
        text = getattr(translation, self.current_language.name.lower())
        return text.format(**kwargs) if kwargs else text

# Create global instance
locales = Locales()

# Function to show disclaimer
def show_disclaimer():
    print(locales.get('disclaimer'))

# Example of setting language and showing the disclaimer
def set_language(language_code: str) -> None:
    """Helper function to set language by code ('en' or 'ru')"""
    try:
        language = Language[language_code.upper()]
        locales.set_language(language)
    except KeyError:
        print(f"Unsupported language code: {language_code}")

# Example usage
set_language('RU')  # Set language to Russian
show_disclaimer()   # Display disclaimer in Russian

set_language('EN')  # Set language to English
show_disclaimer()   # Display disclaimer in English

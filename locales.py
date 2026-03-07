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
        # Default language is Russian
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
                ru="2. Установите время и дату в автоматический режим: Настройки > Настройки устройства > Дата и Время > Автонастройка даты и времени > Использовать время сети"
            ),
            "network_requirement": Translation(
                en="3. Your TV, Nvidia Shield, and PC must be connected to the same network.",
                ru="3. Ваш ТВ, Nvidia Shield и ПК должны быть подключены к одной сети"
            ),
            "reboot_device": Translation(
                en="4. Reboot your TV or Nvidia Shield before using this program.",
                ru="4. Сделайте перезагрузку вашего ТВ или Nvidia Shield перед использованием программы."
            ),
            "firewall_notice": Translation(
                en="5. Allow this program through your firewall when prompted (required for network scanning and ADB connections).",
                ru="5. Разрешите программу в файрволле (брандмауэре) при появлении запроса (необходимо для сканирования сети и ADB-подключений)."
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
            # settings load/save errors
            "settings_load_error": Translation(
                en="Failed to load settings: {error}",
                ru="Не удалось загрузить настройки: {error}"
            ),
            "settings_save_error": Translation(
                en="Failed to save settings: {error}",
                ru="Не удалось сохранить настройки: {error}"
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
            "checking_port": Translation(
                en="Checking port {port} availability on {ip}...",
                ru="Проверка доступности порта {port} на {ip}..."
            ),
            "port_not_available": Translation(
                en="Port {port} is not available on {ip}. The device may be off, ADB is not enabled, or the port is incorrect.",
                ru="Порт {port} недоступен на {ip}. Устройство может быть выключено, ADB не включён или порт указан неверно."
            ),
            "confirm_connection": Translation(
                en="Please confirm the connection on the TV screen if prompted.",
                ru="Пожалуйста, подтвердите подключение на экране ТВ, если появится запрос."
            ),
            "connection_success": Translation(
                en="Successfully connected to {ip}:{port}",
                ru="Подключение к {ip}:{port} выполнено успешно"
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
                "4. You have granted access to the device when prompted on the TV\n"
                "5. This program is allowed through your firewall",
                ru="Убедитесь, что:\n"
                "1. На вашем ТВ включен отладчик ADB\n"
                "2. Ваш ТВ и ПК находятся в одной сети\n"
                "3. IP-адрес введен правильно\n"
                "4. Вы предоставили доступ устройству при появлении запроса на ТВ\n"
                "5. Программа добавлена в исключения файрвола (брандмауэра)"
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
            "device_info": Translation(
                en="Device information:",
                ru="Информация об устройстве:"
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
            "language_loaded_en": Translation(
                en="Language loaded from settings: English",
                ru="Язык загружен из настроек: Английский"
            ),
            "language_loaded_ru": Translation(
                en="Language loaded from settings: Russian",
                ru="Язык загружен из настроек: Русский"
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
            "menu_item_9": Translation(
                 en="9. Auto-setup NTP server (experimental mode)",
                 ru="9. Автоматическая установка NTP-сервера (экспериментальный режим)"
            ),
            "menu_item_10": Translation(
                 en="10. Terminal mode (ADB and system commands)",
                 ru="10. Режим терминала (команды ADB и системные)"
            ),
            "terminal_mode_welcome": Translation(
                 en="Terminal mode activated. Type 'help', 'adb --help' for available commands or 'exit' to quit.",
                 ru="Режим терминала активирован. Введите 'help', 'adb --help' для списка команд или 'exit' для выхода."
            ),
            "terminal_mode_help": Translation(
                 en="\nYou can execute any ADB or system commands."
                 "\nBefore running commands in the terminal, you must connect to the device 'adb connect <ip>:<port>'"
                 "\nAvoid Cyrillic and spaces in file paths or use quotes",
                 ru="\nВы можете выполнять любые команды ADB или системные команды."
                 "\nПрежде чем выполнять команды в терминале необходимо подключиться к устройству 'adb connect <ip>:<port>'"
                 "\nИзбегайте кириллицы и пробелов в путях к файлам или используйте кавычки"
            ),
            "terminal_mode_commands": Translation(
                 en="""Available commands:
                    - Any ADB command (e.g., 'adb devices', 'adb shell')
                    - System commands
                    - 'help', '?', 'adb --help' - Show this help
                    - 'clear' - Clear screen
                    - 'exit', 'quit', or 'q' - Exit terminal mode
            
            Basic commands:
            Connecting to a device:
                adb devices
                    Show connected devices.

                adb connect <ip>:<port>
                    Connect to a device over Wi-Fi.

                adb disconnect [<ip>:<port>]
                    Disconnect from a device (default: all).
            
            Device state information:
                adb get-state
                    Show device state: device, offline, or unauthorized.

                adb get-serialno
                    Get the device's serial number.

                adb get-devpath
                    Get the device's system path.
            
            Working with apps:
            Installing and uninstalling apps:
                adb install <APK path>
                    Install APK on the device.
                    
                adb install-multiple <file paths>
                    Install split APKs.

                adb uninstall <package name>
                    Uninstall an app.

                adb shell pm uninstall --user <user_id> <package_name>
                    Uninstall an app for a specific user.
            
            List installed apps:
                adb shell pm list packages
                    Show all installed packages.

                adb shell pm list packages -s
                    System apps only.

                adb shell pm list packages -3
                    Third-party apps only.
            
            App management:
                adb shell pm enable <package_name>
                    Enable an app.

                adb shell pm disable <package_name>
                    Disable an app.

                adb shell am start -n <package_name>/<activity_name>
                    Start a specific app activity.

                adb shell am force-stop <package_name>
                    Force stop an app.

                adb shell am broadcast -a <action>
                    Send a broadcast intent.
            
            File operations:
            File transfer:
                adb push <local path> <device path>
                    Copy a file to the device.

                adb pull <device path> [local path]
                    Copy a file from the device.
            
            File system management:
                adb shell ls <path>
                    List directory contents.

                adb shell mkdir <path>
                    Create a directory.

                adb shell rm <path>
                    Delete a file or directory.

                adb shell mv <source> <destination>
                    Move or rename a file/directory.
            
            Device operations:
            Rebooting the device:
                adb reboot
                    Reboot the device.

                adb reboot bootloader
                    Reboot into bootloader mode.

                adb reboot recovery
                    Reboot into recovery mode.
            
            Input and interactions:
                adb shell input keyevent <key_code>
                    Simulate a key press (e.g., Home, Back).

                adb shell input text "<text>"
                    Input text.

                adb shell input tap <x> <y>
                    Simulate a tap.

                adb shell input swipe <x1> <y1> <x2> <y2> <duration>
                    Simulate a swipe.
            
            Capturing the screen:
                adb exec-out screencap -p > screen.png
                    Save a screenshot to the PC.

                adb shell screenrecord <path>
                    Record screen video.
            
            System information:
            Logs and diagnostics:
                adb logcat
                    Show device logs.
                    
                adb bugreport > bugreport.zip
                    Save a bug report.
            
            Process information:
                adb shell top
                    Show active processes.

                adb shell ps
                    List all processes.
            
            System details:
                adb shell getprop
                    Show system properties.

                adb shell cat /proc/cpuinfo
                    CPU information.

                adb shell dumpsys
                    General system diagnostics.
            
            Debugging:
            Shell access:
                adb shell
                    Start a shell on the device.
            
            Port management:
                adb forward <local port> <remote port>
                    Forward a port for debugging.

                adb reverse <remote port> <local port>
                    Reverse port forwarding.
            
            Root commands:
                adb root
                    Restart ADB in root mode (if supported).

                adb unroot
                    Restart ADB in non-root mode.
""",
                 ru="""Доступные команды:
                    - Любые команды ADB (например, 'adb devices', 'adb shell')
                    - Системные команды
                    - 'help', '?' или 'adb --help' - Показать эту справку
                    - 'clear' - Очистить экран
                    - 'exit', 'quit' или 'q' - Выйти из режима терминала
            
            Основные команды:
            Подключение к устройству:
                adb devices
                    Показать список подключенных устройств.

                adb connect <ip>:<port>
                    Подключиться к устройству по Wi-Fi.

                adb disconnect [<ip>:<port>]
                    Отключиться от устройства (по умолчанию от всех).

             Информация о состоянии:
                    adb get-state
                        Показать состояние устройства: device, offline или unauthorized.

                    adb get-serialno
                        Получить серийный номер устройства.

                    adb get-devpath
                        Получить путь к устройству в системе.
            
                Работа с приложениями:
                Установка и удаление приложений:
                    adb install <путь к APK>
                        Установить APK на устройство.

                    adb install-multiple <путь к файлам>
                        Установить APK с несколькими компонентами (split APK).

                    adb uninstall <имя пакета>
                        Удалить приложение.

                    adb shell pm uninstall --user <user_id> <package_name>
                        Удалить приложение для конкретного пользователя.
            
                Список установленных приложений:
                    adb shell pm list packages
                        Показать все установленные пакеты.

                    adb shell pm list packages -s
                        Только системные приложения.

                    adb shell pm list packages -3
                        Только сторонние приложения.
            
                Управление приложениями:
                    adb shell pm enable <package_name>
                        Включить приложение.

                    adb shell pm disable <package_name>
                        Отключить приложение.

                    adb shell am start -n <package_name>/<activity_name>
                        Запустить определенное Activity приложения.

                    adb shell am force-stop <package_name>
                        Принудительно остановить приложение.

                    adb shell am broadcast -a <action>
                        Отправить широковещательное сообщение.
            
                Работа с файлами:
                Передача файлов:
                    adb push <локальный путь> <путь на устройстве>
                        Скопировать файл на устройство.

                    adb pull <путь на устройстве> [локальный путь]
                        Скопировать файл с устройства.
            
                Работа с файловой системой:
                    adb shell ls <путь>
                        Просмотреть содержимое каталога.

                    adb shell mkdir <путь>
                        Создать каталог.

                    adb shell rm <путь>
                        Удалить файл или каталог.

                    adb shell mv <откуда> <куда>
                        Переместить или переименовать файл/каталог.
            
                Работа с устройством:
                Перезагрузка устройства:
                    adb reboot
                        Перезагрузить устройство.

                    adb reboot bootloader
                        Перезагрузить в режим загрузчика.

                    adb reboot recovery
                        Перезагрузить в режим восстановления.
            
                Управление состояниями:
                    adb shell input keyevent <key_code>
                        Отправить клавишу (например, Home, Back).

                    adb shell input text "<текст>"
                        Ввести текст.

                    adb shell input tap <x> <y>
                        Эмулировать нажатие.

                    adb shell input swipe <x1> <y1> <x2> <y2> <duration>
                        Эмулировать свайп.
            
                Захват экрана:
                    adb exec-out screencap -p > screen.png
                        Сохранить снимок экрана на ПК.

                    adb shell screenrecord <путь>
                        Записать видео с экрана.
            
                Системная информация:
                Журналы и диагностика:
                    adb logcat
                        Вывести логи устройства.

                    adb bugreport > bugreport.zip
                        Сохранить отчет о состоянии устройства.
            
                Информация о процессах:
                    adb shell top
                        Показать активные процессы.

                    adb shell ps
                        Список всех процессов.
            
                Получение информации о системе:
                    adb shell getprop
                        Показать системные свойства устройства.

                    adb shell cat /proc/cpuinfo
                        Информация о процессоре.

                    adb shell dumpsys
                        Общая диагностика устройства.
            
                Отладка:
                Открытие shell:
                    adb shell
                        Запустить терминал на устройстве.
            
                Управление портами:
                    adb forward <локальный порт> <удаленный порт>
                        Перенаправить порт для отладки.

                    adb reverse <удаленный порт> <локальный порт>
                        Перенаправить порт в обратном направлении.
            
                Запуск команд от имени root:
                    adb root
                        Перезапустить ADB в режиме root (если устройство поддерживает).
                        
                    adb unroot
                        Перезапустить ADB в обычном режиме."""
            ),
            "terminal_mode_exit_ctrl_c": Translation(
                 en="Terminal mode deactivated.",
                 ru="Режим терминала деактивирован."
            ),
            "terminal_mode_error": Translation(
                 en="Error executing command: {error}",
                 ru="Ошибка выполнения команды: {error}"
            ),
            "terminal_mode_critical_error": Translation(
                 en="Critical error in terminal mode: {error}",
                 ru="Критическая ошибка в режиме терминала: {error}"
            ),
            "command_error": Translation(
                 en="Command execution failed",
                 ru="Ошибка выполнения команды"
            ),
            "command_execution_error": Translation(
                 en="Error executing command: {error}",
                 ru="Ошибка выполнения команды: {error}"
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
                en="3. Show country codes with country names and NTP servers (can be copied to clipboard)",
                ru="3. Показать коды стран с названиями и NTP-серверами (можно копировать в буфер обмена)"
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
                en="6. Ping NTP servers",
                ru="6. Пинговать NTP-серверы"
            ),
            "menu_item_7": Translation(
                en="7. Server management",
                ru="7. Управление серверами"
            ),
            "menu_item_8": Translation(
                en="8. Network scan & batch NTP update",
                ru="8. Сканирование сети и групповое обновление NTP"
            ),
            "menu_item_11": Translation(
                en="0. Exit",
                ru="0. Выход"
            ),
            "menu_prompt": Translation(
                en="Enter menu option number:",
                ru="Введите номер пункта меню:"
            ),
            "enter_device_ip": Translation(
                en="Enter the IP address of your device (TV, Nvidia Shield) (find it in Settings > Network and Internet): ",
                ru="Введите IP-адрес вашего устройства (ТВ, Nvidia Shield) (найдите в Настройки > Сеть и интернет): "
            ),
            "enter_device_ip_with_saved": Translation(
                en="Enter the IP address of your device (press Enter to use saved: {saved_ip}): ",
                ru="Введите IP-адрес устройства (нажмите Enter для использования сохранённого: {saved_ip}): "
            ),
            "invalid_ip_format": Translation(
                en="Invalid IP address format. Use the format: xxx.xxx.xxx.xxx or xxx.xxx.xxx.xxx:port (default port: 5555)",
                ru="Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx или xxx.xxx.xxx.xxx:порт (порт по умолчанию: 5555)"
            ),
            "invalid_ntp_server_format": Translation(
                en="Invalid NTP server format. Use a valid domain name (e.g., time.google.com) or IP address.",
                ru="Неверный формат NTP-сервера. Используйте корректное доменное имя (например, time.google.com) или IP-адрес."
            ),
            "enter_country_code": Translation(
                en="Enter your country code (e.g. us for USA, uk for United Kingdom, see country codes menu, q to exit): ",
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
                en="6. Ping NTP Servers",
                ru="6. Пинговать NTP-серверы"
            ),

            "ping_results_summary": Translation(
                en="Ping Results Summary:",
                ru="Итоги проверки:"
            ),
            "total_servers": Translation(
                en="Total servers checked",
                ru="Всего проверено серверов"
            ),
            "reachable_servers": Translation(
                en="Reachable",
                ru="Доступно"
            ),
            "unreachable_servers": Translation(
                en="Unreachable",
                ru="Недоступно"
            ),

            "ping_ntp_servers_start": Translation(
                en="Checking NTP server connectivity (may take time)...",
                ru="Проверка доступности NTP-серверов (может занять время)..."
            ),

            "country_codes_description": Translation(
                en="\nCountry code description (can be copied to clipboard):",
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
uk: United Kingdom
us: United States
ca: Canada
br: Brazil
au: Australia
cn: China
jp: Japan
kz: Kazakhstan
ae: United Arab Emirates
am: Armenia
az: Azerbaijan
bd: Bangladesh
bh: Bahrain
ge: Georgia
hk: Hong Kong
id: Indonesia
il: Israel
in: India
ir: Iran
kg: Kyrgyzstan
kh: Cambodia
kr: Korea
lk: Sri Lanka
mn: Mongolia
mv: Maldives
my: Malaysia
np: Nepal
ph: Philippines
pk: Pakistan
ps: Palestinian Territory
qa: Qatar
sa: Saudi Arabia
sg: Singapore
th: Thailand
tj: Tajikistan
tw: Taiwan
uz: Uzbekistan
ua: Ukraine
vn: Vietnam
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
uk: Великобритания
us: США
ca: Канада
br: Бразилия
au: Австралия
cn: Китай
jp: Япония
kz: Казахстан
ae: Объединённые Арабские Эмираты
am: Армения
az: Азербайджан
bd: Бангладеш
bh: Бахрейн
ge: Грузия
hk: Гонконг
id: Индонезия
il: Израиль
in: Индия
ir: Иран
kg: Кыргызстан
kh: Камбоджа
kr: Корея
lk: Шри-Ланка
mn: Монголия
mv: Мальдивы
my: Малайзия
np: Непал
ph: Филиппины
pk: Пакистан
ps: Палестинская территория
qa: Катар
sa: Саудовская Аравия
sg: Сингапур
th: Таиланд
tj: Таджикистан
tw: Тайвань
uz: Узбекистан
ua: Украина
vn: Вьетнам

"""
            ),
            # ─── Merged country codes display ───────────────────────────
            "available_country_codes_full": Translation(
                en="\nAvailable country codes (code: country -> NTP server):",
                ru="\nДоступные коды стран (код: страна -> NTP-сервер):"
            ),

            # ─── Interactive hints ───────────────────────────────────────
            "hint_matching": Translation(
                en="Matching country codes:",
                ru="Подходящие коды стран:"
            ),
            "hint_no_match": Translation(
                en="No matching country codes found",
                ru="Совпадений не найдено"
            ),
            "hint_type_hint": Translation(
                en="Tip: enter ? to search by partial name, e.g. ?rus or ?uni",
                ru="Подсказка: введите ? для поиска по названию, например ?рос или ?сша"
            ),
            "enter_country_code_search": Translation(
                en="Enter country code or ?<text> to search: ",
                ru="Введите код страны или ?<текст> для поиска: "
            ),

            # ─── Network scan ────────────────────────────────────────────
            "submenu_scan_batch": Translation(
                en="\nNetwork scan & batch operations:",
                ru="\nСканирование сети и групповые операции:"
            ),
            "submenu_scan": Translation(
                en="1. Scan local network for Android TV devices",
                ru="1. Сканировать локальную сеть на устройства Android TV"
            ),
            "submenu_connect_discovered": Translation(
                en="2. Connect to discovered device",
                ru="2. Подключиться к найденному устройству"
            ),
            "submenu_batch": Translation(
                en="3. Batch NTP update (all discovered or entered IPs)",
                ru="3. Групповое обновление NTP (все найденные или введённые IP)"
            ),
            "submenu_time_sync": Translation(
                en="4. Show device time sync status",
                ru="4. Статус синхронизации времени устройства"
            ),
            "submenu_back": Translation(
                en="5. Back to main menu",
                ru="5. Назад в главное меню"
            ),
            "enter_device_ip_scan": Translation(
                en="Enter IP or IP:port if port differs from 5555 (press Enter for saved: {saved_ip}, or 's' to scan network): ",
                ru="Введите IP или IP:порт если порт отличается от 5555 (Enter для сохранённого: {saved_ip}, или 's' для сканирования): "
            ),
            "enter_device_ip_scan_no_saved": Translation(
                en="Enter IP or IP:port if port differs from 5555 (or 's' to scan network): ",
                ru="Введите IP или IP:порт если порт отличается от 5555 (или 's' для сканирования сети): "
            ),
            "scan_select_device": Translation(
                en="Select device number (or Enter to cancel): ",
                ru="Выберите номер устройства (или Enter для отмены): "
            ),
            "scan_start": Translation(
                en="Scanning network {network} for open ADB port 5555...",
                ru="Сканирование сети {network} на открытый порт ADB 5555..."
            ),
            "scan_progress": Translation(
                en="  Progress: {checked}/{total} checked, {found} found",
                ru="  Прогресс: {checked}/{total} проверено, {found} найдено"
            ),
            "scan_found": Translation(
                en="Found {count} device(s) with open ADB port:",
                ru="Найдено {count} устройств с открытым портом ADB:"
            ),
            "scan_none": Translation(
                en="No Android TV devices found on local network",
                ru="Устройства Android TV в локальной сети не найдены"
            ),
            "scan_local_ip_error": Translation(
                en="Could not determine local IP address",
                ru="Не удалось определить локальный IP-адрес"
            ),
            "scan_not_private": Translation(
                en="Your IP address ({ip}) is not in a supported local network range (192.168.x.x or 10.x.x.x). Network scan is only available on local networks.",
                ru="Ваш IP-адрес ({ip}) не входит в поддерживаемый диапазон локальных сетей (192.168.x.x или 10.x.x.x). Сканирование доступно только в локальных сетях."
            ),
            "scan_net_detected": Translation(
                en="Network auto-detected: {network} ({hosts} hosts)",
                ru="Сеть определена автоматически: {network} ({hosts} хостов)"
            ),
            "scan_net_fallback": Translation(
                en="Could not detect subnet mask, using fallback range: {network}",
                ru="Не удалось определить маску подсети, используется запасной диапазон: {network}"
            ),
            "scan_wide_offer": Translation(
                en="No devices found in {narrow}. Scan wider range {wide}? (y/n): ",
                ru="В подсети {narrow} устройства не найдены. Сканировать расширенный диапазон {wide}? (y/n): "
            ),
            "scan_firewall_hint": Translation(
                en="Hint: Make sure this program is allowed through your firewall (Windows Defender, iptables, etc.).",
                ru="Подсказка: Убедитесь, что программа добавлена в исключения файрвола (Брандмауэр Windows, iptables и т.д.)."
            ),
            "no_discovered_devices": Translation(
                en="No discovered devices. Run scan first (option 1).",
                ru="Нет найденных устройств. Сначала запустите сканирование (пункт 1)."
            ),

            # ─── Batch NTP ───────────────────────────────────────────────
            "batch_ntp_title": Translation(
                en="\nBatch NTP server update:",
                ru="\nГрупповое обновление NTP-сервера:"
            ),
            "batch_enter_ntp": Translation(
                en="Enter NTP server for all devices (or q to cancel): ",
                ru="Введите NTP-сервер для всех устройств (или q для отмены): "
            ),
            "batch_enter_ips": Translation(
                en="Enter IP addresses separated by comma, or press Enter to use {count} discovered device(s): ",
                ru="Введите IP-адреса через запятую или Enter для {count} найденных устройств: "
            ),
            "batch_no_targets": Translation(
                en="No target devices. Run network scan first or enter IPs manually.",
                ru="Нет целевых устройств. Сначала выполните сканирование или введите IP вручную."
            ),
            "batch_connecting": Translation(
                en="  [{idx}/{total}] Connecting to {ip}...",
                ru="  [{idx}/{total}] Подключение к {ip}..."
            ),
            "batch_success": Translation(
                en="  OK  {ip}: NTP set to {server}",
                ru="  ОК  {ip}: NTP установлен на {server}"
            ),
            "batch_failed": Translation(
                en="  ERR {ip}: {error}",
                ru="  ОШ  {ip}: {error}"
            ),
            "batch_summary": Translation(
                en="Batch complete: {success} succeeded, {failed} failed (total {total})",
                ru="Завершено: {success} успешно, {failed} ошибок (всего {total})"
            ),

            # ─── Device time sync ────────────────────────────────────────
            "device_time_title": Translation(
                en="\nDevice time synchronization status:",
                ru="\nСтатус синхронизации времени устройства:"
            ),
            "device_time": Translation(
                en="  Device time : {time}",
                ru="  Время устройства: {time}"
            ),
            "pc_time": Translation(
                en="  PC time     : {time}",
                ru="  Время ПК        : {time}"
            ),
            "time_in_sync": Translation(
                en="  Status: Synchronized (difference < 60 sec)",
                ru="  Статус: Синхронизировано (разница < 60 сек)"
            ),
            "time_out_of_sync": Translation(
                en="  Status: OUT OF SYNC — difference: {diff}",
                ru="  Статус: РАССИНХРОНИЗИРОВАНО — разница: {diff}"
            ),
            "device_time_error": Translation(
                en="Could not read device time: {error}",
                ru="Не удалось прочитать время устройства: {error}"
            ),

            # ─── Export / Import settings ────────────────────────────────
            "export_import_menu": Translation(
                en="Export / Import settings",
                ru="Экспорт / Импорт настроек"
            ),
            "export_path_prompt": Translation(
                en="Export file path (Enter = backup.json): ",
                ru="Путь для экспорта (Enter = backup.json): "
            ),
            "import_path_prompt": Translation(
                en="Import file path: ",
                ru="Путь к файлу импорта: "
            ),
            "export_success": Translation(
                en="Settings exported to: {path}",
                ru="Настройки экспортированы в: {path}"
            ),
            "export_failed": Translation(
                en="Export failed: {error}",
                ru="Ошибка экспорта: {error}"
            ),
            "import_success": Translation(
                en="Settings imported from: {path}",
                ru="Настройки импортированы из: {path}"
            ),
            "import_failed": Translation(
                en="Import failed: {error}",
                ru="Ошибка импорта: {error}"
            ),
            "import_not_found": Translation(
                en="File not found: {path}",
                ru="Файл не найден: {path}"
            ),
            "choice_export": Translation(
                en="1. Export settings to file",
                ru="1. Экспортировать настройки в файл"
            ),
            "choice_import": Translation(
                en="2. Import settings from file",
                ru="2. Импортировать настройки из файла"
            ),
            "choice_back": Translation(
                en="3. Back",
                ru="3. Назад"
            ),

            # ─── Windows launcher ────────────────────────────────────────
            "windows_press_enter": Translation(
                en="\nPress Enter to close this window...",
                ru="\nНажмите Enter для закрытия окна..."
            ),

            "ntp_verify_before_apply": Translation(
                en="Verifying NTP server time sync (3 attempts)...",
                ru="Проверка синхронизации времени NTP-сервера (3 попытки)..."
            ),
            "ntp_verify_detailed": Translation(
                en="NTP server {server} is working correctly:\n  RTT: {rtt:.1f}ms | Success: {success:.0f}% | Offset: {offset:.3f}s",
                ru="NTP-сервер {server} работает корректно:\n  RTT: {rtt:.1f}мс | Успех: {success:.0f}% | Смещение: {offset:.3f}с"
            ),
            "ntp_verify_failed": Translation(
                en="Warning: NTP server {server} is not reachable (0/3 attempts). Apply anyway? (y/n): ",
                ru="Внимание: NTP-сервер {server} недоступен (0/3 попыток). Применить всё равно? (y/n): "
            ),
            "ntp_verify_bad_offset": Translation(
                en="Warning: NTP server {server} responds but time offset is too large ({offset:.1f}s). Server may not sync time correctly.",
                ru="Внимание: NTP-сервер {server} отвечает, но смещение времени слишком большое ({offset:.1f}с). Сервер может некорректно синхронизировать время."
            ),
            "ntp_verify_force_apply": Translation(
                en="Apply this server anyway? (y/n): ",
                ru="Всё равно применить этот сервер? (y/n): "
            ),
            "connection_reused": Translation(
                en="Using existing connection to {ip}",
                ru="Используется существующее подключение к {ip}"
            ),
            "reconnect_or_reuse": Translation(
                en="Already connected to {ip}. Use existing connection? (y/n): ",
                ru="Уже подключено к {ip}. Использовать текущее подключение? (y/n): "
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
                en="Error: {error}",
                ru="Ошибка: {error}"
            ),
            "unexpected_error": Translation(
                en="\nUnexpected error: {error}",
                ru="\nНепредвиденная ошибка: {error}"
            ),
            "operation_aborted": Translation(
                en="\nOperation aborted by user",
                ru="\nОперация отменена пользователем"
            ),

            # ─── Ping NTP menu item (no number prefix) ───────────────
            "ping_ntp_menu": Translation(
                en="Ping NTP servers",
                ru="Пинговать NTP-серверы"
            ),

            # ─── Country code format hints ────────────────────────────
            "country_code_format_hint": Translation(
                en="Note: Enter only the 2-letter country code (e.g. ru), NOT the full server address (e.g. ru.pool.ntp.org)",
                ru="Примечание: Вводите только 2-буквенный код страны (напр. ru), а НЕ полный адрес сервера (напр. ru.pool.ntp.org)"
            ),
            "country_code_wrong_format": Translation(
                en="You entered a full NTP server address. Enter only the 2-letter country code (e.g. cy, not cy.pool.ntp.org)",
                ru="Вы ввели полный адрес NTP-сервера. Введите только 2-буквенный код страны (напр. cy, а не cy.pool.ntp.org)"
            ),

            # ─── NTP server format hint ───────────────────────────────
            "ntp_format_hint": Translation(
                en="Format: domain name (e.g. time.google.com, ru.pool.ntp.org) or IP address (e.g. 192.168.1.1)",
                ru="Формат: доменное имя (напр. time.google.com, ru.pool.ntp.org) или IP-адрес (напр. 192.168.1.1)"
            ),

            # ─── Auto-setup mode ─────────────────────────────────────
            "auto_scanning_network": Translation(
                en="\n[Auto] Scanning network for Android TV devices...",
                ru="\n[Авто] Сканирование сети для поиска Android TV устройств..."
            ),
            "auto_found_device": Translation(
                en="[Auto] Found {count} device(s). Connecting to {ip}...",
                ru="[Авто] Найдено {count} устройств. Подключение к {ip}..."
            ),
            "auto_select_device": Translation(
                en="[Auto] Multiple devices found. Select device number: ",
                ru="[Авто] Найдено несколько устройств. Выберите номер устройства: "
            ),
            "auto_no_devices": Translation(
                en="[Auto] No devices found on network. Check that ADB debugging is enabled.",
                ru="[Авто] Устройства не найдены в сети. Проверьте, что отладка ADB включена."
            ),
            "auto_confirm_tv": Translation(
                en="[Auto] Please confirm the connection on the TV screen if prompted...",
                ru="[Авто] Подтвердите подключение на экране ТВ, если появится запрос..."
            ),
            "auto_checking_ntp": Translation(
                en="[Auto] Quick-testing NTP servers to find the best one for you...",
                ru="[Авто] Быстрая проверка NTP-серверов для выбора оптимального..."
            ),
            "auto_best_server": Translation(
                en="[Auto] Recommended server: {server} (RTT: {rtt:.1f}ms)",
                ru="[Авто] Рекомендуемый сервер: {server} (RTT: {rtt:.1f}мс)"
            ),
            "auto_top_servers": Translation(
                en="[Auto] Top-5 best servers (by reliability and speed):",
                ru="[Авто] Топ-5 лучших серверов (по надёжности и скорости):"
            ),
            "auto_confirm_install": Translation(
                en="[Auto] Install {server} on the device? (y/n): ",
                ru="[Авто] Установить {server} на устройство? (y/n): "
            ),
            "auto_installed": Translation(
                en="[Auto] NTP server {server} successfully installed on device!",
                ru="[Авто] NTP-сервер {server} успешно установлен на устройство!"
            ),
            "auto_cancelled": Translation(
                en="[Auto] Installation cancelled by user.",
                ru="[Авто] Установка отменена пользователем."
            ),
            "auto_no_reachable_servers": Translation(
                en="[Auto] No reachable NTP servers found. Check your internet connection.",
                ru="[Авто] Не найдено доступных NTP-серверов. Проверьте подключение к интернету."
            ),
            "auto_choose_from_top": Translation(
                en="[Auto] Enter server number from the list (or Enter for recommended #1): ",
                ru="[Авто] Введите номер сервера из списка (или Enter для рекомендуемого №1): "
            ),
            "auto_checking_progress": Translation(
                en="  [{checked}/{total}] checked, {found} reachable",
                ru="  [{checked}/{total}] проверено, {found} доступно"
            ),
            "auto_server_success": Translation(
                en="Success",
                ru="Успех"
            ),
            "auto_region_detected": Translation(
                en="[Auto] Your region detected: {timezone} ({region})",
                ru="[Авто] Ваш регион определён: {timezone} ({region})"
            ),
            "auto_priority_count": Translation(
                en="[Auto] {count} regional servers will be prioritized",
                ru="[Авто] {count} региональных серверов будут проверены в приоритете"
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

    def get_en(self, key: str, **kwargs: Any) -> str:
        """
        Get English translation for the given key (for logging purposes)
        Always returns English regardless of current language setting
        """
        translation = self.translations.get(key)
        if not translation:
            return f"Missing translation: {key}"

        text = translation.en
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

# Note: Do not call set_language/show_disclaimer at module level
# to avoid printing disclaimers on every import

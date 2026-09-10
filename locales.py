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
            "terminal_output_truncated": Translation(
                en="Only the final part of the command output is retained.",
                ru="Сохранена заключительная часть вывода команды.",
            ),
            "favorite_remove_failed": Translation(
                en="Could not save the removal. The favorite list has been restored.",
                ru="Не удалось сохранить удаление. Список избранного восстановлен.",
            ),
            "import_too_large": Translation(en="The backup exceeds 1 MiB of text.", ru="Размер текста резервной копии превышает 1 МиБ."),
            "import_version_unsupported": Translation(en="Unsupported backup format version.", ru="Эта версия формата резервной копии не поддерживается."),
            "import_rollback_failed": Translation(
                en="Import failed and the previous server list could not be restored on disk. The original list remains in memory; check file access before saving again.",
                ru="Импорт не завершён; прежний список серверов не удалось восстановить на диске. Он сохранён в памяти. Проверьте доступ к файлу перед повторным сохранением.",
            ),
            "import_adb_restart": Translation(
                en="The imported ADB server port will take effect after restarting the application.",
                ru="Импортированный порт ADB-сервера будет использован после перезапуска программы.",
            ),
            "time_comparison_uncertain": Translation(
                en="The reading delay does not allow a reliable comparison with the PC clock.",
                ru="Задержка чтения не позволяет достоверно оценить расхождение с часами ПК.",
            ),
            "menu_item_usb": Translation(en="12. Connect over USB", ru="12. Подключиться по USB"),
            "usb_select_hint": Translation(
                en="Enter u to select a USB device (no IP needed). USB is also available in main menu item 12.",
                ru="Введите u для выбора USB-устройства (IP не нужен). USB также доступен в пункте 12 главного меню.",
            ),
            "usb_setup_hint": Translation(
                en="Enable USB debugging on the target and connect a data cable to its supported device/OTG port. Confirm the RSA prompt on the target. Windows may need an OEM ADB driver; Linux may need udev rules. Another ADB server may already own USB: close its session before retrying.",
                ru="Включите USB-отладку на целевом устройстве и подключите кабель передачи данных к его порту device/OTG. Подтвердите запрос RSA на устройстве. Windows может требовать ADB-драйвер производителя, Linux — правила udev. Другой ADB-сервер может уже занимать USB: закройте его сессию перед повторной попыткой.",
            ),
            "usb_no_devices": Translation(en="No USB ADB devices found. Check the cable, USB role, debugging and drivers.", ru="USB ADB-устройства не найдены. Проверьте кабель, роль USB, отладку и драйверы."),
            "usb_pick_prompt": Translation(en="Enter a device number from the list and press Enter to connect (r to refresh, q to cancel): ", ru="Введите номер устройства из списка и нажмите Enter для подключения (r — обновить список, q — отмена): "),
            "usb_reuse_prompt": Translation(en="USB is selected. Enter to reuse, n for another device, q to cancel: ", ru="Выбрано USB-подключение. Enter — использовать, n — другое устройство, q — отмена: "),
            "usb_list_failed": Translation(en="Cannot list USB devices: {error}. Use the bundled Platform Tools 37.0.1 or newer.", ru="Не удалось получить USB-устройства: {error}. Используйте встроенные Platform Tools 37.0.1 или новее."),
            "usb_disconnected": Translation(en="The selected USB device is disconnected or has changed. Refresh the list and select it again.", ru="Выбранное USB-устройство отключено или изменилось. Обновите список и выберите его заново."),
            "usb_authorize": Translation(en="Unlock the target and allow USB debugging in the RSA prompt, then refresh and reconnect.", ru="Разблокируйте целевое устройство и разрешите USB-отладку в запросе RSA, затем обновите список и подключитесь снова."),
            "usb_no_permissions": Translation(en="No USB access. On Linux install the distribution's Android udev rules and check group access; reconnect the cable. Do not run the application as root.", ru="Нет доступа к USB. В Linux установите правила Android udev из вашего дистрибутива и проверьте группы доступа; переподключите кабель. Запуск приложения от root не требуется."),
            "usb_not_ready": Translation(en="USB device is not ready ({state}). Boot Android normally and reconnect the cable.", ru="USB-устройство не готово ({state}). Загрузите Android в обычном режиме и переподключите кабель."),
            "usb_connected": Translation(en='Device "{device}" successfully connected over USB.', ru='Устройство «{device}» успешно подключено по USB.'),
            "usb_connection_reused": Translation(en='Device "{device}" is already connected over USB. The connection has been checked and is ready to use.', ru='Устройство «{device}» уже подключено по USB. Связь проверена, подключение готово к работе.'),
            "usb_next_steps": Translation(en='In the main menu, choose 5 for device information, or 1 / 2 to change the NTP server. When asked to use the selected USB connection, press Enter.', ru='В главном меню выберите 5 для просмотра сведений об устройстве или 1 / 2 для изменения NTP-сервера. Когда программа предложит использовать выбранное USB-подключение, нажмите Enter.'),
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
            "app_version": Translation(
                en="Version {version}",
                ru="Версия {version}"
            ),
            "project_source_code": Translation(
                en="Project source code:",
                ru="Исходный код проекта:"
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
                en="   Then: Developer options > USB debugging (cable), Network debugging or Wireless debugging (Wi-Fi)",
                ru="   Затем: Для разработчиков > Отладка по USB (кабель), Отладка по сети или Беспроводная отладка (Wi-Fi)"
            ),
            "auto_time_date": Translation(
                en="2. Set time and date to automatic mode:",
                ru="2. Установите время и дату в автоматический режим: Настройки > Настройки устройства > Дата и Время > Автонастройка даты и времени > Использовать время сети"
            ),
            "network_requirement": Translation(
                en="3. Network ADB requires the same network. USB ADB uses a data cable; checking NTP servers still requires Internet access.",
                ru="3. Для сетевого ADB нужна общая сеть. Для USB ADB используется кабель передачи данных; проверка NTP-серверов по-прежнему требует доступа в интернет."
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
            "adb_pubkey_restored": Translation(
                en="Public ADB key restored from the existing private key",
                ru="Публичный ключ ADB восстановлен из существующего приватного ключа"
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
            "enter_scan_port": Translation(
                en="ADB port to scan (Enter for {default}, 'q' to cancel): ",
                ru="Порт ADB для сканирования (Enter — {default}, 'q' — отмена): "
            ),
            "invalid_port": Translation(
                en="Invalid port. Allowed values: 1-65535.",
                ru="Некорректный порт. Допустимы значения 1-65535."
            ),
            "adb_probe_failed": Translation(
                en="Port {port} on {ip} is open, but it is not an ADB daemon (no valid response).",
                ru="Порт {port} на {ip} открыт, но это не ADB-демон (нет корректного ответа)."
            ),
            "adb_protocol_tls_detected": Translation(
                en="Device {ip} uses Android 11+ wireless debugging (encrypted connection).",
                ru="На устройстве {ip} включена беспроводная отладка Android 11+ (шифрованное соединение)."
            ),
            "adb_tls_connect_try": Translation(
                en="Connecting via the bundled adb (the device must be paired; see main menu item 11)...",
                ru="Подключение через встроенный adb (устройство должно быть спарено — пункт 11 главного меню)..."
            ),
            "adb_tls_connect_ok": Translation(
                en="Connected to {ip} over an encrypted connection.",
                ru="Подключено к {ip} по шифрованному соединению."
            ),
            "adb_tls_pairing_required": Translation(
                en="Could not connect to {ip}: the device requires pairing with a code. Use main menu item 11 (Wireless debugging) to pair it. Details: {error}",
                ru="Не удалось подключиться к {ip}: устройство требует спаривания по коду. Спарьте его через пункт 11 главного меню («Беспроводная отладка»). Подробности: {error}"
            ),
            "wireless_menu": Translation(
                en="\nWireless debugging (Android 11+):",
                ru="\nБеспроводная отладка (Android 11+):"
            ),
            "wireless_pair_device": Translation(
                en="Pair a device with a code",
                ru="Спарить устройство по коду"
            ),
            "wireless_mdns_scan": Translation(
                en="Find devices via mDNS",
                ru="Найти устройства по mDNS"
            ),
            "enter_pairing_host": Translation(
                en="Enter the pairing address IP or IP:port from the TV screen ('q' to cancel): ",
                ru="Введите адрес спаривания IP или IP:порт с экрана ТВ ('q' — отмена): "
            ),
            "enter_connect_host": Translation(
                en="Enter the device address IP or IP:port ('q' to cancel): ",
                ru="Введите адрес устройства IP или IP:порт ('q' — отмена): "
            ),
            "enter_pairing_code": Translation(
                en="Enter the 6-digit pairing code from the TV screen ('q' to cancel): ",
                ru="Введите 6-значный код спаривания с экрана ТВ ('q' — отмена): "
            ),
            "invalid_pairing_code": Translation(
                en="Invalid pairing code: exactly 6 digits are expected.",
                ru="Некорректный код спаривания: ожидается ровно 6 цифр."
            ),
            "pairing_in_progress": Translation(
                en="Pairing with {ip}...",
                ru="Спаривание с {ip}..."
            ),
            "pairing_success": Translation(
                en="Paired with {ip}. Now connect using the connection port (it differs from the pairing port).",
                ru="Устройство {ip} спарено. Теперь подключаемся по порту подключения (он отличается от порта спаривания)."
            ),
            "pairing_failed": Translation(
                en="Pairing with {ip} failed: {error}",
                ru="Не удалось спарить устройство {ip}: {error}"
            ),
            "mdns_searching": Translation(
                en="Searching for devices via mDNS...",
                ru="Поиск устройств по mDNS..."
            ),
            "mdns_found": Translation(
                en="Found devices: {count}",
                ru="Найдено устройств: {count}"
            ),
            "mdns_none": Translation(
                en="mDNS found no devices. Enable network debugging or wireless debugging on the device; for pairing, the pairing dialog must be open on its screen.",
                ru="mDNS не нашёл устройств. Включите на устройстве отладку по сети или беспроводную отладку; для спаривания на его экране должен быть открыт диалог спаривания."
            ),
            "mdns_unavailable": Translation(
                en="mDNS discovery is unavailable: the bundled adb has no mDNS backend and the zeroconf library is not installed. Enter the address manually.",
                ru="Обнаружение по mDNS недоступно: у встроенного adb нет mDNS-бэкенда, а библиотека zeroconf не установлена. Введите адрес вручную."
            ),
            "mdns_pick_or_enter": Translation(
                en="Devices found on the network — enter a number to connect:",
                ru="Найдены устройства в сети — введите номер для подключения:"
            ),
            "mdns_pairing_hint": Translation(
                en="  These need a pairing code first: use the wireless debugging menu.",
                ru="  Им сначала нужен код спаривания: см. меню беспроводной отладки."
            ),
            "mdns_none_auto": Translation(
                en="No devices found via mDNS. Enter the address by hand, 's' to scan the network, or 'm' to search again.",
                ru="По mDNS ничего не найдено. Введите адрес вручную, 's' — сканировать сеть, 'm' — искать снова."
            ),
            "mdns_service_pairing": Translation(
                en="Devices awaiting pairing (the pairing dialog is open on screen):",
                ru="Устройства, ожидающие спаривания (на экране открыт диалог спаривания):"
            ),
            "mdns_service_connect": Translation(
                en="Paired devices ready to connect:",
                ru="Спаренные устройства, готовые к подключению:"
            ),
            "adb_shell_command_failed": Translation(
                en="Failed to run the command on the device: {error}",
                ru="Не удалось выполнить команду на устройстве: {error}"
            ),
            "confirm_connection": Translation(
                en="Please confirm the connection on the TV screen if prompted.",
                ru="Пожалуйста, подтвердите подключение на экране ТВ, если появится запрос."
            ),
            "connection_prompt_sent": Translation(
                en="Authorization request sent to the device (attempt {attempt}). Confirm it on the TV screen.",
                ru="Запрос авторизации отправлен на устройство (попытка {attempt}). Подтвердите его на экране ТВ."
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
            "ntp_system_default": Translation(
                en='System time source',
                ru='Системный источник времени'
            ),
            "ntp_before_after": Translation(
                en='NTP setting saved: {before} → {after}',
                ru='Настройка NTP сохранена: {before} → {after}'
            ),
            "ntp_clock_before_after": Translation(
                en='Clock difference from this computer before → after: {before} → {after}. This is not proof of NTP synchronization.',
                ru='Расхождение с часами этого компьютера до → после: {before} → {after}. Это не подтверждение синхронизации по NTP.'
            ),
            "ntp_restart_required": Translation(
                en='Android 6–10 usually needs a TV restart to reload this setting. Restart when convenient, reconnect and check the clock.',
                ru='На Android 6–10 обычно нужен перезапуск TV для перечитывания настройки. Перезапустите в удобное время, подключитесь и проверьте часы.'
            ),
            "ntp_next_refresh": Translation(
                en='Android may apply the setting at its next network time refresh. If the clock stays wrong, check automatic time and restart the TV if needed.',
                ru='Android может применить настройку при следующем обновлении сетевого времени. Если часы не исправились, проверьте автовремя и при необходимости перезапустите TV.'
            ),
            "ntp_auto_on": Translation(
                en='Automatic date and time: enabled',
                ru='Автоматическая дата и время: включены'
            ),
            "ntp_auto_off": Translation(
                en='Automatic date and time: disabled. Enable it in the TV settings for automatic synchronization.',
                ru='Автоматическая дата и время выключены. Включите их в настройках TV для автосинхронизации.'
            ),
            "ntp_auto_unknown": Translation(
                en='Automatic date and time status could not be read.',
                ru='Статус автоматической даты и времени прочитать не удалось.'
            ),
            "ntp_changed_elsewhere": Translation(
                en='The setting changed since this operation. Refresh it before making another change.',
                ru='Настройка уже изменилась после этой операции. Проверьте её перед следующим изменением.'
            ),
            "ntp_no_undo": Translation(
                en='There is no change to undo in this connection.',
                ru='В этом подключении нет изменения для отмены.'
            ),
            "ntp_restore_menu": Translation(
                en='r. Restore system NTP; u. Undo last change in this connection',
                ru='r. Вернуть системный NTP; u. Отменить последнее изменение в этом подключении'
            ),
            "ntp_restore_confirm": Translation(
                en='Change only the NTP setting on the connected device? Type yes to confirm: ',
                ru='Изменить настройку NTP подключённого устройства? Для подтверждения введите yes: '
            ),
            "ntp_unverified_confirm": Translation(
                en='This computer could not validate the server. The TV may have different network access. Save this trusted address without a successful probe? Type yes: ',
                ru='Проверка с компьютера не удалась. Сетевой доступ у TV может отличаться. Сохранить доверенный адрес без успешной проверки? Введите yes: '
            ),
            "ntp_server_set": Translation(
                en="NTP setting saved: {ntp_server}",
                ru="Настройка NTP сохранена: {ntp_server}"
            ),
            "ntp_server_confirmation_failed": Translation(
                en="Failed to confirm NTP server change.",
                ru="Не удалось подтвердить изменение сервера NTP"
            ),
            "ntp_server_update_failed": Translation(
                en="Failed to update NTP server: {error}",
                ru="Не удалось обновить сервер NTP: {error}"
            ),
            "ntp_server_not_added": Translation(
                en="Warning: NTP server {server} is unavailable or does not work as a time server. It was not added.",
                ru="Предупреждение: NTP-сервер {server} недоступен или не работает как сервер времени. Он не добавлен."
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
                en="Server {server} read from clipboard",
                ru="Сервер получен из буфера обмена: {server}"
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
                 en="9. Auto-setup NTP server",
                 ru="9. Автоматическая установка NTP-сервера"
            ),
            "menu_item_10": Translation(
                 en="10. Terminal mode (ADB and system commands)",
                 ru="10. Режим терминала (команды ADB и системные)"
            ),
            "terminal_mode_welcome": Translation(
                 en="Terminal mode: 'help' for the application guide, 'adb --help' for native ADB help, 'exit' to quit.",
                 ru="Режим терминала: 'help' — справочник программы, 'adb --help' — справка ADB, 'exit' — выход."
            ),
            "terminal_mode_help": Translation(
                 en="\nNetwork connection: adb connect IP:PORT (replace with your TV's address)."
                 "\nThen: adb devices -l — check the connection; adb shell getprop ro.product.model — read the model."
                 "\nadb devices only lists devices: do not append an IP address to it."
                 "\nApprove the debugging prompt on the TV. For USB, start with adb devices -l."
                 "\nType help for commands, examples and terminal limitations.",
                 ru="\nПодключение по сети: adb connect IP:PORT (подставьте адрес своего телевизора)."
                 "\nЗатем: adb devices -l — проверить связь; adb shell getprop ro.product.model — прочитать модель."
                 "\nadb devices только показывает список: IP-адрес к этой команде не добавляйте."
                 "\nПодтвердите отладку на TV. Для USB начните с adb devices -l."
                 "\nВведите help для команд, примеров и ограничений терминала."
            ),
            "terminal_mode_commands": Translation(
                 en="""ADB reference for the desktop application
Public commands of the bundled Platform Tools 37.0.1 and Android examples.
Commands run on the connected device unless the description says otherwise.
Android shell tools, permissions and options depend on the TV firmware.
Replace IP:PORT, SERIAL, PACKAGE, USER_ID and file paths with your own values.
Square brackets mean optional arguments; do not type the brackets.
LOCAL is a PC path, REMOTE a device path, PACKAGE an app's package identifier.
COMMAND is the command to run; ... means several arguments; | separates alternatives.
Run each command on a separate line.

1. Connect first, then check the connection
  Example: replace the address with your TV's IP and debugging port.
  For classic network debugging the usual port is 5555.
    adb connect 192.168.1.100:5555

  List devices known to this ADB server. Do not append an IP address.
  This command does not connect to a TV or scan your local network.
  device = connected; unauthorized = approve the RSA prompt on the TV;
  offline = transport unavailable. An empty list means no known devices.
    adb devices -l

  Read the model of the single connected device.
    adb shell getprop ro.product.model

  Select one device when several are connected. Copy SERIAL from devices;
  for a network connection it is normally IP:PORT.
    adb -s SERIAL shell getprop ro.product.model

  Select the single USB device. USB does not need adb connect.
    adb -d shell getprop ro.product.model

  Disconnect this network device.
    adb disconnect IP:PORT

  Disconnect all TCP/IP devices on this server, not USB devices.
    adb disconnect

  Query the selected transport's state; an unauthorized/offline device
  can return an error. Use devices -l to diagnose all device states.
    adb get-state

  Show the selected device's ADB identifier.
    adb get-serialno

  Show its transport path, not a directory on the Android filesystem.
    adb get-devpath

The application uses its own ADB server. Connect within item 10 even if you
already connected from another terminal or menu item. Allow debugging on the TV.
USB debugging alone does not enable TCP/IP port 5555.
With code-based wireless debugging, pair in menu item 11, then connect to the
connection port shown on the TV. The pairing port is a different port.
  Check the ADB mDNS discovery backend.
    adb mdns check

  List advertised debugging addresses and ports; this is not a subnet scan.
    adb mdns services

  Native pairing command. Without CODE it waits for interactive input.
  Use menu item 11 here: it prompts for the code without putting it in
  command arguments. Pairing alone does not guarantee an active connection.
    adb pair IP:PAIR_PORT [CODE]

  Switch an already authorized USB device to classic TCP/IP debugging.
  Requires firmware support; then use adb connect IP:5555.
    adb -d tcpip 5555

  Restart the device's adbd in USB mode; its TCP/IP connection will be lost.
    adb -s SERIAL usb

2. Terminal controls and limits
  Show this application reference.
    help / ?

  Show the complete original help of the actual bundled ADB executable.
    adb --help

  Another spelling of the original ADB help.
    adb help

  Show the ADB version, not the application or Android version.
    adb version

  Clear the screen.
    clear

  Return to the main menu.
    exit / quit / q

Ctrl+C interrupts a running command and returns to terminal>.
A command has a 300-second limit. Prefer finite log/process snapshots.
This terminal handles text output. Use push/pull for binary files.
Local shell redirection (> or <), pipes (|) and command chains (&&) are not
interpreted on the PC. After adb shell they may be interpreted ON THE DEVICE.
Use the file-saving examples below instead of redirecting ADB output.
Interactive adb shell and commands requiring a real terminal should be run in
an external terminal; here use adb shell COMMAND for individual commands.
External executables in PATH can run here. Windows built-ins require cmd /c,
for example cmd /c dir. On Linux/macOS, ls runs directly. cd and environment
changes in a child shell do not persist; use explicit file paths.
Quote local paths with spaces. Remote shell quoting follows Android shell rules.

3. Global ADB options (place before the command)
  -d: single USB device; -e: single TCP/IP device;
  -s: device identifier; -t: transport_id from devices -l.
    adb [-d | -e | -s SERIAL | -t ID] COMMAND

  Select an ADB server on a computer. PORT is the server port, not the
  TV's debugging port. Normally omit these options in this application.
    adb -H HOST -P PORT COMMAND

  Choose a server listening socket, for example tcp:localhost:5039.
    adb -L SOCKET start-server

  Allow the server to listen on all network interfaces.
    adb -a start-server

  Restrict a newly started server to one USB device (serial or USB address).
  -L, -a and --one-device configure server startup; use an external terminal
  for such custom servers, since this application manages its own server.
    adb --one-device SERIAL start-server

  Exit if the command's stdout is closed.
    adb --exit-on-write-error COMMAND

Do not use environment variables to select a device here: use -s explicitly.

4. Files, screenshots and video
  Copy a local file to the device.
    adb push "local file.txt" /sdcard/Download/file.txt

  Copy a device file to the PC. Directories can also be transferred.
    adb pull /sdcard/Download/file.txt "local file.txt"

  --sync: transfer changed files; -n: dry run without storing them;
  -q: quiet progress; -z: compression; -Z: no compression.
    adb push [--sync] [-z ALGORITHM | -Z] [-n] [-q] LOCAL... REMOTE

  -a: preserve timestamps and modes; other transfer flags as above.
  ALGORITHM: any, none, brotli, lz4 or zstd; support also depends on the device.
    adb pull [-a] [-z ALGORITHM | -Z] [-q] REMOTE... LOCAL

  List files in this directory.
    adb shell ls -l /sdcard/Download

  Create the directory, including missing parent directories.
    adb shell mkdir -p /sdcard/Download/adb-demo

  Rename or move a file; an existing destination may be overwritten.
    adb shell mv /sdcard/Download/old.txt /sdcard/Download/new.txt

  Delete this file. Plain rm does not remove directories recursively.
    adb shell rm /sdcard/Download/file.txt

  Remove an empty directory.
    adb shell rmdir /sdcard/Download/adb-demo

  Save a PNG screenshot ON THE DEVICE.
    adb shell screencap -p /sdcard/screen.png

  Download that screenshot to the PC. Run after screencap.
    adb pull /sdcard/screen.png screen.png

  Record 30 seconds of screen video without audio; requires device support.
    adb shell screenrecord --time-limit 30 /sdcard/demo.mp4

  Download the completed recording. Protected content may not be captured.
    adb pull /sdcard/demo.mp4 demo.mp4

5. Applications and users
  Install an APK compatible with this device.
    adb install "app.apk"

  Update an installed app while keeping data; a compatible signature is required.
    adb install -r "app.apk"

  Install one app from its complete, matching set of split APKs.
    adb install-multiple "base.apk" "split_config.apk"

  Install several packages atomically when supported by the device.
    adb install-multi-package "first.apk" "second.apk"

Installation options:
  -r: replace; -t: allow test APKs; -d: allow a version downgrade for
  debuggable apps; -g: grant runtime permissions; --instant: instant app;
  --abi ABI: select ABI; -p: partial install (install-multiple only).
  -s requests external storage; legacy -l requests forward-lock.
  Availability and acceptance of these options depend on Android.
  --streaming / --no-streaming: choose the installation transfer method.
  --fastdeploy / --no-fastdeploy: enable/disable the deployment agent.
  --force-agent / --date-check-agent / --version-check-agent: control agent updates.
  --local-agent: use an agent from a local build (Linux/macOS only).
  Remove an app; -k keeps its data/cache.
    adb uninstall [-k] PACKAGE

  List package identifiers; add -s for system apps or -3 for third-party apps.
    adb shell pm list packages

  Show installed APK paths.
    adb shell pm path PACKAGE

  List Android users and their numeric IDs.
    adb shell pm list users

  Remove the app for that user. User 0 is the owner, not always the active user.
    adb shell pm uninstall --user USER_ID PACKAGE

  Disable an app for that user, subject to firmware permissions.
    adb shell pm disable-user --user USER_ID PACKAGE

  Enable the app again for the same user.
    adb shell pm enable --user USER_ID PACKAGE

  Delete the app's data for that user, including settings and sign-ins.
    adb shell pm clear --user USER_ID PACKAGE

  Launch the specified accessible activity.
    adb shell am start -n PACKAGE/ACTIVITY

  Open Android settings if this action is supported on the TV.
    adb shell am start -a android.settings.SETTINGS

  Stop the app.
    adb shell am force-stop PACKAGE

  Send a broadcast; protected actions can be denied by Android.
    adb shell am broadcast -a ACTION

6. TV controls
  Go to the home screen.
    adb shell input keyevent KEYCODE_HOME

  Go back.
    adb shell input keyevent KEYCODE_BACK

  Press OK. Use KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT for navigation.
    adb shell input keyevent KEYCODE_DPAD_CENTER

  Type "hello world" into the focused field. %s means a space;
  basic Latin text is supported, arbitrary Unicode input is not guaranteed.
    adb shell input text hello%sworld

  Tap screen coordinates, where supported.
    adb shell input tap X Y

  Swipe between coordinates; duration is in milliseconds.
    adb shell input swipe X1 Y1 X2 Y2 DURATION_MS

7. Time and system diagnostics
  Read the device's current date/time.
    adb shell date

  Read the configured NTP override; null means no stored override.
    adb shell settings get global ntp_server

  Read automatic time setting (normally 1 = on, 0 = off).
    adb shell settings get global auto_time

  Read automatic time-zone setting.
    adb shell settings get global auto_time_zone

  Read the current time-zone property.
    adb shell getprop persist.sys.timezone

  Set an NTP override if firmware permits it. This does not prove that time
  has synchronized; menu items 1/2 provide configuration with verification.
    adb shell settings put global ntp_server pool.ntp.org

  Enable automatic time; success does not guarantee an NTP response.
    adb shell settings put global auto_time 1

  Remove the override so the firmware can use its configured default.
    adb shell settings delete global ntp_server

  List system properties; append ro.build.version.release for Android version.
    adb shell getprop

  Show CPU information exposed by the kernel.
    adb shell cat /proc/cpuinfo

  Show filesystem space.
    adb shell df -h

  List all processes on modern Android; on older firmware try ps without -A.
    adb shell ps -A

  Print one process snapshot on Android with Toybox; check top --help on older TVs.
    adb shell top -b -n 1

  List services that provide diagnostics.
    adb shell dumpsys -l

  Inspect one listed service, for example display, power or meminfo.
  network_time_update_service and time_detector can explain time sync if present.
    adb shell dumpsys SERVICE

  Print the last 200 log lines and exit; -d prevents continuous streaming.
    adb logcat -d -t 200

  Print the crash buffer and exit.
    adb logcat -b crash -d

  Stream logs until interrupted; the application's 300-second limit applies.
    adb logcat

  Save a report to the PC without >. ZIP requires Android 7+ support;
  older devices may print text. Reports exceeding 300 seconds need an
  external terminal. Reports can contain device and account information.
    adb bugreport bugreport.zip

  List processes exposing the Java debugger transport.
    adb jdwp

  List features of the PC's ADB server.
    adb host-features

  List features shared by the server and selected device.
    adb features

  Show the server's configuration, backends and paths.
    adb server-status

8. Forward and reverse ports
  List PC-to-device forwarding rules.
    adb forward --list

  Forward PC port 6100 to device port 7100; --no-rebind rejects an existing rule.
    adb forward [--no-rebind] tcp:6100 tcp:7100

  Remove that PC-side forwarding rule.
    adb forward --remove tcp:6100

  Remove all forward rules.
    adb forward --remove-all

  List device-to-PC forwarding rules.
    adb reverse --list

  Forward device port 7100 to PC port 6100.
    adb reverse [--no-rebind] tcp:7100 tcp:6100

  Remove that device-side reverse rule.
    adb reverse --remove tcp:7100

  Remove reverse rules on the selected device.
    adb reverse --remove-all

forward endpoints: tcp:PORT, localabstract:NAME, localreserved:NAME,
localfilesystem:PATH, dev:PATH, dev-raw:PATH, jdwp:PID (remote only),
vsock:CID:PORT (remote only), acceptfd:FD (listening only).
reverse endpoints: tcp:PORT, localabstract:NAME, localreserved:NAME,
localfilesystem:PATH. tcp:0 requests an available listening port.
The target service must exist; forwarding does not start a service.

9. Server, reconnecting and waiting
  Ensure the application's ADB server is running.
    adb start-server

  Stop that server and disconnect its devices, including other application
  instances using it. The next ADB command will start a server again.
    adb kill-server

  Reconnect the selected transport from the PC side.
    adb reconnect

  Request reconnection from the device side.
    adb reconnect device

  Reset offline/unauthorized transports. This does not approve RSA access.
    adb reconnect offline

  Wait for a usable transport, within this terminal's 300-second limit.
    adb wait-for-device

  TRANSPORT: usb, local (TCP/IP), any. STATE: device, recovery, rescue,
  sideload, bootloader, disconnect. For example wait-for-usb-device.
  Waiting does not establish a connection or confirm that Android has booted.
    adb wait-for-TRANSPORT-STATE

  Reattach a detached USB device; requires the libusb backend.
    adb -s SERIAL attach

  Release a USB device for another process; requires libusb.
    adb -s SERIAL detach

10. Reboot, recovery and developer builds
These commands change device state. Root/verity/remount usually require an
eng/userdebug build; retail TVs commonly reject them. ADB does not grant root.
  Reboot Android normally.
    adb reboot

  Reboot into the bootloader, where normal ADB commands are usually unavailable.
    adb reboot bootloader

  Reboot into recovery, if supported.
    adb reboot recovery

  Reboot into recovery's OTA sideload mode.
    adb reboot sideload

  Enter sideload mode and reboot automatically after sideloading.
    adb reboot sideload-auto-reboot

  Send and install a compatible OTA package in recovery sideload mode.
    adb sideload "update.zip"

  Restart the device's adbd as root on builds that allow it.
    adb root

  Restart adbd without root.
    adb unroot

  Remount applicable partitions writable; -R permits a required reboot.
    adb remount [-R]

  Disable dm-verity verification on a supported developer build.
    adb disable-verity

  Re-enable dm-verity on that build; follow the device's reboot instructions.
    adb enable-verity

  Generate an ADB private/public key pair on the PC. This does not pair or
  authorize a TV. The private key must remain private.
    adb keygen "new-adb-key"

  Sync an Android build from ANDROID_PRODUCT_OUT, not an arbitrary folder.
  PARTITION: all, data, odm, oem, product, system, system_ext, vendor.
  -l lists pending copies; -n dry run; -q quiet. Intended for OS developers
  with writable targets and a configured external build environment.
    adb sync [-l] [-n] [-q] [-z ALGORITHM | -Z] [PARTITION]

  Show emulator console commands; only for an Android emulator, not a TV.
    adb emu help

  Send a command from that console reference to the selected emulator.
    adb emu COMMAND

11. Shell syntax and help from the device
  Run an Android shell command. -n disables stdin; -T disables PTY;
  -t requests PTY when attached to a terminal; -tt forces PTY;
  -e chooses an escape character (or none); -x disables remote exit codes
  and stdout/stderr separation. Without COMMAND the shell is interactive:
  use an external terminal for that mode.
    adb shell [-e ESCAPE] [-n] [-T | -t | -tt] [-x] [COMMAND...]

  Read raw command output. Binary output needs an external terminal;
  do not use this terminal to redirect a PNG or other binary stream.
    adb exec-out COMMAND

  Feed stdin to a device command; use an external terminal for file/pipe input.
    adb exec-in COMMAND

  List executables actually present on this Android device.
    adb shell ls /system/bin

  List Toybox utilities when Toybox is included in the firmware.
    adb shell toybox

  Show this Android version's package-manager commands and options.
    adb shell pm help

  Show activity-manager commands and intent options.
    adb shell am help

  List services with shell commands on supported Android versions.
    adb shell cmd -l

  Show the selected service's own command reference.
    adb shell cmd SERVICE help

  Show supported settings operations and namespaces.
    adb shell settings help

  Show available input commands; older versions may show help with input alone.
    adb shell input --help

  Show diagnostic command options.
    adb shell dumpsys --help

  Check process-monitor options for this firmware.
    adb shell top --help

  Check screen recording options and device limitations.
    adb shell screenrecord --help

  Show log options for the connected Android version.
    adb logcat --help

Official references:
  https://developer.android.com/tools/adb
  https://developer.android.com/tools/logcat
  https://developer.android.com/tools/dumpsys
  https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md
Use adb --help for the executable's exact reference; shell help comes from the TV.
""",
                 ru="""Справочник ADB для десктопной программы
Публичные команды встроенных Platform Tools 37.0.1 и примеры для Android.
Команды обращаются к подключённому устройству, если в пояснении не указано иное.
Утилиты Android shell, права и параметры зависят от прошивки телевизора.
Заменяйте IP:PORT, SERIAL, PACKAGE, USER_ID и пути своими значениями.
Квадратные скобки обозначают необязательные аргументы; сами скобки не вводите.
LOCAL — путь на ПК, REMOTE — на устройстве, PACKAGE — имя пакета приложения.
COMMAND — выполняемая команда; ... означает несколько аргументов; | разделяет варианты.
Каждую команду выполняйте отдельной строкой.

1. Сначала подключение, затем проверка
  Пример: замените адрес на IP телевизора и его порт отладки.
  Для классической отладки по сети обычно используется порт 5555.
    adb connect 192.168.1.100:5555

  Список устройств, известных этому ADB-серверу. IP после команды не добавляйте.
  Команда не подключает телевизор и не сканирует локальную сеть.
  device = подключено; unauthorized = подтвердите запрос RSA на телевизоре;
  offline = связь недоступна. Пустой список означает отсутствие устройств.
    adb devices -l

  Прочитать модель единственного подключённого устройства.
    adb shell getprop ro.product.model

  Выбрать устройство, если подключено несколько. SERIAL возьмите из devices;
  для сетевого подключения это обычно IP:PORT.
    adb -s SERIAL shell getprop ro.product.model

  Выбрать единственное USB-устройство. Для USB команда adb connect не нужна.
    adb -d shell getprop ro.product.model

  Отключить это сетевое устройство.
    adb disconnect IP:PORT

  Отключить все TCP/IP-устройства этого сервера; USB не отключается.
    adb disconnect

  Запросить состояние выбранного соединения; unauthorized/offline могут
  возвращаться ошибкой. Для диагностики всех состояний используйте devices -l.
    adb get-state

  Показать ADB-идентификатор выбранного устройства.
    adb get-serialno

  Показать путь транспорта, а не каталог в файловой системе Android.
    adb get-devpath

У программы свой ADB-сервер. Подключайтесь внутри пункта 10, даже если уже
подключались из другого терминала или пункта меню. Разрешите отладку на TV.
Включение USB-отладки само по себе не открывает TCP/IP-порт 5555.
Для беспроводной отладки по коду сначала выполните спаривание в пункте 11,
затем подключитесь к порту соединения с экрана TV. Порт спаривания отличается.
  Проверить доступность механизма обнаружения mDNS в ADB.
    adb mdns check

  Показать объявленные адреса и порты отладки; это не сканирование подсети.
    adb mdns services

  Штатная команда спаривания. Без CODE ожидает интерактивный ввод.
  Здесь используйте пункт 11: он запрашивает код без передачи в аргументах
  команды. Успешное спаривание ещё не гарантирует активное соединение.
    adb pair IP:PAIR_PORT [CODE]

  Переключить уже авторизованное USB-устройство на классическую отладку TCP/IP.
  Требуется поддержка прошивки; после этого выполните adb connect IP:5555.
    adb -d tcpip 5555

  Перезапустить adbd устройства в режиме USB; соединение TCP/IP разорвётся.
    adb -s SERIAL usb

2. Управление терминалом и ограничения
  Эта справка программы.
    help / ?

  Полная оригинальная справка фактически встроенного исполняемого файла ADB.
    adb --help

  Другой вариант вызова оригинальной справки ADB.
    adb help

  Версия ADB, а не программы или Android.
    adb version

  Очистить экран.
    clear

  Вернуться в главное меню.
    exit / quit / q

Ctrl+C прерывает выполняемую команду и возвращает к terminal>.
На команду отводится 300 секунд. Для журналов и процессов удобнее разовые снимки.
Терминал обрабатывает текстовый вывод. Двоичные файлы передавайте через push/pull.
Перенаправление (> или <), конвейеры (|) и цепочки команд (&&) на ПК здесь не
обрабатываются. После adb shell эти операторы могут выполниться НА УСТРОЙСТВЕ.
Для сохранения файлов используйте примеры ниже вместо перенаправления вывода ADB.
Интерактивный adb shell и команды, которым нужен полноценный терминал, запускайте
во внешней консоли; здесь выполняйте отдельные команды через adb shell COMMAND.
Можно запускать внешние программы из PATH. Встроенным командам Windows нужен
cmd /c, например cmd /c dir. В Linux/macOS ls запускается напрямую. cd и изменения
окружения дочерней оболочки не сохраняются; указывайте явные пути к файлам.
Локальные пути с пробелами заключайте в кавычки. Для путей на TV действуют
правила кавычек оболочки Android.

3. Общие параметры ADB (ставятся перед командой)
  -d: единственное USB; -e: единственное TCP/IP-устройство;
  -s: идентификатор устройства; -t: transport_id из devices -l.
    adb [-d | -e | -s SERIAL | -t ID] COMMAND

  Выбрать ADB-сервер на компьютере. PORT — порт сервера, а не порт отладки TV.
  В обычной работе с программой эти параметры не нужны.
    adb -H HOST -P PORT COMMAND

  Выбрать сокет сервера, например tcp:localhost:5039.
    adb -L SOCKET start-server

  Разрешить серверу слушать все сетевые интерфейсы.
    adb -a start-server

  Ограничить новый сервер одним USB-устройством (серийный номер или USB-адрес).
  -L, -a и --one-device задают запуск сервера; для такой настройки используйте
  внешнюю консоль, поскольку программа сама управляет своим сервером.
    adb --one-device SERIAL start-server

  Завершить команду, если её поток stdout закрыт.
    adb --exit-on-write-error COMMAND

Для выбора устройства здесь используйте -s, а не переменные окружения.

4. Файлы, снимки экрана и видео
  Скопировать локальный файл на устройство.
    adb push "local file.txt" /sdcard/Download/file.txt

  Скопировать файл с устройства на ПК. Также можно передавать каталоги.
    adb pull /sdcard/Download/file.txt "local file.txt"

  --sync: передавать изменившиеся файлы; -n: проба без сохранения файлов;
  -q: скрыть прогресс; -z: выбрать сжатие; -Z: отключить сжатие.
    adb push [--sync] [-z ALGORITHM | -Z] [-n] [-q] LOCAL... REMOTE

  -a: сохранить время и режим доступа; остальные параметры как выше.
  ALGORITHM: any, none, brotli, lz4 или zstd; нужна поддержка устройства.
    adb pull [-a] [-z ALGORITHM | -Z] [-q] REMOTE... LOCAL

  Показать файлы в этом каталоге.
    adb shell ls -l /sdcard/Download

  Создать каталог вместе с недостающими родительскими каталогами.
    adb shell mkdir -p /sdcard/Download/adb-demo

  Переместить или переименовать файл; существующий целевой файл может замениться.
    adb shell mv /sdcard/Download/old.txt /sdcard/Download/new.txt

  Удалить этот файл. Обычный rm не удаляет каталоги рекурсивно.
    adb shell rm /sdcard/Download/file.txt

  Удалить пустой каталог.
    adb shell rmdir /sdcard/Download/adb-demo

  Сохранить снимок PNG НА УСТРОЙСТВЕ.
    adb shell screencap -p /sdcard/screen.png

  Скачать этот снимок на ПК. Выполняется после screencap.
    adb pull /sdcard/screen.png screen.png

  Записать 30 секунд экрана без звука; нужна поддержка устройства.
    adb shell screenrecord --time-limit 30 /sdcard/demo.mp4

  Скачать завершённую запись. Защищённое содержимое может не попасть в кадр.
    adb pull /sdcard/demo.mp4 demo.mp4

5. Приложения и пользователи
  Установить совместимый с устройством APK.
    adb install "app.apk"

  Обновить приложение с сохранением данных; требуется совместимая подпись.
    adb install -r "app.apk"

  Установить одно приложение из полного набора совместимых split APK.
    adb install-multiple "base.apk" "split_config.apk"

  Атомарно установить несколько пакетов, если это поддерживает устройство.
    adb install-multi-package "first.apk" "second.apk"

Параметры установки:
  -r: замена; -t: разрешить тестовые APK; -d: разрешить понижение версии
  отлаживаемого приложения; -g: выдать разрешения времени выполнения;
  --instant: мгновенное приложение; --abi ABI: выбрать ABI;
  -p: частичная установка (только install-multiple).
  -s запрашивает внешнее хранилище; устаревший -l — forward-lock.
  Доступность и допустимость параметров зависят от Android.
  --streaming / --no-streaming: выбрать способ передачи при установке.
  --fastdeploy / --no-fastdeploy: включить/выключить агент быстрой установки.
  --force-agent / --date-check-agent / --version-check-agent: обновление агента.
  --local-agent: агент из локальной сборки (только Linux/macOS).
  Удалить приложение; -k сохраняет его данные и кэш.
    adb uninstall [-k] PACKAGE

  Показать имена пакетов; добавьте -s для системных или -3 для сторонних.
    adb shell pm list packages

  Показать пути установленных APK.
    adb shell pm path PACKAGE

  Показать пользователей Android и их числовые идентификаторы.
    adb shell pm list users

  Удалить приложение для этого пользователя. 0 — владелец, не всегда текущий.
    adb shell pm uninstall --user USER_ID PACKAGE

  Отключить приложение для пользователя, если прошивка разрешает.
    adb shell pm disable-user --user USER_ID PACKAGE

  Снова включить приложение для того же пользователя.
    adb shell pm enable --user USER_ID PACKAGE

  Стереть данные приложения для пользователя, включая настройки и входы.
    adb shell pm clear --user USER_ID PACKAGE

  Запустить указанную доступную Activity приложения.
    adb shell am start -n PACKAGE/ACTIVITY

  Открыть настройки Android, если TV поддерживает это действие.
    adb shell am start -a android.settings.SETTINGS

  Остановить приложение.
    adb shell am force-stop PACKAGE

  Отправить широковещательное сообщение; защищённые действия Android отклонит.
    adb shell am broadcast -a ACTION

6. Управление телевизором
  Открыть главный экран.
    adb shell input keyevent KEYCODE_HOME

  Вернуться назад.
    adb shell input keyevent KEYCODE_BACK

  Нажать OK. Для навигации используйте KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT.
    adb shell input keyevent KEYCODE_DPAD_CENTER

  Ввести «hello world» в активное поле. %s обозначает пробел;
  базовая латиница поддерживается, произвольный Unicode не гарантируется.
    adb shell input text hello%sworld

  Нажать в координатах экрана, если устройство поддерживает.
    adb shell input tap X Y

  Провести между координатами; длительность задаётся в миллисекундах.
    adb shell input swipe X1 Y1 X2 Y2 DURATION_MS

7. Время и системная диагностика
  Прочитать текущую дату и время устройства.
    adb shell date

  Прочитать переопределённый NTP-сервер; null означает отсутствие записи.
    adb shell settings get global ntp_server

  Прочитать настройку автоматического времени (обычно 1 — вкл., 0 — выкл.).
    adb shell settings get global auto_time

  Прочитать настройку автоматического часового пояса.
    adb shell settings get global auto_time_zone

  Прочитать свойство текущего часового пояса.
    adb shell getprop persist.sys.timezone

  Задать NTP-сервер, если прошивка разрешает. Запись не доказывает синхронизацию
  часов; пункты 1/2 меню предоставляют настройку с проверкой.
    adb shell settings put global ntp_server pool.ntp.org

  Включить автоматическое время; успех не гарантирует ответ NTP-сервера.
    adb shell settings put global auto_time 1

  Удалить переопределение, чтобы прошивка использовала своё значение по умолчанию.
    adb shell settings delete global ntp_server

  Показать системные свойства; добавьте ro.build.version.release для версии Android.
    adb shell getprop

  Показать сведения о процессоре, доступные через ядро.
    adb shell cat /proc/cpuinfo

  Показать свободное место в файловых системах.
    adb shell df -h

  Все процессы на современных Android; на старых попробуйте ps без -A.
    adb shell ps -A

  Один снимок процессов на Android с Toybox; на старых TV проверьте top --help.
    adb shell top -b -n 1

  Список служб, предоставляющих диагностику.
    adb shell dumpsys -l

  Диагностика выбранной службы, например display, power или meminfo.
  network_time_update_service и time_detector помогают проверить время, если есть.
    adb shell dumpsys SERVICE

  Последние 200 строк журнала с завершением; -d отключает непрерывное ожидание.
    adb logcat -d -t 200

  Вывести буфер ошибок приложений и завершиться.
    adb logcat -b crash -d

  Непрерывный журнал до прерывания; действует лимит программы 300 секунд.
    adb logcat

  Сохранить отчёт на ПК без >. ZIP требует поддержки Android 7+;
  старые устройства могут выдать текст. Для отчёта дольше 300 секунд нужна
  внешняя консоль. Отчёт может содержать сведения об устройстве и аккаунтах.
    adb bugreport bugreport.zip

  Список процессов, предоставляющих транспорт отладчика Java.
    adb jdwp

  Возможности ADB-сервера на ПК.
    adb host-features

  Общие возможности сервера и выбранного устройства.
    adb features

  Конфигурация сервера, его механизмы USB/mDNS и используемые пути.
    adb server-status

8. Перенаправление портов
  Список перенаправлений с ПК на устройство.
    adb forward --list

  Порт 6100 ПК на порт 7100 устройства; --no-rebind запрещает замену правила.
    adb forward [--no-rebind] tcp:6100 tcp:7100

  Удалить это перенаправление со стороны ПК.
    adb forward --remove tcp:6100

  Удалить все правила forward.
    adb forward --remove-all

  Список перенаправлений с устройства на ПК.
    adb reverse --list

  Порт 7100 устройства на порт 6100 ПК.
    adb reverse [--no-rebind] tcp:7100 tcp:6100

  Удалить это обратное перенаправление.
    adb reverse --remove tcp:7100

  Удалить правила reverse выбранного устройства.
    adb reverse --remove-all

Адреса forward: tcp:PORT, localabstract:NAME, localreserved:NAME,
localfilesystem:PATH, dev:PATH, dev-raw:PATH, jdwp:PID (только удалённая сторона),
vsock:CID:PORT (только удалённая сторона), acceptfd:FD (только слушающая сторона).
Адреса reverse: tcp:PORT, localabstract:NAME, localreserved:NAME,
localfilesystem:PATH. tcp:0 запрашивает свободный слушающий порт.
Целевая служба должна существовать; перенаправление не запускает службу.

9. Сервер, переподключение и ожидание
  Убедиться, что ADB-сервер программы запущен.
    adb start-server

  Остановить этот сервер и отключить его устройства, включая сессии других
  экземпляров программы. Следующая команда ADB снова запустит сервер.
    adb kill-server

  Переподключить выбранный транспорт со стороны ПК.
    adb reconnect

  Запросить переподключение со стороны устройства.
    adb reconnect device

  Сбросить соединения offline/unauthorized. Это не подтверждает доступ по RSA.
    adb reconnect offline

  Дождаться доступного соединения в пределах лимита терминала 300 секунд.
    adb wait-for-device

  TRANSPORT: usb, local (TCP/IP), any. STATE: device, recovery, rescue,
  sideload, bootloader, disconnect. Например, wait-for-usb-device.
  Ожидание не создаёт соединение и не подтверждает полную загрузку Android.
    adb wait-for-TRANSPORT-STATE

  Вернуть отсоединённое USB-устройство этому серверу; нужен механизм libusb.
    adb -s SERIAL attach

  Освободить USB-устройство для другого процесса; нужен libusb.
    adb -s SERIAL detach

10. Перезагрузка, recovery и сборки разработчика
Эти команды меняют состояние устройства. Для root/verity/remount обычно нужна
сборка eng/userdebug; серийные TV часто отказывают. ADB сам не предоставляет root.
  Обычная перезагрузка Android.
    adb reboot

  Перезагрузка в загрузчик; обычные команды ADB там, как правило, недоступны.
    adb reboot bootloader

  Перезагрузка в режим восстановления, если он поддерживается.
    adb reboot recovery

  Перезагрузка в режим установки OTA через recovery.
    adb reboot sideload

  Войти в sideload и автоматически перезагрузиться после установки.
    adb reboot sideload-auto-reboot

  Передать и установить совместимый пакет OTA в режиме recovery sideload.
    adb sideload "update.zip"

  Перезапустить adbd устройства от root на сборке, которая это разрешает.
    adb root

  Перезапустить adbd без root.
    adb unroot

  Перемонтировать поддерживаемые разделы для записи; -R разрешает перезагрузку.
    adb remount [-R]

  Отключить проверку dm-verity на совместимой сборке разработчика.
    adb disable-verity

  Включить dm-verity обратно; выполните указания устройства о перезагрузке.
    adb enable-verity

  Создать закрытый и открытый ADB-ключи на ПК. Команда не спаривает TV и не
  выдаёт доступ к нему. Закрытый ключ должен оставаться секретным.
    adb keygen "new-adb-key"

  Синхронизировать сборку Android из ANDROID_PRODUCT_OUT, не произвольную папку.
  PARTITION: all, data, odm, oem, product, system, system_ext, vendor.
  -l: список будущих копирований; -n: проба; -q: без прогресса. Для разработчиков
  ОС с доступными для записи разделами и настроенным внешним окружением сборки.
    adb sync [-l] [-n] [-q] [-z ALGORITHM | -Z] [PARTITION]

  Справка консоли эмулятора Android; к физическому телевизору не относится.
    adb emu help

  Выполнить команду из этой справки консоли на выбранном эмуляторе.
    adb emu COMMAND

11. Синтаксис shell и справка самого устройства
  Выполнить команду Android. -n: не читать stdin; -T: без псевдотерминала;
  -t: запросить его при наличии терминала; -tt: запросить принудительно;
  -e: символ выхода (или none); -x: отключить коды завершения Android
  и разделение stdout/stderr. Без COMMAND открывается интерактивная оболочка:
  для неё используйте внешнюю консоль.
    adb shell [-e ESCAPE] [-n] [-T | -t | -tt] [-x] [COMMAND...]

  Получить сырой вывод команды. Для двоичных данных нужна внешняя консоль;
  не перенаправляйте PNG и другие двоичные потоки через этот терминал.
    adb exec-out COMMAND

  Передать stdin команде устройства; для файла или конвейера нужна внешняя консоль.
    adb exec-in COMMAND

  Список исполняемых файлов, реально присутствующих на этом Android.
    adb shell ls /system/bin

  Список утилит Toybox, если он включён в прошивку.
    adb shell toybox

  Команды и параметры менеджера пакетов этой версии Android.
    adb shell pm help

  Команды управления Activity и параметры Intent.
    adb shell am help

  Список служб с командами shell на поддерживаемых версиях Android.
    adb shell cmd -l

  Справка команд выбранной службы.
    adb shell cmd SERVICE help

  Поддерживаемые операции с настройками и пространства имён.
    adb shell settings help

  Доступные команды ввода; на старых версиях справку может дать input без аргументов.
    adb shell input --help

  Параметры системной диагностики.
    adb shell dumpsys --help

  Параметры просмотра процессов именно этой прошивки.
    adb shell top --help

  Параметры записи экрана и ограничения устройства.
    adb shell screenrecord --help

  Параметры журналов подключённой версии Android.
    adb logcat --help

Официальные источники:
  https://developer.android.com/tools/adb
  https://developer.android.com/tools/logcat
  https://developer.android.com/tools/dumpsys
  https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md
Точная справка исполняемого файла — adb --help; справка shell поступает с телевизора.
"""
            ),
            "terminal_mode_exit_ctrl_c": Translation(
                 en="Command interrupted. Type 'exit' to leave terminal mode.",
                 ru="Команда прервана. Для выхода из режима терминала введите 'exit'."
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
            "menu_item_wireless": Translation(
                en="11. Android 11+ wireless debugging (pairing and mDNS discovery)",
                ru="11. Беспроводная отладка Android 11+ (спаривание и поиск по mDNS)"
            ),
            "menu_item_11": Translation(
                en="0. Exit",
                ru="0. Выход"
            ),
            "menu_prompt": Translation(
                en="Enter menu option number:",
                ru="Введите номер пункта меню:"
            ),
            "invalid_ip_format": Translation(
                en="Invalid IP address format. Use the format: xxx.xxx.xxx.xxx or xxx.xxx.xxx.xxx:port (default port: {port})",
                ru="Неверный формат IP-адреса. Используйте формат: xxx.xxx.xxx.xxx или xxx.xxx.xxx.xxx:порт (порт по умолчанию: {port})"
            ),
            "invalid_ntp_server_format": Translation(
                en="Invalid NTP server format. Use a valid domain name (e.g., time.google.com) or IP address.",
                ru="Неверный формат NTP-сервера. Используйте корректное доменное имя (например, time.google.com) или IP-адрес."
            ),
            "enter_country_code": Translation(
                en="Enter your country code (e.g. us for USA, uk for United Kingdom, see country codes menu, q to exit): ",
                ru="Введите код вашей страны (например, ru для России, by для Беларуси, смотри в меню коды стран, для возврата q): "
            ),
            "invalid_country_code": Translation(
                en="Invalid country code",
                ru="Недействительный код страны"
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
                en="Enter a number, IP, IP:port, CIDR subnet, 'u' for USB, 's' to scan, 'm' to search again, 'q' to cancel (Enter for saved: {saved_ip}): ",
                ru="Введите номер, IP, IP:порт, CIDR-подсеть, 'u' — USB, 's' для сканирования, 'm' — искать снова, 'q' для отмены (Enter для сохранённого: {saved_ip}): "
            ),
            "enter_device_ip_scan_no_saved": Translation(
                en="Enter a number, IP, IP:port, CIDR subnet, 'u' for USB, 's' to scan network, 'm' to search again, or 'q' to cancel: ",
                ru="Введите номер, IP, IP:порт, CIDR-подсеть, 'u' — USB, 's' для сканирования сети, 'm' — искать снова или 'q' для отмены: "
            ),
            "scan_select_device": Translation(
                en="Select device number (or Enter to cancel): ",
                ru="Выберите номер устройства (или Enter для отмены): "
            ),
            "scan_start": Translation(
                en="Scanning network {network} for open ADB port {port}...",
                ru="Сканирование сети {network} на открытый порт ADB {port}..."
            ),
            "scan_retry": Translation(
                en="Rechecking {count} addresses that did not respond (slower, more reliable)...",
                ru="Повторная проверка {count} не ответивших адресов (медленнее, но надёжнее)..."
            ),
            "scan_progress": Translation(
                en="  Progress: {checked}/{total} checked, {found} found",
                ru="  Прогресс: {checked}/{total} проверено, {found} найдено"
            ),
            "scan_complete": Translation(
                en="Scan complete. Connection candidates: {count}",
                ru="Сканирование завершено. Адресов для проверки подключения: {count}"
            ),
            "scan_found": Translation(
                en="Found {count} connection candidate(s); ADB authorization still needs to be checked:",
                ru="Найдено {count} адресов для подключения; доступ ADB ещё требует проверки:"
            ),
            "scan_none": Translation(
                en="No Android TV devices found on local network",
                ru="Устройства Android TV в локальной сети не найдены"
            ),
            "scan_local_ip_error": Translation(
                en="Could not determine local IP address",
                ru="Не удалось определить локальный IP-адрес"
            ),
            "scan_net_detected": Translation(
                en="Network auto-detected: {network} ({hosts} hosts)",
                ru="Сеть определена автоматически: {network} ({hosts} хостов)"
            ),
            "scan_wide_offer": Translation(
                en="No devices found in {narrow}. Scan wider range {wide}? (y/n): ",
                ru="В подсети {narrow} устройства не найдены. Сканировать расширенный диапазон {wide}? (y/n): "
            ),
            "scan_additional_available": Translation(
                en="Additional local networks are available:",
                ru="Доступны дополнительные локальные сети:"
            ),
            "scan_additional_prompt": Translation(
                en="Scan additional networks? Enter number(s), 'all', or 'n': ",
                ru="Сканировать дополнительные сети? Введите номер(а), 'all' или 'n': "
            ),
            "scan_virtual_marker": Translation(
                en="[virtual/VPN]",
                ru="[виртуальная/VPN]"
            ),
            "scan_physical_marker": Translation(
                en="[network]",
                ru="[сеть]"
            ),
            "scan_invalid_cidr": Translation(
                en="Invalid or unsupported CIDR subnet: {cidr}",
                ru="Некорректная или неподдерживаемая CIDR-подсеть: {cidr}"
            ),
            "scan_large_custom_offer": Translation(
                en="Subnet {network} contains {hosts} hosts and may take a long time. Continue? (y/n): ",
                ru="Подсеть {network} содержит {hosts} хостов и может сканироваться долго. Продолжить? (y/n): "
            ),
            "scan_too_large": Translation(
                en="Scan cancelled: the selected network contains {hosts} hosts; the safe limit is {limit}.",
                ru="Сканирование отменено: выбранная сеть содержит {hosts} хостов; безопасный лимит — {limit}."
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
                en="Enter IP or IP:port addresses separated by comma, or press Enter to use {count} discovered device(s): ",
                ru="Введите адреса IP или IP:порт через запятую или Enter для {count} найденных устройств: "
            ),
            "batch_no_targets": Translation(
                en="No target devices. Run network scan first or enter IPs manually.",
                ru="Нет целевых устройств. Сначала выполните сканирование или введите IP вручную."
            ),
            "batch_connecting": Translation(
                en="  [{idx}/{total}] Connecting to {ip}...",
                ru="  [{idx}/{total}] Подключение к {ip}..."
            ),
            "batch_prompt_sent": Translation(
                en="  Authorization request sent to {ip}. Confirm it on the device screen.",
                ru="  Запрос авторизации отправлен на {ip}. Подтвердите его на экране устройства."
            ),
            "batch_success": Translation(
                en="  OK  {ip}: NTP setting saved: {server}. Enable automatic time; Android 6–10 usually needs a TV restart.",
                ru="  ОК  {ip}: настройка NTP сохранена: {server}. Включите автовремя; на Android 6–10 обычно нужен перезапуск TV."
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
                en="\nComparison with the PC clock (UTC):",
                ru="\nСравнение с часами ПК (UTC):"
            ),
            "device_time": Translation(
                en="  Device time (UTC): {time}",
                ru="  Время устройства (UTC): {time}"
            ),
            "pc_time": Translation(
                en="  PC time (UTC): {time}",
                ru="  Время ПК (UTC): {time}"
            ),
            "time_in_sync": Translation(
                en="  Difference from the PC clock is less than 60 seconds.",
                ru="  Расхождение с часами ПК меньше 60 секунд."
            ),
            "time_out_of_sync": Translation(
                en="  Difference from the PC clock: {diff}",
                ru="  Расхождение с часами ПК: {diff}"
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
                en="Testing the NTP server from this computer (3 attempts)...",
                ru="Проверка ответа NTP-сервера с этого компьютера (3 попытки)..."
            ),
            "ntp_verify_detailed": Translation(
                en="NTP server {server} replied to this computer:\n  RTT: {rtt:.1f}ms | Success: {success:.0f}% | Offset: {offset:.3f}s",
                ru="NTP-сервер {server} ответил этому компьютеру:\n  RTT: {rtt:.1f}мс | Успех: {success:.0f}% | Смещение: {offset:.3f}с"
            ),
            "ntp_verify_failed": Translation(
                en="Warning: NTP server {server} is not reachable as a time server. It will not be added.",
                ru="Предупреждение: NTP-сервер {server} недоступен как сервер времени. Он не будет добавлен."
            ),
            "ntp_verify_bad_offset": Translation(
                en="Warning: NTP server {server} responds but time offset is too large ({offset:.1f}s). It will not be added.",
                ru="Предупреждение: NTP-сервер {server} отвечает, но смещение времени слишком большое ({offset:.1f}с). Он не будет добавлен."
            ),
            "connection_reused": Translation(
                en="Using existing connection to {ip}",
                ru="Используется существующее подключение к {ip}"
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
                en="[Auto] Connection candidate: {ip}",
                ru="[Авто] Адрес для проверки подключения: {ip}"
            ),
            "auto_use_found_device": Translation(
                en="[Auto] Press Enter to use this address and continue, or q to cancel: ",
                ru="[Авто] Нажмите Enter, чтобы использовать этот адрес и продолжить, или q для отмены: "
            ),
            "auto_mdns_fallback": Translation(
                en="[Auto] No connection addresses found via mDNS. Some devices do not advertise debugging or the network blocks discovery. Choose an ADB port to try a network scan (q to cancel).",
                ru="[Авто] По mDNS не найдены адреса подключения. Некоторые устройства не объявляют отладку или сеть блокирует обнаружение. Выберите ADB-порт для поиска в сети (q — отмена)."
            ),
            "auto_select_device": Translation(
                en="[Auto] Multiple addresses found. Select a number, or q to cancel: ",
                ru="[Авто] Найдено несколько адресов. Выберите номер или q для отмены: "
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
                en="[Auto] Five NTP requests per server with pauses. Ranking considers valid replies, median delay and variation. This may take several minutes...",
                ru="[Авто] Пять NTP-запросов к каждому серверу с паузами. Учитываются корректные ответы, медиана задержки и её разброс. Проверка может занять несколько минут..."
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
                en="[Auto] NTP server {server} is reachable and has been added to the device!",
                ru="[Авто] NTP-сервер {server} доступен и добавлен на устройство!"
            ),
            "auto_cancelled": Translation(
                en="[Auto] Installation cancelled by user.",
                ru="[Авто] Установка отменена пользователем."
            ),
            "auto_no_reachable_servers": Translation(
                en="[Auto] No NTP server provided at least four valid replies out of five. Check your network and try again.",
                ru="[Авто] Не найдено NTP-серверов с минимум четырьмя корректными ответами из пяти. Проверьте сеть и повторите поиск."
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

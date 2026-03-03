# Release Notes / Примечания к релизу

## What's New / Что нового

### Новые функции

- **Автоматическая установка NTP-сервера (экспериментальный режим)** — полный цикл: сканирование сети → обнаружение устройства → тестирование всех NTP-серверов → выбор оптимального по RTT → установка на устройство
- **Сканирование локальной сети** — автоматическое обнаружение устройств Android TV с открытым ADB-портом 5555 в подсети /16 (до 65 534 хостов)
- **Групповое обновление NTP** — одновременная установка NTP-сервера на нескольких устройствах
- **Статус синхронизации времени** — сравнение времени устройства и ПК с отображением разницы
- **Экспорт/импорт настроек** — сохранение и восстановление всех настроек (язык, IP, избранные серверы) в JSON-файл
- **Управление серверами** — полноценное подменю: избранные серверы, копирование/вставка из буфера, пинг NTP, экспорт/импорт
- **Интерактивные подсказки кодов стран** — поиск по частичному вводу (`?рос`, `?uni`), автоматические подсказки при неверном коде
- **Подсказки формата ввода** — чёткие указания формата для кодов стран и NTP-серверов, защита от ввода полного адреса вместо кода
- **Проверка доступности NTP** — верификация сервера перед применением с возможностью отмены
- **Переиспользование подключений** — повторное подключение без разрыва при работе с тем же устройством
- **Предупреждение о файрволле** — напоминание о необходимости разрешить программу в брандмауэре

### Улучшения

- **Обновлённая иконка приложения** — современный дизайн: тёмный TV-экран с неоновыми часами, градиентный фон с эффектом свечения
- **Главное меню расширено до 10 пунктов** — добавлены управление серверами, сканирование сети, авто-установка NTP
- **Исправлены дубликаты ключей локализации** — устранены дублирующиеся ключи `no_favorite_servers`, `ping_ntp_servers_start`, `invalid_choice`
- **Исправлено отслеживание прогресса** — `ThreadPoolExecutor` теперь использует `as_completed` для корректного отображения прогресса сканирования и проверки серверов
- **Очищен код** — удалены закомментированные строки, исправлена непоследовательная индентация в locales.py
- **Обновлена документация** — README.md и README_EN.md полностью переписаны с описанием всех новых функций, подменю и возможностей

### Исправления ошибок

- Исправлен комментарий о языке по умолчанию в `locales.py` (было "English", фактически — Russian)
- Устранены дубликаты ключей перевода, которые могли вызывать непредсказуемое поведение
- Исправлена работа `as_completed` при параллельном сканировании сети и проверке NTP-серверов

---

### New Features

- **Auto-setup NTP server (experimental mode)** — full cycle: network scan → device discovery → test all NTP servers → select optimal by RTT → install on device
- **Local network scanning** — automatic discovery of Android TV devices with open ADB port 5555 in /16 subnet (up to 65,534 hosts)
- **Batch NTP update** — simultaneous NTP server installation on multiple devices
- **Time sync status** — device vs PC time comparison with difference display
- **Export/import settings** — save and restore all settings (language, IP, favorite servers) to JSON file
- **Server management** — full submenu: favorite servers, clipboard copy/paste, NTP ping, export/import
- **Interactive country code hints** — search by partial input (`?rus`, `?uni`), automatic hints for invalid codes
- **Input format hints** — clear format instructions for country codes and NTP servers, protection against entering full address instead of code
- **NTP availability check** — server verification before applying with option to cancel
- **Connection reuse** — reconnection without disconnect when working with the same device
- **Firewall notice** — reminder to allow the program through the firewall

### Improvements

- **Updated application icon** — modern design: dark TV screen with neon clock, gradient background with glow effect
- **Main menu expanded to 10 items** — added server management, network scan, NTP auto-setup
- **Fixed duplicate localization keys** — removed duplicate keys `no_favorite_servers`, `ping_ntp_servers_start`, `invalid_choice`
- **Fixed progress tracking** — `ThreadPoolExecutor` now uses `as_completed` for correct progress display during scanning and server checks
- **Code cleanup** — removed commented-out lines, fixed inconsistent indentation in locales.py
- **Updated documentation** — README.md and README_EN.md fully rewritten with descriptions of all new features, submenus and capabilities

### Bug Fixes

- Fixed comment about default language in `locales.py` (was "English", actually Russian)
- Removed duplicate translation keys that could cause unpredictable behavior
- Fixed `as_completed` usage for parallel network scanning and NTP server checking

# Changelog / История изменений

---

## [1.1.0] - 2026-01-25

### English

#### New Features
- **Multilingual support**: Added language selection (English/Russian) at program startup
- **NTP Server ping feature**: Test connectivity to 110+ NTP servers with RTT display and success rate
- **Terminal mode**: Execute any ADB and system commands with built-in command reference
- **Extended device information**: Display 25+ device parameters including CPU, RAM, network, battery, etc.
- **Cross-platform support**: Now works on Windows, Linux, and macOS

#### Improvements
- Added IP address remembering - no need to re-enter on each connection
- Added NTP server format validation before setting
- Improved connection process with timeout display
- Log file now always uses English for consistency regardless of UI language
- Added application icon
- Improved GitHub Actions for automated builds

#### Bug Fixes
- Fixed missing translation for device information display
- Fixed connection timeout display showing placeholder instead of actual time
- Fixed various translation inconsistencies

---

### Русский

#### Новые возможности
- **Многоязычная поддержка**: Добавлен выбор языка (английский/русский) при запуске программы
- **Пинг NTP-серверов**: Проверка доступности 110+ NTP-серверов с отображением времени отклика и процента успешных подключений
- **Режим терминала**: Выполнение любых команд ADB и системных команд со встроенной справкой
- **Расширенная информация об устройстве**: Отображение 25+ параметров устройства, включая CPU, RAM, сеть, батарею и др.
- **Кроссплатформенная поддержка**: Теперь работает на Windows, Linux и macOS

#### Улучшения
- Добавлено запоминание IP-адреса - не нужно вводить заново при каждом подключении
- Добавлена валидация формата NTP-сервера перед установкой
- Улучшен процесс подключения с отображением оставшегося времени
- Лог-файл теперь всегда использует английский язык для единообразия независимо от языка интерфейса
- Добавлена иконка приложения
- Улучшены GitHub Actions для автоматической сборки

#### Исправления ошибок
- Исправлена отсутствующая локализация для отображения информации об устройстве
- Исправлено отображение таймаута подключения (показывался placeholder вместо реального времени)
- Исправлены различные несоответствия в переводах

---

## [1.0.0] - Initial Release

### English

#### Features
- Change NTP server by country code (65+ countries)
- Set custom NTP server
- View available country codes and NTP servers
- View alternative NTP servers list
- Display current device NTP server
- ADB key generation
- Basic device information display

---

### Русский

#### Возможности
- Изменение NTP-сервера по коду страны (65+ стран)
- Установка пользовательского NTP-сервера
- Просмотр доступных кодов стран и NTP-серверов
- Просмотр списка альтернативных NTP-серверов
- Отображение текущего NTP-сервера устройства
- Генерация ADB-ключей
- Базовая информация об устройстве

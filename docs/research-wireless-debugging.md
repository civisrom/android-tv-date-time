# Исследование: поддержка беспроводной отладки Android 11–16 в десктопной версии

Проведено 2026-09-02 для Windows / Linux / macOS-версии. Вопрос: поддерживает ли
проект новую отладку по сети для Android 13/14/15/16 и что добавить, чтобы
работало со всеми новыми версиями Android TV.

## Прямой ответ: не поддерживал

Вся полезная работа шла через `adb_shell` (`pyproject.toml`:
`adb-shell = "^0.4.4"`), а это только **legacy**-путь.

### Доказательства из кода (на момент исследования)

- В установленном пакете нет ни `import ssl`, ни константы `STLS` — в
  `adb_shell/constants.py` только `AUTH/CNXN/OPEN/WRTE/CLSE/OKAY/SYNC`.
  Транспорты: `tcp_transport.py`, `usb_transport.py`, TLS-транспорта нет.
- Апстрим это знает:
  [JeffLIrion/adb_shell#175 «Support wireless debugging (STLS)»](https://github.com/JeffLIrion/adb_shell/issues/175)
  открыт, реализации нет. Симптом на устройстве с беспроводной отладкой —
  `Unknown command: 1397511251 = b'STLS'`, соединение падает на первом же ответе
  демона.
- Порт 5555 был захардкожен в `_check_adb_port`, `parse_ip_port`,
  `_retry_adb_connection`, `ADBProcessManager.disconnect_device`. Беспроводная
  отладка открывает **случайный** порт — сканирование не нашло бы её даже
  теоретически.
- mDNS не использовался нигде.

## Что при этом работало на Android 13–16

Ключевой момент: **дело не в версии Android, а в том, какой переключатель даёт
прошивка.** Google не удалял старый путь — он живёт как отдельный mDNS-сервис.

| Как включена отладка на TV | Протокол | До этих изменений |
|---|---|---|
| «Отладка по сети» / `adb tcpip 5555` — сервис `_adb._tcp` | `A_AUTH`, открытый TCP | работало и на 13, и на 16 |
| «Беспроводная отладка» — `_adb-tls-connect._tcp` | `A_STLS`, TLS 1.3 + клиентский сертификат | не работало |
| Первичное спаривание — `_adb-tls-pairing._tcp` | SPAKE2 + TLS-PSK, 6-значный код | не работало |

Практический вывод: на большинстве Android TV-приставок и телевизоров (Xiaomi,
TCL, Nvidia Shield, Fire TV) тумблер «Отладка по сети» на месте, и программа
работала. **Но на Google TV Streamer и Chromecast with Google TV после
обновления до Android 14 остаётся только беспроводная отладка со спариванием** —
с ними программа не подключалась вообще, и таких устройств будет больше.

## Ресурс, который уже был в коробке

В сборке уже лежит platform-tools **r37.0.1** (`.github/workflows/build.yml`:
`ADB_VERSION: '37.0.1'`, распаковка в `resources/`). Она умеет и `adb pair`, и
TLS-коннект, и `adb mdns services`. Возможность физически была в архиве — просто
не подключена к основному потоку. Плюс уже были готовы `get_adb_path()`,
`_retry_adb_connection()`, `ADBProcessManager`.

## План из шести шагов (реализуется)

1. **Абстракция транспорта.** Все обращения идут через `self.device.shell(...)` —
   таких мест 18. Интерфейс + `LegacyTransport`/`PlatformToolsTransport`, после
   чего `set_ntp_server`, `get_device_info`, `batch_set_ntp` не меняются.
2. **Автоопределение протокола.** Отправить CNXN и прочитать 24-байтный
   заголовок ответа: `AUTH` → legacy, `STLS` → platform-tools путь.
3. **Спаривание** (`adb pair IP:PAIR_PORT CODE` → `adb connect IP:CONNECT_PORT`).
4. **mDNS-обнаружение вместо сканирования** — три сервиса `_adb._tcp`,
   `_adb-tls-connect._tcp`, `_adb-tls-pairing._tcp`. Решает три проблемы разом:
   случайные порты, необходимость знать IP, медленный перебор 254 хостов.
5. **Изоляция adb-сервера** — `ANDROID_ADB_SERVER_PORT`, иначе бинарник поднимает
   демон на 5037 и дерётся с adb пользователя и Android Studio, а
   `reset_adb_server()` убивает чужие сессии.
6. **Снятие хардкода 5555.**

## Чего делать НЕ стоит

**Не реализовывать спаривание на чистом Python.** Потребуется: SPAKE2 в варианте
BoringSSL (свои точки M/N и своя схема вывода ключа —
[python-spake2](https://github.com/warner/python-spake2) из коробки
несовместим), TLS 1.3 с PSK (штатный `ssl` этого не даёт, а
[sslpsk2](https://github.com/autinerd/sslpsk2) — C-расширение, ломающее
кроссплатформенную PyInstaller-матрицу), плюс собственный клиентский сертификат
для `_adb-tls-connect`. Это недели работы и вечный источник поломок ради того,
что уже лежащий в архиве бинарник делает сам.

## Оценка трудозатрат (исходная)

| Работа | Строк | Срок |
|---|---|---|
| Транспорт + автоопределение + `adb pair` | ~450 | 2–3 дня |
| mDNS-обнаружение | ~150 | 1 день |
| Изоляция сервера, порты, новые строки в `locales.py` | ~100 | 1 день |

≈ неделя до полной поддержки Android 11–16.

## Решения, принятые пользователем по ходу

- При обнаружении `STLS` — пробовать `adb connect` встроенным platform-tools
  (шаг 2), сначала со штатным `~/.android`.
- Ввод порта — **во всех меню, где порт может быть введён** (шаг 6).
- mDNS — **оба бэкенда**: основной `adb mdns check`/`adb mdns services`,
  запасной — библиотека `zeroconf` (шаг 4).
- Изоляция — **полная, с переносом ключей**: свой `ANDROID_ADB_SERVER_PORT` и
  свой каталог adb (через подмену `HOME` в `env=` дочернего процесса) плюс
  однократное копирование ключей из `~/.android`, чтобы уже спаренные устройства
  продолжили работать (шаг 5).

## Перепроверенные технические факты

- `A_STLS = 0x534C5453 = 1397511251` — ровно то число из ошибки adb_shell.
  `AUTH = 0x48545541`, `CNXN = 0x4E584E43`. `MESSAGE_FORMAT = b'<6I'`, размер 24.
  Отправлять `msg.pack() + msg.data`: `pack()` возвращает только заголовок.
  Проба протокола прототипирована против поддельного adbd на loopback:
  `AUTH → legacy`, `STLS → tls`, `CNXN → authorized`.
- **Каталог ключей adb уводится только на Linux и macOS, и только через `HOME`.**
  Измерено на настоящем бинарнике 37.0.1 на всех трёх ОС: `ANDROID_USER_HOME` и
  `ANDROID_SDK_HOME` игнорируются везде вопреки документации Android, а на
  Windows не работает ни одна переменная — adb берёт профиль через системный
  API. `HOME` подменяется только в `env=` дочернего процесса, никогда в
  `os.environ`, иначе поедут `Path.home()` и platformdirs самой программы.
- `adb pair` печатает `Successfully paired to <host>:<port> [guid=…]`; код
  возврата ненадёжен — проверять и его, и строку.
- Порт спаривания и порт подключения на устройстве **разные** и случайные.
- `zeroconf` 0.151.3: 18 скомпилированных Cython-модулей + зависимость `ifaddr`.
  Проверено по колесу: ни один его модуль не импортирует ничего из списка
  `excludes` в `pyinstaller.spec`.

## Источники

- [adb_wifi.md — протокол ADB over TLS](https://github.com/LineageOS/android_packages_modules_adb/blob/lineage-23.2/docs/dev/adb_wifi.md)
- [adb_shell issue #175 (STLS)](https://github.com/JeffLIrion/adb_shell/issues/175)
- [ADB на Chromecast with Google TV после Android 14](https://community.home-assistant.io/t/android-debug-bridge-on-chromecast-wgtv-4k-after-android-14-update/864037)
- [Android Debug Bridge (adb)](https://developer.android.com/tools/adb)
- [Environment variables (ANDROID_USER_HOME)](https://developer.android.com/tools/variables)

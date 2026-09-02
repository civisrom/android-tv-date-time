# Состояние работ: поддержка беспроводной отладки Android 11–16

Обновлено 2026-09-02. Все шесть шагов дорожной карты закончены.

## Дорожная карта

| Шаг | Что | Статус |
|---|---|---|
| 1 | Абстракция транспорта (`DeviceTransport`, `PlatformToolsTransport`) | готово |
| 2 | Автоопределение протокола (CNXN → AUTH / STLS / CNXN) | готово |
| 3 | Спаривание `adb pair` + подменю «Беспроводная отладка» | готово |
| 4 | mDNS-обнаружение (встроенный adb + запасной zeroconf) | готово |
| 5 | Изоляция от системного adb (свой сервер и свой каталог ключей) | готово |
| 6 | Снятие хардкода порта 5555, ввод порта во всех меню | готово |

49 тестов зелёные (было 19 до начала работ). Аудит проведён, в том числе против
**настоящего бинарника adb 37.0.1** и настоящего mDNS-анонса.

## Что получилось

### `src/android_time_fixer.py`

Модульный уровень: `DEFAULT_ADB_PORT`, `DEFAULT_ADB_SERVER_PORT`, `A_STLS`,
`ADB_HOME_FILES`, `ADB_MDNS_SERVICES`, `_IP_PORT_RE`, `ADB_CONNECTION_ERRORS`,
`_subprocess_encoding()`, `adb_env()`, необязательный импорт zeroconf,
`_MdnsCollector`, `DeviceTransport` (Protocol), `PlatformToolsTransport`.

Методы: `_setup_adb_environment()`, `_migrate_adb_home()`, `adb_dot_android`,
`load_adb_server_port()`, `_detect_adb_protocol()`, `_connect_via_platform_tools()`,
`_run_adb()`, `mdns_available()`, `_parse_mdns_services()`, `_mdns_via_adb()`,
`_mdns_via_zeroconf()`, `mdns_discover()`, `validate_pairing_code()`,
`pair_device()`, `_select_from_list()`, `_pick_wireless_address()`,
`wireless_menu()`, `prompt_adb_port(default, persist)`, `load_scan_port()`,
`save_scan_port()`, `_split_cidr_port()`.

Главное меню: пункт 11 «Беспроводная отладка Android 11+».

### Прочее
- `locales.py`: 223 ключа (было 198), ru/en, плейсхолдеры сверены.
- `tests/test_core.py`: 49 тестов.
- `pyproject.toml` / `poetry.lock`: добавлен `zeroconf ^0.151.3`; диф лока
  минимальный — только `zeroconf` и `ifaddr`.
- `pyinstaller.spec`: `zeroconf` и `ifaddr` в списке `collect_all`.
- `README.md` / `README_EN.md`: описан подкаталог `adb/`.

## Как проверять

```bash
PY=/home/claude/openai/android-tv-date-time/.venv/bin/python   # venv с зависимостями
$PY -m py_compile src/android_time_fixer.py locales.py tests/test_core.py
$PY -m unittest discover -s tests -v      # 49 OK
$PY -m pyflakes src/android_time_fixer.py locales.py scripts tests
PYTHONPATH=src $PY -c "import android_time_fixer"
```

В самом проекте venv нет. CI гоняет `unittest` (`.github/workflows/ci.yml`) и
`flake8 --select=F`. `poetry 2.3.4` установлен в тот же venv.

Для проверки на настоящем adb: скачать platform-tools r37.0.1 с
`https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip`
(sha256 `d230f138…`, тот же, что в `build.yml`).

## Что осталось непроверенным

- **Живое устройство с беспроводной отладкой.** `adb pair` проверен только на
  пути ошибки (несуществующий адрес) и на валидации кода; успешный путь
  разбирается по строке `Successfully paired to …`, снятой из документации, а не
  с реального устройства.
- **`adb mdns services` с реальным устройством в списке.** Проверен формат
  заголовка на настоящем adb (`List of discovered mdns services`) и разбор
  синтетических строк; настоящей строки с устройством не видели.
- **Заморозка zeroconf в PyInstaller** на трёх платформах — только в CI. Если
  сборка упадёт на импорте, внести `.pyd`/`.so` zeroconf в `upx_exclude`
  (`pyinstaller.spec`): UPX умеет портить Cython-расширения.

## Обязательные идиомы проекта

- Тесты — **только stdlib `unittest`**, не pytest. Объект строится через
  `AndroidTVTimeFixer.__new__(AndroidTVTimeFixer)` с ручной установкой
  атрибутов: настоящий `__init__` требует бинарника adb, которого в дереве нет.
  **Добавив новый атрибут, нужный методу под тестом, обнови все места `__new__`.**
- Подмена методов — прямым присваиванием, а не `mock.patch`.
- `Popen` импортирован по имени → патчится как `src.android_time_fixer.Popen`;
  `subprocess.run` → как `src.android_time_fixer.subprocess.run`.
- Всё, что печатает, оборачивать в `contextlib.redirect_stdout(io.StringIO())`.
- Локали: неизвестный ключ **не падает**, а молча возвращает
  `"Missing translation: <key>"` — отсюда обязательная сверка скриптом.

## Ключевые технические факты (перепроверены на настоящем adb)

- `A_STLS = 0x534C5453 = 1397511251` — ровно то число из ошибки adb_shell.
  `AUTH = 0x48545541`, `CNXN = 0x4E584E43`. `MESSAGE_FORMAT = b'<6I'`, размер 24.
  Отправлять надо `msg.pack() + msg.data`: `pack()` даёт только заголовок.
- **adb 37.0.1 берёт свой каталог ТОЛЬКО из `HOME`/`USERPROFILE`.**
  `ANDROID_USER_HOME` и `ANDROID_SDK_HOME` он игнорирует вопреки документации
  Android и всё равно пишет в `$HOME/.android`. Поэтому изоляция сделана
  подменой `HOME` в `env=` дочернего процесса — никогда в `os.environ`, иначе
  поедут `Path.home()` и platformdirs самой программы.
- `adb mdns check` на живом бинарнике отвечает
  `mdns daemon version [adb discovery 0.0.0]`, `adb mdns services` — заголовком
  `List of discovered mdns services`.
- Порт спаривания и порт подключения на устройстве **разные** и случайные —
  ради этого и нужен mDNS.
- `self.keys_folder` (`data_dir/keys`) — пара для adb_shell, плоская раскладка,
  `adb` её не читает. Каталог adb — `data_dir/adb/.android`.

# Аудит беспроводной отладки Android 14

Дата: 2026-09-05. Исходная точка: `39f3b1b0268bcfc2b8e531ddf08182afb6895a6e`,
версия desktop 2.6.0, APK с `kadb-android:2.1.3`.

## Вывод

Основа протокола совместима с AOSP 14, однако исходная интеграция содержала
подтверждённые дефекты. Исправления в этой ветке устраняют потерю ключа APK,
ложный успех desktop, неограниченное ожидание сопряжения и ошибки жизненного
цикла NSD. Уже работающий mDNS не заменялся другим механизмом.

**Физического Android TV 14 для проверки не было.** Исследование исходников,
реальные TLS/SPAKE2-обмены на localhost и JVM-модели callbacks не доказывают
работу системного Conscrypt/hidden API, TV UI и OEM-сетевого стека конкретной
прошивки. Это оставшаяся проверка совместимости, а не подтверждённый дефект.

## Подтверждённые Дефекты И Исправления

| Приоритет | До исправления | Воспроизведение | Исправление |
|---|---|---|---|
| P1 | Ключ Kadb существовал только в памяти процесса | Два JVM-процесса с оригинальным AAR получили разные публичные ключи | `TimeFixerApplication` один раз настраивает `AdbIdentity`: атомарное файловое хранилище в `noBackupFilesDir` |
| P1 | Desktop принимал `failed to authenticate to ...` с кодом выхода 0 за подключение | Настоящий `adb 37.0.1` с локальным smart-socket сервером | Точное положительное подтверждение нужного адреса и реальная shell-проба до публикации успеха |
| P2 | `Kadb.pair()` не имел connect/read/общего deadline | TCP peer принимал соединение, но pairing оставался заблокирован через 17 секунд | `PairingSession` владеет сокетом: TCP 10 с, TLS/read 15 с; `PairingClient` ограничивает всю операцию 60 с и закрывает сокет при отмене |
| P2 | API 34 callback использовался как одноразовый resolve; отсутствие первого update блокировало очередь | Два найденных сервиса, первый не разрешается; stop/start и поздние callbacks | Независимые API 34 подписки, поколения поиска и токены сервисов, снятие подписок, сброс legacy-очереди при остановке |
| P2 | Ошибка проверки зашифрованного PeerInfo показывалась как недоступность сети | Повреждённый GCM-тег вызывал обычный `IOException` upstream | Отдельные причины: отказ аутентификации, таймаут, TLS, прерванный/неверный протокол, недоступный адрес |

Отмена coroutine теперь освобождает `busy` в `finally`. Повреждённый или пустой
существующий файл ключа не заменяется автоматически: отказ сети не должен
незаметно менять идентичность клиента. Код сопряжения на диск не пишется.
Обновление с прежней версии потребует одного нового сопряжения, поскольку
прежний ключ после завершения процесса восстановить неоткуда.

## Сопоставление С Протоколом

### Сопряжение

Порт из окна с кодом принимает прямой TLS 1.3. Это **не TLS-PSK**: шестизначный
код дополняется 64 байтами TLS exporter с меткой `adb-label\0`, затем участвует
в SPAKE2. Роли используют идентификаторы `adb pair client\0` и
`adb pair server\0`. Из результата получают ключ AES-128-GCM через HKDF-SHA256
с контекстом `adb pairing_auth aes-128-gcm key`. PeerInfo имеет размер 8192
байта: клиент передаёт RSA public key, устройство возвращает GUID.
См. AOSP 14: [pairing_connection.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/pairing_connection/pairing_connection.cpp),
[pairing_auth.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/pairing_auth/pairing_auth.cpp),
[aes_128_gcm.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/pairing_auth/aes_128_gcm.cpp).

Адаптер приложения сохраняет эти криптографические операции Kadb, но сам
управляет сокетом и проверяет длину/версию/тип пакетов до выделения памяти.
Для повреждённого тега есть отдельная ошибка аутентификации. Разрыв соединения
не объявляется доказанно неверным кодом: это может быть закрытое окно или
перепутанный порт, поэтому пользователю предлагается обновить код и адрес.

### Подключение

Другой, connect-порт сначала принимает ADB `CNXN`, затем происходит
`STLS -> STLS -> TLS 1.3`, после чего идут ADB-пакеты и shell. Доверие
проверяется по клиентскому публичному ключу, сохранённому при сопряжении.
См. [ADB STLS](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/adb.cpp),
[проверка сертификата в daemon/auth.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/daemon/auth.cpp),
[TLS connection](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/tls/tls_connection.cpp).

Отказ авторизации может быть текстом ответа успешного обмена CLI с
ADB-сервером. Поэтому нулевой exit code `adb connect` сам по себе недостаточен:
[client/transport_local.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/android-14.0.0_r1/client/transport_local.cpp).
Desktop по-прежнему делегирует TLS штатному platform-tools; APK использует Kadb.

### Системный Android TLS

Нужная сигнатура `Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int)`
существует в [Conscrypt Android 14](https://android.googlesource.com/platform/external/conscrypt/+/android-14.0.0_r1/common/src/main/java/org/conscrypt/Conscrypt.java).
APK использует этот системный exporter через HiddenApiBypass, как исходная
интеграция Kadb. Публичного переносимого Android SDK API для этой операции
адаптер не предполагает. Проверка доступа к hidden API остаётся обязательной
на устройстве. JVM-тесты используют внешний Conscrypt только в test classpath.

## mDNS И Разрешения

Три сервиса сохранены: `_adb._tcp`, `_adb-tls-pairing._tcp`,
`_adb-tls-connect._tcp`. Последний объявляет endpoint, **не подтверждает**
авторизацию конкретного клиента. Порты pairing/connect нельзя подменять друг
другом или постоянно считать равными 5555.

Ветка API 34 выбирается по ОС **устройства с APK**, а не управляемого TV.
`registerServiceInfoCallback` непрерывен, и первый `onServiceUpdated` может
вообще не прийти. Поэтому подписки нельзя ставить в последовательную очередь,
ожидающую этот callback. Основание: [NsdManager Android 14](https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-14.0.0_r1/framework-t/src/android/net/nsd/NsdManager.java).
Проверка на настоящем NSD нужна дополнительно к модели callback-порядка.

Для используемых NSD-вызовов Android 13-16 `NEARBY_WIFI_DEVICES` не требуется;
ненужный запрос удалён. Правило Android 17 зависит также от target SDK:
при текущем target 36 доступ к LAN предоставляется через `INTERNET`, при
target 37+ нужен `ACCESS_LOCAL_NETWORK`. Отказ ограничит и ручное подключение,
не только discovery. См. [официальные правила LAN permission](https://developer.android.com/privacy-and-security/local-network-permission).
Это не следует переносить на Android 14.

IPv6 не добавлялся: существующие ввод/проверка адреса поддерживают IPv4.
NSD теперь не предлагает IPv6-only адрес, который приложение не сможет принять.

## Исправленные Неточности Старых Заметок

- Официальный порог wireless debugging: телефоны Android 11+, TV Android 13+,
  а не универсальное обещание для всех TV 11+. Наличие меню зависит от
  прошивки: [Android Developers, ADB](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).
- APK сохраняет minSdk 23 для legacy ADB; его TLS-клиент требует Android 10+
  с системным TLS 1.3. Старые Android 6-9 показывают ограничение вместо
  попытки неподдерживаемого сопряжения. Это не версия управляемого TV.
- Открытый connect-сервис не означает, что этот клиент уже спарен.
- Нет универсального подтверждения срока кода «около минуты». Следует
  использовать код и порт из текущего открытого окна, не закрывая его.
- Адрес подключения лучше узнать до открытия окна pairing. Инструкция
  «не закрывая модальное окно, вернуться на главный экран» ненадёжна.
- Перезапуск приложения не должен менять ключ, но отзыв/истечение авторизации,
  очистка данных и переустановка могут потребовать новый pairing.
- Android 14 может отключать Wi-Fi debugging при потере/смене Wi-Fi;
  Ethernet-only и OEM UI требуют отдельной проверки. См.
  [AdbDebuggingManager Android 14](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/adb/AdbDebuggingManager.java).

## Воспроизведение

Итоговый локальный прогон: **114 Android-тестов прошли**, Python: **61 тест,
60 прошли и 1 штатно пропущен**. Проверка настоящим adb отклоняет ложный
успех аутентификации. Debug APK собирался, Android Lint: 0 ошибок
(11 предупреждений, включая существующие замечания к зависимостям).
После последних уточнений стенда повторно выполнен весь Android test suite.
Релизные APK/desktop-архивы собирает GitHub Actions по тегу `v2.6.1`;
локальная release-сборка была остановлена по выбранному процессу публикации.

Сборка/тесты требуют JDK 21, Android SDK 37 и Gradle wrapper проекта.
Причина JDK 21: опубликованный Kadb содержит class-file version 65;
прежние тесты не загружали настоящие криптографические классы. Java target
приложения остаётся 17. В CI обновлена только JDK Android jobs.

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

`AdbIdentityTest` проверяет повторную загрузку сохранённого ключа, отказ при
повреждённом и пустом файле. `PairingClientTest` выполняет настоящие TLS 1.3,
SPAKE2, AES-GCM и CNXN/STLS/shell, проверяет повреждённый тег, oversized packet,
SO_TIMEOUT, общий deadline и закрытие сокета при отмене. NSD-тесты проверяют
реальный класс приложения с минимальными framework-fakes API 33/34. Эти
fakes находятся только в `src/test` и не попадают в APK.

```bash
python -m unittest discover -s tests -q
ANDROID_HOME=/path/to/android-sdk python tests/wireless/native_adb_auth.py
```

Вторая команда дополнительно запускает настоящий `adb` против localhost
smart-socket fixture: ни реальный TV, ни пользовательский ADB-сервер не нужны.
На исходном коде воспроизводился ложный успех, на исправленном ожидается отказ.

Локальный сервер сопряжения также был независимо проверен штатным
`adb 37.0.1`: успешный pairing с GUID `adb-audit-fixture`. Для этого использованы
отдельные временные ключи и случайный серверный порт, сервер затем остановлен.
Это уменьшает риск самосогласованной ошибки стенда, но не делает его adbd.
Исходные материалы аудита сохранены локально в игнорируемой папке `docs/`;
этот отчёт и регрессионные тесты включены в репозиторий.

## Зависимости И Остаточные Риски

Проверены [опубликованные sources 2.1.3](https://repo.maven.apache.org/maven2/com/flyfishxu/kadb-android/2.1.3/kadb-android-2.1.3-sources.jar)
и [AAR 2.1.3](https://repo.maven.apache.org/maven2/com/flyfishxu/kadb-android/2.1.3/kadb-android-2.1.3.aar).
SHA-256 AAR: `553f71038fcbfd7b83b095133587c968087fe8dcfedb17def32566bb0c6a26cb`.
SHA-256 sources: `314f9cdb5de1453b6a8f24d30c074a36926b684593ba8119fef1fe641726f0b4`.
Публичный API хранилища описан в [документации KadbCert](https://github.com/flyfishxu/Kadb/blob/main/docs/kadbcert.md);
для выводов о 2.1.3 использованы именно опубликованные исходники, а не только main.

`PairingSession` использует внутренние crypto API Kadb; версия закреплена.
Это ограниченный обход отсутствующего публичного управления сокетом, не
переписывание криптографии. Перед обновлением Kadb/Kotlin обязательны сборка
и протокольные тесты; suppression внутренних API не является стабильным
контрактом Kotlin. Проверенные [sources 2.1.4](https://repo.maven.apache.org/maven2/com/flyfishxu/kadb-android/2.1.4/kadb-android-2.1.4-sources.jar)
не добавляют нужные deadline/cancellation в pairing, поэтому простое обновление
не выбрано в качестве исправления.

## Минимальная Проверка На Устройстве

1. APK на Android 10+: новый код, два правильных порта, pairing, shell и
   чтение/проверяемая запись настройки на Android TV 14.
2. Force-stop и перезагрузка клиента: connect прежним ключом без нового кода.
3. Отозвать доверие на TV: ни APK, ни desktop не должны показать ложный успех.
4. Неверный код, закрытое окно, перепутанные порты и пропавшая сеть:
   ограниченное ожидание, понятная ошибка, возможность повторить.
5. Выключить/включить wireless debugging: обнаружить новый connect-port.
6. APK на Android 14+: исчезновение pairing-сервиса до первого update,
   переход в фон/возврат, отсутствие старых устройств после stop.
7. APK на самом TV: отдельно проверить переключение между настройками и
   приложением. `127.0.0.1:5555` остаётся legacy, не заменяет TLS pairing.

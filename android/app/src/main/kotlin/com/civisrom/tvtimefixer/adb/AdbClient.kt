package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress

/** Результат выполнения команды на устройстве. */
data class ShellResult(
    val output: String,
    val errorOutput: String,
    val exitCode: Int,
) {
    /** Вывод без хвостовых переводов строки — команды adb почти всегда их добавляют. */
    val trimmedOutput: String get() = output.trim()
}

/**
 * Подключение к устройству.
 *
 * Интерфейс существует, чтобы логика подключения проверялась на JVM без
 * телевизора: настоящая реализация поверх Kadb требует сокета и рукопожатия,
 * а всё, что вокруг — состояния, разбор ошибок, выбор адреса — не требует.
 */
interface AdbClient : AutoCloseable {
    fun shell(command: String): ShellResult
    fun isAlive(): Boolean
}

/** Почему не удалось подключиться. Разделено по тому, что пользователю делать дальше. */
enum class ConnectionError {
    /** Адрес не разобран: не IPv4 или порт вне диапазона. */
    INVALID_ADDRESS,

    /** До устройства не достучались: не та сеть, не тот адрес, отладка выключена. */
    UNREACHABLE,

    /** Устройство требует спаривания по коду (Android 11+). */
    PAIRING_REQUIRED,

    /** Ключ не авторизован: на экране устройства ждёт запрос подтверждения. */
    NOT_AUTHORIZED,

    /** Код спаривания не из шести цифр либо отвергнут устройством. */
    PAIRING_REJECTED,

    UNKNOWN,
}

/** Ошибка подключения с исходной причиной — причина нужна для журнала, не для экрана. */
class AdbConnectionException(
    val reason: ConnectionError,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

/**
 * Создаёт подключения. Отдельно от AdbClient, потому что спаривание —
 * операция над устройством, а не над уже открытым соединением.
 */
interface AdbClientFactory {
    /** Открывает соединение. Бросает AdbConnectionException. */
    fun connect(address: DeviceAddress): AdbClient

    /** Спаривание по шестизначному коду (Android 11+). */
    suspend fun pair(address: DeviceAddress, pairingCode: String)
}

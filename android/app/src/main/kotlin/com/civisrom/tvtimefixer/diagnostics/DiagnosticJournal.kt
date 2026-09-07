package com.civisrom.tvtimefixer.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.civisrom.tvtimefixer.adb.ConnectionError

enum class Operation {
    APP_START, CONNECT_NETWORK, CONNECT_USB, PAIR, DISCONNECT, USB_PERMISSION,
    USB_DETACHED, USB_SCAN, READ_DEVICE, CHECK_NTP, APPLY_NTP, SCAN_NTP, DISCOVERY, CRASH, STORAGE, CHECK_TIME,
}

enum class Outcome { STARTED, SUCCESS, FAILED, CANCELLED }
enum class DiagnosticTransport { NONE, NETWORK, USB }
enum class DiagnosticIssue {
    NTP_UNREACHABLE, NTP_UNUSABLE, NTP_NOT_CONFIRMED, INVALID_NTP,
    USB_NONE, USB_NO_ADB, USB_ENUMERATION, USB_HOST_UNSUPPORTED,
    TIME_MISMATCH, TIME_UNCERTAIN, TIME_UNAVAILABLE,
}

data class DiagnosticEvent(
    val id: Long,
    val time: Long,
    val operation: Operation,
    val outcome: Outcome,
    val transport: DiagnosticTransport = DiagnosticTransport.NONE,
    val durationMs: Long = 0,
    val reason: ConnectionError? = null,
    val details: String = "",
    val issue: DiagnosticIssue? = null,
)

data class DiagnosticSnapshot(
    val events: List<DiagnosticEvent> = emptyList(),
    val storageAvailable: Boolean = true,
    val dropped: Long = 0,
    val previousCrashId: Long? = null,
)

/** Только имена классов/методов и номера строк. Throwable.message не читается. */
internal fun safeExceptionDetails(error: Throwable): String = buildString {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    var framesLeft = 20
    repeat(3) {
        val cause = current ?: return@repeat
        if (!seen.add(cause)) return@repeat
        appendLine(safeSymbol(cause.javaClass.name))
        for (frame in cause.stackTrace.take(framesLeft)) {
            appendLine("  ${safeSymbol(frame.className)}.${safeSymbol(frame.methodName)}:${frame.lineNumber}")
            framesLeft--
        }
        current = cause.cause
    }
}.take(2048)

private fun safeSymbol(value: String): String =
    value.takeIf { it.length <= 180 && it.matches(Regex("[A-Za-z0-9_.$<>-]+")) } ?: "?"

/**
 * Один писатель, неблокирующая ограниченная очередь, никаких строк от ADB.
 * Основной файл + его временная копия <= 240 КиБ; crash + копия <= 16 КиБ.
 * JVM-тесты используют тот же код хранения, что и APK.
 */
class DiagnosticJournal(
    private val directory: File,
    private val legacyCrash: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private sealed interface Command {
        data class Append(val event: DiagnosticEvent, val epoch: Long) : Command
        data object Clear : Command
        data object Refresh : Command
        data class Barrier(val done: CountDownLatch) : Command
        data object Stop : Command
    }

    private val ids = AtomicLong(System.currentTimeMillis() * 1000)
    private val clearEpoch = AtomicLong(0)
    private val queue = ArrayBlockingQueue<Command>(256)
    private val mutableSnapshot = MutableStateFlow(DiagnosticSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val eventsFile = File(directory, "events.bin")
    private val crashFile = File(directory, "crash.bin")
    private var events = mutableListOf<DiagnosticEvent>()
    private val worker = thread(name = "diagnostics-writer", isDaemon = true) {
        load()
        var processedClear = 0L
        while (true) {
            val command = queue.take()
            val requestedClear = clearEpoch.get()
            if (processedClear != requestedClear) {
                events.clear()
                val saved = persist()
                if (saved) {
                    runCatching {
                        if (crashFile.exists() && !crashFile.delete()) throw IOException()
                        if (legacyCrash?.exists() == true && !legacyCrash.delete()) throw IOException()
                    }.onFailure { unavailable() }
                }
                mutableSnapshot.update { it.copy(dropped = 0, previousCrashId = null) }
                processedClear = requestedClear
            }
            when (command) {
                Command.Stop -> return@thread
                is Command.Barrier -> command.done.countDown()
                Command.Clear -> Unit
                is Command.Append -> {
                    if (command.epoch == processedClear) {
                        events.add(command.event)
                        persist()
                    }
                }
                Command.Refresh -> persist()
            }
        }
    }

    fun record(
        operation: Operation,
        outcome: Outcome,
        transport: DiagnosticTransport = DiagnosticTransport.NONE,
        durationMs: Long = 0,
        reason: ConnectionError? = null,
        error: Throwable? = null,
        issue: DiagnosticIssue? = null,
        usb: UsbObservation? = null,
    ): Long {
        val details = listOfNotNull(usb?.details(), error?.let {
            runCatching { safeExceptionDetails(it) }.getOrDefault("")
        }).joinToString("\n").take(2048)
        val event = DiagnosticEvent(ids.incrementAndGet(), clock(), operation, outcome, transport,
            durationMs.coerceAtLeast(0), reason, details, issue)
        offer(Command.Append(event, clearEpoch.get()))
        return event.id
    }

    fun clear() {
        clearEpoch.incrementAndGet()
        // Даже при полной очереди следующий её элемент выполнит очистку;
        // старые ожидающие записи не вернут удалённую историю.
        queue.offer(Command.Clear)
    }
    fun refresh() = offer(Command.Refresh)

    private fun offer(command: Command) {
        if (!queue.offer(command)) mutableSnapshot.update { it.copy(dropped = it.dropped + 1) }
    }

    /** Единственная синхронная запись: маленький файл перед системным crash handler. */
    fun recordCrash(error: Throwable) {
        runCatching {
            val event = DiagnosticEvent(ids.incrementAndGet(), clock(), Operation.CRASH, Outcome.FAILED,
                details = safeExceptionDetails(error))
            atomicWrite(crashFile, encode(listOf(event)))
        }
    }

    private fun load() {
        try {
            if (eventsFile.exists()) events = decode(eventsFile, FILE_BUDGET).toMutableList()
        } catch (_: Exception) {
            events.clear()
            events.add(DiagnosticEvent(ids.incrementAndGet(), clock(), Operation.STORAGE, Outcome.FAILED))
        }
        var importedCrash: DiagnosticEvent? = null
        try {
            if (crashFile.exists()) importedCrash = decode(crashFile, 8192).firstOrNull()
            if (importedCrash == null && legacyCrash?.isFile == true) {
                // Старый файл мог содержать сообщения с адресами/секретами.
                // Импортируем только распознанные строки кадров стека.
                val text = legacyCrash.inputStream().use { input ->
                    val bytes = ByteArray(8192)
                    val size = input.read(bytes)
                    if (size > 0) String(bytes, 0, size, Charsets.UTF_8) else ""
                }
                val frame = Regex("\\s*at ([A-Za-z0-9_.$]+)\\(([A-Za-z0-9_.$]+):([0-9]+)\\)\\s*")
                val details = text.lineSequence().mapNotNull { line ->
                    frame.matchEntire(line)?.let { "  ${it.groupValues[1]}:${it.groupValues[3]}" }
                }.take(20).joinToString("\n").take(2048)
                importedCrash = DiagnosticEvent(ids.incrementAndGet(), legacyCrash.lastModified(),
                    Operation.CRASH, Outcome.FAILED, details = details)
            }
            importedCrash?.let { event -> if (events.none { it.id == event.id }) events.add(event) }
        } catch (_: Exception) {
            unavailable()
        }
        val loadedId = events.maxOfOrNull { it.id } ?: 0
        // AtomicLong.updateAndGet появился только в API 24; minSdk = 23.
        while (true) {
            val current = ids.get()
            if (current >= loadedId || ids.compareAndSet(current, loadedId)) break
        }
        val saved = persist()
        mutableSnapshot.update { state -> state.copy(previousCrashId = importedCrash?.id?.takeIf { id ->
            events.any { it.id == id }
        }) }
        // Удаление только после успешного переноса. При повторной загрузке ID
        // предотвращает дублирование сохранённой записи падения.
        if (saved && importedCrash != null) {
            runCatching { crashFile.delete(); legacyCrash?.delete() }
        }
    }

    private fun persist(): Boolean {
        events.removeAll { clock() - it.time > MAX_AGE_MS }
        events = events.takeLast(MAX_EVENTS).toMutableList()
        var bytes = encode(events)
        while (bytes.size > FILE_BUDGET && events.isNotEmpty()) {
            events.removeAt(0)
            bytes = encode(events)
        }
        val saved = runCatching { atomicWrite(eventsFile, bytes) }.isSuccess
        mutableSnapshot.update { state -> state.copy(events = events.toList(), storageAvailable = saved,
            previousCrashId = state.previousCrashId?.takeIf { id -> events.any { it.id == id } }) }
        return saved
    }

    private fun unavailable() = mutableSnapshot.update { it.copy(storageAvailable = false) }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Storage unavailable")
        val temp = File(directory, file.name + ".tmp")
        temp.outputStream().use { it.write(bytes) }
        if (!temp.renameTo(file)) throw IOException("Storage unavailable")
    }

    private fun encode(values: List<DiagnosticEvent>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(values.size)
            values.forEach {
                out.writeLong(it.id); out.writeLong(it.time)
                out.writeUTF(it.operation.name); out.writeUTF(it.outcome.name); out.writeUTF(it.transport.name)
                out.writeLong(it.durationMs); out.writeUTF(it.reason?.name.orEmpty()); out.writeUTF(it.details)
                out.writeUTF(it.issue?.name.orEmpty())
            }
        }
    }.toByteArray()

    private fun decode(file: File, limit: Int): List<DiagnosticEvent> {
        require(file.length() <= limit)
        val bytes = file.inputStream().use { it.readBytes() }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val count = input.readInt().also { require(it in 0..MAX_EVENTS) }
            List(count) {
                val id = input.readLong()
                val time = input.readLong()
                val operation = Operation.valueOf(input.readUTF())
                val outcome = Outcome.valueOf(input.readUTF())
                val transport = DiagnosticTransport.valueOf(input.readUTF())
                val duration = input.readLong().also { require(it >= 0) }
                val reason = input.readUTF().takeIf { it.isNotEmpty() }?.let(ConnectionError::valueOf)
                val details = input.readUTF().also { require(it.length <= 2048) }
                val issue = input.readUTF().takeIf { it.isNotEmpty() }?.let(DiagnosticIssue::valueOf)
                DiagnosticEvent(id, time, operation, outcome, transport, duration, reason, details, issue)
            }.also { require(input.available() == 0) }
        }
    }

    /** Только синхронизация тестов/завершения: вызывающий UI не ждёт запись. */
    internal fun awaitIdle() {
        val done = CountDownLatch(1)
        check(queue.offer(Command.Barrier(done), 5, TimeUnit.SECONDS))
        check(done.await(5, TimeUnit.SECONDS))
    }

    override fun close() {
        awaitIdle()
        queue.put(Command.Stop)
        worker.join(5000)
    }

    companion object {
        const val MAX_EVENTS = 200
        const val FILE_BUDGET = 120 * 1024
        const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

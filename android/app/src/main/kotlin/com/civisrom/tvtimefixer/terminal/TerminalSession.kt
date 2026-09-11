package com.civisrom.tvtimefixer.terminal

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val TERMINAL_OUTPUT_LIMIT = 64 * 1024
const val TERMINAL_HISTORY_LIMIT = 50
enum class TerminalStatus { READY, RUNNING, COMPLETE, CANCELLED, TIMEOUT, FAILED }
data class TerminalOutput(val text: String, val error: Boolean)
data class TerminalState(
    val draft: String = "",
    val command: String = "",
    val target: String = "",
    val history: List<String> = emptyList(),
    val output: List<TerminalOutput> = emptyList(),
    val truncated: Boolean = false,
    val status: TerminalStatus = TerminalStatus.READY,
    val exitCode: Int? = null,
    val problem: TerminalProblem? = null,
    val transferred: Long? = null,
    val helpRequest: Int = 0,
    val files: List<String> = emptyList(),
) {
    val running: Boolean get() = status == TerminalStatus.RUNNING
}

/** Memory-only command history; command bodies never enter diagnostics or savedInstanceState. */
class TerminalSession {
    private val mutable = MutableStateFlow(TerminalState())
    val state = mutable.asStateFlow()
    private val output = ArrayDeque<TerminalOutput>()
    private var outputSize = 0
    private var dropped = false
    private var published = 0L
    private var transferred: Long? = null
    private var escape = 0

    fun edit(text: String) { mutable.update { it.copy(draft = text.take(TERMINAL_COMMAND_LIMIT + 1), problem = null) } }
    fun clearHistory() { mutable.update { it.copy(history = emptyList()) } }
    fun refreshFiles(files: List<String>) { mutable.update { it.copy(files = files) } }
    fun showHelp() { mutable.update { it.copy(helpRequest = it.helpRequest + 1) } }

    fun clearOutput() {
        if (state.value.running) return
        output.clear(); outputSize = 0; dropped = false; escape = 0
        transferred = null
        mutable.update { it.copy(output = emptyList(), command = "", target = "", truncated = false,
            status = TerminalStatus.READY, exitCode = null, problem = null, transferred = null) }
    }

    fun start(target: String): TerminalCommand? {
        if (state.value.running) return null
        val parsed = try { parseTerminalCommand(state.value.draft) } catch (e: TerminalException) {
            mutable.update { it.copy(problem = e.problem) }; return null
        }
        if (parsed == TerminalCommand.Help) { showHelp(); return null }
        if (parsed == TerminalCommand.Clear) { clearOutput(); return null }
        clearOutput()
        mutable.update { old ->
            val text = old.draft.trim()
            old.copy(command = text, target = target, history = (listOf(text) + old.history.filter { it != text })
                .take(TERMINAL_HISTORY_LIMIT), status = TerminalStatus.RUNNING)
        }
        published = 0L
        return parsed
    }

    /** Called from the single worker. UI snapshots are throttled, even for an endless logcat. */
    fun append(text: String, error: Boolean = false) {
        val safe = buildString {
            text.forEach { c ->
                // Drop ANSI/OSC terminal instructions, including sequences split across reads.
                when (escape) {
                    1 -> escape = when (c) { '[' -> 2; ']' -> 3; else -> 0 }
                    2 -> if (c in '@'..'~') escape = 0
                    3 -> when (c) { '\u0007' -> escape = 0; '\u001b' -> escape = 4 }
                    4 -> escape = if (c == '\\') 0 else 3
                    else -> when {
                        c == '\u001b' -> escape = 1
                        c == '\r' -> append('\n')
                        c == '\n' || c == '\t' || (c.code >= 32 && c.code != 127 && c !in '\u202a'..'\u202e' && c !in '\u2066'..'\u2069') -> append(c)
                    }
                }
            }
        }
        // Small immutable blocks also bound Compose paragraph measurement.
        var offset = 0
        while (offset < safe.length) {
            var end = minOf(offset + 1024, safe.length)
            if (end < safe.length && safe[end - 1].isHighSurrogate() && safe[end].isLowSurrogate()) end--
            val part = safe.substring(offset, end)
            val last = output.lastOrNull()
            if (last != null && last.error == error && last.text.length + part.length <= 1024) {
                output.removeLast(); output.addLast(last.copy(text = last.text + part))
            } else output.addLast(TerminalOutput(part, error))
            outputSize += part.length
            offset = end
        }
        while (outputSize > TERMINAL_OUTPUT_LIMIT && output.isNotEmpty()) {
            outputSize -= output.removeFirst().text.length
            dropped = true
        }
        publish()
    }

    fun progress(bytes: Long) { transferred = bytes; publish() }

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (force || now - published >= 100_000_000) {
            published = now
            val snapshot = output.toList()
            mutable.update { it.copy(output = snapshot, truncated = dropped, transferred = transferred) }
        }
    }

    fun finish(exitCode: Int?) {
        publish(true)
        mutable.update { it.copy(status = TerminalStatus.COMPLETE, exitCode = exitCode) }
    }

    fun fail(error: Exception) {
        publish(true)
        mutable.update { it.copy(status = when (error) {
            is CancellationException, is InterruptedException -> TerminalStatus.CANCELLED
            is SocketTimeoutException -> TerminalStatus.TIMEOUT
            else -> TerminalStatus.FAILED
        }, problem = (error as? TerminalException)?.problem ?: TerminalProblem.IO) }
    }
}

package com.civisrom.tvtimefixer.terminal

const val TERMINAL_TIMEOUT_MS = 300_000
const val TERMINAL_COMMAND_LIMIT = 4000

enum class TerminalProblem { EMPTY, TOO_LONG, INVALID_TEXT, QUOTES, INTERACTIVE, HOST_COMMAND, ARGUMENTS, FILE, CONNECTION, IO, INSTALL }
class TerminalException(val problem: TerminalProblem) : Exception(problem.name)

sealed interface TerminalCommand {
    data class Shell(val text: String) : TerminalCommand
    data class Adb(val name: String, val arguments: List<String>) : TerminalCommand
    data object Help : TerminalCommand
    data object Clear : TerminalCommand
}

/** Shell text is preserved verbatim; only host ADB arguments are tokenized. */
fun parseTerminalCommand(input: String): TerminalCommand {
    val text = input.trim()
    if (text.isEmpty()) throw TerminalException(TerminalProblem.EMPTY)
    if (text.toByteArray(Charsets.UTF_8).size > TERMINAL_COMMAND_LIMIT) throw TerminalException(TerminalProblem.TOO_LONG)
    if (text.any { it == '\u0000' || (it.code < 32 && it !in "\t\r\n") }) throw TerminalException(TerminalProblem.INVALID_TEXT)
    if (text in setOf("help", "?", "adb help", "adb --help")) return TerminalCommand.Help
    if (text == "clear") return TerminalCommand.Clear
    val first = text.takeWhile { !it.isWhitespace() }
    if (first != "adb") return TerminalCommand.Shell(text)
    val tail = text.removePrefix("adb").trimStart()
    val name = tail.takeWhile { !it.isWhitespace() }
    var rest = tail.drop(name.length).trimStart()
    if (name == "shell") {
        while (rest.takeWhile { !it.isWhitespace() } in setOf("-n", "-T", "--")) {
            val option = rest.takeWhile { !it.isWhitespace() }
            rest = rest.drop(option.length).trimStart()
            if (option == "--") break
        }
        if (rest.isBlank() || rest.startsWith("-")) throw TerminalException(TerminalProblem.INTERACTIVE)
        if (rest.first() in "\"'") {
            val quoted = splitAdbArguments(rest)
            if (quoted.size == 1) rest = quoted.single()
        }
        if (rest.isBlank()) throw TerminalException(TerminalProblem.INTERACTIVE)
        return TerminalCommand.Shell(rest)
    }
    if (name.isBlank() || name.startsWith("-")) throw TerminalException(TerminalProblem.ARGUMENTS)
    return TerminalCommand.Adb(name, splitAdbArguments(rest))
}

internal fun splitAdbArguments(text: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var started = false
    text.forEachIndexed { index, char ->
        when {
            escaped -> { word.append(char); escaped = false; started = true }
            char == '\\' && quote != '\'' && (quote != '"' || text.getOrNull(index + 1) in listOf('\\', '"', '$', '`', '\n')) -> {
                escaped = true; started = true
            }
            char == quote -> quote = null
            quote != null -> word.append(char)
            char == '\'' || char == '"' -> { quote = char; started = true }
            char.isWhitespace() -> if (started) { words += word.toString(); word.clear(); started = false }
            else -> { word.append(char); started = true }
        }
    }
    if (escaped || quote != null) throw TerminalException(TerminalProblem.QUOTES)
    if (started) words += word.toString()
    return words
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

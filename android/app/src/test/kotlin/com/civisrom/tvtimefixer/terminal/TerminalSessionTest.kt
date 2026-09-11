package com.civisrom.tvtimefixer.terminal

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TerminalSessionTest {
    @Test fun `history is deduplicated bounded and selection never executes a command`() {
        val session = TerminalSession()
        repeat(60) { session.edit("echo $it"); session.start("TV"); session.finish(0) }
        assertEquals(50, session.state.value.history.size)
        session.edit("echo 40")
        assertFalse(session.state.value.running)
        session.start("TV"); session.finish(0)
        assertEquals("echo 40", session.state.value.history.first())
        assertEquals(1, session.state.value.history.count { it == "echo 40" })
        session.clearHistory()
        assertTrue(session.state.value.history.isEmpty())
    }

    @Test fun `repeated start and clear cannot replace an active command`() {
        val session = TerminalSession()
        session.edit("logcat"); session.start("first TV")
        session.append("early output")
        session.edit("reboot"); assertNull(session.start("second TV"))
        session.clearOutput()
        session.fail(CancellationException("test"))
        assertEquals(TerminalStatus.CANCELLED, session.state.value.status)
        assertEquals("logcat", session.state.value.command)
        assertEquals("first TV", session.state.value.target)
        assertEquals("early output", session.state.value.output.joinToString("") { it.text })
    }

    @Test fun `endless output has bounded text and blocks with visible truncation`() {
        val session = TerminalSession()
        session.edit("logcat"); session.start("TV")
        repeat(10000) { session.append("line".repeat(300), it % 2 == 0) }
        session.append("last output")
        session.finish(null)
        val state = session.state.value
        assertTrue(state.truncated)
        assertTrue(state.output.sumOf { it.text.length } <= TERMINAL_OUTPUT_LIMIT)
        assertTrue(state.output.all { it.text.length <= 1024 })
        assertTrue(state.output.joinToString("") { it.text }.endsWith("last output"))
        assertNull(state.exitCode)
    }

    @Test fun `ANSI OSC and bidi controls cannot rewrite displayed output`() {
        val session = TerminalSession()
        session.edit("id"); session.start("TV")
        session.append("before\u001b["); session.append("31mred\u001b[0m\u001b]52;c;hidden")
        session.append("\u0007after\u202efake\u0000")
        session.finish(2)
        assertEquals("beforeredafterfake", session.state.value.output.joinToString("") { it.text })
        assertEquals(2, session.state.value.exitCode)
    }

    @Test fun `display blocks never split an emoji surrogate pair`() {
        val session = TerminalSession().apply { edit("echo"); start("TV") }
        val text = "a".repeat(1023) + "🌍" + "b".repeat(1023) + "🚀"
        session.append(text); session.finish(0)
        assertEquals(text, session.state.value.output.joinToString("") { it.text })
        assertTrue(session.state.value.output.none { it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate() })
    }

    @Test fun `timeout preserves partial output and distinguishes a remote failure exit`() {
        val session = TerminalSession()
        session.edit("sleep 900"); session.start("TV"); session.append("partial")
        session.fail(SocketTimeoutException())
        assertEquals(TerminalStatus.TIMEOUT, session.state.value.status)
        assertEquals("partial", session.state.value.output.single().text)
        session.edit("false"); session.start("TV"); session.finish(1)
        assertEquals(TerminalStatus.COMPLETE, session.state.value.status)
        assertEquals(1, session.state.value.exitCode)
    }

    @Test fun `help clear and validation are local and never enter history`() {
        val session = TerminalSession()
        session.edit("help"); assertNull(session.start("TV"))
        assertEquals(1, session.state.value.helpRequest)
        session.edit("clear"); assertNull(session.start("TV"))
        session.edit("adb shell"); assertNull(session.start("TV"))
        assertEquals(TerminalProblem.INTERACTIVE, session.state.value.problem)
        assertTrue(session.state.value.history.isEmpty())
    }
}

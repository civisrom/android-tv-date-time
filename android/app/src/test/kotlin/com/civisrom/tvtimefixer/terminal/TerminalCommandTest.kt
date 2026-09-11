package com.civisrom.tvtimefixer.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalCommandTest {
    @Test fun `raw shell preserves quotes pipes variables and scripts`() {
        val script = "for item in 'a b' c; do printf '%s\\n' \"\$item\"; done | head -n 1"
        assertEquals(TerminalCommand.Shell(script), parseTerminalCommand(script))
        assertEquals(TerminalCommand.Shell(script), parseTerminalCommand("adb shell $script"))
        assertEquals(TerminalCommand.Shell("echo one\necho two"), parseTerminalCommand("adb\tshell\techo one\necho two"))
    }

    @Test fun `host argument tokenizer handles quoted filenames and literal shell metacharacters`() {
        assertEquals(TerminalCommand.Adb("install", listOf("-r", "my apk's file.apk")),
            parseTerminalCommand("adb install -r \"my apk's file.apk\""))
        assertEquals(listOf("a b", "c", "", "x'y"), splitAdbArguments("a\\ b 'c' \"\" 'x'\\''y'"))
        assertEquals(TerminalCommand.Adb("push", listOf("a; touch b", "/sdcard/a")),
            parseTerminalCommand("adb push 'a; touch b' /sdcard/a"))
    }

    @Test fun `noninteractive shell options and a quoted remote command follow adb syntax`() {
        assertEquals(TerminalCommand.Shell("echo \"hello world\""), parseTerminalCommand("adb shell -n -T 'echo \"hello world\"'"))
        assertEquals(TerminalCommand.Shell("printf '%s\\n' hi"), parseTerminalCommand("adb shell \"printf '%s\\n' hi\""))
        assertEquals(TerminalCommand.Shell("echo hi"), parseTerminalCommand("adb shell -- echo hi"))
    }

    @Test fun `blank control NUL overlong UTF8 and unfinished host quotes are rejected`() {
        listOf("" to TerminalProblem.EMPTY, "echo\u0000hello" to TerminalProblem.INVALID_TEXT,
            "adb push 'file" to TerminalProblem.QUOTES, "adb install file\\" to TerminalProblem.QUOTES,
            "я".repeat(2001) to TerminalProblem.TOO_LONG, "adb shell" to TerminalProblem.INTERACTIVE,
            "adb shell -tt" to TerminalProblem.INTERACTIVE, "adb -s other shell id" to TerminalProblem.ARGUMENTS).forEach { (input, reason) ->
            assertEquals(reason, assertThrows(TerminalException::class.java) { parseTerminalCommand(input) }.problem)
        }
    }

    @Test fun `unknown host commands never silently become shell commands`() {
        assertEquals(TerminalCommand.Adb("not-a-command", listOf("data")), parseTerminalCommand("adb not-a-command data"))
        assertEquals(TerminalCommand.Help, parseTerminalCommand("help"))
        assertEquals(TerminalCommand.Help, parseTerminalCommand("adb --help"))
        assertEquals(TerminalCommand.Clear, parseTerminalCommand("clear"))
        assertEquals(TerminalCommand.Shell("clear; echo ok"), parseTerminalCommand("clear; echo ok"))
    }

    @Test fun `executable catalog examples are accepted and reference identifiers are unique`() {
        val examples = terminalCatalog.flatMap { it.examples }
        assertTrue(examples.size >= 60)
        assertEquals(examples.size, examples.map { it.id }.toSet().size)
        assertEquals(examples.size, examples.map { it.command }.toSet().size)
        examples.filter { it.availableInApp }.forEach { parseTerminalCommand(it.command) }
        assertEquals(terminalCatalog.size, terminalCatalog.map { it.id }.toSet().size)
        assertTrue(terminalCatalog.filter { it.id.startsWith("pc_") }.flatMap { it.examples }.all { !it.availableInApp })
    }

    @Test fun `reference covers the public host commands listed by Platform Tools 37 and AOSP adb manpage`() {
        val commands = terminalCatalog.flatMap { it.examples }.map { it.command }
        val publicCommands = listOf("devices", "help", "version", "connect", "disconnect", "pair", "forward", "reverse",
            "mdns", "push", "pull", "sync", "shell", "emu", "install", "install-multiple", "install-multi-package",
            "uninstall", "bugreport", "jdwp", "logcat", "disable-verity", "enable-verity", "keygen", "wait-for-device",
            "get-state", "get-serialno", "get-devpath", "remount", "reboot", "sideload", "root", "unroot", "usb", "tcpip",
            "start-server", "kill-server", "reconnect", "attach", "detach", "host-features", "features", "server-status")
        publicCommands.forEach { name ->
            assertTrue("Missing ADB reference: $name", commands.any { it == "adb $name" || it.startsWith("adb $name ") })
        }
        // Device-provided help is needed because Android versions and vendor firmware expose different shell tools.
        listOf("cmd -l", "pm help", "am help", "toybox").forEach { name ->
            assertTrue("Missing device help: $name", commands.any { it.removePrefix("adb shell ") == name })
        }
    }

    @Test fun `shell quoting roundtrips local filenames including apostrophes`() {
        listOf("normal.apk", "a b.apk", "a'b;\$x.apk").forEach { name ->
            assertEquals(listOf(name), splitAdbArguments(shellQuote(name)))
        }
    }
}

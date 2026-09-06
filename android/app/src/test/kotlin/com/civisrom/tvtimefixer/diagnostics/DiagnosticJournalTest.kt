package com.civisrom.tvtimefixer.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class DiagnosticJournalTest {
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("diagnostics-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun `history survives restart and drops records after seven days`() = temporary { root ->
        var now = 1_800_000_000_000L
        DiagnosticJournal(root, clock = { now }).use {
            it.record(Operation.USB_SCAN, Outcome.SUCCESS, DiagnosticTransport.USB, issue = DiagnosticIssue.USB_NO_ADB)
            it.awaitIdle()
            assertEquals(1, it.snapshot.value.events.size)
        }
        DiagnosticJournal(root, clock = { now }).use {
            it.awaitIdle()
            assertEquals(DiagnosticIssue.USB_NO_ADB, it.snapshot.value.events.single().issue)
            now += DiagnosticJournal.MAX_AGE_MS + 1
            it.refresh(); it.awaitIdle()
            assertTrue(it.snapshot.value.events.isEmpty())
        }
    }

    @Test fun `event and disk budgets include atomic temporary and crash files`() = temporary { root ->
        DiagnosticJournal(root).use { journal ->
            val error = IOException("NEVER_STORE_TOKEN")
            error.stackTrace = Array(20) { StackTraceElement("example." + "A".repeat(150), "method", "source", it) }
            repeat(300) {
                journal.record(Operation.CHECK_NTP, Outcome.FAILED, error = error)
                if (it % 20 == 0) journal.awaitIdle()
            }
            journal.awaitIdle()
            journal.recordCrash(error)
            assertTrue(journal.snapshot.value.events.size <= 200)
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= 256 * 1024)
            assertTrue(File(root, "events.bin").length() <= DiagnosticJournal.FILE_BUDGET)
        }
    }

    @Test fun `messages codes identifiers and raw output never enter history`() = temporary { root ->
        val secret = "TOKEN_password_pairing123456_serial_PRIVATE_output"
        DiagnosticJournal(root).use { journal ->
            val cause = IllegalArgumentException(secret)
            val error = IOException(secret, cause)
            journal.record(Operation.PAIR, Outcome.FAILED, error = error)
            journal.recordCrash(error)
            journal.awaitIdle()
            val text = journal.snapshot.value.events.joinToString { it.details }
            assertFalse(text.contains(secret))
            assertTrue(text.contains("java.io.IOException"))
            assertTrue(text.contains("java.lang.IllegalArgumentException"))
            root.listFiles()!!.forEach { assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(secret)) }
        }
    }

    @Test fun `crash is migrated once and remains until retention or explicit clear`() = temporary { root ->
        DiagnosticJournal(root).use { it.recordCrash(IOException("secret")) }
        assertTrue(File(root, "crash.bin").isFile)
        DiagnosticJournal(root).use {
            it.awaitIdle()
            assertEquals(1, it.snapshot.value.events.count { event -> event.operation == Operation.CRASH })
            assertNotNull(it.snapshot.value.previousCrashId)
            assertFalse(File(root, "crash.bin").exists())
        }
        DiagnosticJournal(root).use {
            it.awaitIdle()
            assertEquals(1, it.snapshot.value.events.size)
            it.clear(); it.awaitIdle()
            assertTrue(it.snapshot.value.events.isEmpty())
        }
        DiagnosticJournal(root).use { it.awaitIdle(); assertTrue(it.snapshot.value.events.isEmpty()) }
    }

    @Test fun `old crash keeps only stack frames and deletes source after saving`() = temporary { root ->
        val old = File(root, "last-crash.txt").apply {
            writeText("worker\njava.io.IOException: SECRET_PASSWORD\n\tat example.Client.connect(Client.kt:42)\nsecret.raw.output")
        }
        DiagnosticJournal(File(root, "new"), old).use {
            it.awaitIdle()
            val details = it.snapshot.value.events.single().details
            assertEquals("  example.Client.connect:42", details)
            assertFalse(old.exists())
        }
    }

    @Test fun `unwritable storage preserves memory and old crash for retry`() = temporary { root ->
        val blocked = File(root, "blocked").apply { writeText("file instead of directory") }
        val old = File(root, "old.txt").apply { writeText("java.io.IOException: secret") }
        DiagnosticJournal(blocked, old).use {
            it.record(Operation.CONNECT_USB, Outcome.SUCCESS)
            it.awaitIdle()
            assertFalse(it.snapshot.value.storageAvailable)
            assertTrue(it.snapshot.value.events.any { event -> event.operation == Operation.CONNECT_USB })
            assertTrue(old.exists())
        }
    }

    @Test fun `corrupt and oversized histories recover without blocking new events`() = temporary { root ->
        for (size in listOf(3, DiagnosticJournal.FILE_BUDGET + 1)) {
            File(root, "events.bin").writeBytes(ByteArray(size))
            DiagnosticJournal(root).use {
                it.record(Operation.APP_START, Outcome.SUCCESS)
                it.awaitIdle()
                assertTrue(it.snapshot.value.storageAvailable)
                assertTrue(it.snapshot.value.events.any { event -> event.operation == Operation.STORAGE })
                assertEquals(Operation.APP_START, it.snapshot.value.events.last().operation)
            }
        }
    }

    @Test fun `concurrent producers are nonblocking and records have unique IDs`() = temporary { root ->
        DiagnosticJournal(root).use { journal ->
            val done = CountDownLatch(4)
            repeat(4) {
                thread {
                    repeat(500) { journal.record(Operation.DISCOVERY, Outcome.SUCCESS) }
                    done.countDown()
                }
            }
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            journal.awaitIdle()
            val events = journal.snapshot.value.events
            assertTrue(events.size <= 200)
            assertEquals(events.size, events.map { it.id }.distinct().size)
        }
    }

    @Test fun `clear discards queued old events even when the queue was full`() = temporary { root ->
        DiagnosticJournal(root).use { journal ->
            repeat(1000) { journal.record(Operation.DISCOVERY, Outcome.SUCCESS) }
            journal.clear()
            journal.awaitIdle()
            assertTrue(journal.snapshot.value.events.isEmpty())
            journal.record(Operation.CONNECT_USB, Outcome.SUCCESS)
            journal.awaitIdle()
            assertEquals(Operation.CONNECT_USB, journal.snapshot.value.events.single().operation)
        }
    }
}

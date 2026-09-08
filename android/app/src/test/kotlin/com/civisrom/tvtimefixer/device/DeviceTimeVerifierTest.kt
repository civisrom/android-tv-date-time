package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.SntpResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DeviceTimeVerifierTest {
    private val epoch = 1_800_000_000_000L
    private var elapsed = 1_000L
    private var adbDelay = 80L
    private var offset = 0L
    private var automatic = "1"
    private var timeZone = "Europe/Moscow"
    private var server = "pool.ntp.org"
    private var queryCount = 0
    private var queryFailure: Exception? = null
    private var failingCommand: String? = null
    private var commandFailure: Exception = IOException("private device details")
    private var dateOutput: String? = null
    private var dateExit = 0
    private var dateError = ""
    private var reference = SntpResult(20, 0.0, referenceTimeMillis = epoch, referenceElapsedMillis = elapsed)
    private val commands = mutableListOf<String>()
    private val client = object : AdbClient {
        override fun shell(command: String): ShellResult {
            commands += command
            if (command == failingCommand) throw commandFailure
            return when (command) {
                "settings get global ntp_server" -> ShellResult(server, "", 0)
                "settings get global auto_time" -> ShellResult(automatic, "", 0)
                "getprop persist.sys.timezone" -> ShellResult(timeZone, "", 0)
                "date +%s" -> {
                    elapsed += adbDelay
                    ShellResult(dateOutput ?: ((epoch + offset) / 1000).toString(), dateError, dateExit)
                }
                else -> error("unexpected command: $command")
            }
        }
        override fun isAlive() = true
        override fun close() = Unit
    }
    private val query = object : SntpQuery {
        override fun query(host: String): SntpResult {
            queryCount++
            assertEquals(server, host)
            queryFailure?.let { throw it }
            return reference
        }
    }
    private fun verify() = DeviceTimeVerifier(query) { elapsed }.verify(client)

    @Test fun `aligned device clocks match with ADB and NTP uncertainty`() {
        val check = verify()
        assertEquals(DeviceTimeStatus.MATCH, check.status)
        assertEquals(epoch, check.deviceTimeMillis)
        assertEquals(0.46, check.differenceSeconds!!, 0.001)
        assertEquals(0.552, check.uncertaintySeconds!!, 0.001)
        assertEquals(true, check.automaticTime)
        assertEquals(server, check.server)
        assertEquals("Europe/Moscow", check.timeZoneId)
    }

    @Test fun `device zone affects display but never the UTC comparison`() {
        val moscow = verify()
        elapsed = 1_000L
        timeZone = "America/New_York"
        val newYork = verify()
        assertEquals(moscow.deviceTimeMillis, newYork.deviceTimeMillis)
        assertEquals(moscow.differenceSeconds, newYork.differenceSeconds)
        assertEquals(moscow.status, newYork.status)
        assertEquals(timeZone, newYork.timeZoneId)
    }

    @Test fun `unknown or unreadable zone does not become a false GMT display`() {
        for (value in listOf("", "Unknown/Zone", "null")) {
            timeZone = value
            assertNull(verify().timeZoneId)
        }
        failingCommand = "getprop persist.sys.timezone"
        assertNull(verify().timeZoneId)
    }

    @Test fun `both slow and fast device clocks report a signed mismatch`() {
        for (value in listOf(-120_000L, 120_000L)) {
            elapsed = 1_000L
            offset = value
            val check = verify()
            assertEquals(DeviceTimeStatus.MISMATCH, check.status)
            assertEquals(value > 0, check.differenceSeconds!! > 0)
        }
    }

    @Test fun `uncertainty interval must fit entirely inside five seconds to confirm`() {
        adbDelay = 0
        for ((difference, expected) in listOf(4498L to DeviceTimeStatus.MATCH,
            4499L to DeviceTimeStatus.UNCERTAIN, 5502L to DeviceTimeStatus.UNCERTAIN,
            5503L to DeviceTimeStatus.MISMATCH)) {
            for (sign in listOf(-1, 1)) {
                reference = reference.copy(rttMs = 0, referenceTimeMillis = epoch + 500 - difference * sign)
                assertEquals("difference=$difference sign=$sign", expected, verify().status)
            }
        }
    }

    @Test fun `a slow ADB response cannot produce a false confirmation`() {
        adbDelay = 12_000
        assertEquals(DeviceTimeStatus.UNCERTAIN, verify().status)
    }

    @Test fun `a slow NTP response cannot produce a false confirmation`() {
        reference = reference.copy(rttMs = 12_000)
        assertEquals(DeviceTimeStatus.UNCERTAIN, verify().status)
    }

    @Test fun `old reference is inconclusive instead of silently reused`() {
        elapsed += 31_000
        val check = verify()
        assertEquals(DeviceTimeStatus.UNCERTAIN, check.status)
        assertNull(check.differenceSeconds)
    }

    @Test fun `comparison does not trust the phone wall clock offset`() {
        reference = reference.copy(offsetSeconds = 86_400_000.0)
        assertEquals(DeviceTimeStatus.MATCH, verify().status)
    }

    @Test fun `an elapsed clock discontinuity is inconclusive`() {
        elapsed = 999
        assertEquals(DeviceTimeStatus.UNCERTAIN, verify().status)
    }

    @Test fun `automatic time disabled remains visible even when the clocks match`() {
        automatic = "0"
        val check = verify()
        assertEquals(DeviceTimeStatus.MATCH, check.status)
        assertEquals(false, check.automaticTime)
    }

    @Test fun `missing automatic time is unknown rather than off`() {
        for (value in listOf("null", "", "permission denied", "2")) {
            automatic = value
            assertNull(verify().automaticTime)
        }
    }

    @Test fun `unsupported automatic setting does not prevent reading clocks`() {
        failingCommand = "settings get global auto_time"
        val check = verify()
        assertEquals(DeviceTimeStatus.MATCH, check.status)
        assertNull(check.automaticTime)
    }

    @Test fun `no configured server or invalid value never triggers a network request`() {
        for (value in listOf("null", "", "$(reboot)", "127.0.0.1;reboot", "ntp://pool.ntp.org")) {
            server = value
            assertEquals(DeviceTimeStatus.NO_SERVER, verify().status)
        }
        assertEquals(0, queryCount)
    }

    @Test fun `NTP failure is explicitly unavailable`() {
        queryFailure = IOException("secret network details")
        val check = verify()
        assertEquals(DeviceTimeStatus.NTP_UNAVAILABLE, check.status)
        assertEquals(true, check.automaticTime)
        assertNull(check.differenceSeconds)
        assertFalse(check.toString().contains("secret"))
    }

    @Test fun `an offset without a monotonic reference cannot confirm the clocks`() {
        reference = SntpResult(20, 0.0)
        assertEquals(DeviceTimeStatus.NTP_UNAVAILABLE, verify().status)
    }

    @Test fun `bad date output cannot become a matching clock`() {
        for (value in listOf("", "null", "permission denied", "1800000000\n1800000001", "-1",
            "253402300800", "9223372036854775807", "9223372036854775808")) {
            dateOutput = value
            assertEquals(value, DeviceTimeStatus.DEVICE_UNAVAILABLE, verify().status)
        }
    }

    @Test fun `epoch zero is a readable but incorrect device clock`() {
        dateOutput = "0\n"
        assertEquals(DeviceTimeStatus.MISMATCH, verify().status)
    }

    @Test fun `nonzero exit and stderr cannot be interpreted as successful date output`() {
        dateExit = 1
        assertEquals(DeviceTimeStatus.DEVICE_UNAVAILABLE, verify().status)
        dateExit = 0
        dateError = "permission denied"
        assertEquals(DeviceTimeStatus.DEVICE_UNAVAILABLE, verify().status)
    }

    @Test fun `lost ADB connection is distinct from an NTP failure`() {
        failingCommand = "date +%s"
        assertEquals(DeviceTimeStatus.DEVICE_UNAVAILABLE, verify().status)
        failingCommand = "settings get global ntp_server"
        assertEquals(DeviceTimeStatus.DEVICE_UNAVAILABLE, verify().status)
    }

    @Test fun `cancellation is never converted to a failed check`() {
        queryFailure = CancellationException("cancel")
        assertThrows(CancellationException::class.java) { verify() }
        queryFailure = null
        commandFailure = CancellationException("cancel")
        for (command in listOf("settings get global ntp_server", "settings get global auto_time", "date +%s",
            "getprop persist.sys.timezone")) {
            failingCommand = command
            assertThrows(CancellationException::class.java) { verify() }
        }
    }

    @Test fun `verification never writes settings or forces synchronization`() {
        verify()
        assertEquals(listOf("settings get global ntp_server", "settings get global auto_time", "date +%s",
            "getprop persist.sys.timezone"), commands)
        assertEquals(1, queryCount)
    }

    @Test fun `a repeated check observes corrected clocks and obtains a fresh NTP reply`() {
        offset = 120_000
        assertEquals(DeviceTimeStatus.MISMATCH, verify().status)
        offset = 0
        assertEquals(DeviceTimeStatus.MATCH, verify().status)
        assertEquals(2, queryCount)
    }

    @Test fun `an explicitly configured IPv4 address can be the comparison server`() {
        server = "192.0.2.10"
        assertEquals(DeviceTimeStatus.MATCH, verify().status)
        assertEquals(1, queryCount)
    }

    @Test fun `NTP fractional rounding at the five second boundary cannot produce a match`() {
        adbDelay = 0
        reference = reference.copy(rttMs = 0)
        dateOutput = (epoch / 1000 - 5).toString()
        assertEquals(DeviceTimeStatus.UNCERTAIN, verify().status)
    }

    @Test fun `verdict stays conservative across asymmetric delays and subsecond sampling`() {
        val random = java.util.Random(27)
        repeat(500) {
            elapsed = 1_000
            adbDelay = random.nextInt(10_000).toLong()
            val ntpDelay = random.nextInt(2_000)
            val referenceError = if (ntpDelay == 0) 0 else random.nextInt(ntpDelay + 1) - ntpDelay / 2
            val trueOffset = random.nextInt(60_001) - 30_000
            val readAt = random.nextInt(adbDelay.toInt() + 1)
            reference = SntpResult(ntpDelay.toLong(), 0.0, referenceTimeMillis = epoch + referenceError,
                referenceElapsedMillis = elapsed)
            dateOutput = ((epoch + readAt + trueOffset) / 1000).toString()
            when (verify().status) {
                DeviceTimeStatus.MATCH -> assertTrue("false match at $trueOffset ms", kotlin.math.abs(trueOffset) <= 5_000)
                DeviceTimeStatus.MISMATCH -> assertTrue("false mismatch at $trueOffset ms", kotlin.math.abs(trueOffset) > 5_000)
                DeviceTimeStatus.UNCERTAIN -> Unit
                else -> fail("unexpected unavailable result")
            }
        }
    }
}

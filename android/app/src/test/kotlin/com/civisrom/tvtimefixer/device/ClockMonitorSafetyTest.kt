package com.civisrom.tvtimefixer.device

import org.junit.Assert.*
import org.junit.Test

class ClockMonitorSafetyTest {
    @Test fun `late wakeup records one sample and waits a full interval instead of catching up`() {
        var now = 1_000L
        val monitor = ClockMonitor { now }
        monitor.start()
        monitor.add(skipped = ClockSampleSkip.BUSY)
        now += 95_000L
        assertTrue(monitor.due())
        monitor.add(skipped = ClockSampleSkip.UNAVAILABLE)
        assertFalse(monitor.due())
        now += 29_999L
        assertFalse(monitor.due())
        now += 1L
        assertTrue(monitor.due())
        assertEquals(2, monitor.state.samples.size)
    }

    @Test fun `reversed monotonic origin ends the session without reporting an old success again`() {
        var now = 10_000L
        val monitor = ClockMonitor { now }
        monitor.start()
        monitor.add(DeviceTimeCheck(DeviceTimeStatus.MATCH))
        now = 9_999L
        assertFalse(monitor.due())
        assertFalse(monitor.state.running)
        assertEquals(ClockMonitorEnd.DURATION, monitor.state.ended)
        monitor.add(DeviceTimeCheck(DeviceTimeStatus.MATCH))
        assertEquals(1, monitor.state.samples.size)
    }

    @Test fun `user stop is final until an explicit fresh start and clears the old session on restart`() {
        var now = 0L
        val monitor = ClockMonitor { now }
        monitor.start()
        monitor.add(skipped = ClockSampleSkip.BUSY)
        monitor.stop(ClockMonitorEnd.USER)
        now = 60_000L
        monitor.add(DeviceTimeCheck(DeviceTimeStatus.MATCH))
        assertFalse(monitor.due())
        assertEquals(ClockMonitorEnd.USER, monitor.state.ended)
        assertEquals(1, monitor.state.samples.size)
        monitor.start()
        assertTrue(monitor.state.samples.isEmpty())
        assertNull(monitor.state.ended)
        assertTrue(monitor.due())
    }

    @Test fun `measurement completed after total deadline is not added as a fresh sample`() {
        var now = 0L
        val monitor = ClockMonitor { now }
        monitor.start()
        assertTrue(monitor.due())
        now = CLOCK_MONITOR_DURATION_MS + 1
        monitor.add(DeviceTimeCheck(DeviceTimeStatus.MATCH))
        assertTrue(monitor.state.samples.isEmpty())
        assertFalse(monitor.state.running)
    }
}

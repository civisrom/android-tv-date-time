package com.civisrom.tvtimefixer.device

import org.junit.Assert.*
import org.junit.Test

class ClockMonitorTest {
    @Test fun `monitor records skips and uncertainty at intervals then stops without background work`() {
        var now = 1000L
        val monitor = ClockMonitor { now }
        monitor.start()
        assertTrue(monitor.due())
        monitor.add(skipped = ClockSampleSkip.BUSY)
        assertFalse(monitor.due())
        now += 30_000
        val check = DeviceTimeCheck(DeviceTimeStatus.UNCERTAIN, uncertaintySeconds = 6.0)
        monitor.add(check)
        assertEquals(6.0, monitor.state.samples.last().check!!.uncertaintySeconds!!, 0.0)
        monitor.stop(ClockMonitorEnd.BACKGROUND)
        now += 60_000
        monitor.add(skipped = ClockSampleSkip.UNAVAILABLE)
        assertEquals(2, monitor.state.samples.size)
        assertEquals(ClockMonitorEnd.BACKGROUND, monitor.state.ended)
        assertFalse(monitor.due())
    }

    @Test fun `ten minute deadline and twenty sample cap are independent of wall clock`() {
        var now = 0L
        val monitor = ClockMonitor { now }
        monitor.start()
        repeat(20) { monitor.add(skipped = ClockSampleSkip.BUSY); now += 30_000 }
        assertFalse(monitor.due())
        assertEquals(20, monitor.state.samples.size)
        monitor.start()
        now += 600_000
        assertFalse(monitor.due())
        assertTrue(monitor.state.samples.isEmpty())
        assertEquals(ClockMonitorEnd.DURATION, monitor.state.ended)
    }
}

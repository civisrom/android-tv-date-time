package com.civisrom.tvtimefixer.device

const val CLOCK_MONITOR_INTERVAL_MS = 30_000L
const val CLOCK_MONITOR_DURATION_MS = 600_000L
const val CLOCK_MONITOR_MAX_SAMPLES = 20

enum class ClockSampleSkip { BUSY, UNAVAILABLE }
enum class ClockMonitorEnd { USER, DURATION, BACKGROUND, DISCONNECTED }
data class ClockSample(val elapsedMs: Long, val check: DeviceTimeCheck? = null, val skipped: ClockSampleSkip? = null)
data class ClockMonitorState(
    val running: Boolean = false,
    val samples: List<ClockSample> = emptyList(),
    val ended: ClockMonitorEnd? = null,
)

/** Monotonic limits do not depend on the incorrect wall clock being diagnosed. No service or persistence. */
class ClockMonitor(private val monotonic: () -> Long) {
    private var started = 0L
    private var next = 0L
    var state = ClockMonitorState()
        private set

    fun start(): ClockMonitorState {
        started = monotonic(); next = started
        state = ClockMonitorState(running = true)
        return state
    }

    fun due(): Boolean {
        val now = monotonic()
        if (state.running && (now < started || now - started >= CLOCK_MONITOR_DURATION_MS ||
                state.samples.size >= CLOCK_MONITOR_MAX_SAMPLES)) stop(ClockMonitorEnd.DURATION)
        return state.running && now >= next
    }

    fun add(check: DeviceTimeCheck? = null, skipped: ClockSampleSkip? = null): ClockMonitorState {
        require((check == null) != (skipped == null))
        if (!due()) return state
        val elapsed = (monotonic() - started).coerceAtLeast(0)
        state = state.copy(samples = state.samples + ClockSample(elapsed, check, skipped))
        next = monotonic() + CLOCK_MONITOR_INTERVAL_MS
        return state
    }

    fun stop(reason: ClockMonitorEnd): ClockMonitorState {
        if (state.running) state = state.copy(running = false, ended = reason)
        return state
    }
}

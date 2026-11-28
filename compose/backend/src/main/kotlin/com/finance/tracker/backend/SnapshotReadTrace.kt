package com.finance.tracker.backend

internal object SnapshotReadTrace {
    class Timing(var calls: Int = 0, var elapsedNs: Long = 0)

    private val active = ThreadLocal<MutableMap<String, Timing>>()

    fun <T> capture(samples: MutableMap<String, Timing>, action: () -> T): T {
        check(active.get() == null)
        active.set(samples)
        try { return action() }
        finally { active.remove() }
    }

    fun <T> stage(name: String, action: () -> T): T {
        val samples = active.get() ?: return action()
        val timing = samples.getOrPut(name, ::Timing)
        val start = System.nanoTime()
        try { return action() }
        finally {
            timing.calls++
            timing.elapsedNs += System.nanoTime() - start
        }
    }
}

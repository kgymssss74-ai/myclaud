package com.myclaud.audioverify.core.routing

import com.myclaud.audioverify.core.engine.PlaybackEngine
import com.myclaud.audioverify.core.engine.PlaybackMetrics
import java.util.concurrent.CountDownLatch

data class RoutedEngine(val label: String, val engine: PlaybackEngine)

/**
 * Drives multiple [PlaybackEngine] instances simultaneously (e.g. one feeding
 * speaker, another feeding BT). Start is barrier-synchronised. Each engine's
 * underrun count is tracked separately; the runner aggregates them per route.
 */
class ConcurrentRouter(private val engines: List<RoutedEngine>) {

    fun startAll() {
        val barrier = CountDownLatch(engines.size)
        val threads = engines.map { (label, e) ->
            Thread({
                barrier.countDown()
                barrier.await()
                e.start()
            }, "ConcurrentRouter-$label")
        }
        threads.forEach { it.start() }
    }

    fun snapshotAll(): Map<String, PlaybackMetrics> =
        engines.associate { it.label to it.engine.snapshot() }

    fun stopAll() {
        engines.forEach { runCatching { it.engine.stop() } }
    }
}

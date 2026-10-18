package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.runtime.FailureAccumulator
import dev.s7a.strata.runtime.minecraft.canvas.CanvasFailures
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

/**
 * Calls the actual collectors through the benchmark compilation's Kotlin friend boundary.
 * Each operation owns a fresh exception graph and borrows one synchronous callback.
 * No production visibility or diagnostic state is added for measurement.
 */
public open class CleanupFailuresBenchmark {
    /**
     * Returns the exact collected failure after attempting every independent callback.
     */
    @Benchmark
    public fun cleanup(state: Cleanup): Throwable? = state.execute()

    /**
     * The collectors intentionally preserve different suppression-graph contracts.
     */
    public enum class Collector {
        /**
         * Flattens optional later failures.
         */
        Core,

        /**
         * Remembers nested graphs without flattening them.
         */
        Canvas,
    }

    /**
     * Fixed callback counts and exception shapes, independent of sampling settings.
     */
    public enum class Workload(
        internal val callbacks: Int,
    ) {
        CleanEmpty(0),
        CleanOne(1),
        CleanMany(64),
        Single(64),
        Distinct(64),
        Identical(64),
        EqualDistinct(64),
        Nested(64),
        Cyclic(64),
        Initial(64),
    }

    /**
     * Owns only the current operation's graph and one reusable borrowed callback.
     */
    @State(Scope.Thread)
    public open class Cleanup {
        /**
         * Actual collector selected by JMH.
         */
        @JvmField
        @Param
        public var collector: Collector = Collector.Core

        /**
         * Complete success or failure shape selected by JMH.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.CleanEmpty

        private var failures: Array<Throwable> = emptyFailures
        private var initial: Throwable? = null
        private var current: Throwable? = null
        private var result: Throwable? = null
        private var completed = 0
        private val action: () -> Unit = {
            completed++
            current?.let { throw it }
        }

        /**
         * Attempts every owner and catches only the collector's final rethrow.
         * The direct catch preserves arbitrary throwable identity without allocating a Result wrapper during sampling.
         */
        @Suppress("TooGenericExceptionCaught")
        public fun execute(): Throwable? {
            prepare()
            completed = 0
            result = null
            result =
                when (collector) {
                    Collector.Core -> {
                        val accumulator = FailureAccumulator(initial)
                        repeat(workload.callbacks) { index ->
                            current = if (index < failures.size) failures[index] else null
                            accumulator.capture(action)
                        }
                        current = null
                        try {
                            accumulator.throwIfPresent()
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    }

                    Collector.Canvas -> {
                        val accumulator = CanvasFailures(initial)
                        repeat(workload.callbacks) { index ->
                            current = if (index < failures.size) failures[index] else null
                            accumulator.attempt(action)
                        }
                        current = null
                        try {
                            accumulator.throwIfPresent()
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    }
                }
            return result
        }

        private fun prepare() {
            initial = null
            failures =
                when (workload) {
                    Workload.CleanEmpty, Workload.CleanOne, Workload.CleanMany -> {
                        emptyFailures
                    }

                    Workload.Single -> {
                        arrayOf(Failure(false))
                    }

                    Workload.Distinct -> {
                        arrayOf(Failure(false), Failure(false), Failure(false))
                    }

                    Workload.Identical -> {
                        val first = Failure(false)
                        arrayOf(first, first, first)
                    }

                    Workload.EqualDistinct -> {
                        arrayOf(Failure(true), Failure(true))
                    }

                    Workload.Nested -> {
                        arrayOf<Throwable>(Failure(false), Failure(false), Failure(false), Failure(false)).also {
                            it[1].addSuppressed(it[2])
                            it[2].addSuppressed(it[3])
                        }
                    }

                    Workload.Cyclic -> {
                        arrayOf<Throwable>(Failure(false), Failure(false), Failure(false)).also {
                            it[1].addSuppressed(it[2])
                            it[2].addSuppressed(it[1])
                            it[2].addSuppressed(it[0])
                        }
                    }

                    Workload.Initial -> {
                        val first = Failure(false)
                        val existing = Failure(false)
                        val later = Failure(false)
                        first.addSuppressed(existing)
                        existing.addSuppressed(first)
                        initial = first
                        arrayOf(first, existing, later, later)
                    }
                }
        }

        /**
         * Checks callback count and the exact original suppression graph outside sampling.
         */
        public fun verifyResult() {
            check(completed == workload.callbacks) { "Cleanup skipped an independent callback" }
            if (failures.isEmpty()) {
                check(result == null) { "Successful cleanup recorded a failure" }
                return
            }
            val primary = failures[0]
            check(result === primary) { "Primary throwable identity changed" }
            val expected =
                when (workload) {
                    Workload.CleanEmpty, Workload.CleanOne, Workload.CleanMany, Workload.Single, Workload.Identical -> emptyArray()
                    Workload.Distinct -> arrayOf(failures[1], failures[2])
                    Workload.EqualDistinct -> arrayOf(failures[1])
                    Workload.Nested -> if (collector == Collector.Core) arrayOf(failures[1], failures[2], failures[3]) else arrayOf(failures[1])
                    Workload.Cyclic -> if (collector == Collector.Core) arrayOf(failures[1], failures[2]) else arrayOf(failures[1])
                    Workload.Initial -> arrayOf(failures[1], failures[2])
                }
            val actual = primary.suppressed
            check(actual.size == expected.size) { "Suppression count changed" }
            expected.indices.forEach { check(actual[it] === expected[it]) { "Suppression identity or order changed" } }
            if (workload == Workload.Nested || workload == Workload.Cyclic) check(failures[1].suppressed[0] === failures[2]) { "Nested graph changed" }
        }

        /**
         * Reports distinct observed identities outside sampling, without depending on value equality.
         */
        internal fun distinctObserved(): Int = failures.indices.count { index -> (0 until index).none { failures[it] === failures[index] } }

        /**
         * Reports the current operation's completed callback count outside sampling.
         */
        internal fun completedCallbacks(): Int = completed

        private companion object {
            val emptyFailures: Array<Throwable> = emptyArray()
        }
    }

    /**
     * Stackless errors retain suppression and deliberate value equality without charging stack capture.
     */
    private class Failure(
        private val equal: Boolean,
    ) : RuntimeException("cleanup fixture failure", null, true, false) {
        override fun equals(other: Any?): Boolean = this === other || (equal && other is Failure && other.equal)

        override fun hashCode(): Int = if (equal) 0 else System.identityHashCode(this)
    }

    /**
     * Supplies the external collector controls and untimed parity checks.
     */
    public companion object {
        /**
         * Returns every original collector control through the shared owner-thread meter.
         */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = CollectorCleanupOperationReport

        /**
         * Runs all compiled success and failure controls outside timing.
         */
        @JvmStatic
        public fun verifyWork() {
            Collector.entries.forEach { collector ->
                Workload.entries.forEach { workload ->
                    val state =
                        Cleanup().also {
                            it.collector = collector
                            it.workload = workload
                        }
                    repeat(128) {
                        state.execute()
                        state.verifyResult()
                    }
                    println("cleanup,$collector,$workload,callbacks=${state.completedCallbacks()},observedIdentities=${state.distinctObserved()}")
                }
            }
        }
    }
}

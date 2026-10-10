package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.render.DrawImage
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual packaged Fabric RGBA decoding and public host admission, including every required cold and capacity control.
 * Standard JMH owns forks, time, allocation, warmup, and sampling; fixtures use the ordinary application loader.
 */
public open class ResourceImageDecodeBenchmark {
    /**
     * Includes current source opening, decode or exact-input reuse, fresh snapshot copying, and retained host evaluation.
     */
    @Benchmark
    public fun resolve(state: Images): DrawImage = state.workload.resolve()

    /**
     * Includes fresh decoder ownership, public host construction, admission, one resolution, and terminal release.
     */
    @Benchmark
    public fun completeProtocol(state: Images): DrawImage = state.workload.completeProtocol()

    /**
     * One primed public host and actual adapter decoder per owner worker.
     */
    @State(Scope.Thread)
    public open class Images {
        /**
         * Explicit source size and direct or fixed host-admission boundary.
         */
        @JvmField
        @Param
        public var case: Case = Case.DirectSmall

        /**
         * Repeated exact input, a broad eight-source overflow set, or current-byte replacement.
         */
        @JvmField
        @Param
        public var pattern: Pattern = Pattern.Hot

        internal lateinit var workload: ResourceImageDecodeWorkload

        /**
         * Constructs deterministic PNGs and primes the actual current host outside measurements.
         */
        @Setup(Level.Trial)
        public fun setup() {
            workload = ResourceImageDecodeWorkload(case, pattern)
        }

        /**
         * Releases the actual host and decoder entry and checks physical snapshot independence.
         */
        @TearDown(Level.Trial)
        public fun close() {
            workload.close()
        }
    }

    /**
     * Declares source dimensions and the original host identifier or logical payload capacity.
     */
    public enum class Case(
        internal val extent: Int,
        internal val identifiers: Int = 0,
        internal val byteImages: Int = 0,
        internal val flat: Boolean = false,
    ) {
        DirectSmall(16),
        DirectMedium(256),
        DirectLarge(1024),
        EncodedOverflow(1536),
        PayloadOverflow(2048, flat = true),
        IdentifiersBelow(16, identifiers = 511),
        IdentifiersAt(16, identifiers = 512),
        IdentifiersAbove(16, identifiers = 513),
        IdentifiersAboveLarge(1024, identifiers = 513),
        BytesBelow(1024, byteImages = 31),
        BytesAt(1024, byteImages = 32),
        BytesAbove(1024, byteImages = 33),
    }

    /**
     * Changes only source selection or encoded content; current pixel and identity expectations remain independent.
     */
    public enum class Pattern {
        Hot,
        Cold,
        Replacement,
    }

    /**
     * Checks every compiled row against source pixels, lazy calls, fresh overflow identities, and terminal release.
     */
    public companion object {
        @JvmStatic
        public fun verifyWork() {
            Case.entries.forEach { case ->
                Pattern.entries.forEach { pattern ->
                    ResourceImageDecodeWorkload(case, pattern, verify = true).use { workload ->
                        workload.verifySteady()
                        workload.completeProtocol()
                        workload.verify()
                    }
                }
            }
        }
    }
}

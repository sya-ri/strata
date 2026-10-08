@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Separates a public Minecraft host's declaration cutoff from its complete retained frame.
 * Synthetic immutable ASCII assets exclude resource acquisition, native fonts and GPU work from collection.
 */
public class StatefulControlBenchmark {
    /**
     * Commits fresh descriptions and reconciliation, including the existing declaration snapshot projection.
     */
    @Benchmark
    public fun declarationReconcile(state: Controls): RuntimeDeclaration = state.scene.nextDeclaration()

    /**
     * Commits the same workload through measurement, paint assembly and semantics.
     */
    @Benchmark
    public fun completeFrame(state: Controls): RuntimeUiFrame = state.scene.nextFrame()

    /**
     * Thread-confined retained ownership, prepared and released outside measured operations.
     */
    @State(Scope.Thread)
    public open class Controls {
        /**
         * Size-matched row count, including the independent no-control fixture.
         */
        @JvmField
        @Param("1", "64", "512")
        public var count: Int = 64

        /**
         * Independent controls and their ordinary application composition.
         */
        @JvmField
        @Param
        public var kind: Kind = Kind.Mixed

        /**
         * Fixed short or long printable-ASCII labels.
         */
        @JvmField
        @Param
        public var labels: Labels = Labels.Short

        /**
         * Ordered CycleButton candidate count; other controls retain this as an explicit negative control.
         */
        @JvmField
        @Param
        public var choices: Choices = Choices.Small

        /**
         * Complete clean, exact-reuse, fresh-equivalent and necessary-change controls.
         */
        @JvmField
        @Param
        public var change: Change = Change.Fresh

        internal lateinit var scene: StatefulControlFixture

        /**
         * Acquires one public host, immutable assets and a committed interior-independent frame.
         */
        @Setup(Level.Trial)
        public fun setup() {
            scene = StatefulControlFixture(count, kind, labels, choices, change)
        }

        /**
         * Closes the actual host and all source observations on every trial exit.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (::scene.isInitialized) scene.close()
        }
    }

    /**
     * Existing primitive implementations and size-matched application controls.
     */
    public enum class Kind {
        Checkbox,
        Slider,
        Cycle,
        Mixed,
        NoControls,
    }

    /**
     * Fixed glyph counts chosen before collection.
     */
    public enum class Labels(public val length: Int) {
        Short(1),
        Long(32),
    }

    /**
     * Immutable option orders used by every CycleButton in the fixture.
     */
    public enum class Choices(public val size: Int) {
        Small(3),
        Many(64),
    }

    /**
     * Source and root actions applied before the same selected operation.
     */
    public enum class Change {
        Clean,
        Reuse,
        Fresh,
        OneLabel,
        AllLabels,
    }

    /**
     * Complete generated-matrix work verification shared by CI and the standard collector.
     */
    public companion object {
        /**
         * Verifies both operation paths for all 300 parameter tuples, admitting historical paint only as a baseline.
         */
        @JvmStatic
        public fun verifyWork() {
            for (count in listOf(1, 64, 512)) {
                for (kind in Kind.entries) {
                    for (labels in Labels.entries) {
                        verifyChoices(count, kind, labels)
                    }
                }
            }
        }

        private fun verifyChoices(count: Int, kind: Kind, labels: Labels) {
            for (choices in Choices.entries) {
                for (change in Change.entries) {
                    StatefulControlFixture(count, kind, labels, choices, change).use { it.verifyWork() }
                }
            }
        }
    }
}

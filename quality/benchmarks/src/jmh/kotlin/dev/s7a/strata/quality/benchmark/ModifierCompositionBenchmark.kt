package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Measures immutable membership composition separately from a complete declaration rebuild.
 * Description creation and prepared input chains stay outside construction-only invocations.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ModifierCompositionBenchmark {
    /**
     * Builds a fresh chain from individual immutable descriptions.
     */
    @Benchmark
    public fun append(state: Inputs): Modifier = state.appended()

    /**
     * Joins prepared left and right chains with the selected total length.
     */
    @Benchmark
    public fun concatenate(state: Inputs): Modifier = state.concatenated()

    /**
     * Joins a chain to itself, preserving repeated description references.
     */
    @Benchmark
    public fun selfConcatenate(state: Inputs): Modifier = state.selfConcatenated()

    /**
     * Reuses a prepared intermediate chain and appends one description.
     */
    @Benchmark
    public fun extendIntermediate(state: Inputs): Modifier = state.extended()

    /**
     * Rebuilds an actual 128-child declaration, composing every child's chain in the operation.
     * This boundary includes component builders and their necessary child snapshots, but no retained frame.
     */
    @Benchmark
    public fun declarations(state: Inputs): Element = state.declarations()

    /**
     * Immutable descriptions and prepared chains owned by one JMH worker.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Number of input descriptions; self-concatenation produces twice this length.
         */
        @JvmField
        @Param("0", "1", "8", "32", "128")
        public var length: Int = 0

        private lateinit var descriptions: List<ModifierElement>
        private lateinit var left: Modifier
        private lateinit var right: Modifier
        private lateinit var complete: Modifier
        private lateinit var extra: ModifierElement

        /**
         * Freezes descriptions and verifies exact constructed membership outside measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            descriptions =
                List(length) {
                    Modifier.Empty
                        .padding(it % 4)
                        .elements()
                        .single()
                }
            left = chain(descriptions.take(length / 2))
            right = chain(descriptions.drop(length / 2))
            complete = appended()
            extra =
                Modifier.Empty
                    .padding(7)
                    .elements()
                    .single()
            check(complete.elements() == descriptions)
            check(concatenated() == complete)
            check(selfConcatenated().elements() == descriptions + descriptions)
            check(extended().elements() == descriptions + extra)
            val tree = declarations()
            check(tree.children.size == 128)
            check(tree.children.all { it.modifier == complete })
            check(complete.elements() == descriptions)
        }

        /**
         * Constructs a fresh membership sequence from the prepared immutable inputs.
         */
        public fun appended(): Modifier = chain(descriptions)

        /**
         * Joins prepared halves without creating new descriptions.
         */
        public fun concatenated(): Modifier = left.then(right)

        /**
         * Creates duplicate membership from one prepared chain.
         */
        public fun selfConcatenated(): Modifier = complete.then(complete)

        /**
         * Extends a previously constructed chain without changing its earlier value.
         */
        public fun extended(): Modifier = complete.then(extra)

        /**
         * Evaluates one real container rebuild with 128 independently composed child chains.
         */
        public fun declarations(): Element =
            evaluateComponentTree {
                Column {
                    repeat(128) { Spacer(modifier = appended()) }
                }
            }

        private fun chain(elements: List<ModifierElement>): Modifier {
            var result = Modifier.Empty
            for (element in elements) result = result.then(element)
            return result
        }
    }

    /**
     * Untimed checks shared by the complete inventory gate and JMH trial preparation.
     */
    public companion object {
        /**
         * Checks every declared chain size without collecting timings.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(ModifierCompositionBenchmark::class.java), setOf("avgt")).size == 25)
            for (length in listOf(0, 1, 8, 32, 128)) {
                val inputs = Inputs()
                inputs.length = length
                inputs.setup()
            }
        }
    }
}

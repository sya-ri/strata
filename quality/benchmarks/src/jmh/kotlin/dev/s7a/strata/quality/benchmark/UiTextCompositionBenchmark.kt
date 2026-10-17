package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Measures text membership construction and a complete 128-label declaration rebuild separately.
 * Prepared immutable parts and untimed membership/frame oracles stay outside every invocation.
 */
@OptIn(InternalStrataRuntimeApi::class)
@State(Scope.Thread)
public open class UiTextCompositionBenchmark {
    private val single: UiText = UiText.Literal("single")

    /**
     * Calls the public vararg constructor, including its caller spread-array copy.
     */
    @Benchmark
    public fun constructor(state: Inputs): UiText.Concatenated = state.constructed()

    /**
     * Calls the companion factory, including its spread-array copy and singleton shortcut.
     */
    @Benchmark
    public fun companion(state: Inputs): UiText = state.combined()

    /**
     * Unchanged direct-list construction, with its necessary final defensive snapshot.
     */
    @Benchmark
    public fun listControl(state: Inputs): UiText.Concatenated = state.listControl()

    /**
     * Rebuilds all 128 children, labels, semantics modifiers and container membership.
     * Retained phases, resource resolution and rasterization are outside this boundary.
     */
    @Benchmark
    public fun declarations(state: Inputs): Element = state.declarations()

    /**
     * Preserves the empty companion shortcut as an independent control.
     */
    @Benchmark
    public fun empty(): UiText = UiText.concat()

    /**
     * Preserves the singleton companion identity shortcut as an independent control.
     */
    @Benchmark
    public fun singleton(): UiText = UiText.concat(single)

    /**
     * Worker-owned immutable input references; construction never mutates or resolves a part.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Complete composition sizes, including singleton and small-input regression controls.
         */
        @JvmField
        @Param("1", "2", "8", "32", "128")
        public var length: Int = 1

        private lateinit var parts: Array<UiText>
        private lateinit var expected: List<UiText>

        /**
         * Prepares repeated and nested unresolved references and verifies exact output membership.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val literal = UiText.Literal("part")
            val templates = listOf(literal, literal.withFont(ResourceId("test", "font")), UiText.Translated("test.label", "fallback"), UiText.Concatenated(literal, literal))
            parts = Array(length) { templates[it % templates.size] }
            expected = parts.toList()
            verify(constructed())
            verify(listControl())
            val combined = combined()
            if (length == 1) check(combined === expected.single()) else verify(combined as UiText.Concatenated)
        }

        /**
         * Constructs a detached composition using the public array entry point.
         * The frozen operation includes the caller's spread copy on both runtime sides.
         */
        @Suppress("SpreadOperator")
        public fun constructed(): UiText.Concatenated = UiText.Concatenated(*parts)

        /**
         * Constructs a detached composition through the companion's original shortcuts.
         * The frozen operation includes the caller's spread copy on both runtime sides.
         */
        @Suppress("SpreadOperator")
        public fun combined(): UiText = UiText.concat(*parts)

        /**
         * Copies the prepared list through the unchanged public list entry point.
         */
        public fun listControl(): UiText.Concatenated = UiText.Concatenated(expected)

        /**
         * Evaluates a complete container with one newly composed label on each child.
         */
        public fun declarations(): Element = declarations(::combined)

        /**
         * Compares complete retained semantics, geometry, commands and detached headless pixels.
         * The reference uses the unchanged list path with the same singleton value semantics.
         */
        public fun verifyDeclarations() {
            val actual = frame(declarations())
            val reference = frame(declarations { if (length == 1) expected.single() else listControl() })
            check(actual.size == reference.size && actual.drawCommands == reference.drawCommands && actual.semantics == reference.semantics)
            check(actual.semantics.size == 128)
            val label = if (length == 1) expected.single() else listControl()
            check(actual.semantics.all { it.semantics.label == label })
            val viewport = IntSize(128, 128)
            check(rasterizeHeadless(actual.drawCommands, viewport).copyArgb().contentEquals(rasterizeHeadless(reference.drawCommands, viewport).copyArgb()))
        }

        private fun verify(value: UiText.Concatenated) {
            check(value.parts == expected)
            check(value.parts.indices.all { value.parts[it] === expected[it] })
            check(value.hashCode() == expected.hashCode())
            check(value.toString() == "Concatenated(parts=$expected)")
        }

        private fun declarations(label: () -> UiText): Element =
            evaluateComponentTree {
                Column {
                    repeat(128) { Spacer(modifier = Modifier.semantics(Semantics(label = label()))) }
                }
            }

        private fun frame(root: Element): RuntimeUiFrame {
            val session = createRuntimeUiSession { root }
            try {
                session.attach()
                return session.frame(Constraints.fixed(128, 128))
            } finally {
                session.close()
            }
        }
    }

    /**
     * Complete generated fixture admission and optional shared operation-report binding.
     */
    public companion object {
        /**
         * Verifies every declared row and independent frame oracle without collecting timings.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(UiTextCompositionBenchmark::class.java), setOf("avgt")).size == 22)
            for (length in listOf(1, 2, 8, 32, 128)) {
                val inputs = Inputs()
                inputs.length = length
                inputs.setup()
                inputs.verifyDeclarations()
            }
            val benchmark = UiTextCompositionBenchmark()
            check(benchmark.empty() == UiText.Literal(""))
            check(benchmark.singleton() === benchmark.single)
        }

        /**
         * Binds all original rows to the existing shared owner-thread meter and collector.
         */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = UiTextCompositionOperationReport
    }
}

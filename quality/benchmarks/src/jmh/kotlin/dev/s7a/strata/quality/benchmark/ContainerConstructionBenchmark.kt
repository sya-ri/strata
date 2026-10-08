package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.performance.JmhWorkloadInventory
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
 * Separates complete DSL declaration construction from construction plus retained frame reconciliation.
 * Inputs and work checks are frozen with the fixture; the generic collector owns timing and allocation.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ContainerConstructionBenchmark {
    /**
     * Builds a detached declaration without creating or updating retained nodes.
     */
    @Benchmark
    public fun construct(state: Scene): Element = state.construct()

    /**
     * Rebuilds the declaration after a real observed revision and commits its retained frame.
     */
    @Benchmark
    public fun rebuildFrame(state: Scene): RuntimeUiFrame = state.rebuildFrame()

    /**
     * Complete synchronous private-child-scope ownership boundaries.
     */
    public enum class Container {
        Row,
        FlowRow,
        Column,
        Stack,
        Grid,
        TiledImage,
    }

    /**
     * Fixed construction controls, cardinalities, nesting and repeated template membership.
     *
     * @property width leaf membership at the bottom of each container.
     * @property groups independent containers emitted into one outer stack.
     * @property depth additional two-child ancestors above the bottom container.
     * @property template whether immutable leaf descriptions are reused between parents.
     */
    public enum class Shape(
        public val width: Int,
        public val groups: Int = 1,
        public val depth: Int = 0,
        public val template: Boolean = false,
    ) {
        Empty(0),
        Single(1),
        Eight(8),
        Wide1024(1_024),
        Deep32(8, depth = 32),
        Grouped1000Eight(8, groups = 1_000),
        Template1000Eight(8, groups = 1_000, template = true),
    }

    /**
     * One immutable input recipe and one owner-confined retained session per JMH worker.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Container kind supplied by compiled JMH metadata.
         */
        @JvmField
        @Param
        public var container: Container = Container.Row

        /**
         * Fixed declaration topology supplied by compiled JMH metadata.
         */
        @JvmField
        @Param
        public var shape: Shape = Shape.Empty

        private lateinit var fixture: ContainerDeclarationFixture

        /**
         * Primes retained behavior and checks the full fixture outside measured operations.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = ContainerDeclarationFixture(container, shape)
            fixture.attach()
            fixture.verifyWork()
        }

        /**
         * Builds fresh parent membership while preserving the fixture's immutable template inputs.
         */
        public fun construct(): Element = fixture.construct(0)

        /**
         * Publishes one revision and commits construction, reconciliation and retained presentation.
         */
        public fun rebuildFrame(): RuntimeUiFrame = fixture.rebuildFrame()

        /**
         * Releases the worker's current tree and verifies terminal resource ownership.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (::fixture.isInitialized) fixture.close()
        }
    }

    /**
     * Automatic generic-collector work hook; no benchmark-specific launcher registration is required.
     */
    public companion object {
        /**
         * Requires every generated case and independent declaration, geometry, pixel and lifecycle checks.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(ContainerConstructionBenchmark::class.java), setOf("avgt")).size == 84)
            for (container in Container.entries) {
                for (shape in Shape.entries) {
                    val scene = Scene()
                    scene.container = container
                    scene.shape = shape
                    try {
                        scene.setUp()
                    } finally {
                        scene.close()
                    }
                }
            }
            ContainerDeclarationFixture.verifyParentData()
        }
    }
}

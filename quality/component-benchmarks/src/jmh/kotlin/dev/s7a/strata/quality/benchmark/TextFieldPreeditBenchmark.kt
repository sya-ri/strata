package dev.s7a.strata.quality.benchmark

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
 * Whole-Issue TextField preedit corpus, including actual input replacement, unchanged-input dirty paint and all controls.
 * JMH and the existing testkit own timing, normalized allocation, qualification and archive/origin receipts.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextFieldPreeditBenchmark {
    /**
     * Measures the row's entire transition through its completed production host frame.
     */
    @Benchmark
    public fun completedFieldOperation(state: FieldSession): RuntimeUiFrame = state.next()

    /**
     * One independent host and immutable producer pair per worker; reference checking stays outside timed operations.
     */
    @State(Scope.Thread)
    public open class FieldSession {
        /**
         * Exactly 78 compiled rows, covering 54 distinct fixtures without extra Cartesian cases.
         */
        @JvmField
        @Param
        public var row: TextFieldPreeditRow = TextFieldPreeditRow.NoCompositionDirtyField

        private lateinit var fixture: TextFieldPreeditFixture

        /**
         * Constructs and primes the full host and both event producers outside collection.
         */
        @Setup(Level.Trial)
        public fun setup() { fixture = TextFieldPreeditFixture(row) }

        /**
         * Executes only the declared complete operation; no helper-only samples enter the matrix.
         */
        public fun next(): RuntimeUiFrame = fixture.next()

        /**
         * Releases the host, current composition, source observation and diagnostic references.
         */
        @TearDown(Level.Trial)
        public fun close() { fixture.close() }
    }

    /**
     * Automatically discovered verification for the complete generated matrix.
     */
    public companion object {
        /**
         * Checks all 78 rows and independent full-frame pixel, input, work, semantics and terminal assertions.
         */
        @JvmStatic
        public fun verifyWork() {
            check(TextFieldPreeditRow.entries.size == 78)
            check(TextFieldPreeditRow.entries.map { it.fixtureId }.toSet().size == 54)
            check(TextFieldPreeditRow.entries.count { it.control != null } == 30)
            check(JmhWorkloadInventory.capture(listOf(TextFieldPreeditBenchmark::class.java), setOf("avgt")).size == 78)
            TextFieldPreeditRow.entries.forEach { row ->
                TextFieldPreeditFixture(row, verify = true).use { fixture ->
                    var previous = fixture.frame()
                    fixture.verify(previous)
                    repeat(4) {
                        fixture.checkpoint()
                        val frame = fixture.next()
                        fixture.verify(frame)
                        fixture.verifyWork(previous, frame)
                        previous = frame
                    }
                }
                println("preedit,${row.fixtureId},${row.name},verified")
            }
        }
    }
}

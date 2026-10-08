package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises selection boundaries and untimed verifier failures without running JMH measurement.
 */
class JmhFixtureSelectionTest {
    @Test
    fun exactNamesAndUnambiguousAliasesPreserveSelectionOrder() {
        assertEquals(
            listOf("one.SampledRasterBenchmark", "one.DenseSampledRasterBenchmark"),
            JmhFixtureSelection.resolve(listOf("one.SampledRasterBenchmark", "DenseSampledRasterBenchmark"), setOf("one.SampledRasterBenchmark", "one.DenseSampledRasterBenchmark")),
        )
    }

    @Test
    fun ambiguousAliasesAndUnknownNamesFailBeforeLoadingClasses() {
        val available = setOf("one.Fixture", "two.Fixture")
        assertFailsWith<IllegalArgumentException> { JmhFixtureSelection.resolve(listOf("Fixture"), available) }
        assertFailsWith<IllegalArgumentException> { JmhFixtureSelection.resolve(listOf("Missing"), available) }
        assertEquals(listOf("two.Fixture"), JmhFixtureSelection.resolve(listOf("two.Fixture"), available))
    }

    @Test
    fun nestedBinaryNamesSupportUnambiguousSimpleNames() {
        val first = "one.Outer\$Fixture"
        val second = "two.Other\$Fixture"
        assertEquals(listOf(first), JmhFixtureSelection.resolve(listOf("Fixture"), setOf(first)))
        assertEquals(listOf(second), JmhFixtureSelection.resolve(listOf(second), setOf(first, second)))
        assertFailsWith<IllegalArgumentException> { JmhFixtureSelection.resolve(listOf("Fixture"), setOf(first, second)) }
        val canonical = first.replace('$', '.')
        assertEquals(listOf(canonical), JmhFixtureSelection.resolve(listOf(first), setOf(canonical)))
        assertEquals(listOf(canonical), JmhFixtureSelection.resolve(listOf("Fixture"), setOf(canonical)))
        assertFailsWith<IllegalArgumentException> { JmhFixtureSelection.resolve(listOf(first, canonical), setOf(canonical)) }
    }

    @Test
    fun emptyNamesAndDuplicateQualifiedAliasesAreRejected() {
        val available = setOf("one.Fixture")
        listOf(emptyList(), listOf(""), listOf("Fixture", ""), listOf("Fixture", "one.Fixture")).forEach { names ->
            assertFailsWith<IllegalArgumentException> { JmhFixtureSelection.resolve(names, available) }
        }
    }

    @Test
    fun classFiltersDoNotAdmitPrefixOrSuffixCollisions() {
        val fixture = PlainFixture::class.java
        val name = fixture.name.replace('$', '.')
        val include = Regex(JmhFixtureSelection.includes(listOf(fixture)).single())
        assertTrue(include.containsMatchIn("$name.run"))
        assertFalse(include.containsMatchIn("${fixture.name}.run"))
        assertFalse(include.containsMatchIn("prefix.$name.run"))
        assertFalse(include.containsMatchIn("${name}Suffix.run"))
        assertFalse(include.containsMatchIn("$name.Nested.run"))
    }

    @Test
    fun optionalWorkVerifierRunsOnceAndPreservesTheOriginalFailure() {
        CheckedFixture.calls = 0
        JmhFixtureSelection.verifyWork(listOf(PlainFixture::class.java, CheckedFixture::class.java, CheckedFixture::class.java))
        assertEquals(1, CheckedFixture.calls)
        assertSame(FailedFixture.failure, assertFailsWith<IllegalStateException> { JmhFixtureSelection.verifyWork(listOf(FailedFixture::class.java)) })
    }

    /**
     * Represents a generated benchmark without an optional deterministic verifier.
     */
    class PlainFixture

    /**
     * Represents a fixture whose work check must execute outside timing.
     */
    class CheckedFixture private constructor() {
        /**
         * Holds the verifier's observable invocation count.
         */
        companion object {
            var calls = 0

            /**
             * Records one untimed verification.
             */
            @JvmStatic
            fun verifyWork() {
                calls += 1
            }
        }
    }

    /**
     * Represents a fixture with failed deterministic acceptance.
     */
    class FailedFixture private constructor() {
        /**
         * Holds the unchanged failure used to verify exception propagation.
         */
        companion object {
            val failure = IllegalStateException("Invalid fixture work")

            /**
             * Fails acceptance before timing can begin.
             */
            @JvmStatic
            fun verifyWork(): Unit = throw failure
        }
    }
}

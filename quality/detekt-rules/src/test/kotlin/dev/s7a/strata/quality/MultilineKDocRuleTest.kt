package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies visible declaration documentation and its intentional exemptions.
 */
internal class MultilineKDocRuleTest {
    /**
     * Reports undocumented visible classes and methods.
     */
    @Test
    internal fun reportsMissingDocumentation() {
        val source =
            """
            class Screen {
                fun render() = Unit
            }
            """.trimIndent()

        assertEquals(2, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Accepts multiline KDoc and skips overrides and test functions.
     */
    @Test
    internal fun acceptsDocumentedAndExemptDeclarations() {
        val source =
            """
            @Test
            fun testFunction() = Unit

            @CustomTest
            fun customTestFunction() = Unit

            private fun hiddenFunction() = Unit

            /**
             * A documented screen.
             */
            open class Screen {
                /**
                 * Draws the screen.
                 */
                open fun render() = Unit

                override fun toString() = "screen"

                /**
                 * Draws with a local helper.
                 */
                fun renderWithLocalHelper() {
                    fun localHelper() = Unit
                    localHelper()
                }
            }
            """.trimIndent()

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Rejects a one-line KDoc even when a declaration has documentation text.
     */
    @Test
    internal fun rejectsOneLineKDoc() {
        val source =
            """
            /** One-line documentation. */
            class Screen
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }
}

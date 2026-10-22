package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies qualified body references without confusing ordinary member chains.
 */
internal class BodyQualifiedNameRuleTest {
    /**
     * Reports qualified types and static calls in a body.
     */
    @Test
    internal fun reportsBodyQualifiedReferences() {
        val source =
            DetektTestSources.read("body-qualified-name-rule/reports-body-qualified-references")

        assertEquals(2, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Ignores package and import directives while reporting qualified references in bodies.
     */
    @Test
    internal fun ignoresPackageAndImportDirectives() {
        val source =
            DetektTestSources.read("body-qualified-name-rule/ignores-package-and-import-directives")

        assertEquals(0, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Preserves ordinary member chains, string contents, and Gradle catalog access.
     */
    @Test
    internal fun ignoresNonQualifiedReferences() {
        val source =
            DetektTestSources.read("body-qualified-name-rule/ignores-non-qualified-references")

        assertEquals(0, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Keeps class literals and call-result member chains distinct from package roots.
     */
    @Test
    internal fun ignoresMemberChainsWithNonNameReceivers() {
        val source =
            DetektTestSources.read("body-qualified-name-rule/ignores-member-chains-with-non-name-receivers")

        assertEquals(0, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Includes the called name when checking fully qualified constructors and functions.
     */
    @Test
    internal fun reportsQualifiedConstructorsAndFunctions() {
        val source =
            DetektTestSources.read("body-qualified-name-rule/reports-qualified-constructors-and-functions")

        assertEquals(2, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Retains the qualified package prefix before a subsequent ordinary call.
     */
    @Test
    internal fun reportsQualifiedCallsBeforeMemberChains() {
        val source =
            DetektTestSources.read("body-qualified-name-rule/reports-qualified-calls-before-member-chains")

        assertEquals(2, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }
}

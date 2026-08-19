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
            """
            fun create(): java.util.UUID = java.util.UUID.randomUUID()
            """.trimIndent()

        assertEquals(2, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Ignores package and import directives while reporting qualified references in bodies.
     */
    @Test
    internal fun ignoresPackageAndImportDirectives() {
        val source =
            """
            package dev.example.feature

            import java.util.UUID
            import java.util.Collections.emptyList
            import kotlin.collections.List as KotlinList

            fun create(): UUID = UUID.randomUUID()
            fun values(): KotlinList<String> = emptyList()
            """.trimIndent()

        assertEquals(0, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }

    /**
     * Preserves ordinary member chains, string contents, and Gradle catalog access.
     */
    @Test
    internal fun ignoresNonQualifiedReferences() {
        val source =
            """
            fun read(user: User): String = user.profile.name
            fun literal(): String = "java.util.UUID"
            val dependency = libs.versions.kotlin
            """.trimIndent()

        assertEquals(0, BodyQualifiedNameRule(Config.empty).lint(source).size)
    }
}

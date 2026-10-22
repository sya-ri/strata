package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.test.utils.KotlinAnalysisApiEngine
import dev.detekt.test.utils.createEnvironment
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Confirms rule identities and DSL receiver resolution against the real compiled public API.
 */
internal class PublicApiRuleTest {
    @Test
    fun identifiesRealStateFactoriesAndIgnoredModifier() {
        val environment = createEnvironment()
        KotlinAnalysisApiEngine().use { engine ->
            val file =
                engine.compile(
                    code =
                        checkNotNull(javaClass.getResource("/source-fixtures/public-api/state-factories-and-ignored-modifier.kt.txt")).readText(),
                    javaSourceRoots = environment.javaSourceRoots,
                    jvmClasspathRoots = environment.jvmClasspathRoots,
                    allowCompilationErrors = false,
                )
            assertEquals(12, StateCreatedDuringComposition(Config.empty).visitFile(file, LanguageVersionSettingsImpl.DEFAULT).size)
            assertEquals(1, UnusedComponentModifier(Config.empty).visitFile(file, LanguageVersionSettingsImpl.DEFAULT).size)
        }
    }
}

package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.RuleName
import dev.detekt.test.utils.KotlinAnalysisApiEngine
import dev.detekt.test.utils.createEnvironment
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiles the guide's examples and measures a warm rule pass using the shared testkit.
 * This bounded contract check excludes compiler startup and is not formal regression evidence.
 */
internal class AuthoringPerformanceContractTest {
    @Test
    fun checksDocumentedExamplesAndCollectsResolvedRulePassEvidence() {
        val factories = StrataRuleSetProvider().instance().rules
        val environment = createEnvironment()
        KotlinAnalysisApiEngine().use { engine ->
            val file =
                engine.compile(
                    code = examples(invalid = false, count = factories.size),
                    javaSourceRoots = environment.javaSourceRoots,
                    jvmClasspathRoots = environment.jvmClasspathRoots,
                    allowCompilationErrors = false,
                )
            factories.forEach { (_, factory) ->
                assertEquals(0, factory(Config.empty).visitFile(file, LanguageVersionSettingsImpl.DEFAULT).size)
            }
        }
        KotlinAnalysisApiEngine().use { engine ->
            val file =
                engine.compile(
                    code = examples(invalid = true, count = factories.size),
                    javaSourceRoots = environment.javaSourceRoots,
                    jvmClasspathRoots = environment.jvmClasspathRoots,
                    allowCompilationErrors = false,
                )
            val expected = factories.keys.associateWith { 1 }
            val sample =
                JvmPerformanceRunner.measure(
                    name = "strata-resolved-rule-pass",
                    plan = PerformancePlan(warmup = 1, samples = 3, repetitions = 1),
                    afterOperation = { _: Int, findings: Map<RuleName, Int> -> assertEquals(expected, findings) },
                ) {
                    factories.mapValues { (_, factory) -> factory(Config.empty).visitFile(file, LanguageVersionSettingsImpl.DEFAULT).size }
                }
            assertEquals(expected, sample.value)
            val report = Path.of(System.getProperty("strata.authoringPerformanceReport"))
            Files.createDirectories(report.parent)
            Files.writeString(report, sample.evidence.toString())
        }
    }

    /**
     * Uses checked Markdown examples as the fixture, so rule guidance cannot silently stop compiling.
     */
    private fun examples(
        invalid: Boolean,
        count: Int,
    ): String {
        val guide = Files.readString(Path.of(System.getProperty("strata.authoringGuide")))
        val imports = Files.readString(Path.of(System.getProperty("strata.authoringImports")))
        val kind = if (invalid) "invalid" else "valid"
        val snippets =
            Regex("<!-- checked-example: $kind -->\\s+```kotlin\\s*\\n([\\s\\S]*?)\\n```")
                .findAll(guide)
                .map { it.groupValues[1] }
                .toList()
        assertEquals(count, snippets.size)
        return "package authoring.$kind\n$imports\n${snippets.joinToString("\n\n")}\n"
    }
}

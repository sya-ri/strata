package dev.s7a.strata.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.test.utils.KotlinAnalysisApiEngine
import dev.detekt.test.utils.createEnvironment
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl

/**
 * Compiles minimal public contracts so tests exercise resolved symbols, not callee spelling.
 */
internal object ConsumerRuleFixture {
    /**
     * Analyzes consumer code against the compiled public API, rejecting invalid fixtures.
     */
    internal fun analyzePublic(
        rule: Rule,
        source: String,
    ): List<Finding> {
        val environment = createEnvironment()
        return KotlinAnalysisApiEngine().use { engine ->
            val file =
                engine.compile(
                    code = source,
                    javaSourceRoots = environment.javaSourceRoots,
                    jvmClasspathRoots = environment.jvmClasspathRoots,
                    allowCompilationErrors = false,
                )
            rule.visitFile(file, LanguageVersionSettingsImpl.DEFAULT)
        }
    }

    /**
     * Analyzes a real Kotlin file with distinct DSL, state, and modifier declarations.
     */
    internal fun analyze(
        rule: Rule,
        source: String,
    ): List<Finding> {
        val environment = createEnvironment()
        return KotlinAnalysisApiEngine().use { engine ->
            val file =
                engine.compile(
                    code = source,
                    dependencyCodes = listOf(COMPONENTS, MODIFIERS, STATE),
                    javaSourceRoots = environment.javaSourceRoots,
                    jvmClasspathRoots = environment.jvmClasspathRoots,
                    allowCompilationErrors = false,
                )
            rule.visitFile(file, LanguageVersionSettingsImpl.DEFAULT)
        }
    }

    private const val COMPONENTS = """
        package dev.s7a.strata.component
        open class UiScope
        class RowScope : UiScope()
        class ScrollState
        class TextFieldState
        class TextAreaState
        class VirtualListState
        class PanZoomState
        class CheckboxState
        class CycleButtonState
        class SliderState
        class SelectionListState
        fun screen(content: UiScope.() -> Unit) {}
        fun UiScope.Row(content: RowScope.() -> Unit) {}
        fun UiScope.action(callback: () -> Unit) {}
    """
    private const val MODIFIERS = """
        package dev.s7a.strata.modifier
        class Modifier {
            companion object { val Empty = Modifier() }
        }
    """
    private const val STATE = """
        package dev.s7a.strata.state
        class MutableState<T>(val value: T)
        fun <T> mutableStateOf(value: T) = MutableState(value)
        fun <T, R> MutableState<T>.map(transform: (T) -> R) = MutableState(transform(value))
    """
}

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
                        """
                        import dev.s7a.strata.component.UiScope
                        import dev.s7a.strata.component.ScrollState
                        import dev.s7a.strata.component.TextFieldState
                        import dev.s7a.strata.component.TextAreaState
                        import dev.s7a.strata.component.VirtualListState
                        import dev.s7a.strata.component.PanZoomState
                        import dev.s7a.strata.component.CheckboxState
                        import dev.s7a.strata.component.CycleButtonState
                        import dev.s7a.strata.component.SliderState
                        import dev.s7a.strata.component.SelectionListState
                        import dev.s7a.strata.modifier.Modifier
                        import dev.s7a.strata.state.mutableStateOf
                        import dev.s7a.strata.state.map
                        import dev.s7a.strata.state.StateSource
                        fun UiScope.content(source: StateSource<Int>, modifier: Modifier = Modifier.Empty) {
                            ScrollState()
                            TextFieldState()
                            TextAreaState()
                            VirtualListState<Long>()
                            PanZoomState()
                            CheckboxState(false)
                            CycleButtonState(listOf("one", "two"), "one")
                            SliderState(0.5)
                            SelectionListState<Long>()
                            mutableStateOf(1)
                            val retained = mutableStateOf(2)
                            source.map { it.toString() }
                        }
                        """.trimIndent(),
                    javaSourceRoots = environment.javaSourceRoots,
                    jvmClasspathRoots = environment.jvmClasspathRoots,
                    allowCompilationErrors = false,
                )
            assertEquals(12, StateCreatedDuringComposition(Config.empty).visitFile(file, LanguageVersionSettingsImpl.DEFAULT).size)
            assertEquals(1, UnusedComponentModifier(Config.empty).visitFile(file, LanguageVersionSettingsImpl.DEFAULT).size)
        }
    }
}

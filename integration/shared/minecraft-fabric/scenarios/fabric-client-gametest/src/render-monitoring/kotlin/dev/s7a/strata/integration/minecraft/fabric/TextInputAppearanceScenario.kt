@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Verifies custom input frames on every loaded runtime using actual host progress and native pixels.
 * Editor composition and focus dispatch are tested independently through the shared Minecraft host.
 * Editors remain unfocused so native window focus changes cannot invalidate the static-work measurement.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object TextInputAppearanceScenario {
    /**
     * Opens retained editors, changes only their appearance, and compares native output with a literal reference.
     * The caller owns screen cleanup, including on assertion failure.
     */
    fun verify(driver: ReactiveRenderTestDriver): String {
        val initial = appearance(0xFFF1F3F4.toInt())
        val changed = appearance(0xFFE0F0E0.toInt())
        val source = driver.onClient { ReactiveRenderSource(initial) }
        val field = driver.onClient { TextFieldState("Search") }
        val area = driver.onClient { TextAreaState("Draft\nSecond line") }
        val values = driver.onClient { field.value to area.value }
        val owner =
            driver.onClient {
                driver.open(
                    ScreenDefinition("Input appearance acceptance") {
                        Observe(source) { current -> editors(current, field, area) }
                    },
                )
            }
        driver.await { driver.work().inputs != null }
        val ids = driver.onClient { listOf(MinecraftPresentationObservation.identities(owner, fieldKey), MinecraftPresentationObservation.identities(owner, areaKey)) }
        val before = driver.onClient {
            val state = driver.work()
            source.publish(changed)
            state
        }
        driver.await { before.renderExtractions < driver.work().renderExtractions }
        driver.onClient {
            check(ids.all { it.size == 1 })
            check(ids == listOf(MinecraftPresentationObservation.identities(owner, fieldKey), MinecraftPresentationObservation.identities(owner, areaKey)))
            check(field.value == values.first && area.value == values.second)
            check(before.inputs !== driver.work().inputs)
        }
        verifyStable(driver, source, changed)
        driver.assertPixels(
            ScreenDefinition("Input appearance reference") {
                editors(changed, TextFieldState("Search"), TextAreaState("Draft\nSecond line"))
            },
            ReactiveRenderCapture.InputAppearance,
        )
        return "inputAppearanceRetainedDeclarations=2\ninputAppearanceStablePresentedFrames=100\ninputAppearancePixels=literal-headless-exact\n"
    }

    private fun verifyStable(
        driver: ReactiveRenderTestDriver,
        source: ReactiveRenderSource<TextInputAppearance.Custom>,
        changed: TextInputAppearance.Custom,
    ) {
        val stable = driver.onClient {
            source.publish(changed.copy())
            driver.work()
        }
        driver.await { 100L <= driver.work().renderExtractions - stable.renderExtractions }
        driver.onClient {
            val after = driver.work()
            check(stable.commands === after.commands && stable.inputs === after.inputs)
            check(stable.samePortable(after) && stable.sameSources(after))
        }
    }

    private fun UiScope.editors(
        appearance: TextInputAppearance,
        field: TextFieldState,
        area: TextAreaState,
    ) {
        Column(modifier = Modifier.Empty.size(160, 64).background(ArgbColor(0xFF000000.toInt())), spacing = 4) {
            TextField(field, appearance, IntSize(160, 20), textStyle = TextStyle.ContainerLabel, key = fieldKey)
            TextArea(area, appearance, TextAreaViewport.Size(IntSize(160, 40)), textStyle = TextStyle.ContainerLabel, key = areaKey)
        }
    }

    private fun appearance(color: Int): TextInputAppearance.Custom {
        val frame = ImageSource.Pixels(createDrawImage(IntSize(3, 3), IntArray(9) { color }))
        return TextInputAppearance.Custom(frame, frame, ArgbColor(0xFF204020.toInt()))
    }

    private val fieldKey = ElementKey("appearance-field")
    private val areaKey = ElementKey("appearance-area")
}

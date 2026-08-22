@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Scroll
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Tab
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Creates a representative screen while compiling against the API artifact alone.
 *
 * This fixture deliberately lives in the integration module's main source set, whose only dependency is `:api`.
 * It verifies that applications can author profile components, layout components, resource-backed images, asynchronous player heads, events, and synchronized slots without importing a runtime implementation.
 * The returned definition remains unevaluated until a host runtime transfers or closes it.
 *
 * @param onClose action invoked by the close button through active modifier behavior.
 * @return a one-shot platform-neutral screen definition.
 */
public fun createApiOnlyScreenDefinition(onClose: () -> Unit): ScreenDefinition {
    val search = TextFieldState()
    return ScreenDefinition("API-only screen") {
        Stack(modifier = Modifier.Empty.size(320, 180)) {
            Column(
                modifier = Modifier.Empty.fillMaxWidth().padding(left = 16, top = 12, right = 16),
                spacing = 4,
            ) {
                Row(spacing = 4) {
                    Tab("All", selected = true, width = 72)
                    Tab("Hidden", selected = false, width = 72)
                    TextField(search, size = IntSize(132, 20))
                }
                Grid(columns = 3, horizontalSpacing = 2, verticalSpacing = 2) {
                    repeat(3) { index -> Slot(bind = Slots.playerInventory(index)) }
                }
                Scroll(modifier = Modifier.Empty.size(288, 48)) {
                    Row(spacing = 4) {
                        PlayerHead(source = PlayerSkinSource.Name("Player0"))
                        Image(
                            source = ImageSource.Resource(ResourceId("example", "textures/gui/status.png")),
                            modifier = Modifier.Empty.size(24, 24),
                        )
                        Text("Resource-pack replaceable content")
                    }
                }
                Spacer(
                    modifier =
                        Modifier.Empty
                            .fillMaxWidth()
                            .height(1)
                            .background(ArgbColor(0xFF5A5A5A.toInt())),
                )
                Button(
                    label = "Close",
                    modifier = Modifier.Empty.onPress(onClose).align(HorizontalAlignment.Center),
                )
            }
        }
    }
}

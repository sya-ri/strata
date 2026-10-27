package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:image
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.tooltip
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.ui.UiDefinition

/**
 * Builds a self-contained Image showcase from caller-provided deterministic pixels or an active resource.
 *
 * @param source immutable pixel snapshot or resource-pack identifier rendered by the Image component itself.
 * @return one-shot definition containing the complete 32 by 32 nearest-sampled image inside a minimal canvas.
 */
internal fun createImageShowcaseScreenDefinition(source: ImageSource): UiDefinition =
    UiDefinition("Image showcase") {
        Stack(
            modifier =
                Modifier
                    .size(64, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                source,
                size = IntSize(32, 32),
                modifier =
                    Modifier.tooltip(padding = false) {
                        Column {
                            Text("Image preview")
                            Image(ImageSource.Pixels(createDrawImage(IntSize(2, 2), intArrayOf(-65536, -16711936, -16776961, -1))), size = IntSize(16, 16))
                        }
                    },
            )
        }
    }
// showcase-source-end:image

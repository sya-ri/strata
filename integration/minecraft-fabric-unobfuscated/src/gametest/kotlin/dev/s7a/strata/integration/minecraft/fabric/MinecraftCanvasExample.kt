package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:canvas
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained CPU Canvas showcase with immutable, independently known source texels.
 *
 * Construction runs on the screen owner thread and creates no source subscription or native resource.
 * The one-shot definition owns its source description, while its retained canvas later owns the attachment binding.
 *
 * @return an unevaluated definition stretching a four-by-two CPU image into a 64 by 32 logical rectangle.
 */
internal fun createCanvasShowcaseScreenDefinition(): ScreenDefinition {
    val image =
        createDrawImage(
            IntSize(4, 2),
            intArrayOf(
                0xFF4CC9F0.toInt(),
                0xFF4361EE.toInt(),
                0xFF7209B7.toInt(),
                0xFFF72585.toInt(),
                0xFF90BE6D.toInt(),
                0xFFF9C74F.toInt(),
                0xFFF8961E.toInt(),
                0x80F94144.toInt(),
            ),
        )
    val source = canvasSource(image)
    return ScreenDefinition("Canvas showcase") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(96, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(source, size = IntSize(64, 32))
        }
    }
}
// showcase-source-end:canvas

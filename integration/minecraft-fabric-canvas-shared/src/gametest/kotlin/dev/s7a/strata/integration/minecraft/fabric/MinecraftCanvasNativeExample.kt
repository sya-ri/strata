package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Compiles a native texture and independent custom-renderer Canvas scene with clipping and ordered portable overlays.
 *
 * The externally owned fixture supplies sources only; declaration acquires no native resource.
 * The returned definition is consumed once on the client thread and propagates scene-construction failures.
 */
internal fun createNativeCanvasScreenDefinition(fixture: MinecraftCanvasTestFixture): ScreenDefinition =
    ScreenDefinition("Native Canvas acceptance") {
        Stack(Modifier.Empty.background(ArgbColor(0xFF000000.toInt()))) {
            Column(spacing = 16) {
                Row(spacing = 16) {
                    Canvas(fixture.textureSource, IntSize(32, 32))
                    Canvas(fixture.flippedTextureSource, IntSize(32, 32))
                    Canvas(fixture.textureSource, IntSize(32, 32))
                    Stack(Modifier.Empty.size(32, 32).background(ArgbColor(0xFF0000FF.toInt()))) {
                        Canvas(fixture.rendererSource, IntSize(32, 32))
                        Spacer(Modifier.Empty.size(8, 8).background(ArgbColor(0xFFFFFFFF.toInt())))
                    }
                }
                Row(spacing = 16) {
                    canvasTestClip(IntSize(32, 16)) {
                        Canvas(fixture.textureSource, IntSize(32, 32))
                    }
                    Canvas(fixture.rendererSource, IntSize(32, 32))
                    Stack(Modifier.Empty.size(32, 32).background(ArgbColor(0xFFFFFFFF.toInt()))) {
                        Canvas(fixture.transparentSource, IntSize(32, 32))
                    }
                }
                Canvas(fixture.textureSource, IntSize(1, 1))
            }
        }
    }

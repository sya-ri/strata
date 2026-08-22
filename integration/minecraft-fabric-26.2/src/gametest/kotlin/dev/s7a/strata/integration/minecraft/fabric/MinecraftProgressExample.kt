@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:progress-screen
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.BoxScope
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftImageScale
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.imageBackground
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds one advancement-inspired Mod screen from active Minecraft assets and a downstream component.
 *
 * The standard runtime remains limited to reusable primitives; [ExampleProgressGraph] is application-owned composition that may encode this Mod's progression domain.
 *
 * @param window active `textures/gui/advancements/window.png` pixels.
 * @param background active stone advancement-background tile.
 * @param obtained active obtained task-frame sprite.
 * @param unobtained active unobtained task-frame sprite.
 * @return one-shot definition for the verified Fabric and headless screen.
 */
internal fun createProgressScreenDefinition(
    window: DrawImage,
    background: DrawImage,
    obtained: DrawImage,
    unobtained: DrawImage,
): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Power milestones") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
        ) {
            Box(
                modifier = Modifier.Empty.size(320, 151),
                contentAlignment = Alignment.BottomCenter,
            ) {
                ExampleProgressGraph(background, obtained, unobtained)
            }
            Box(
                modifier = Modifier.Empty.size(320, 160),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Image(window, source = IntRect(0, 0, 252, 140))
            }
            Box(
                modifier = Modifier.Empty.size(236, 35).align(Alignment.TopCenter),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text("Power milestones", style = MinecraftTextStyle.ContainerLabel)
            }
            Box(
                modifier = Modifier.Empty.size(320, 174),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Button("Done", width = 200, modifier = Modifier.Empty.onPress {})
            }
        }
    }

/**
 * Emits one application-owned progression graph by composing only public Strata primitives.
 *
 * This downstream component is deliberately not part of the standard runtime because its node meanings and progression domain belong to the application.
 * It retains no callback or scope after synchronous emission.
 *
 * @receiver active owner-thread UI scope.
 * @param background immutable background tile.
 * @param obtained immutable obtained frame sprite.
 * @param unobtained immutable unobtained frame sprite.
 * @param modifier active behavior surrounding the fixed 234 by 113 graph.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when used from another thread or outside its callback lifetime.
 */
internal fun UiScope.ExampleProgressGraph(
    background: DrawImage,
    obtained: DrawImage,
    unobtained: DrawImage,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Box(
        modifier = modifier.size(234, 113).imageBackground(background, MinecraftImageScale.Tile),
        key = key,
    ) {
        Box(
            modifier = Modifier.Empty.size(134, 44),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(modifier = Modifier.Empty.size(96, 2).background(connectionColor)) {}
        }
        Box(
            modifier = Modifier.Empty.size(135, 80),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(modifier = Modifier.Empty.size(2, 38).background(connectionColor)) {}
        }
        progressNode(obtained, ArgbColor(0xFF22D3EE.toInt()), 25, 30, "Generator")
        progressNode(obtained, ArgbColor(0xFFFBBF24.toInt()), 121, 30, "Storage")
        progressNode(unobtained, ArgbColor(0xFFA78BFA.toInt()), 121, 68, "Automation")
    }
}

private fun BoxScope.progressNode(
    frame: DrawImage,
    color: ArgbColor,
    x: Int,
    y: Int,
    label: String,
) {
    Box(
        modifier = Modifier.Empty.size(x + 60, y + 36),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier.Empty.size(64, 36),
            spacing = 1,
        ) {
            Box(
                modifier = Modifier.Empty.padding(left = 4).size(26, 26),
                contentAlignment = Alignment.Center,
            ) {
                Image(frame)
                Box(modifier = Modifier.Empty.size(16, 16).background(color)) {}
            }
            Text(label)
        }
    }
}

private val connectionColor = ArgbColor(0xFF7A7A7A.toInt())
// showcase-source-end:progress-screen

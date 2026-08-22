@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:progress-screen
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds one advancement-inspired Mod screen from resource-pack sources and an application-owned component.
 *
 * The standard runtime remains limited to reusable primitives; [ExampleProgressGraph] may encode this Mod's progression domain because it remains downstream application code.
 *
 * @param window active advancement-window source.
 * @param background active advancement-background tile source.
 * @param obtained active obtained task-frame source.
 * @param unobtained active unobtained task-frame source.
 * @return one-shot definition for the verified Fabric and headless screen.
 */
internal fun createProgressScreenDefinition(
    window: ImageSource = advancementWindow,
    background: ImageSource = advancementBackground,
    obtained: ImageSource = obtainedTaskFrame,
    unobtained: ImageSource = unobtainedTaskFrame,
): ScreenDefinition =
    ScreenDefinition("Power milestones") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(modifier = Modifier.Empty.size(252, 140)) {
                Image(window, sourceRegion = IntRect(0, 0, 252, 140))
                Column(
                    modifier = Modifier.Empty.padding(left = 9, top = 6, right = 9, bottom = 9),
                    spacing = 4,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Power milestones", style = TextStyle.ContainerLabel)
                    ExampleProgressGraph(background, obtained, unobtained)
                }
            }
            Button(
                "Done",
                width = 200,
                modifier =
                    Modifier.Empty
                        .padding(bottom = 6)
                        .align(Alignment.BottomCenter)
                        .onPress {},
            )
        }
    }

/**
 * Emits one application-owned progression graph by composing only public Strata primitives.
 *
 * This downstream component is deliberately not part of the standard runtime because its node meanings and progression domain belong to the application.
 * It retains no callback or scope after synchronous emission.
 *
 * @receiver active owner-thread UI scope.
 * @param background immutable or resource-backed background tile.
 * @param obtained immutable or resource-backed obtained frame.
 * @param unobtained immutable or resource-backed unobtained frame.
 * @param modifier active behavior surrounding the fixed graph.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when used from another thread or outside its callback lifetime.
 */
internal fun UiScope.ExampleProgressGraph(
    background: ImageSource,
    obtained: ImageSource,
    unobtained: ImageSource,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Row(
        modifier = modifier.size(234, 113).imageBackground(background, ImageScale.Tile),
        key = key,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        progressNode(obtained, ArgbColor(0xFF22D3EE.toInt()), "Generator")
        Spacer(modifier = Modifier.Empty.size(32, 2).background(connectionColor))
        Column(
            spacing = 4,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            progressNode(obtained, ArgbColor(0xFFFBBF24.toInt()), "Storage")
            Spacer(modifier = Modifier.Empty.size(2, 12).background(connectionColor))
            progressNode(unobtained, ArgbColor(0xFFA78BFA.toInt()), "Automation")
        }
    }
}

private fun UiScope.progressNode(
    frame: ImageSource,
    color: ArgbColor,
    label: String,
) {
    this.Column(
        horizontalAlignment = HorizontalAlignment.Center,
        spacing = 1,
    ) {
        Stack(
            modifier = Modifier.Empty.size(26, 26),
            contentAlignment = Alignment.Center,
        ) {
            Image(frame)
            Spacer(modifier = Modifier.Empty.size(16, 16).background(color))
        }
        Text(label)
    }
}

private val advancementWindow = ImageSource.Resource(ResourceId("minecraft", "textures/gui/advancements/window.png"))
private val advancementBackground = ImageSource.Resource(ResourceId("minecraft", "textures/gui/advancements/backgrounds/stone.png"))
private val obtainedTaskFrame = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_obtained.png"))
private val unobtainedTaskFrame = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_unobtained.png"))
private val connectionColor = ArgbColor(0xFF7A7A7A.toInt())
// showcase-source-end:progress-screen

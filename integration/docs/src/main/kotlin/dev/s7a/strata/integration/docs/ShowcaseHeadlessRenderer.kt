package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.integration.minecraft.fabric.createButtonShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createCanvasShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createCheckboxShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createColumnShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createConfirmScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createCycleButtonShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createFlowRowShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createGridShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createImageShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createIndustrialScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createLoadingIndicatorShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createPlayerHeadShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createProgressBarShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createProgressScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createRowShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createScrollAreaShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createScrollbarShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSelectionListShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSliderShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSlotShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSocialScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSpacerShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createStackShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTabShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTextAreaShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTextFieldShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTextShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTiledImageShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createVirtualListShowcaseScreenDefinition
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Renders the same compiled API-only examples used by the independent Fabric acceptance tests.
 * Each call creates and closes its own host on the caller thread, sampling source glyphs at the requested output density.
 * Only immutable resource inputs are shared; no game, graphics context, captured text, or previous frame is an input.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ShowcaseHeadlessRenderer {
    /**
     * Renders the complete overview with its fixed logical pointer and animation time.
     * The returned PNG owns detached pixels; profile and source assets remain caller-owned immutable data.
     */
    internal fun overview(assets: ShowcaseMinecraftAssets): ByteArray =
        render(
            assets.profile,
            createConfirmScreenDefinition(),
            ShowcaseViewport(ShowcaseScenarioCatalog.overview.viewport, ShowcaseScenarioCatalog.overview.scale),
            IntOffset(100, 110),
        )

    /**
     * Renders one complete minimal component example at its catalog density without resizing a rasterized image.
     * Resource, layout, unsupported command, and rasterization failures propagate after the host closes.
     * The catalog dispatch is intentionally exhaustive so a new built-in cannot silently lack its compiled renderer.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun component(
        scenario: ComponentScenario,
        assets: ShowcaseMinecraftAssets,
    ): ByteArray {
        val definition =
            when (scenario.component) {
                DocumentedComponent.Row -> createRowShowcaseScreenDefinition()
                DocumentedComponent.FlowRow -> createFlowRowShowcaseScreenDefinition()
                DocumentedComponent.Column -> createColumnShowcaseScreenDefinition()
                DocumentedComponent.Stack -> createStackShowcaseScreenDefinition()
                DocumentedComponent.Grid -> createGridShowcaseScreenDefinition()
                DocumentedComponent.Spacer -> createSpacerShowcaseScreenDefinition()
                DocumentedComponent.Text -> createTextShowcaseScreenDefinition()
                DocumentedComponent.TextField -> createTextFieldShowcaseScreenDefinition()
                DocumentedComponent.TextArea -> createTextAreaShowcaseScreenDefinition()
                DocumentedComponent.Button -> createButtonShowcaseScreenDefinition()
                DocumentedComponent.Checkbox -> createCheckboxShowcaseScreenDefinition()
                DocumentedComponent.CycleButton -> createCycleButtonShowcaseScreenDefinition()
                DocumentedComponent.Slider -> createSliderShowcaseScreenDefinition()
                DocumentedComponent.Tab -> createTabShowcaseScreenDefinition()
                DocumentedComponent.ScrollArea -> createScrollAreaShowcaseScreenDefinition()
                DocumentedComponent.Scrollbar -> createScrollbarShowcaseScreenDefinition()
                DocumentedComponent.VirtualList -> createVirtualListShowcaseScreenDefinition()
                DocumentedComponent.SelectionList -> createSelectionListShowcaseScreenDefinition()
                DocumentedComponent.Image -> createImageShowcaseScreenDefinition(image(assets, coalGenerator))
                DocumentedComponent.Canvas -> createCanvasShowcaseScreenDefinition()
                DocumentedComponent.TiledImage -> createTiledImageShowcaseScreenDefinition()
                DocumentedComponent.Slot -> createSlotShowcaseScreenDefinition()
                DocumentedComponent.PlayerHead -> createPlayerHeadShowcaseScreenDefinition(assets.playerSkin)
                DocumentedComponent.LoadingIndicator -> createLoadingIndicatorShowcaseScreenDefinition()
                DocumentedComponent.ProgressBar -> createProgressBarShowcaseScreenDefinition()
            }
        val pointer = if (scenario.component == DocumentedComponent.Slot) IntOffset(32, 32) else IntOffset.Zero
        return render(assets.profile, definition, scenario.viewportMetadata, pointer)
    }

    /**
     * Renders a portable complete-screen example using explicit immutable image and player inputs.
     * A server-bound inventory is deliberately rejected: its separate native evidence is never replaced with a mock screen.
     *
     * @throws IllegalArgumentException for the server-bound inventory scenario.
     */
    internal fun screen(
        scenario: ScreenScenario,
        assets: ShowcaseMinecraftAssets,
    ): ByteArray {
        val definition =
            when (scenario.screen) {
                DocumentedScreen.SocialInteractions -> {
                    createSocialScreenDefinition(
                        panel = image(assets, socialPanel),
                        searchIcon = image(assets, searchIcon),
                        playerSkin = assets.playerSkin,
                        playerName = assets.playerName,
                    )
                }

                DocumentedScreen.SynchronizedInventory -> {
                    throw IllegalArgumentException("A server-bound inventory requires its explicitly supplied native evidence.")
                }

                DocumentedScreen.IndustrialController -> {
                    createIndustrialScreenDefinition(
                        panel = image(assets, coalGenerator),
                        fuelBinding = null,
                        chargeBinding = null,
                        playerInventory = { null },
                    )
                }

                DocumentedScreen.PowerMilestones -> {
                    createProgressScreenDefinition(
                        window = image(assets, advancementWindow),
                        background = image(assets, advancementBackground),
                        obtained = image(assets, obtainedTask),
                        unobtained = image(assets, unobtainedTask),
                    )
                }
            }
        return render(assets.profile, definition, ShowcaseViewport(IntSize(scenario.viewportWidth, scenario.viewportHeight), scenario.scale))
    }

    /**
     * Creates fresh commands and pixels at a fixed time, keeping logical layout independent of physical density.
     * The supplied definition is evaluated only inside this host and no mutable state escapes on success or failure.
     */
    internal fun render(
        profile: MinecraftUiProfile,
        definition: ScreenDefinition,
        viewport: ShowcaseViewport,
        pointer: IntOffset = IntOffset.Zero,
    ): ByteArray =
        createMinecraftUiHost(definition, profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(viewport.size, FrameTime(0L))
            host.dispatchPointer(PointerEvent.Move(pointer))
            val frame = host.frame(viewport.size, FrameTime(0L))
            val framebufferClear =
                DrawCommand.FillRectangle(
                    IntRect(0, 0, frame.size.width, frame.size.height),
                    ArgbColor(0xFF000000.toInt()),
                )
            val image = rasterizeHeadless(listOf(framebufferClear) + frame.drawCommands, frame.size, viewport.scale)
            check(image.size == viewport.physicalSize) { "Headless showcase dimensions differ from the requested full viewport." }
            image.encodePng()
        }

    private fun image(
        assets: ShowcaseMinecraftAssets,
        id: ResourceId,
    ): ImageSource = ImageSource.Pixels(assets.image(id))

    private val coalGenerator = ResourceId("strata_test", "textures/gui/coal_generator.png")
    private val socialPanel = ResourceId("minecraft", "textures/gui/sprites/social_interactions/background.png")
    private val searchIcon = ResourceId("minecraft", "textures/gui/sprites/icon/search.png")
    private val advancementWindow = ResourceId("minecraft", "textures/gui/advancements/window.png")
    private val advancementBackground = ResourceId("minecraft", "textures/gui/advancements/backgrounds/stone.png")
    private val obtainedTask = ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_obtained.png")
    private val unobtainedTask = ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_unobtained.png")
}

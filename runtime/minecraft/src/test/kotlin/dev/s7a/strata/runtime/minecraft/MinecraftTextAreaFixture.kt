package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap

/**
 * Owns one deterministic CPU text service shared by explicitly closed test trees.
 *
 * Normal and compact fonts have distinct scalar advances and ink extents, including supplementary characters.
 * All editor input must bypass display shaping; the fixture stores no generated strings or historical layouts.
 *
 * @param glyph optional source-backed metric substitute for overhang and numeric edge cases.
 * @param cacheEntries bounded common glyph cache; zero exposes actual layout work through face-call counts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextAreaFixture(
    glyph: ((MinecraftTrueTypeSettings, Int) -> MinecraftFontGlyph?)? = null,
    cacheEntries: Int = 4096,
) : AutoCloseable {
    /**
     * Calls reaching the CPU face after the common glyph cache, independent of line and command counts.
     */
    var glyphCalls = 0
        private set

    private val image = createDrawImage(IntSize(2, 8), IntArray(16) { -1 })

    /**
     * Counted backend whose native-like metrics require no loaded game or native libraries.
     */
    val backend =
        FontTestBackend(open = { _, settings ->
            object : MinecraftTrueTypeFace {
                override fun glyph(codePoint: Int): MinecraftFontGlyph? {
                    glyphCalls += 1
                    return if (glyph == null) {
                        MinecraftFontGlyph(settings.size, 0f, 0f, settings.size - 1f, settings.size * 2f + 2f, image)
                    } else {
                        glyph(settings, codePoint)
                    }
                }

                override fun close() = Unit
            }
        })

    private val snapshot =
        FontTestResources.snapshot(
            FontTestResources.font("minecraft:default", """{"type":"reference","id":"test:area"}"""),
            FontTestResources.font("test:area", """{"type":"ttf","file":"test:area.ttf","size":3}"""),
            FontTestResources.font("test:compact", """{"type":"ttf","file":"test:area.ttf","size":2}"""),
            "assets/test/font/area.ttf" to byteArrayOf(1),
        )
    private val profile = MinecraftProfileFixture.create(fontSnapshot = snapshot)
    private val renderer = MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }, cacheEntries = cacheEntries))

    /**
     * Supplies borrowed controller inputs for direct invalidation-count tests without adding production instrumentation.
     */
    fun configuration(
        state: TextAreaState,
        size: IntSize = IntSize(32, 26),
    ): MinecraftTextAreaConfiguration =
        MinecraftTextAreaConfiguration(
            image,
            image,
            renderer,
            ResourceId("test", "area"),
            state,
            TextAreaViewport.Size(size),
            true,
            TextStyle.TextField,
            TextWrap.None,
            0,
        )

    /**
     * Creates fresh immutable public-DSL inputs with a stable editor identity and no attached observer.
     * The caller may reuse the same result after a previous tree releases the state's sole editor attachment.
     */
    fun description(
        state: TextAreaState,
        size: IntSize = IntSize(32, 26),
        font: ResourceId = ResourceId("test", "area"),
        style: TextStyle = TextStyle.TextField,
        wrap: TextWrap = TextWrap.None,
        lineSpacing: Int = 0,
        enabled: Boolean = true,
        focused: Boolean = true,
        highlightedColor: Int? = null,
        viewport: TextAreaViewport = TextAreaViewport.Size(size),
    ): Element =
        MinecraftProfileImplementation.createEvaluator(
            if (highlightedColor == null) {
                profile
            } else {
                MinecraftProfileFixture.create(
                    fontSnapshot = snapshot,
                    highlightedTextField = createDrawImage(IntSize(200, 20), IntArray(4000) { highlightedColor }),
                )
            },
            {
                TextArea(
                    state,
                    viewport,
                    font,
                    enabled = enabled,
                    textStyle = style,
                    wrap = wrap,
                    lineSpacing = lineSpacing,
                    modifier = if (focused) Modifier.Empty.initialFocus() else Modifier.Empty,
                    key = ElementKey(Unit),
                )
            },
            textRenderer = renderer,
        )()

    /**
     * Completes ordinary retained phases for a requested outer size and returns detached portable commands.
     */
    fun frame(
        tree: UiTree,
        size: IntSize = IntSize(32, 26),
    ): List<DrawCommand> {
        tree.measure(Constraints.fixed(size.width, size.height))
        tree.layout()
        return tree.paint()
    }

    /**
     * Delivers one key press and settles its geometry before the next synthetic input.
     */
    fun key(
        tree: UiTree,
        key: KeyCode,
        size: IntSize = IntSize(32, 26),
        modifiers: KeyboardModifiers = KeyboardModifiers(),
    ): List<DrawCommand> {
        tree.dispatchKeyboard(KeyboardEvent.Press(key, 0, modifiers))
        return frame(tree, size)
    }

    /**
     * Delivers one committed scalar or composition and settles its retained geometry.
     */
    fun input(
        tree: UiTree,
        event: TextInputEvent,
        size: IntSize = IntSize(32, 26),
    ): List<DrawCommand> {
        tree.dispatchTextInput(event)
        return frame(tree, size)
    }

    override fun close() {
        renderer.close()
    }
}

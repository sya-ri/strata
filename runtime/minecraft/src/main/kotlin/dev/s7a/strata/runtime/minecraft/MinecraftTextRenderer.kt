package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.text.UiText

/**
 * Host-owned text service borrowing either immutable compatibility glyphs or an independently owned font engine.
 * Runs detach the glyphs they need and do not retain this service or an entire UI profile.
 * The owner calls close after disposing the tree; closed services reject further text construction.
 */
internal class MinecraftTextRenderer private constructor(
    private var legacyGlyphs: Map<Int, MinecraftGlyphSnapshot>?,
    private var engine: MinecraftFontEngine?,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false

    /**
     * Creates one immutable run using the same metrics and glyphs as rendering.
     *
     * @param text unresolved literal or font composition.
     * @param style selected vanilla color and shadow treatment.
     * @param enabled selects the enabled TextField tint when applicable.
     * @param font inherited font identifier, overridden by inner wrappers.
     * @param logicalOrder bypasses display shaping and reordering for the native EditBox scalar-order contract.
     * @return detached immutable text run.
     * @throws IllegalStateException after close or from another thread.
     * @throws IllegalArgumentException when compatibility glyphs cannot represent the requested text or font.
     */
    @JvmSynthetic
    internal fun create(
        text: UiText,
        style: TextStyle,
        enabled: Boolean = true,
        font: ResourceId = defaultFont,
        logicalOrder: Boolean = false,
    ): MinecraftTextRun {
        check(Thread.currentThread() === owner && closed.not()) { "Text renderer is closed or accessed from another thread." }
        val currentEngine = engine
        if (currentEngine != null) {
            val foreground =
                when (style) {
                    TextStyle.Normal -> 0xffffff
                    TextStyle.Inactive -> 0xa0a0a0
                    TextStyle.ContainerLabel -> 0x404040
                    TextStyle.TextField -> if (enabled) 0xe0e0e0 else 0x707070
                }
            val shadow = if (style == TextStyle.ContainerLabel) null else ArgbColor(0xff000000.toInt() or ((foreground and 0xfcfcfc) ushr 2))
            return MinecraftTextRun.createFonts(text, currentEngine, font, ArgbColor(0xff000000.toInt() or foreground), shadow, logicalOrder)
        }
        require(font == defaultFont) { "Custom fonts require a font-resource snapshot." }
        val glyphs = checkNotNull(legacyGlyphs)
        return when (style) {
            TextStyle.Normal -> MinecraftTextRun.createNormal(text, glyphs::getValue)
            TextStyle.Inactive -> MinecraftTextRun.createInactive(text, glyphs::getValue)
            TextStyle.ContainerLabel -> MinecraftTextRun.createContainerLabel(text, glyphs::getValue)
            TextStyle.TextField -> MinecraftTextRun.createTextField(text, enabled, glyphs::getValue)
        }
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "Text renderer is confined to its owner thread." }
        if (closed) return
        closed = true
        legacyGlyphs = null
        val retained = engine
        engine = null
        retained?.close()
    }

    /**
     * Constructs distinct owner-thread services without retaining any mutable global font state.
     */
    companion object {
        /**
         * Immutable default resource font requested when a component has no explicit wrapper.
         */
        @get:JvmSynthetic
        internal val defaultFont: ResourceId = ResourceId("minecraft", "default")

        /**
         * Creates a distinct owner-thread compatibility service borrowing immutable glyphs and no UI profile.
         * The caller owns the returned service and must close it after disposing its tree.
         *
         * @param glyphs complete immutable compatibility table, retained without modification until close.
         * @return a new service that initializes no native resources.
         */
        @JvmSynthetic
        internal fun legacy(glyphs: Map<Int, MinecraftGlyphSnapshot>): MinecraftTextRenderer = MinecraftTextRenderer(glyphs, null)

        /**
         * Transfers an independently opened font engine to a new text service on the current owner thread.
         * The caller must close the returned service, which closes the engine after the retained tree is disposed.
         *
         * @param engine exclusively transferred font engine opened on this thread.
         * @return a new service with terminal ownership of the engine.
         */
        @JvmSynthetic
        internal fun fonts(engine: MinecraftFontEngine): MinecraftTextRenderer = MinecraftTextRenderer(null, engine)
    }
}

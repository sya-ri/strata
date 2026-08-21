package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.util.Collections

/**
 * Owns callback-lifetime profile construction and validation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftProfileImplementation {
    /**
     * Creates one complete immutable profile through a callback-lifetime builder.
     *
     * @param content declaration callback invoked synchronously on the calling thread.
     * @return a complete immutable profile.
     * @throws IllegalArgumentException when declarations are invalid, duplicated, or incomplete.
     * @throws Throwable when [content] fails; the exact callback failure escapes unchanged.
     */
    @JvmSynthetic
    fun create(content: MinecraftUiProfileBuilder.() -> Unit): MinecraftUiProfile {
        val builder = Builder()
        return try {
            builder.content()
            builder.snapshot()
        } finally {
            builder.close()
        }
    }

    /**
     * Creates one core-session evaluator from a complete profile and transferred content callback.
     *
     * The evaluator retains its complete profile and content callback until one evaluation or explicit release and then clears them.
     *
     * @param profile complete profile produced by this runtime.
     * @param content transferred application callback.
     * @return an owner-thread one-shot element evaluator.
     */
    @JvmSynthetic
    fun createEvaluator(
        profile: MinecraftUiProfile,
        content: MinecraftUiContext.() -> Element,
    ): () -> Element =
        when (profile) {
            is ProfileSnapshot -> Evaluator.create(profile, content)
        }

    /**
     * Releases a one-shot evaluator created by [createEvaluator].
     *
     * The operation is owner-thread confined and idempotent after evaluation or an earlier release.
     *
     * @param evaluator evaluator whose captured profile and application callback are released.
     * @throws IllegalStateException when the evaluator is foreign or release runs from another thread.
     */
    @JvmSynthetic
    fun releaseEvaluator(evaluator: () -> Element) {
        check(evaluator is Evaluator) { "Minecraft content evaluator was not created by this runtime." }
        evaluator.release()
    }

    @Suppress("TooManyFunctions")
    private class Builder : MinecraftUiProfileBuilder {
        private val ownerThread = Thread.currentThread()
        private val menuSize = IntSize(16, 16)
        private val glyphSize = IntSize(8, 8)
        private val buttonSize = IntSize(200, 20)
        private val renderedButtonSize = IntSize(150, 20)
        private val listSeparatorSize = IntSize(32, 2)
        private val scrollbarSize = IntSize(6, 32)
        private val glyphRange = 0x21..0x7E
        private val transparentWhite = ArgbColor(0x00FFFFFF)
        private val opaqueWhite = ArgbColor(-1)
        private val normalShadowColor = ArgbColor(-0xC0C0C1)
        private val inactiveForegroundColor = ArgbColor(-0x5F5F60)
        private val inactiveShadowColor = ArgbColor(-0xD7D7D8)
        private val glyphs = LinkedHashMap<Int, MinecraftGlyphSnapshot>()
        private var active = true
        private var menu: DrawImage? = null
        private var listBackground: DrawImage? = null
        private var listHeaderSeparator: DrawImage? = null
        private var listFooterSeparator: DrawImage? = null
        private var scrollbarBackground: DrawImage? = null
        private var scrollbarThumb: DrawImage? = null
        private var normalButton: MinecraftButtonSpriteSnapshot? = null
        private var highlightedButton: MinecraftButtonSpriteSnapshot? = null
        private var disabledButton: MinecraftButtonSpriteSnapshot? = null

        override fun menuBackground(image: DrawImage) {
            checkUsable()
            require(menu == null) { "Menu background was already declared." }
            require(image.size == menuSize) {
                "Menu background must be 16 by 16 pixels."
            }
            menu = image
        }

        override fun listBackground(image: DrawImage) {
            checkUsable()
            require(listBackground == null) { "List background was already declared." }
            require(image.size == menuSize) { "List background must be 16 by 16 pixels." }
            listBackground = image
        }

        override fun listHeaderSeparator(image: DrawImage) {
            checkUsable()
            require(listHeaderSeparator == null) { "List header separator was already declared." }
            require(image.size == listSeparatorSize) { "List header separator must be 32 by 2 pixels." }
            listHeaderSeparator = image
        }

        override fun listFooterSeparator(image: DrawImage) {
            checkUsable()
            require(listFooterSeparator == null) { "List footer separator was already declared." }
            require(image.size == listSeparatorSize) { "List footer separator must be 32 by 2 pixels." }
            listFooterSeparator = image
        }

        override fun scrollbarBackground(image: DrawImage) {
            checkUsable()
            require(scrollbarBackground == null) { "Scrollbar background was already declared." }
            require(image.size == scrollbarSize) { "Scrollbar background must be 6 by 32 pixels." }
            scrollbarBackground = image
        }

        override fun scrollbarThumb(image: DrawImage) {
            checkUsable()
            require(scrollbarThumb == null) { "Scrollbar thumb was already declared." }
            require(image.size == scrollbarSize) { "Scrollbar thumb must be 6 by 32 pixels." }
            scrollbarThumb = image
        }

        override fun printableAsciiGlyph(
            codePoint: Int,
            mask: DrawImage,
        ) {
            checkUsable()
            require(codePoint in glyphRange) {
                "Glyph code point must be U+0021 through U+007E."
            }
            require((codePoint in glyphs).not()) { "Glyph code point was already declared." }
            require(mask.size == glyphSize) {
                "Glyph mask must be 8 by 8 pixels."
            }
            glyphs[codePoint] = createGlyph(mask)
        }

        override fun buttonNormal(
            image: DrawImage,
            border: Int,
            centerMode: MinecraftNineSliceCenterMode,
        ) {
            checkUsable()
            require(normalButton == null) { "Normal Button sprite was already declared." }
            normalButton = createButton(image, border, centerMode)
        }

        override fun buttonHighlighted(
            image: DrawImage,
            border: Int,
            centerMode: MinecraftNineSliceCenterMode,
        ) {
            checkUsable()
            require(highlightedButton == null) { "Highlighted Button sprite was already declared." }
            highlightedButton = createButton(image, border, centerMode)
        }

        override fun buttonDisabled(
            image: DrawImage,
            border: Int,
            centerMode: MinecraftNineSliceCenterMode,
        ) {
            checkUsable()
            require(disabledButton == null) { "Disabled Button sprite was already declared." }
            disabledButton = createButton(image, border, centerMode)
        }

        fun snapshot(): ProfileSnapshot {
            checkUsable()
            require(glyphs.size == glyphRange.count()) {
                "Every U+0021 through U+007E glyph must be declared."
            }
            for (codePoint in glyphRange) {
                require(codePoint in glyphs) { "Every U+0021 through U+007E glyph must be declared." }
            }
            return ProfileSnapshot.create(
                menuBackground = requireNotNull(menu) { "Menu background must be declared." },
                listBackground = requireNotNull(listBackground) { "List background must be declared." },
                listHeaderSeparator = requireNotNull(listHeaderSeparator) { "List header separator must be declared." },
                listFooterSeparator = requireNotNull(listFooterSeparator) { "List footer separator must be declared." },
                scrollbarBackground = requireNotNull(scrollbarBackground) { "Scrollbar background must be declared." },
                scrollbarThumb = requireNotNull(scrollbarThumb) { "Scrollbar thumb must be declared." },
                glyphs = glyphs,
                normalButton = requireNotNull(normalButton) { "Normal Button sprite must be declared." },
                highlightedButton = requireNotNull(highlightedButton) { "Highlighted Button sprite must be declared." },
                disabledButton = requireNotNull(disabledButton) { "Disabled Button sprite must be declared." },
            )
        }

        fun close() {
            active = false
            glyphs.clear()
            menu = null
            listBackground = null
            listHeaderSeparator = null
            listFooterSeparator = null
            scrollbarBackground = null
            scrollbarThumb = null
            normalButton = null
            highlightedButton = null
            disabledButton = null
        }

        private fun checkUsable() {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI profile builder requires its creator thread." }
            check(active) { "Minecraft UI profile builder is closed." }
        }

        private fun createGlyph(mask: DrawImage): MinecraftGlyphSnapshot {
            val pixels = mask.copyArgb()
            val normalShadow = IntArray(pixels.size)
            val normalForeground = IntArray(pixels.size)
            val inactiveShadow = IntArray(pixels.size)
            val inactiveForeground = IntArray(pixels.size)
            var rightmost = -1
            for (index in pixels.indices) {
                when (ArgbColor(pixels[index])) {
                    transparentWhite -> {
                        normalShadow[index] = transparentWhite.value
                        normalForeground[index] = transparentWhite.value
                        inactiveShadow[index] = transparentWhite.value
                        inactiveForeground[index] = transparentWhite.value
                    }

                    opaqueWhite -> {
                        val x = index % glyphSize.width
                        if (rightmost < x) rightmost = x
                        normalShadow[index] = normalShadowColor.value
                        normalForeground[index] = opaqueWhite.value
                        inactiveShadow[index] = inactiveShadowColor.value
                        inactiveForeground[index] = inactiveForegroundColor.value
                    }

                    else -> {
                        throw IllegalArgumentException("Glyph masks must contain only transparent white or opaque white pixels.")
                    }
                }
            }
            return MinecraftGlyphSnapshot.create(
                advance = Math.addExact(rightmost, 2),
                normalShadow = createDrawImage(glyphSize, normalShadow),
                normalForeground = createDrawImage(glyphSize, normalForeground),
                inactiveShadow = createDrawImage(glyphSize, inactiveShadow),
                inactiveForeground = createDrawImage(glyphSize, inactiveForeground),
            )
        }

        private fun createButton(
            image: DrawImage,
            border: Int,
            centerMode: MinecraftNineSliceCenterMode,
        ): MinecraftButtonSpriteSnapshot {
            require(image.size == buttonSize) {
                "Button sprites must be 200 by 20 pixels."
            }
            require(0 < border) { "Button sprite border must be positive." }
            require(border < buttonSize.width / 2) {
                "Button sprite borders must leave a nonempty source center."
            }
            require(border < renderedButtonSize.width / 2) {
                "Button sprite borders must leave a nonempty fixed destination center."
            }
            return MinecraftButtonSpriteSnapshot.create(image, border, centerMode)
        }
    }

    private class ProfileSnapshot private constructor(
        val menuBackground: DrawImage,
        val listBackground: DrawImage,
        val listHeaderSeparator: DrawImage,
        val listFooterSeparator: DrawImage,
        val scrollbarBackground: DrawImage,
        val scrollbarThumb: DrawImage,
        glyphs: Map<Int, MinecraftGlyphSnapshot>,
        val normalButton: MinecraftButtonSpriteSnapshot,
        val highlightedButton: MinecraftButtonSpriteSnapshot,
        val disabledButton: MinecraftButtonSpriteSnapshot,
    ) : MinecraftUiProfile {
        private val glyphs: Map<Int, MinecraftGlyphSnapshot> = Collections.unmodifiableMap(LinkedHashMap(glyphs))

        fun glyph(codePoint: Int): MinecraftGlyphSnapshot = glyphs.getValue(codePoint)

        companion object {
            /**
             * Creates one private complete profile snapshot.
             *
             * @param menuBackground immutable menu image.
             * @param glyphs complete printable-ASCII glyph map.
             * @param listBackground immutable menu-list background image.
             * @param listHeaderSeparator immutable menu-list header separator.
             * @param listFooterSeparator immutable menu-list footer separator.
             * @param scrollbarBackground immutable scrollbar-track sprite.
             * @param scrollbarThumb immutable scrollbar-thumb sprite.
             * @param normalButton normal sprite policy.
             * @param highlightedButton highlighted sprite policy.
             * @param disabledButton disabled sprite policy.
             * @return a private immutable profile implementation.
             */
            @JvmSynthetic
            internal fun create(
                menuBackground: DrawImage,
                listBackground: DrawImage,
                listHeaderSeparator: DrawImage,
                listFooterSeparator: DrawImage,
                scrollbarBackground: DrawImage,
                scrollbarThumb: DrawImage,
                glyphs: Map<Int, MinecraftGlyphSnapshot>,
                normalButton: MinecraftButtonSpriteSnapshot,
                highlightedButton: MinecraftButtonSpriteSnapshot,
                disabledButton: MinecraftButtonSpriteSnapshot,
            ): ProfileSnapshot =
                ProfileSnapshot(
                    menuBackground,
                    listBackground,
                    listHeaderSeparator,
                    listFooterSeparator,
                    scrollbarBackground,
                    scrollbarThumb,
                    glyphs,
                    normalButton,
                    highlightedButton,
                    disabledButton,
                )
        }
    }

    private class Context private constructor(
        initialProfile: ProfileSnapshot,
    ) : MinecraftUiContext {
        private val ownerThread = Thread.currentThread()
        private var profile: ProfileSnapshot? = initialProfile

        override fun UiScope.MenuBackground(
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val description = createMinecraftMenuBackgroundElement(requireProfile().menuBackground, modifier, key)
            element(description)
        }

        override fun UiScope.Text(
            text: UiText,
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val currentProfile = requireProfile()
            element(
                createMinecraftTextElement(
                    MinecraftTextRun.createNormal(text, currentProfile::glyph),
                    modifier,
                    key,
                ),
            )
        }

        override fun UiScope.Button(
            label: UiText,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val currentProfile = requireProfile()
            val normalText = MinecraftTextRun.createNormal(label, currentProfile::glyph)
            val inactiveText = MinecraftTextRun.createInactive(label, currentProfile::glyph)
            element(
                createMinecraftPointerButtonElement(
                    currentProfile.normalButton,
                    currentProfile.highlightedButton,
                    currentProfile.disabledButton,
                    normalText,
                    inactiveText,
                    normalText.text,
                    enabled,
                    modifier,
                    key,
                ),
            )
        }

        override fun UiScope.Scroll(
            modifier: Modifier,
            key: ElementKey<*>?,
            scrollRate: Int,
            content: UiScope.() -> Unit,
        ) {
            val currentProfile = requireProfile()
            require(0 < scrollRate) { "Minecraft Scroll rate must be positive." }
            val child = buildUi(content)
            element(
                createMinecraftScrollElement(
                    currentProfile.listBackground,
                    currentProfile.listHeaderSeparator,
                    currentProfile.listFooterSeparator,
                    currentProfile.scrollbarBackground,
                    currentProfile.scrollbarThumb,
                    scrollRate,
                    child,
                    modifier,
                    key,
                ),
            )
        }

        fun close() {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI context requires its creator thread." }
            profile = null
        }

        private fun requireProfile(): ProfileSnapshot {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI context requires its creator thread." }
            return checkNotNull(profile) { "Minecraft UI context is closed." }
        }

        companion object {
            /**
             * Creates one private callback-lifetime context.
             *
             * @param profile complete immutable profile available during evaluation.
             * @return an active context bound to the current thread.
             */
            @JvmSynthetic
            internal fun create(
                profile: ProfileSnapshot,
            ): Context = Context(profile)
        }
    }

    private class Evaluator private constructor(
        initialProfile: ProfileSnapshot,
        initialContent: MinecraftUiContext.() -> Element,
    ) : () -> Element {
        private val ownerThread = Thread.currentThread()
        private var profile: ProfileSnapshot? = initialProfile
        private var content: (MinecraftUiContext.() -> Element)? = initialContent

        override fun invoke(): Element {
            check(Thread.currentThread() === ownerThread) { "Minecraft content evaluation requires the host owner thread." }
            val currentProfile = checkNotNull(profile) { "Minecraft screen content was already evaluated." }
            val currentContent = checkNotNull(content) { "Minecraft screen content was already evaluated." }
            profile = null
            content = null
            val context = Context.create(currentProfile)
            return try {
                context.currentContent()
            } finally {
                context.close()
            }
        }

        fun release() {
            check(Thread.currentThread() === ownerThread) { "Minecraft content release requires the host owner thread." }
            profile = null
            content = null
        }

        companion object {
            /**
             * Creates one private owner-thread evaluator.
             *
             * @param profile complete profile retained until evaluation or release.
             * @param content application content retained until evaluation or release.
             * @return a one-shot evaluator.
             */
            @JvmSynthetic
            internal fun create(
                profile: ProfileSnapshot,
                content: MinecraftUiContext.() -> Element,
            ): Evaluator = Evaluator(profile, content)
        }
    }
}

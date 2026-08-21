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
 * Owns callback-lifetime profile construction, validation, retained evaluation, and dynamically scoped component dispatch.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // These operations share one profile/context ownership boundary.
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
     * Creates one owner-thread mutable TextField state.
     *
     * @param initialValue initial printable-ASCII value.
     * @param maxLength positive maximum UTF-16 length.
     * @return detached state with no live observer.
     * @throws IllegalArgumentException when the value or length is invalid.
     */
    @JvmSynthetic
    fun createTextFieldState(
        initialValue: String,
        maxLength: Int,
    ): MinecraftTextFieldState = TextFieldState.create(initialValue, maxLength)

    /**
     * Installs the sole live observer for one state.
     *
     * @param state state created by this runtime.
     * @param observer owner-thread callback invoked after distinct successful writes.
     * @return idempotent owner-thread release callback.
     * @throws IllegalStateException when the state is foreign, observed already, or used from another thread.
     */
    @JvmSynthetic
    fun observeTextFieldState(
        state: MinecraftTextFieldState,
        observer: (String) -> Unit,
    ): () -> Unit =
        when (state) {
            is TextFieldState -> state.observe(observer)
        }

    /**
     * Creates one core-session evaluator from a complete profile and transferred content callback.
     *
     * The evaluator retains its complete profile and content callback until one evaluation or explicit release and then clears them.
     *
     * @param profile complete profile produced by this runtime.
     * @param content transferred application callback.
     * @param platform optional version services retained until evaluation or explicit release.
     * @return an owner-thread one-shot element evaluator.
     */
    @JvmSynthetic
    fun createEvaluator(
        profile: MinecraftUiProfile,
        content: UiScope.() -> Unit,
        platform: MinecraftUiPlatform? = null,
    ): () -> Element =
        when (profile) {
            is ProfileSnapshot -> Evaluator.create(profile, content, platform)
        }

    /**
     * Appends the active profile's menu-background behavior.
     *
     * @param modifier caller-owned immutable chain.
     * @return a new chain retaining the selected asset.
     * @throws IllegalStateException when no screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun menuBackground(modifier: Modifier): Modifier = currentContext().menuBackground(modifier)

    /**
     * Appends the active profile's generic-container behavior.
     *
     * @param modifier caller-owned immutable chain.
     * @param rows requested chest row count.
     * @return a new chain retaining the selected asset and row policy.
     * @throws IllegalStateException when no screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun containerBackground(
        modifier: Modifier,
        rows: Int,
    ): Modifier = currentContext().containerBackground(modifier, rows)

    /**
     * Emits one Slot through the active profile context.
     *
     * @param scope active destination scope.
     * @param highlightable whether native hover layers are enabled.
     * @param modifier active Slot behavior.
     * @param key optional sibling identity.
     * @param content optional single item-root callback.
     * @throws IllegalStateException when no matching screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun emitSlot(
        scope: UiScope,
        highlightable: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
        content: (UiScope.() -> Unit)?,
    ) {
        currentContext().emitSlot(scope, highlightable, modifier, key, content)
    }

    /**
     * Emits one bound inventory Slot through the active profile and platform context.
     *
     * @param scope active destination scope.
     * @param binding immutable player-inventory or active-menu locator.
     * @param highlightable whether native hover layers are enabled.
     * @param modifier active Slot behavior.
     * @param key optional sibling identity.
     * @throws IllegalArgumentException when [binding] cannot be resolved by the active menu.
     * @throws IllegalStateException when no matching versioned screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun emitBoundSlot(
        scope: UiScope,
        binding: MinecraftSlotBinding,
        highlightable: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ) {
        currentContext().emitBoundSlot(scope, binding, highlightable, modifier, key)
    }

    /**
     * Emits one Text through the active profile context.
     *
     * @param scope active destination scope.
     * @param text unresolved text value.
     * @param style typed profile-backed style.
     * @param modifier active Text behavior.
     * @param key optional sibling identity.
     * @throws IllegalStateException when no matching screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun emitText(
        scope: UiScope,
        text: UiText,
        style: MinecraftTextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ) {
        currentContext().emitText(scope, text, style, modifier, key)
    }

    /**
     * Emits one TextField through the active profile context.
     *
     * @param scope active destination scope.
     * @param state caller-owned field state.
     * @param enabled whether editing and focus are enabled.
     * @param modifier active TextField behavior.
     * @param key optional sibling identity.
     * @throws IllegalStateException when no matching screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun emitTextField(
        scope: UiScope,
        state: MinecraftTextFieldState,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ) {
        currentContext().emitTextField(scope, state, enabled, modifier, key)
    }

    /**
     * Emits one Button through the active profile context.
     *
     * @param scope active destination scope.
     * @param label unresolved button label.
     * @param width requested logical width.
     * @param enabled whether enabled appearance and semantics are used.
     * @param modifier active Button behavior.
     * @param key optional sibling identity.
     * @throws IllegalStateException when no matching screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun emitButton(
        scope: UiScope,
        label: UiText,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ) {
        currentContext().emitButton(scope, label, width, enabled, modifier, key)
    }

    /**
     * Emits one Scroll through the active profile context.
     *
     * @param scope active destination scope.
     * @param modifier active Scroll behavior.
     * @param key optional sibling identity.
     * @param scrollRate positive logical wheel multiplier.
     * @param content single content-root callback.
     * @throws IllegalStateException when no matching screen-content callback is active on this thread.
     */
    @JvmSynthetic
    fun emitScroll(
        scope: UiScope,
        modifier: Modifier,
        key: ElementKey<*>?,
        scrollRate: Int,
        content: UiScope.() -> Unit,
    ) {
        currentContext().emitScroll(scope, modifier, key, scrollRate, content)
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
        private val containerSize = IntSize(256, 256)
        private val slotHighlightSize = IntSize(24, 24)
        private val glyphSize = IntSize(8, 8)
        private val buttonSize = IntSize(200, 20)
        private val listSeparatorSize = IntSize(32, 2)
        private val scrollbarSize = IntSize(6, 32)
        private val glyphRange = 0x21..0x7E
        private val transparentWhite = ArgbColor(0x00FFFFFF)
        private val opaqueWhite = ArgbColor(-1)
        private val normalShadowColor = ArgbColor(-0xC0C0C1)
        private val inactiveForegroundColor = ArgbColor(-0x5F5F60)
        private val inactiveShadowColor = ArgbColor(-0xD7D7D8)
        private val textFieldForegroundColor = ArgbColor(-0x1F1F20)
        private val textFieldShadowColor = ArgbColor(-0xC7C7C8)
        private val textFieldDisabledForegroundColor = ArgbColor(-0x8F8F90)
        private val textFieldDisabledShadowColor = ArgbColor(-0xE3E3E4)
        private val containerForegroundColor = ArgbColor(-0xBFBFC0)
        private val glyphs = LinkedHashMap<Int, MinecraftGlyphSnapshot>()
        private var active = true
        private var menu: DrawImage? = null
        private var containerBackground: DrawImage? = null
        private var slotHighlightBack: DrawImage? = null
        private var slotHighlightFront: DrawImage? = null
        private var listBackground: DrawImage? = null
        private var listHeaderSeparator: DrawImage? = null
        private var listFooterSeparator: DrawImage? = null
        private var scrollbarBackground: DrawImage? = null
        private var scrollbarThumb: DrawImage? = null
        private var normalTextField: DrawImage? = null
        private var highlightedTextField: DrawImage? = null
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

        override fun containerBackground(image: DrawImage) {
            checkUsable()
            require(containerBackground == null) { "Container background was already declared." }
            require(image.size == containerSize) { "Container background must be 256 by 256 pixels." }
            containerBackground = image
        }

        override fun slotHighlightBack(image: DrawImage) {
            checkUsable()
            require(slotHighlightBack == null) { "Slot back highlight was already declared." }
            require(image.size == slotHighlightSize) { "Slot highlights must be 24 by 24 pixels." }
            slotHighlightBack = image
        }

        override fun slotHighlightFront(image: DrawImage) {
            checkUsable()
            require(slotHighlightFront == null) { "Slot front highlight was already declared." }
            require(image.size == slotHighlightSize) { "Slot highlights must be 24 by 24 pixels." }
            slotHighlightFront = image
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

        override fun textFieldNormal(image: DrawImage) {
            checkUsable()
            require(normalTextField == null) { "Normal TextField sprite was already declared." }
            require(image.size == buttonSize) { "TextField sprites must be 200 by 20 pixels." }
            normalTextField = image
        }

        override fun textFieldHighlighted(image: DrawImage) {
            checkUsable()
            require(highlightedTextField == null) { "Highlighted TextField sprite was already declared." }
            require(image.size == buttonSize) { "TextField sprites must be 200 by 20 pixels." }
            highlightedTextField = image
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
                containerBackground = requireNotNull(containerBackground) { "Container background must be declared." },
                slotHighlightBack = requireNotNull(slotHighlightBack) { "Slot back highlight must be declared." },
                slotHighlightFront = requireNotNull(slotHighlightFront) { "Slot front highlight must be declared." },
                listBackground = requireNotNull(listBackground) { "List background must be declared." },
                listHeaderSeparator = requireNotNull(listHeaderSeparator) { "List header separator must be declared." },
                listFooterSeparator = requireNotNull(listFooterSeparator) { "List footer separator must be declared." },
                scrollbarBackground = requireNotNull(scrollbarBackground) { "Scrollbar background must be declared." },
                scrollbarThumb = requireNotNull(scrollbarThumb) { "Scrollbar thumb must be declared." },
                normalTextField = requireNotNull(normalTextField) { "Normal TextField sprite must be declared." },
                highlightedTextField = requireNotNull(highlightedTextField) { "Highlighted TextField sprite must be declared." },
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
            containerBackground = null
            slotHighlightBack = null
            slotHighlightFront = null
            listBackground = null
            listHeaderSeparator = null
            listFooterSeparator = null
            scrollbarBackground = null
            scrollbarThumb = null
            normalTextField = null
            highlightedTextField = null
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
            val textFieldShadow = IntArray(pixels.size)
            val textFieldForeground = IntArray(pixels.size)
            val textFieldDisabledShadow = IntArray(pixels.size)
            val textFieldDisabledForeground = IntArray(pixels.size)
            val containerForeground = IntArray(pixels.size)
            var rightmost = -1
            for (index in pixels.indices) {
                when (ArgbColor(pixels[index])) {
                    transparentWhite -> {
                        normalShadow[index] = transparentWhite.value
                        normalForeground[index] = transparentWhite.value
                        inactiveShadow[index] = transparentWhite.value
                        inactiveForeground[index] = transparentWhite.value
                        textFieldShadow[index] = transparentWhite.value
                        textFieldForeground[index] = transparentWhite.value
                        textFieldDisabledShadow[index] = transparentWhite.value
                        textFieldDisabledForeground[index] = transparentWhite.value
                        containerForeground[index] = transparentWhite.value
                    }

                    opaqueWhite -> {
                        val x = index % glyphSize.width
                        if (rightmost < x) rightmost = x
                        normalShadow[index] = normalShadowColor.value
                        normalForeground[index] = opaqueWhite.value
                        inactiveShadow[index] = inactiveShadowColor.value
                        inactiveForeground[index] = inactiveForegroundColor.value
                        textFieldShadow[index] = textFieldShadowColor.value
                        textFieldForeground[index] = textFieldForegroundColor.value
                        textFieldDisabledShadow[index] = textFieldDisabledShadowColor.value
                        textFieldDisabledForeground[index] = textFieldDisabledForegroundColor.value
                        containerForeground[index] = containerForegroundColor.value
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
                textFieldShadow = createDrawImage(glyphSize, textFieldShadow),
                textFieldForeground = createDrawImage(glyphSize, textFieldForeground),
                textFieldDisabledShadow = createDrawImage(glyphSize, textFieldDisabledShadow),
                textFieldDisabledForeground = createDrawImage(glyphSize, textFieldDisabledForeground),
                containerForeground = createDrawImage(glyphSize, containerForeground),
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
            return MinecraftButtonSpriteSnapshot.create(image, border, centerMode)
        }
    }

    private class ProfileSnapshot private constructor(
        val menuBackground: DrawImage,
        val containerBackground: DrawImage,
        val slotHighlightBack: DrawImage,
        val slotHighlightFront: DrawImage,
        val listBackground: DrawImage,
        val listHeaderSeparator: DrawImage,
        val listFooterSeparator: DrawImage,
        val scrollbarBackground: DrawImage,
        val scrollbarThumb: DrawImage,
        val normalTextField: DrawImage,
        val highlightedTextField: DrawImage,
        glyphs: Map<Int, MinecraftGlyphSnapshot>,
        val normalButton: MinecraftButtonSpriteSnapshot,
        val highlightedButton: MinecraftButtonSpriteSnapshot,
        val disabledButton: MinecraftButtonSpriteSnapshot,
    ) : MinecraftUiProfile {
        private val glyphs: Map<Int, MinecraftGlyphSnapshot> = Collections.unmodifiableMap(LinkedHashMap(glyphs))

        fun glyph(codePoint: Int): MinecraftGlyphSnapshot = glyphs.getValue(codePoint)

        fun glyphSnapshot(): Map<Int, MinecraftGlyphSnapshot> = glyphs

        companion object {
            /**
             * Creates one private complete profile snapshot.
             *
             * @param menuBackground immutable menu image.
             * @param containerBackground immutable generic-container image.
             * @param slotHighlightBack immutable back-highlight image.
             * @param slotHighlightFront immutable front-highlight image.
             * @param glyphs complete printable-ASCII glyph map.
             * @param listBackground immutable menu-list background image.
             * @param listHeaderSeparator immutable menu-list header separator.
             * @param listFooterSeparator immutable menu-list footer separator.
             * @param scrollbarBackground immutable scrollbar-track sprite.
             * @param scrollbarThumb immutable scrollbar-thumb sprite.
             * @param normalTextField normal TextField sprite.
             * @param highlightedTextField focused TextField sprite.
             * @param normalButton normal sprite policy.
             * @param highlightedButton highlighted sprite policy.
             * @param disabledButton disabled sprite policy.
             * @return a private immutable profile implementation.
             */
            @JvmSynthetic
            internal fun create(
                menuBackground: DrawImage,
                containerBackground: DrawImage,
                slotHighlightBack: DrawImage,
                slotHighlightFront: DrawImage,
                listBackground: DrawImage,
                listHeaderSeparator: DrawImage,
                listFooterSeparator: DrawImage,
                scrollbarBackground: DrawImage,
                scrollbarThumb: DrawImage,
                normalTextField: DrawImage,
                highlightedTextField: DrawImage,
                glyphs: Map<Int, MinecraftGlyphSnapshot>,
                normalButton: MinecraftButtonSpriteSnapshot,
                highlightedButton: MinecraftButtonSpriteSnapshot,
                disabledButton: MinecraftButtonSpriteSnapshot,
            ): ProfileSnapshot =
                ProfileSnapshot(
                    menuBackground,
                    containerBackground,
                    slotHighlightBack,
                    slotHighlightFront,
                    listBackground,
                    listHeaderSeparator,
                    listFooterSeparator,
                    scrollbarBackground,
                    scrollbarThumb,
                    normalTextField,
                    highlightedTextField,
                    glyphs,
                    normalButton,
                    highlightedButton,
                    disabledButton,
                )
        }
    }

    private class Context private constructor(
        initialProfile: ProfileSnapshot,
        initialPlatform: MinecraftUiPlatform?,
    ) {
        private val ownerThread = Thread.currentThread()
        private var profile: ProfileSnapshot? = initialProfile
        private var platform: MinecraftUiPlatform? = initialPlatform

        fun menuBackground(modifier: Modifier): Modifier = modifier.then(createMinecraftMenuBackgroundModifier(requireProfile().menuBackground))

        fun containerBackground(
            modifier: Modifier,
            rows: Int,
        ): Modifier = modifier.then(createMinecraftContainerBackgroundModifier(requireProfile().containerBackground, rows))

        fun emitSlot(
            scope: UiScope,
            highlightable: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
            content: (UiScope.() -> Unit)?,
        ) {
            val currentProfile = requireProfile()
            val child = content?.let(::buildUi)
            scope.element(
                createMinecraftSlotElement(
                    currentProfile.slotHighlightBack,
                    currentProfile.slotHighlightFront,
                    highlightable,
                    child,
                    null,
                    modifier,
                    key,
                ),
            )
        }

        fun emitBoundSlot(
            scope: UiScope,
            binding: MinecraftSlotBinding,
            highlightable: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val currentProfile = requireProfile()
            val currentPlatform = checkNotNull(platform) { "Bound inventory Slots require a versioned Minecraft platform host." }
            scope.element(
                createMinecraftSlotElement(
                    currentProfile.slotHighlightBack,
                    currentProfile.slotHighlightFront,
                    highlightable,
                    null,
                    currentPlatform.inventorySlot(binding),
                    modifier,
                    key,
                ),
            )
        }

        fun emitText(
            scope: UiScope,
            text: UiText,
            style: MinecraftTextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val currentProfile = requireProfile()
            scope.element(
                createMinecraftTextElement(
                    when (style) {
                        MinecraftTextStyle.Normal -> MinecraftTextRun.createNormal(text, currentProfile::glyph)
                        MinecraftTextStyle.Inactive -> MinecraftTextRun.createInactive(text, currentProfile::glyph)
                        MinecraftTextStyle.ContainerLabel -> MinecraftTextRun.createContainerLabel(text, currentProfile::glyph)
                    },
                    modifier,
                    key,
                ),
            )
        }

        fun emitButton(
            scope: UiScope,
            label: UiText,
            width: Int,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val currentProfile = requireProfile()
            require(0 < width && width <= 200) { "Minecraft Button width must be positive and no larger than 200." }
            require(currentProfile.normalButton.border * 2 < width) { "Minecraft Button width must leave a nonempty normal center." }
            require(currentProfile.highlightedButton.border * 2 < width) { "Minecraft Button width must leave a nonempty highlighted center." }
            require(currentProfile.disabledButton.border * 2 < width) { "Minecraft Button width must leave a nonempty disabled center." }
            val normalText = MinecraftTextRun.createNormal(label, currentProfile::glyph)
            val inactiveText = MinecraftTextRun.createInactive(label, currentProfile::glyph)
            scope.element(
                createMinecraftPointerButtonElement(
                    currentProfile.normalButton,
                    currentProfile.highlightedButton,
                    currentProfile.disabledButton,
                    normalText,
                    inactiveText,
                    normalText.text,
                    width,
                    enabled,
                    modifier,
                    key,
                ),
            )
        }

        fun emitTextField(
            scope: UiScope,
            state: MinecraftTextFieldState,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ) {
            val currentProfile = requireProfile()
            scope.element(
                createMinecraftTextFieldElement(
                    currentProfile.normalTextField,
                    currentProfile.highlightedTextField,
                    currentProfile.glyphSnapshot(),
                    state,
                    enabled,
                    modifier,
                    key,
                ),
            )
        }

        fun emitScroll(
            scope: UiScope,
            modifier: Modifier,
            key: ElementKey<*>?,
            scrollRate: Int,
            content: UiScope.() -> Unit,
        ) {
            val currentProfile = requireProfile()
            require(0 < scrollRate) { "Minecraft Scroll rate must be positive." }
            val child = buildUi(content)
            scope.element(
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
            platform = null
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
             * @param platform optional version services available only during evaluation.
             * @return an active context bound to the current thread.
             */
            @JvmSynthetic
            internal fun create(
                profile: ProfileSnapshot,
                platform: MinecraftUiPlatform?,
            ): Context = Context(profile, platform)
        }
    }

    private class Evaluator private constructor(
        initialProfile: ProfileSnapshot,
        initialContent: UiScope.() -> Unit,
        initialPlatform: MinecraftUiPlatform?,
    ) : () -> Element {
        private val ownerThread = Thread.currentThread()
        private var profile: ProfileSnapshot? = initialProfile
        private var content: (UiScope.() -> Unit)? = initialContent
        private var platform: MinecraftUiPlatform? = initialPlatform

        override fun invoke(): Element {
            check(Thread.currentThread() === ownerThread) { "Minecraft content evaluation requires the host owner thread." }
            val currentProfile = checkNotNull(profile) { "Minecraft screen content was already evaluated." }
            val currentContent = checkNotNull(content) { "Minecraft screen content was already evaluated." }
            val currentPlatform = platform
            profile = null
            content = null
            platform = null
            val context = Context.create(currentProfile, currentPlatform)
            return try {
                ContextBinding.withContext(context) { buildUi(currentContent) }
            } finally {
                context.close()
            }
        }

        @Suppress("unused")
        fun release() {
            check(Thread.currentThread() === ownerThread) { "Minecraft content release requires the host owner thread." }
            profile = null
            content = null
            platform = null
        }

        companion object {
            /**
             * Creates one private owner-thread evaluator.
             *
             * @param profile complete profile retained until evaluation or release.
             * @param content application content retained until evaluation or release.
             * @param platform optional version services retained until evaluation or release.
             * @return a one-shot evaluator.
             */
            @JvmSynthetic
            internal fun create(
                profile: ProfileSnapshot,
                content: UiScope.() -> Unit,
                platform: MinecraftUiPlatform?,
            ): Evaluator = Evaluator(profile, content, platform)
        }
    }

    private fun currentContext(): Context = ContextBinding.current()

    private object ContextBinding {
        private val active = ThreadLocal<Context?>()

        fun current(): Context = checkNotNull(active.get()) { "Minecraft UI functions require an active screen-content callback on this thread." }

        fun <T> withContext(
            context: Context,
            operation: () -> T,
        ): T {
            val previous = active.get()
            active.set(context)
            return try {
                operation()
            } finally {
                if (previous == null) {
                    active.remove()
                } else {
                    active.set(previous)
                }
            }
        }
    }

    private class TextFieldState private constructor(
        initialValue: String,
        override val maxLength: Int,
    ) : MinecraftTextFieldState {
        private val ownerThread = Thread.currentThread()
        private var observer: ((String) -> Unit)? = null
        private var currentValue: String = validate(initialValue, maxLength)

        override var value: String
            get() {
                checkThread()
                return currentValue
            }
            set(value) {
                checkThread()
                val validated = validate(value, maxLength)
                if (currentValue == validated) return
                currentValue = validated
                observer?.invoke(validated)
            }

        fun observe(callback: (String) -> Unit): () -> Unit {
            checkThread()
            check(observer == null) { "Minecraft TextField state already has a live observer." }
            observer = callback
            return {
                checkThread()
                if (observer === callback) observer = null
            }
        }

        private fun checkThread() {
            check(Thread.currentThread() === ownerThread) { "Minecraft TextField state requires its creator thread." }
        }

        companion object {
            /**
             * Creates validated private state.
             *
             * @param initialValue initial printable-ASCII value.
             * @param maxLength positive maximum UTF-16 length.
             * @return owner-thread state.
             */
            @JvmSynthetic
            internal fun create(
                initialValue: String,
                maxLength: Int,
            ): TextFieldState {
                require(0 < maxLength) { "Minecraft TextField maximum length must be positive." }
                return TextFieldState(initialValue, maxLength)
            }

            private fun validate(
                value: String,
                maxLength: Int,
            ): String {
                require(value.length <= maxLength) { "Minecraft TextField value exceeds its maximum length." }
                require(value.all { character -> character.code in 0x20..0x7E }) {
                    "Minecraft TextField supports only U+0020 through U+007E."
                }
                return value
            }
        }
    }
}

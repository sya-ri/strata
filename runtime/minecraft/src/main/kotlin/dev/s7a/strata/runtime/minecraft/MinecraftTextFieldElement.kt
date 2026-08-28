package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained implementation of the explicitly sized profile-backed Minecraft TextField component.
 *
 * The description snapshots the two sprites and borrows its host-owned text renderer without retaining a complete profile.
 * Nodes clear the renderer reference on disposal; only the host closes the shared font resources after disposing its tree.
 * Composition stays inline and uses the supplied caret and focused block; it does not reproduce Minecraft's native IME popup or platform candidate window.
 * Editable text paints in logical scalar order, matching native EditBox's forward formatter rather than display-label shaping.
 * Caret and composition geometry uses signed native widths; portions outside the field are omitted before conversion to portable integer bounds.
 */
private class MinecraftTextFieldElement private constructor(
    @get:JvmSynthetic
    internal val normalSprite: DrawImage,
    @get:JvmSynthetic
    internal val highlightedSprite: DrawImage,
    @get:JvmSynthetic
    internal val textRenderer: MinecraftTextRenderer,
    @get:JvmSynthetic
    internal val font: ResourceId,
    @get:JvmSynthetic
    internal val state: TextFieldState,
    @get:JvmSynthetic
    internal val fieldSize: IntSize,
    @get:JvmSynthetic
    internal val enabled: Boolean,
    @get:JvmSynthetic
    internal val textStyle: TextStyle,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Retained owner-thread EditBox subset with state observation, focus, pointer cursor placement, editing, paint, and semantics.
     */
    @Suppress("TooManyFunctions")
    private class Node(
        initialNormalSprite: DrawImage,
        initialHighlightedSprite: DrawImage,
        initialTextRenderer: MinecraftTextRenderer,
        initialFont: ResourceId,
        initialState: TextFieldState,
        initialFieldSize: IntSize,
        initialEnabled: Boolean,
        initialTextStyle: TextStyle,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerInputNode,
        FocusTargetNode,
        KeyboardInputNode,
        TextInputNode,
        SemanticsNode,
        LifecycleNode {
        private var fieldSize = initialFieldSize
        private val textOrigin: IntOffset
            get() = IntOffset(4, Math.subtractExact(fieldSize.height, 8) / 2)
        private val innerWidth: Int
            get() = Math.subtractExact(fieldSize.width, 8)
        private val cursorColor = ArgbColor(0xFFFFFFFF.toInt())
        private var normalSprite: DrawImage? = initialNormalSprite
        private var highlightedSprite: DrawImage? = initialHighlightedSprite
        private var textRenderer: MinecraftTextRenderer? = initialTextRenderer
        private var font = initialFont
        private var state: TextFieldState? = initialState
        private var enabled = initialEnabled
        private var textStyle = initialTextStyle
        private var focused = false
        private var attached = false
        private var cursor = initialState.value.length
        private var preedit: TextInputEvent.Preedit? = null
        private var releaseObserver: AutoCloseable? = null

        override val acceptsFocus: Boolean
            get() = enabled

        override val requiresTextInput: Boolean
            get() = enabled

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(fieldSize)) {
                "Minecraft TextField constraints must contain its requested size."
            }
            return fieldSize
        }

        override fun paint(scope: PaintScope) {
            val sprite = if (focused && enabled) checkNotNull(highlightedSprite) else checkNotNull(normalSprite)
            paintMinecraftNineSlice(scope, sprite, Insets.all(1), NineSliceCenterMode.Tiled)
            val currentValue = checkNotNull(state).value
            val composed = composedText(currentValue)
            val visualCursor = Math.addExact(cursor, preedit?.caretPosition ?: 0)
            val visible = visibleText(composed, visualCursor)
            val run = createRun(visible.text)
            run.paint(scope, textOrigin.x, textOrigin.y)
            if (focused && enabled) {
                paintPreeditBlock(scope, visible)
                val cursorPosition = textOrigin.x.toLong() + width(composed.substring(visible.start, visualCursor))
                if (cursorPosition < 0L || fieldSize.width.toLong() <= cursorPosition) return
                val cursorX = cursorPosition.toInt()
                val appendCursor = preedit == null && cursor == currentValue.length && currentValue.length < checkNotNull(state).maxLength
                if (appendCursor) {
                    createRun("_").paint(scope, cursorX, textOrigin.y)
                } else {
                    val cursorTop = Math.subtractExact(textOrigin.y, 1)
                    scope.fillRectangle(
                        IntRect(cursorX, cursorTop, Math.addExact(cursorX, 1), Math.addExact(textOrigin.y, 10)),
                        cursorColor,
                    )
                }
            }
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            if (enabled.not() || (event is PointerEvent.Press).not() || event.button !== PointerButton.Primary) {
                return InputResult.Ignored
            }
            val next = cursorAt(Math.subtractExact(localPosition.x, textOrigin.x))
            if (cursor != next || preedit != null) {
                cursor = next
                preedit = null
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Ignored
        }

        override fun onFocusChanged(focused: Boolean) {
            if (this.focused == focused) return
            this.focused = focused
            if (focused.not()) preedit = null
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult {
            if (enabled.not() || (event is KeyboardEvent.Press).not()) return InputResult.Ignored
            return when (event.key) {
                KeyCode.Left -> moveCursor(previousScalar(checkNotNull(state).value, cursor))
                KeyCode.Right -> moveCursor(nextScalar(checkNotNull(state).value, cursor))
                KeyCode.Home -> moveCursor(0)
                KeyCode.End -> moveCursor(checkNotNull(state).value.length)
                KeyCode.Backspace -> deleteBeforeCursor()
                KeyCode.Delete -> deleteAtCursor()
                else -> InputResult.Ignored
            }
        }

        override fun onTextInput(event: TextInputEvent): InputResult {
            if (enabled.not()) return InputResult.Ignored
            return when (event) {
                is TextInputEvent.Character -> insert(event)
                is TextInputEvent.Preedit -> updatePreedit(event)
            }
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(
                Semantics(
                    label = UiText.Literal(checkNotNull(state).value),
                    role = SemanticsRole.TextField,
                    disabled = enabled.not(),
                ),
            )
        }

        override fun attach() {
            cursor = scalarBoundary(checkNotNull(state).value, cursor)
            preedit = null
            attached = true
            observeState()
        }

        override fun detach() {
            releaseObserver?.close()
            releaseObserver = null
            attached = false
            focused = false
            preedit = null
        }

        override fun dispose() {
            releaseObserver?.close()
            releaseObserver = null
            attached = false
            normalSprite = null
            highlightedSprite = null
            textRenderer = null
            state = null
            preedit = null
        }

        @Suppress("unused")
        @JvmSynthetic
        internal fun updateFrom(current: MinecraftTextFieldElement): DirtyMask {
            val previousState = checkNotNull(state)
            val stateChanged = previousState !== current.state
            if (stateChanged && attached) {
                releaseObserver?.close()
                releaseObserver = null
            }
            val paintChanged =
                normalSprite !== current.normalSprite ||
                    highlightedSprite !== current.highlightedSprite ||
                    textRenderer !== current.textRenderer ||
                    font != current.font ||
                    stateChanged ||
                    enabled != current.enabled ||
                    textStyle != current.textStyle
            val semanticsChanged = stateChanged || enabled != current.enabled
            val sizeChanged = fieldSize != current.fieldSize
            normalSprite = current.normalSprite
            highlightedSprite = current.highlightedSprite
            textRenderer = current.textRenderer
            font = current.font
            state = current.state
            fieldSize = current.fieldSize
            enabled = current.enabled
            textStyle = current.textStyle
            if (enabled.not()) {
                focused = false
                preedit = null
            }
            cursor = scalarBoundary(current.state.value, cursor)
            if (stateChanged) preedit = null
            if (stateChanged && attached) observeState()
            return updateMask(sizeChanged, paintChanged, semanticsChanged)
        }

        private fun updateMask(
            sizeChanged: Boolean,
            paintChanged: Boolean,
            semanticsChanged: Boolean,
        ): DirtyMask {
            var dirty = if (sizeChanged) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
            if (paintChanged) dirty += DirtyMask.of(DirtyPhase.Paint)
            if (semanticsChanged) dirty += DirtyMask.of(DirtyPhase.Semantics)
            return dirty
        }

        @OptIn(InternalStrataRuntimeApi::class)
        private fun observeState() {
            releaseObserver =
                checkNotNull(state).observe { value ->
                    cursor = scalarBoundary(value, cursor)
                    preedit = null
                    invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics))
                }
        }

        private fun moveCursor(next: Int): InputResult {
            val bounded = scalarBoundary(checkNotNull(state).value, next)
            if (cursor != bounded || preedit != null) {
                cursor = bounded
                preedit = null
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Consumed
        }

        private fun deleteBeforeCursor(): InputResult {
            clearPreedit()
            if (cursor == 0) return InputResult.Consumed
            val current = checkNotNull(state)
            val previous = previousScalar(current.value, cursor)
            current.value = current.value.removeRange(previous, cursor)
            cursor = previous
            return InputResult.Consumed
        }

        private fun deleteAtCursor(): InputResult {
            clearPreedit()
            val current = checkNotNull(state)
            if (cursor < current.value.length) {
                current.value = current.value.removeRange(cursor, nextScalar(current.value, cursor))
            }
            return InputResult.Consumed
        }

        private fun insert(event: TextInputEvent.Character): InputResult {
            if (isAcceptedCodePoint(event.codePoint).not()) return InputResult.Ignored
            val current = checkNotNull(state)
            val inserted = event.asString()
            clearPreedit()
            if (current.maxLength - current.value.length < inserted.length) return InputResult.Consumed
            val next = Math.addExact(cursor, inserted.length)
            current.value = current.value.substring(0, cursor) + inserted + current.value.substring(cursor)
            cursor = next
            return InputResult.Consumed
        }

        private fun updatePreedit(event: TextInputEvent.Preedit): InputResult {
            if (
                isAcceptedText(event.fullText).not() ||
                event.blocks.any { block -> isAcceptedText(block).not() } ||
                scalarBoundary(event.fullText, event.caretPosition) != event.caretPosition
            ) {
                return InputResult.Ignored
            }
            val next = event.takeIf { current -> current.fullText.isNotEmpty() }
            if (preedit != next) {
                preedit = next
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Consumed
        }

        private fun visibleText(
            text: String,
            visualCursor: Int,
        ): VisibleText {
            var start = 0
            while (start < visualCursor && innerWidth < width(text.substring(start, visualCursor))) {
                start = nextScalar(text, start)
            }
            var end = start
            while (end < text.length) {
                val next = nextScalar(text, end)
                val candidate = text.substring(start, next)
                if (innerWidth < width(candidate)) break
                end = next
            }
            return VisibleText(text.substring(start, end), start)
        }

        private fun cursorAt(localX: Int): Int {
            val value = checkNotNull(state).value
            val composed = composedText(value)
            val visible = visibleText(composed, Math.addExact(cursor, preedit?.caretPosition ?: 0))
            val position = positionAt(visible.text, localX.coerceIn(0, innerWidth))
            val composedPosition = Math.addExact(visible.start, position)
            val compositionEnd = Math.addExact(cursor, preedit?.fullText?.length ?: 0)
            return when {
                composedPosition <= cursor -> composedPosition
                composedPosition < compositionEnd -> cursor
                else -> composedPosition - (compositionEnd - cursor)
            }
        }

        private fun positionAt(
            text: String,
            localX: Int,
        ): Int {
            if (localX <= 0) return 0
            var position = 0
            var x = 0L
            while (position < text.length) {
                val next = nextScalar(text, position)
                val nextX = width(text.substring(0, next)).toLong()
                if (localX.toLong() < x + Math.floorDiv(nextX - x + 1L, 2L)) return position
                x = nextX
                position = next
            }
            return text.length
        }

        private fun composedText(value: String): String =
            preedit?.let { composition ->
                value.substring(0, cursor) + composition.fullText + value.substring(cursor)
            } ?: value

        private fun paintPreeditBlock(
            scope: PaintScope,
            visible: VisibleText,
        ) {
            val composition = preedit ?: return
            if (composition.focusedBlock < 0 || composition.blocks.joinToString("") != composition.fullText) return
            val blockStart = Math.addExact(cursor, composition.blocks.take(composition.focusedBlock).sumOf(String::length))
            val blockEnd = Math.addExact(blockStart, composition.blocks[composition.focusedBlock].length)
            val visibleEnd = Math.addExact(visible.start, visible.text.length)
            val start = maxOf(visible.start, blockStart)
            val end = minOf(visibleEnd, blockEnd)
            if (end <= start) return
            val first = textOrigin.x.toLong() + width(visible.text.substring(0, start - visible.start))
            val last = textOrigin.x.toLong() + width(visible.text.substring(0, end - visible.start))
            val left = minOf(first, last).coerceIn(0L, fieldSize.width.toLong()).toInt()
            val right = maxOf(first, last).coerceIn(0L, fieldSize.width.toLong()).toInt()
            val top = Math.addExact(textOrigin.y, 9)
            if (left < right) scope.fillRectangle(IntRect(left, top, right, Math.addExact(top, 1)), cursorColor)
        }

        private fun clearPreedit() {
            if (preedit == null) return
            preedit = null
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        private fun previousScalar(
            text: String,
            offset: Int,
        ): Int = if (offset == 0) 0 else text.offsetByCodePoints(offset, -1)

        private fun nextScalar(
            text: String,
            offset: Int,
        ): Int = if (offset == text.length) offset else text.offsetByCodePoints(offset, 1)

        private fun scalarBoundary(
            text: String,
            offset: Int,
        ): Int {
            val bounded = offset.coerceIn(0, text.length)
            if (bounded == 0 || bounded == text.length) return bounded
            return if (text[bounded - 1].isHighSurrogate() && text[bounded].isLowSurrogate()) {
                bounded - 1
            } else {
                bounded
            }
        }

        private fun isAcceptedText(text: String): Boolean {
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                if ((codePoint in 0xD800..0xDFFF) || isAcceptedCodePoint(codePoint).not()) return false
                offset += Character.charCount(codePoint)
            }
            return true
        }

        private fun isAcceptedCodePoint(codePoint: Int): Boolean = 0x20 <= codePoint && codePoint != 0x7F && codePoint != 0xA7

        private fun width(text: String): Int = createRun(text).nativeWidth

        private fun createRun(text: String): MinecraftTextRun = checkNotNull(textRenderer).create(UiText.Literal(text), textStyle, enabled, font, logicalOrder = true)

        private data class VisibleText(
            val text: String,
            val start: Int,
        )
    }

    companion object {
        private val TYPE: ElementType<MinecraftTextFieldElement, Node> =
            ElementType(
                elementClass = MinecraftTextFieldElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.state.value.length <= element.state.maxLength) {
                        "Minecraft TextField state exceeds its maximum length."
                    }
                    require(9 <= element.fieldSize.width && 9 <= element.fieldSize.height) {
                        "Minecraft TextField size must be at least 9 by 9."
                    }
                },
                createNode = { element ->
                    Node(
                        element.normalSprite,
                        element.highlightedSprite,
                        element.textRenderer,
                        element.font,
                        element.state,
                        element.fieldSize,
                        element.enabled,
                        element.textStyle,
                    )
                },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        @JvmSynthetic
        internal fun create(
            normalSprite: DrawImage,
            highlightedSprite: DrawImage,
            textRenderer: MinecraftTextRenderer,
            font: ResourceId,
            state: TextFieldState,
            fieldSize: IntSize,
            enabled: Boolean,
            textStyle: TextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftTextFieldElement(normalSprite, highlightedSprite, textRenderer, font, state, fieldSize, enabled, textStyle, modifier, key)
    }
}

/**
 * Creates one private TextField description from exact profile assets and owner state.
 *
 * @param normalSprite exact unfocused sprite.
 * @param highlightedSprite exact focused sprite.
 * @param textRenderer borrowed owner-thread text service closed only by the owning host.
 * @param font font identifier resolved against the host's pinned resources.
 * @param state owner-thread mutable value.
 * @param fieldSize requested logical extent.
 * @param enabled whether editing and focus are accepted.
 * @param textStyle profile-backed glyph layers used by the field.
 * @param modifier active behavior.
 * @param key optional stable identity.
 * @return private retained TextField description.
 */
@JvmSynthetic
internal fun createMinecraftTextFieldElement(
    normalSprite: DrawImage,
    highlightedSprite: DrawImage,
    textRenderer: MinecraftTextRenderer,
    font: ResourceId,
    state: TextFieldState,
    fieldSize: IntSize,
    enabled: Boolean,
    textStyle: TextStyle,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftTextFieldElement.create(normalSprite, highlightedSprite, textRenderer, font, state, fieldSize, enabled, textStyle, modifier, key)

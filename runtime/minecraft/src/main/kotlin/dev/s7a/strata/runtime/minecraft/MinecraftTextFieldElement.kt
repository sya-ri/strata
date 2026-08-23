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
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.util.Collections
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained implementation of the explicitly sized profile-backed Minecraft TextField component.
 *
 * The description snapshots the two sprites and printable font references but retains no complete profile.
 */
private class MinecraftTextFieldElement private constructor(
    @get:JvmSynthetic
    internal val normalSprite: DrawImage,
    @get:JvmSynthetic
    internal val highlightedSprite: DrawImage,
    glyphs: Map<Int, MinecraftGlyphSnapshot>,
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
    @get:JvmSynthetic
    internal val glyphs: Map<Int, MinecraftGlyphSnapshot> = Collections.unmodifiableMap(LinkedHashMap(glyphs))

    /**
     * Retained owner-thread EditBox subset with state observation, focus, pointer cursor placement, editing, paint, and semantics.
     */
    @Suppress("TooManyFunctions")
    private class Node(
        initialNormalSprite: DrawImage,
        initialHighlightedSprite: DrawImage,
        initialGlyphs: Map<Int, MinecraftGlyphSnapshot>,
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
        private var glyphs: Map<Int, MinecraftGlyphSnapshot>? = initialGlyphs
        private var state: TextFieldState? = initialState
        private var enabled = initialEnabled
        private var textStyle = initialTextStyle
        private var focused = false
        private var attached = false
        private var cursor = initialState.value.length
        private var preedit = ""
        private var releaseObserver: AutoCloseable? = null

        override val acceptsFocus: Boolean
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
            val composed = currentValue.substring(0, cursor) + preedit + currentValue.substring(cursor)
            val visualCursor = Math.addExact(cursor, preedit.length)
            val visible = visibleText(composed, visualCursor)
            val run = createRun(visible.text)
            run.paint(scope, textOrigin.x, textOrigin.y)
            if (focused && enabled) {
                val cursorX = Math.addExact(textOrigin.x, width(visible.text.substring(0, visualCursor - visible.start)))
                val appendCursor = preedit.isEmpty() && cursor == currentValue.length && currentValue.length < checkNotNull(state).maxLength
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
            if (cursor != next || preedit.isNotEmpty()) {
                cursor = next
                preedit = ""
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Ignored
        }

        override fun onFocusChanged(focused: Boolean) {
            if (this.focused == focused) return
            this.focused = focused
            if (focused.not()) preedit = ""
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult {
            if (enabled.not() || (event is KeyboardEvent.Press).not()) return InputResult.Ignored
            return when (event.key) {
                KeyCode.Left -> moveCursor(cursor - 1)
                KeyCode.Right -> moveCursor(cursor + 1)
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
            attached = true
            observeState()
        }

        override fun detach() {
            releaseObserver?.close()
            releaseObserver = null
            attached = false
            focused = false
            preedit = ""
        }

        override fun dispose() {
            releaseObserver?.close()
            releaseObserver = null
            attached = false
            normalSprite = null
            highlightedSprite = null
            glyphs = null
            state = null
            preedit = ""
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
                    glyphs != current.glyphs ||
                    stateChanged ||
                    enabled != current.enabled ||
                    textStyle != current.textStyle
            val semanticsChanged = stateChanged || enabled != current.enabled
            val sizeChanged = fieldSize != current.fieldSize
            normalSprite = current.normalSprite
            highlightedSprite = current.highlightedSprite
            glyphs = current.glyphs
            state = current.state
            fieldSize = current.fieldSize
            enabled = current.enabled
            textStyle = current.textStyle
            if (enabled.not()) {
                focused = false
                preedit = ""
            }
            cursor = cursor.coerceIn(0, current.state.value.length)
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
                    cursor = cursor.coerceIn(0, value.length)
                    preedit = ""
                    invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics))
                }
        }

        private fun moveCursor(next: Int): InputResult {
            val bounded = next.coerceIn(0, checkNotNull(state).value.length)
            if (cursor != bounded || preedit.isNotEmpty()) {
                cursor = bounded
                preedit = ""
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Consumed
        }

        private fun deleteBeforeCursor(): InputResult {
            if (cursor == 0) return InputResult.Consumed
            val current = checkNotNull(state)
            current.value = current.value.removeRange(cursor - 1, cursor)
            cursor -= 1
            return InputResult.Consumed
        }

        private fun deleteAtCursor(): InputResult {
            val current = checkNotNull(state)
            if (cursor < current.value.length) {
                current.value = current.value.removeRange(cursor, cursor + 1)
            }
            return InputResult.Consumed
        }

        private fun insert(event: TextInputEvent.Character): InputResult {
            if (event.codePoint !in 0x20..0x7E) return InputResult.Ignored
            val current = checkNotNull(state)
            if (current.maxLength <= current.value.length) return InputResult.Consumed
            val inserted = event.asString()
            current.value = current.value.substring(0, cursor) + inserted + current.value.substring(cursor)
            cursor = Math.addExact(cursor, inserted.length)
            preedit = ""
            return InputResult.Consumed
        }

        private fun updatePreedit(event: TextInputEvent.Preedit): InputResult {
            if (event.fullText.all { character -> character.code in 0x20..0x7E }.not()) return InputResult.Ignored
            if (preedit != event.fullText) {
                preedit = event.fullText
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Consumed
        }

        private fun visibleText(
            text: String,
            visualCursor: Int,
        ): VisibleText {
            var start = 0
            while (innerWidth < width(text.substring(start, visualCursor))) start += 1
            var end = start
            while (end < text.length) {
                val candidate = text.substring(start, end + 1)
                if (innerWidth < width(candidate)) break
                end += 1
            }
            return VisibleText(text.substring(start, end), start)
        }

        private fun cursorAt(localX: Int): Int {
            val value = checkNotNull(state).value
            if (localX <= 0) return 0
            var position = 0
            var x = 0
            while (position < value.length) {
                val advance = width(value.substring(position, position + 1))
                if (localX < x + (advance + 1) / 2) return position
                x = Math.addExact(x, advance)
                position += 1
            }
            return value.length
        }

        private fun width(text: String): Int = createRun(text).size.width

        private fun createRun(text: String): MinecraftTextRun =
            when (textStyle) {
                TextStyle.Normal -> MinecraftTextRun.createNormal(UiText.Literal(text), ::glyphAt)
                TextStyle.Inactive -> MinecraftTextRun.createInactive(UiText.Literal(text), ::glyphAt)
                TextStyle.ContainerLabel -> MinecraftTextRun.createContainerLabel(UiText.Literal(text), ::glyphAt)
                TextStyle.TextField -> MinecraftTextRun.createTextField(UiText.Literal(text), enabled, ::glyphAt)
            }

        private fun glyphAt(codePoint: Int): MinecraftGlyphSnapshot = checkNotNull(glyphs).getValue(codePoint)

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
                        element.glyphs,
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
            glyphs: Map<Int, MinecraftGlyphSnapshot>,
            state: TextFieldState,
            fieldSize: IntSize,
            enabled: Boolean,
            textStyle: TextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftTextFieldElement(normalSprite, highlightedSprite, glyphs, state, fieldSize, enabled, textStyle, modifier, key)
    }
}

/**
 * Creates one private TextField description from exact profile assets and owner state.
 *
 * @param normalSprite exact unfocused sprite.
 * @param highlightedSprite exact focused sprite.
 * @param glyphs complete immutable printable-ASCII glyph map.
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
    glyphs: Map<Int, MinecraftGlyphSnapshot>,
    state: TextFieldState,
    fieldSize: IntSize,
    enabled: Boolean,
    textStyle: TextStyle,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftTextFieldElement.create(normalSprite, highlightedSprite, glyphs, state, fieldSize, enabled, textStyle, modifier, key)

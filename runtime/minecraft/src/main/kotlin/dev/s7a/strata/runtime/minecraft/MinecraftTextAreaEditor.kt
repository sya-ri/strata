@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText

/**
 * Owner-thread editing interval for one retained multiline control.
 *
 * The controller owns its text and scroll subscriptions, cursor, inline composition, and exactly one current composed layout.
 * Focused underline extrema are derived once for the current layout, insertion offset, and focused range, bounded by the state's normalized text capacity.
 * State, preedit, font, enabled tint, style, width, wrap, or line-spacing changes invalidate that layout; scroll and cursor changes reuse it.
 * Detach drops subscriptions and composition, while disposal also clears the borrowed renderer and caller state references.
 * Selection, clipboard operations, grapheme navigation, and typed accessibility edit/focus actions are not implemented.
 *
 * @param initial immutable description inputs borrowed for this interval.
 * @param invalidate notifies the retained owner without retaining callback-lifetime scopes.
 */
internal class MinecraftTextAreaEditor(
    initial: MinecraftTextAreaConfiguration,
    private val invalidate: (DirtyMask) -> Unit,
) {
    private var current: MinecraftTextAreaConfiguration? = initial
    private val cursor = MinecraftTextAreaCursor(initial.state.value.length)
    private var preedit: MinecraftTextAreaPreedit? = null
    private var layout: MinecraftTextLayout? = null
    private var underlines: MinecraftTextAreaUnderlines? = null
    private var textObserver: AutoCloseable? = null
    private var scrollObserver: ScrollStateObserver? = null
    private var attached = false
    private var editingValue = false
    private val viewport = MinecraftTextAreaViewportState()
    private val editing = Editing()

    /**
     * Current borrowed immutable inputs; unavailable after disposal.
     */
    @get:JvmSynthetic
    internal val configuration: MinecraftTextAreaConfiguration
        get() = checkNotNull(current)

    /**
     * Whether the current enabled editor owns keyboard and text-input focus.
     */
    @get:JvmSynthetic
    internal var focused: Boolean = false
        private set

    /**
     * Claims the sole text observer and one vertical-scroll observer before the first measure.
     */
    @JvmSynthetic
    internal fun attach() {
        check(attached.not()) { "TextArea editor is already attached." }
        cursor.move(configuration.state.value, cursor.offset)
        viewport.synchronize(configuration.state.scrollState.metrics.offset)
        textObserver =
            configuration.state.observe { value ->
                if (editingValue) return@observe
                cursor.move(value, cursor.offset)
                preedit = null
                layout = null
                underlines = null
                viewport.followCaret = false
                invalidate(DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint, DirtyPhase.Semantics))
            }
        try {
            scrollObserver =
                configuration.state.scrollState.observe { metrics ->
                    viewport.followCaret = false
                    if (viewport.scrolled(metrics.offset)) {
                        invalidate(DirtyMask.of(DirtyPhase.Paint))
                    }
                }
            attached = true
        } finally {
            if (attached.not()) {
                textObserver?.close()
                textObserver = null
            }
        }
    }

    /**
     * Releases this attachment's observers and clears transient composition without changing committed text or scroll position.
     */
    @JvmSynthetic
    internal fun detach() {
        textObserver?.close()
        textObserver = null
        scrollObserver?.close()
        scrollObserver = null
        attached = false
        focused = false
        preedit = null
        layout = null
        underlines = null
        viewport.clear()
    }

    /**
     * Releases all presentation and borrowed references; only the host closes its font renderer.
     */
    @JvmSynthetic
    internal fun dispose() {
        detach()
        current = null
    }

    /**
     * Reconciles one immutable description and transfers observer ownership only when its caller state changes.
     * A focused state transfer follows the newly measured caret; an unfocused transfer resets horizontal pan and preserves the new state's vertical position.
     *
     * @param next replacement inputs.
     * @return precise dirty phases; frame-only changes preserve composition and layout, and only state/enabled changes refresh semantics.
     */
    @JvmSynthetic
    internal fun update(next: MinecraftTextAreaConfiguration): DirtyMask {
        val change = Reconciliation(configuration, next)
        if (change.dirty == DirtyMask.None) return DirtyMask.None
        val reattach = change.stateChanged && attached
        val wasFocused = focused
        if (change.stateChanged) detach()
        current = next
        if (change.layoutChanged) {
            cursor.move(next.state.value, cursor.offset, affinity = if (change.stateChanged) MinecraftTextCaretAffinity.Downstream else cursor.affinity)
            layout = null
            underlines = null
        }
        if (change.clearComposition) {
            preedit = null
            viewport.followCaret = false
        }
        focused = wasFocused && next.enabled
        if (change.stateChanged && focused) viewport.followCaret = true
        if (change.resetPan) viewport.resetHorizontal()
        if (reattach) attach()
        return change.dirty
    }

    /**
     * Computes only the current layout, publishes derived vertical geometry, and applies a pending input-driven caret-follow request once.
     */
    @JvmSynthetic
    internal fun measure() {
        val result = currentLayout()
        val settings = configuration
        val scroll = settings.state.scrollState
        val origin = checkNotNull(scrollObserver)
        scroll.updateGeometry(settings.innerSize.height, result.size.height, origin)
        if (viewport.followCaret) {
            val caret = result.caretPosition(Math.addExact(cursor.offset, preedit?.caretPosition ?: 0), displayAffinity)
            viewport.follow(settings, origin, caret)
        }
        val caret = result.caretPosition(Math.addExact(cursor.offset, preedit?.caretPosition ?: 0), displayAffinity)
        viewport.clampHorizontal(settings, caret.x)
        viewport.synchronize(scroll.metrics.offset)
    }

    /**
     * Paints detached lines and inline editing decoration inside the structural text viewport.
     * No state, geometry, or resource resolution is changed by this callback.
     */
    @JvmSynthetic
    internal fun paint(scope: PaintScope) {
        MinecraftTextAreaPainter.paint(scope, checkNotNull(layout), configuration, cursor.offset, preedit, focused, viewport.horizontalOffset, displayAffinity, underlines)
    }

    /**
     * Updates editable focus and discards uncommitted composition immediately on loss.
     */
    @JvmSynthetic
    internal fun focus(value: Boolean) {
        val next = value && configuration.enabled
        if (focused == next) return
        focused = next
        val compositionChanged = next.not() && preedit != null
        if (next.not()) {
            preedit = null
            if (compositionChanged) {
                layout = null
                underlines = null
            }
            viewport.followCaret = false
        }
        invalidate(if (compositionChanged) DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint) else DirtyMask.of(DirtyPhase.Paint))
    }

    /**
     * Handles pointer placement and vertical wheel movement using the current composed layout and externally controlled scroll position.
     */
    @JvmSynthetic
    internal fun pointer(
        event: PointerEvent,
        localPosition: IntOffset,
    ): InputResult {
        if (configuration.enabled.not()) return InputResult.Ignored
        return when (event) {
            is PointerEvent.Scroll -> {
                val delta = event.deltaY * currentLayout().lineStep
                if (delta.isFinite()) {
                    viewport.followCaret = false
                    val next =
                        configuration.state.scrollState
                            .scrollBy(delta, checkNotNull(scrollObserver))
                    if (viewport.scrolled(next)) {
                        invalidate(DirtyMask.of(DirtyPhase.Paint))
                    }
                }
                InputResult.Consumed
            }

            is PointerEvent.Press -> {
                if (event.button !== PointerButton.Primary) return InputResult.Ignored
                val x = (localPosition.x.toLong() - 4L).coerceIn(0L, configuration.innerSize.width.toLong()) + viewport.horizontalOffset
                val y =
                    (localPosition.y.toLong() - 4L).coerceIn(0L, configuration.innerSize.height.toLong() - 1L) +
                        configuration.state.scrollState.metrics.offset
                            .toLong()
                val currentLayout = currentLayout()
                val index = currentLayout.lineIndexAt(y.toDouble())
                val localX = x.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                val composed = currentLayout.lines[index].offsetAt(localX)
                val end = Math.addExact(cursor.offset, preedit?.fullText?.length ?: 0)
                val committed =
                    if (composed <= cursor.offset) {
                        composed
                    } else if (composed < end) {
                        cursor.offset
                    } else {
                        composed - (end - cursor.offset)
                    }
                editing.edit {
                    if (preedit == null) {
                        cursor.moveToLine(currentLayout, index, localX)
                    } else {
                        editing.clearPreedit()
                        cursor.move(configuration.state.value, committed)
                    }
                }
                InputResult.Ignored
            }

            else -> {
                InputResult.Ignored
            }
        }
    }

    /**
     * Handles scalar editing, line and document boundaries, vertical/page navigation, and newline insertion.
     * Unknown keys remain available to enclosing modifiers and the host.
     */
    @JvmSynthetic
    internal fun keyboard(event: KeyboardEvent): InputResult = editing.keyboard(event)

    /**
     * Accepts canonical committed scalars or validated inline IME updates without changing committed text during composition.
     */
    @JvmSynthetic
    internal fun textInput(event: TextInputEvent): InputResult = editing.textInput(event)

    private fun currentLayout(): MinecraftTextLayout {
        layout?.let { return it }
        val settings = configuration
        val value = settings.state.value
        val composed = preedit?.let { value.substring(0, cursor.offset) + it.fullText + value.substring(cursor.offset) } ?: value
        val result =
            MinecraftTextLineBreaker.create(
                MinecraftTextContent.create(UiText.Literal(composed), settings.font, multiline = true),
                settings.renderer,
                settings.policy,
                settings.innerSize.width,
                settings.style,
                settings.enabled,
                logicalOrder = true,
            )
        layout = result
        underlines = MinecraftTextAreaUnderlines.create(result, cursor.offset, preedit?.focusedRange)
        val caret = result.caretPosition(Math.addExact(cursor.offset, preedit?.caretPosition ?: 0), displayAffinity)
        viewport.layoutChanged(result, settings, caret)
        return result
    }

    private val displayAffinity: MinecraftTextCaretAffinity
        get() = if ((preedit?.caretPosition ?: 0) == 0) cursor.affinity else MinecraftTextCaretAffinity.Downstream

    private class Reconciliation(
        previous: MinecraftTextAreaConfiguration,
        next: MinecraftTextAreaConfiguration,
    ) {
        val layoutChanged = previous.hasSameLayout(next).not()
        val stateChanged = previous.state !== next.state
        val clearComposition = stateChanged || next.enabled.not()
        val resetPan = stateChanged || next.policy.wrap != TextWrap.None
        private val frameChanged = previous.normalSprite !== next.normalSprite || previous.highlightedSprite !== next.highlightedSprite
        val dirty =
            if (layoutChanged.not() && frameChanged.not()) {
                DirtyMask.None
            } else {
                var phases = DirtyMask.of(DirtyPhase.Paint)
                if (layoutChanged) phases += DirtyMask.of(DirtyPhase.Measure)
                if (stateChanged || previous.enabled != next.enabled) phases += DirtyMask.of(DirtyPhase.Semantics)
                phases
            }
    }

    private inner class Editing {
        fun keyboard(event: KeyboardEvent): InputResult {
            if (configuration.enabled.not() || (event is KeyboardEvent.Press).not()) return InputResult.Ignored
            return when (event.key) {
                KeyCode.Enter -> {
                    edit { insert("\n") }
                }

                KeyCode.Backspace -> {
                    edit { delete(before = true) }
                }

                KeyCode.Delete -> {
                    edit { delete(before = false) }
                }

                KeyCode.Left, KeyCode.Right, KeyCode.Up, KeyCode.Down, KeyCode.Home, KeyCode.End, KeyCode.PageUp, KeyCode.PageDown -> {
                    edit {
                        clearPreedit()
                        cursor.navigate(event, currentLayout(), configuration.innerSize.height)
                    }
                }

                else -> {
                    InputResult.Ignored
                }
            }
        }

        fun textInput(event: TextInputEvent): InputResult {
            if (configuration.enabled.not()) return InputResult.Ignored
            return when (event) {
                is TextInputEvent.Character -> MinecraftTextAreaComposition.committed(event.codePoint)?.let { value -> edit { insert(value) } } ?: InputResult.Ignored
                is TextInputEvent.Preedit -> composition(event)
            }
        }

        private fun composition(event: TextInputEvent.Preedit): InputResult {
            val state = configuration.state
            val normalized = MinecraftTextAreaComposition.normalize(event, state.maxLength - state.value.length) ?: return InputResult.Ignored
            val next = normalized.takeIf { it.fullText.isNotEmpty() }
            if (preedit == next) return InputResult.Consumed
            val textChanged = preedit?.fullText != next?.fullText
            val caretChanged = (preedit?.caretPosition ?: 0) != (next?.caretPosition ?: 0)
            val rangeChanged = preedit?.focusedRange != next?.focusedRange
            cursor.move(configuration.state.value, cursor.offset, affinity = cursor.affinity)
            preedit = next
            if (textChanged) {
                layout = null
                underlines = null
            } else if (rangeChanged) {
                underlines = layout?.let { MinecraftTextAreaUnderlines.create(it, cursor.offset, next?.focusedRange) }
            }
            if (textChanged || caretChanged) {
                viewport.followCaret = true
                invalidate(DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint))
            } else {
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
            return InputResult.Consumed
        }

        fun edit(operation: () -> Unit): InputResult {
            val previousValue = configuration.state.value
            val previousOffset = cursor.offset
            val previousAffinity = cursor.affinity
            val previousComposition = preedit
            editingValue = true
            try {
                operation()
            } finally {
                editingValue = false
            }
            val valueChanged = previousValue != configuration.state.value
            if (valueChanged) {
                layout = null
                underlines = null
            }
            val cursorChanged = previousOffset != cursor.offset || previousAffinity != cursor.affinity
            if (valueChanged || cursorChanged || previousComposition != preedit) {
                viewport.followCaret = true
                var dirty = DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint)
                if (valueChanged) dirty += DirtyMask.of(DirtyPhase.Semantics)
                invalidate(dirty)
            }
            return InputResult.Consumed
        }

        private fun insert(inserted: String) {
            clearPreedit()
            val state = configuration.state
            if (state.maxLength - state.value.length < inserted.length) return
            val next = Math.addExact(cursor.offset, inserted.length)
            state.value = state.value.substring(0, cursor.offset) + inserted + state.value.substring(cursor.offset)
            cursor.move(state.value, next)
        }

        private fun delete(before: Boolean) {
            clearPreedit()
            val state = configuration.state
            val value = state.value
            val start = if (before && 0 < cursor.offset) value.offsetByCodePoints(cursor.offset, -1) else cursor.offset
            val end = if (before.not() && cursor.offset < value.length) value.offsetByCodePoints(cursor.offset, 1) else cursor.offset
            if (start != end) state.value = value.removeRange(start, end)
            cursor.move(state.value, start)
        }

        fun clearPreedit() {
            if (preedit == null) return
            preedit = null
            layout = null
            underlines = null
        }
    }
}

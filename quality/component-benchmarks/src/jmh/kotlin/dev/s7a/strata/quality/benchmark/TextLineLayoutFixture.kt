package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.CompositionRegion
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.ControlOperation
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.LayoutOperation
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Shape
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import dev.s7a.strata.ui.UiDefinition

// Why: one fixture owns the actual host, prepared opportunities and release oracles for both retained consumers.

/**
 * Real public retained layout opportunities with prepared resources and one primed font owner.
 * Timing includes each complete mutation/input/frame cycle, with no invocation setup or reflective adapter work.
 * Initial cycles replace a keyed component; content edits and width changes reuse its current owner.
 * Only untimed validation keeps temporary old layouts or raster snapshots, and close releases every owned face.
 */
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal class TextLineLayoutFixture(
    private val consumer: Consumer,
    private val shape: Shape,
    private val region: CompositionRegion = CompositionRegion.Before,
) : AutoCloseable {
    private val assets = TextLineLayoutAssets(shape)
    private val firstValue = assets.value().let { if (consumer === Consumer.TextArea) it.replace("\r\n", "\n") else it }
    private val secondValue = if (firstValue.isEmpty()) "Z" else firstValue.substring(0, firstValue.offsetByCodePoints(firstValue.length, -1)) + "Z"
    private val values = listOf(firstValue, secondValue)
    private val texts = values.map(::text)
    private val states = List(2) { TextAreaState(firstValue, maxLength = maxOf(firstValue.length, secondValue.length) + 16) }
    private val selected = mutableStateOf(Side.First)
    private val content = mutableStateOf(Side.First)
    private val firstSize = IntSize(width(), 64)
    private val secondSize = IntSize(width() + 3, 64)
    private val size = mutableStateOf(firstSize)
    private val keys = Side.entries.map(::ElementKey)
    private val time = FrameTime(0)
    private val wrap = if (shape === Shape.Wrapped) TextWrap.Character else TextWrap.None
    private val policy = TextLayout.Multiline(wrap = wrap, overflow = if (ellipsis()) TextOverflow.Ellipsis else TextOverflow.Clip, lineSpacing = 2)
    private val right = KeyboardEvent.Press(KeyCode.Right, 0)
    private val left = KeyboardEvent.Press(KeyCode.Left, 0)
    private val home = KeyboardEvent.Press(KeyCode.Home, 0, KeyboardModifiers(control = true))
    private val clear = TextInputEvent.Preedit("", 0)
    private val firstComposition = TextInputEvent.Preedit("🙂Z", 0, listOf("🙂", "Z"), 0)
    private val focusedBlock =
        when (region) {
            CompositionRegion.Before -> 0
            CompositionRegion.Inside -> 1
            CompositionRegion.After -> -1
        }
    private val compositionCaret =
        when (region) {
            CompositionRegion.Before -> 0
            CompositionRegion.Inside -> 2
            CompositionRegion.After -> 3
        }
    private val secondComposition = TextInputEvent.Preedit("🙂Z", compositionCaret, listOf("🙂", "Z"), focusedBlock)
    private var closed = false
    private val host =
        createMinecraftUiHost(
            UiDefinition("Retained current text-line layout") {
                val owner = selected.value.ordinal
                val value = content.value
                val viewport = size.value
                if (consumer === Consumer.TextArea) {
                    TextArea(states[owner], TextAreaViewport.Size(viewport), font = font(value), wrap = wrap, lineSpacing = 2, modifier = Modifier.Empty.initialFocus(), key = keys[owner])
                } else {
                    Text(texts[value.ordinal], policy, style = TextStyle.TextField, key = keys[owner])
                }
            },
            assets.profile,
            fontBackend = MinecraftFontBackendFactory { assets.backend() },
        )

    init {
        try {
            host.attach()
            frame()
            layout(LayoutOperation.Edit)
            layout(LayoutOperation.Edit)
            if (consumer === Consumer.TextArea) {
                resetCaret()
                control(ControlOperation.Composition)
            }
            check(assets.backends == 1)
        } catch (failure: Throwable) {
            try {
                host.close()
            } catch (release: Throwable) {
                if (release !== failure) failure.addSuppressed(release)
            }
            throw failure
        }
    }

    /**
     * Delivers one complete prepared construction opportunity through unchanged public entry points.
     */
    internal fun layout(operation: LayoutOperation): RuntimeUiFrame {
        when (operation) {
            LayoutOperation.Initial -> {
                val next = opposite(selected.value)
                states[next.ordinal].value = values[content.value.ordinal]
                selected.value = next
            }

            LayoutOperation.Edit -> {
                val next = opposite(content.value)
                states[selected.value.ordinal].value = values[next.ordinal]
                content.value = next
            }

            LayoutOperation.Reflow -> {
                size.value = if (size.value == firstSize) secondSize else firstSize
            }
        }
        return frame()
    }

    /**
     * Includes clean reuse, both restoring scalar moves, or insertion/focused-range/loss of the fixed preedit.
     */
    internal fun control(operation: ControlOperation): RuntimeUiFrame =
        when (operation) {
            ControlOperation.Clean -> {
                frame()
            }

            ControlOperation.Caret -> {
                host.dispatchKeyboard(right)
                frame()
                host.dispatchKeyboard(left)
                frame()
            }

            ControlOperation.Composition -> {
                host.dispatchTextInput(firstComposition)
                frame()
                host.dispatchTextInput(secondComposition)
                frame()
                host.dispatchTextInput(clear)
                frame()
            }
        }

    /**
     * Verifies actual replacement, independent original metrics, bounded reference pixels and old snapshot immutability.
     */
    internal fun verifyLayout(operation: LayoutOperation) {
        val previousOwner = owner()
        val previousLayout = currentLayout()
        val previous = TextLineLayoutAccess.snapshot(previousLayout)
        val oldFrame = frame()
        val pixels = pixels(oldFrame)
        layout(operation)
        val nextOwner = owner()
        check(currentLayout() !== previousLayout)
        if (operation === LayoutOperation.Initial) {
            check(nextOwner !== previousOwner)
            TextLineLayoutAccess.verifyReleased(previousOwner, consumer)
        } else {
            check(nextOwner === previousOwner)
        }
        resetCaret()
        verifyCurrent()
        TextLineLayoutAccess.verifySnapshot(previousLayout, previous)
        check(pixels(oldFrame).contentEquals(pixels))
        verifyClean()
    }

    /**
     * Checks independent placement/range metrics, retained controls and unchanged committed semantics and pixels.
     */
    internal fun verifyControl(operation: ControlOperation) {
        resetCaret()
        verifyCurrent()
        val oldLayout = currentLayout()
        val snapshot = TextLineLayoutAccess.snapshot(oldLayout)
        val before = frame()
        val beforePixels = pixels(before)
        val calls = assets.glyphCalls
        val token = host.textInputFocus
        if (operation === ControlOperation.Composition) verifyComposition() else control(operation)
        if (operation !== ControlOperation.Composition) {
            check(currentLayout() === oldLayout)
            check(assets.glyphCalls == calls)
        } else {
            check(currentLayout() !== oldLayout)
        }
        check(host.textInputFocus === token)
        verifyCurrent()
        check(pixels(frame()).contentEquals(beforePixels))
        TextLineLayoutAccess.verifySnapshot(oldLayout, snapshot)
        check(pixels(before).contentEquals(beforePixels))
        verifyClean()
    }

    private fun verifyComposition() {
        host.dispatchTextInput(firstComposition)
        frame()
        val composed = currentLayout()
        val expected = reference("🙂Z" + states[selected.value.ordinal].value)
        expected.verify(composed)
        expected.verifyPixels(frame())
        expected.verifyUnderline(TextAreaInputAccess.optional(owner(), "underlines"), 0..1)
        val snapshot = TextLineLayoutAccess.snapshot(composed)
        val calls = assets.glyphCalls
        host.dispatchTextInput(secondComposition)
        frame()
        check(currentLayout() === composed)
        check(assets.glyphCalls == calls)
        val range =
            when (region) {
                CompositionRegion.Before -> 0..1
                CompositionRegion.Inside -> 2..2
                CompositionRegion.After -> null
            }
        expected.verifyUnderline(TextAreaInputAccess.optional(owner(), "underlines"), range)
        host.dispatchTextInput(clear)
        frame()
        check(TextAreaInputAccess.optional(owner(), "underlines") == null)
        check(TextAreaInputAccess.optional(owner(), "preedit") == null)
        TextLineLayoutAccess.verifySnapshot(composed, snapshot)
    }

    private fun verifyCurrent() {
        val expected = reference(values[content.value.ordinal])
        expected.verify(currentLayout())
        expected.verifyPixels(frame())
        val role = if (consumer === Consumer.TextArea) SemanticsRole.TextArea else SemanticsRole.Text
        val semantic = frame().semantics.single { it.semantics.role === role }.semantics
        if (consumer === Consumer.TextArea) {
            check(semantic.value == UiText.Literal(values[content.value.ordinal]))
            check(host.textInputFocus != null)
            check(TextAreaInputAccess.field(TextAreaInputAccess.field(owner(), "cursor"), "offset") == 0)
        } else {
            check(semantic.label == texts[content.value.ordinal])
            check(host.textInputFocus == null)
        }
    }

    private fun verifyClean() {
        val previous = frame()
        val layout = currentLayout()
        val calls = assets.glyphCalls
        check(frame() === previous)
        check(currentLayout() === layout)
        check(assets.glyphCalls == calls)
    }

    private fun reference(value: String): TextLineLayoutReference {
        val split = value.offsetByCodePoints(0, value.codePointCount(0, value.length) / 2)
        val fontAt: (Int) -> ResourceId = { offset ->
            if (consumer === Consumer.MultilineText && shape === Shape.MixedFonts && split <= offset) assets.alternateFont else font(content.value)
        }
        return TextLineLayoutReference(value, fontAt, assets, consumer, shape, size.value)
    }

    private fun text(value: String): UiText {
        if (shape !== Shape.MixedFonts) return UiText.Literal(value)
        val split = value.offsetByCodePoints(0, value.codePointCount(0, value.length) / 2)
        return UiText.concat(UiText.Literal(value.substring(0, split)).withFont(assets.defaultFont), UiText.Literal(value.substring(split)).withFont(assets.alternateFont))
    }

    private fun font(value: Side): ResourceId = if (consumer === Consumer.TextArea && shape === Shape.MixedFonts && value === Side.Second) assets.alternateFont else assets.defaultFont

    private fun width(): Int =
        when (shape) {
            Shape.EllipsisFirst -> 9
            Shape.EllipsisMiddle -> 48
            Shape.EllipsisLast -> 98_298
            else -> 64
        }

    private fun ellipsis(): Boolean = shape === Shape.EllipsisFirst || shape === Shape.EllipsisMiddle || shape === Shape.EllipsisLast

    private fun resetCaret() {
        if (consumer === Consumer.TextArea) {
            host.dispatchKeyboard(home)
            frame()
        }
    }

    private fun owner(): Any = TextLineLayoutAccess.owner(host, consumer)

    private fun currentLayout(): Any = TextLineLayoutAccess.layout(owner(), consumer)

    private fun frame(): RuntimeUiFrame = host.frame(size.value, time)

    private fun pixels(frame: RuntimeUiFrame): IntArray = rasterizeHeadless(frame.drawCommands, IntSize(minOf(64, size.value.width), minOf(64, size.value.height))).copyArgb()

    private fun opposite(value: Side): Side = if (value === Side.First) Side.Second else Side.First

    /**
     * Releases the actual current owner and proves every temporary old line/frame stays immutable after detach/close.
     */
    override fun close() {
        if (closed) return
        closed = true
        val current = owner()
        val layout = currentLayout()
        val snapshot = TextLineLayoutAccess.snapshot(layout)
        val previous = frame()
        val expected = pixels(previous)
        try {
            host.detach()
            TextLineLayoutAccess.verifySnapshot(layout, snapshot)
            check(host.textInputFocus == null)
            states.forEach { state -> state.observe { }.close() }
        } finally {
            host.close()
        }
        TextLineLayoutAccess.verifyReleased(current, consumer)
        TextLineLayoutAccess.verifySnapshot(layout, snapshot)
        check(pixels(previous).contentEquals(expected))
        check(assets.faces == 0 && assets.backends == 0)
        states.forEach { state -> state.observe { }.close() }
    }

    private enum class Side {
        First,
        Second,
    }
}

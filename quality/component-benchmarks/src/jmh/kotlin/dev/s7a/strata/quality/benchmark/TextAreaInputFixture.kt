package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Operation
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Point
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Shape
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import java.util.function.IntUnaryOperator

/**
 * One actual retained editor with fixed synthetic resources, current-layout oracles and bounded face ownership.
 * The constructor establishes an arbitrary canonical column through the caller state before first layout.
 * Navigation cycles restore through the opposite public key; composition cycles restore through public Home and preedit.
 * No reflection, font creation, frame production or oracle scan occurs in [input].
 */
// Why: this single current editor owns preparation, independent input/control oracles and terminal release together.
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal class TextAreaInputFixture(
    private val shape: Shape,
    private val point: Point = Point.Beginning,
    private val operation: Operation = Operation.Primary,
    private val composed: Boolean = false,
) : AutoCloseable {
    private val size = IntSize(32, 44)
    private val time = FrameTime(0)
    private val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
    private val value = value()
    private var backends = 0
    private var faces = 0
    private var glyphCalls = 0
    private val profile = profile()
    private val state = TextAreaState(value.substring(0, initialOffset()))
    private val host = fresh(state)
    private val key = KeyboardEvent.Press(key(operation), 0)
    private val opposite = KeyboardEvent.Press(opposite(operation), 0)
    private val home = KeyboardEvent.Press(KeyCode.Home, 0, KeyboardModifiers(control = true))
    private val composition = TextInputEvent.Preedit("🙂B", 2, listOf("🙂", "B"), 0)
    private val press: PointerEvent.Press
    private val committedControl: RuntimeUiFrame

    init {
        host.attach()
        state.value = value
        frame()
        val offset = TextAreaInputAccess.field(cursor(), "offset") as Int
        val first = if (offset == 0) KeyCode.Right else KeyCode.Left
        val second = if (offset == 0) KeyCode.Left else KeyCode.Right
        host.dispatchKeyboard(KeyboardEvent.Press(first, 0))
        host.dispatchKeyboard(KeyboardEvent.Press(second, 0))
        committedControl = frame()
        press = pointer()
    }

    /**
     * Restores the fixed public input opportunity and settles geometry; measured cycles include this work.
     */
    internal fun prepareInput() {
        if (composed) {
            host.dispatchKeyboard(home)
            frame()
            host.dispatchTextInput(composition)
        } else if (operation !== Operation.Primary) {
            host.dispatchKeyboard(opposite)
        }
        frame()
    }

    /**
     * Measures the complete public opportunity cycle, including preparation allocation and preedit layout replacement.
     * No per-invocation timestamp, reflective access or independent oracle participates in this operation.
     */
    internal fun inputCycle(): InputResult {
        prepareInput()
        return input()
    }

    /**
     * Delivers exactly one fixed public event; preedit cancellation remains part of a composed press.
     */
    internal fun input(): InputResult = if (operation === Operation.Primary) host.dispatchPointer(press) else host.dispatchKeyboard(key)

    /**
     * Returns one fixed-time current frame without adding it to the input interval.
     */
    internal fun frame(): RuntimeUiFrame = host.frame(size, time)

    /**
     * Includes complete fresh host ownership and first frame, with resource bytes already prepared.
     */
    internal fun initialLayout(): RuntimeUiFrame =
        fresh(TextAreaState(value)).use { next ->
            next.attach()
            next.frame(size, time)
        }

    /**
     * Borrows one actual line for a separate method-only corpus.
     */
    internal fun currentLine(): Any {
        val layout = TextAreaInputAccess.field(editor(), "layout")
        return TextAreaInputAccess.lines(layout)[TextAreaInputAccess.lineIndex(layout, cursor())]
    }

    /**
     * Selects beginning/middle/end coordinates from the actual immutable rounded positions.
     */
    internal fun lookupX(line: Any, selected: Point): Int {
        val positions = TextAreaInputAccess.field(line, "positions") as IntArray
        val index =
            when (selected) {
                Point.Beginning -> 0
                Point.Middle -> positions.size / 2
                Point.End -> positions.lastIndex
            }
        return positions[index]
    }

    /**
     * Requires exact loaded lookup output, no font work and the candidate's actual coordinate-read ceiling.
     */
    internal fun verifyLookup(line: Any, x: Int, lookup: IntUnaryOperator) {
        val calls = glyphCalls
        check(lookup.applyAsInt(x) == TextAreaInputAccess.nearest(line, x))
        TextAreaInputAccess.verifyVisits(line, x)
        check(glyphCalls == calls)
    }

    /**
     * Requires identity/pixel reuse for the clean control and independent first-layout lifetime.
     */
    internal fun verifyClean() {
        val first = frame()
        val calls = glyphCalls
        check(frame() === first)
        val next = initialLayout()
        check(backends == 1 && faces == 1)
        check(next.semantics.single { entry -> entry.semantics.role === SemanticsRole.TextArea }.semantics.value == UiText.Literal(value))
        val expected = first.drawCommands.filterNot { command -> command is DrawCommand.FillRectangle }
        val actual = next.drawCommands.filterNot { command -> command is DrawCommand.FillRectangle }
        for (scale in 1..3) {
            check(rasterizeHeadless(expected, size, scale).copyArgb().contentEquals(rasterizeHeadless(actual, size, scale).copyArgb()))
        }
        check(calls < glyphCalls)
    }

    /**
     * Checks real input against a complete scalar scan, unchanged committed semantics and current-layout ownership.
     */
    internal fun verifyInput() {
        prepareInput()
        val previous = TextAreaInputAccess.field(editor(), "layout")
        val before = if (composed) committedControl else frame()
        val scroll = state.scrollState.metrics.offset
        val pan = TextAreaInputAccess.field(TextAreaInputAccess.field(editor(), "viewport"), "horizontalOffset") as Int
        val calls = glyphCalls
        val token = host.textInputFocus
        val expected = expectedPlacement(previous)
        val result = input()
        check(result === if (operation === Operation.Primary) InputResult.Ignored else InputResult.Consumed)
        check(TextAreaInputAccess.field(cursor(), "offset") == expected.offset)
        check(glyphCalls == calls)
        check(state.value == value)
        check(host.textInputFocus === token)
        if (composed.not()) check(TextAreaInputAccess.field(editor(), "layout") === previous)
        val after = frame()
        check(after.semantics.single { entry -> entry.semantics.role === SemanticsRole.TextArea }.semantics.value == UiText.Literal(value))
        check(state.scrollState.metrics.offset == scroll)
        check(TextAreaInputAccess.field(TextAreaInputAccess.field(editor(), "viewport"), "horizontalOffset") == pan)
        verifyPixels(before, after, expected, pan, scroll.toInt())
        verifyCleanFrame(after)
    }

    private fun verifyCleanFrame(frame: RuntimeUiFrame) {
        val calls = glyphCalls
        check(this.frame() === frame)
        check(glyphCalls == calls)
    }

    private fun verifyPixels(
        before: RuntimeUiFrame,
        after: RuntimeUiFrame,
        placement: Placement,
        pan: Int,
        scroll: Int,
    ) {
        val layout = TextAreaInputAccess.field(editor(), "layout")
        val line = TextAreaInputAccess.lines(layout)[placement.line]
        val coordinate = TextAreaInputAccess.coordinate(line, placement.offset) - pan
        val left = if (coordinate == size.width - 8) coordinate - 1 else coordinate
        val top = placement.line * (TextAreaInputAccess.field(layout, "lineStep") as Int) - scroll
        val bounds = IntRect(4 + left, 4 + maxOf(0, top), 5 + left, 4 + minOf(size.height - 8, top + 9))
        val reference = before.drawCommands.map { command -> if (command is DrawCommand.FillRectangle) command.copy(bounds = bounds) else command }
        check(after.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single().bounds == bounds)
        for (scale in 1..3) {
            check(rasterizeHeadless(reference, size, scale).copyArgb().contentEquals(rasterizeHeadless(after.drawCommands, size, scale).copyArgb()))
        }
    }

    private fun expectedPlacement(layout: Any): Placement {
        val placement = if (operation === Operation.Primary) pointerPlacement(layout) else keyPlacement(layout)
        if (composed.not()) return placement
        val offset = TextAreaInputAccess.field(cursor(), "offset") as Int
        val next = placement.offset
        val committed = if (next <= offset) next else if (next < offset + composition.fullText.length) offset else next - composition.fullText.length
        return Placement(committed, 0)
    }

    private fun pointerPlacement(layout: Any): Placement {
        val pan = TextAreaInputAccess.field(TextAreaInputAccess.field(editor(), "viewport"), "horizontalOffset") as Int
        val y = (press.position.y - 4).coerceIn(0, size.height - 9) + state.scrollState.metrics.offset.toInt()
        val index = TextAreaInputAccess.pointerLine(layout, y)
        val x = (press.position.x - 4).coerceIn(0, size.width - 8) + pan
        return nearest(TextAreaInputAccess.lines(layout), index, x)
    }

    private fun keyPlacement(layout: Any): Placement {
        val current = cursor()
        val offset = TextAreaInputAccess.field(current, "offset") as Int
        val lines = TextAreaInputAccess.lines(layout)
        val source = TextAreaInputAccess.lineIndex(layout, current)
        val step = TextAreaInputAccess.field(layout, "lineStep") as Int
        val distance = if (operation === Operation.PageUp || operation === Operation.PageDown) maxOf(1, (size.height - 8) / step) else 1
        val delta = if (operation === Operation.Up || operation === Operation.PageUp) -distance else distance
        val index = (source + delta).coerceIn(0, lines.lastIndex)
        val x = TextAreaInputAccess.optional(current, "preferredX") as Int? ?: TextAreaInputAccess.coordinate(lines[source], offset)
        return nearest(lines, index, x)
    }

    private fun nearest(lines: List<Any>, index: Int, x: Int): Placement {
        val offset = TextAreaInputAccess.nearest(lines[index], x)
        TextAreaInputAccess.verifyVisits(lines[index], x)
        return Placement(offset, index)
    }

    private fun pointer(): PointerEvent.Press {
        if (composed) {
            val x =
                when (point) {
                    Point.Beginning -> 0
                    Point.Middle -> 3
                    Point.End -> 12
                }
            return PointerEvent.Press(IntOffset(4 + x, 4), PointerButton.Primary)
        }
        val layout = TextAreaInputAccess.field(editor(), "layout")
        val current = cursor()
        val index = TextAreaInputAccess.lineIndex(layout, current)
        val line = TextAreaInputAccess.lines(layout)[index]
        val x = TextAreaInputAccess.coordinate(line, TextAreaInputAccess.field(current, "offset") as Int)
        val pan = TextAreaInputAccess.field(TextAreaInputAccess.field(editor(), "viewport"), "horizontalOffset") as Int
        val step = TextAreaInputAccess.field(layout, "lineStep") as Int
        val y = index * step - state.scrollState.metrics.offset.toInt()
        return PointerEvent.Press(IntOffset(4 + (x - pan).coerceIn(0, size.width - 8), 4 + y.coerceIn(0, size.height - 9)), PointerButton.Primary)
    }

    private fun editor(): Any = TextAreaInputAccess.editor(host)

    private fun cursor(): Any = TextAreaInputAccess.field(editor(), "cursor")

    private fun initialOffset(): Int {
        if (composed) return 0
        val rows = value.split('\n')
        val second = operation === Operation.Up || operation === Operation.PageUp
        val row = if (second) rows.last() else rows.first()
        val count = row.codePointCount(0, row.length)
        val column =
            when (point) {
                Point.Beginning -> 0
                Point.Middle -> count / 2
                Point.End -> count
            }
        return (if (second) rows.first().length + 1 else 0) + row.offsetByCodePoints(0, column)
    }

    private fun value(): String =
        when (shape) {
            Shape.ShortWrapped -> "AB🙂CD".repeat(8)
            Shape.LongBmp -> "A".repeat(if (composed) 32_764 else 32_767)
            Shape.Supplementary -> "🙂".repeat(16_383)
            Shape.TwoLines -> "A".repeat(16_383) + "\n" + "A".repeat(16_383)
            Shape.Plateau, Shape.Signed -> "AB".repeat(16_383) + "A"
        }

    private fun profile(): MinecraftUiProfile {
        val source = MinecraftMemoryFontAssetSource("text-area-input-v1", mapOf("assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:input.ttf"}]}""".toByteArray(Charsets.UTF_8), "assets/strata_benchmark/font/input.ttf" to byteArrayOf(1)))
        val compatibility = ComponentFontAssets.snapshot().compatibility.copy(saturatingCeil = true)
        val snapshot = MinecraftFontSnapshot.load(listOf(source), compatibility)
        check(snapshot.diagnostics.isEmpty())
        return ComponentProfile.create(snapshot)
    }

    private fun fresh(state: TextAreaState): MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Logical TextArea input") { TextArea(state, TextAreaViewport.Size(size), wrap = if (shape === Shape.ShortWrapped) TextWrap.Character else TextWrap.None, lineSpacing = 2, modifier = Modifier.Empty.initialFocus()) },
            profile,
            fontBackend = MinecraftFontBackendFactory { backend() },
        )

    private fun backend(): MinecraftFontBackend {
        backends += 1
        return object : MinecraftFontBackend {
            override fun decodePng(bytes: ByteArray): DrawImage = error("The metric fixture has no bitmap provider")

            override fun openTrueType(bytes: ByteArray, settings: MinecraftTrueTypeSettings): MinecraftTrueTypeFace {
                faces += 1
                return object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        glyphCalls += 1
                        val advance =
                            if (codePoint == 'B'.code) {
                                when (shape) {
                                    Shape.Plateau -> 0f
                                    Shape.Signed -> -2f
                                    else -> 3f
                                }
                            } else {
                                3f
                            }
                        return MinecraftFontGlyph(advance, 0f, 0f, 1f, 1f, image)
                    }

                    override fun close() {
                        faces -= 1
                    }
                }
            }

            override fun close() {
                backends -= 1
            }
        }
    }

    private fun key(operation: Operation): KeyCode =
        when (operation) {
            Operation.Primary -> KeyCode.Enter
            Operation.Up -> KeyCode.Up
            Operation.Down -> KeyCode.Down
            Operation.PageUp -> KeyCode.PageUp
            Operation.PageDown -> KeyCode.PageDown
        }

    private fun opposite(operation: Operation): KeyCode =
        when (operation) {
            Operation.Primary -> KeyCode.Enter
            Operation.Up -> KeyCode.Down
            Operation.Down -> KeyCode.Up
            Operation.PageUp -> KeyCode.PageDown
            Operation.PageDown -> KeyCode.PageUp
        }

    private data class Placement(
        val offset: Int,
        val line: Int,
    )

    override fun close() {
        host.close()
        check(backends == 0 && faces == 0)
        state.observe {}.close()
    }
}

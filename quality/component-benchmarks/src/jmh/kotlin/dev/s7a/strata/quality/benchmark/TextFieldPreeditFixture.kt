package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.quality.benchmark.TextFieldPreeditRow.Control
import dev.s7a.strata.quality.benchmark.TextFieldPreeditRow.Operation
import dev.s7a.strata.quality.benchmark.TextFieldPreeditRow.Partition
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition

/**
 * One owner-thread real TextField host and preconstructed immutable producers for a frozen whole-operation row.
 * Diagnostics and the independent CPU reference exist only in untimed verification, never in JMH collection.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class TextFieldPreeditFixture(
    private val row: TextFieldPreeditRow,
    verify: Boolean = false,
) : AutoCloseable {
    private val resourceFonts = row.control in setOf(Control.ValidSupplementaryBlocks, Control.SplitSupplementaryCaret, Control.NegativeAdvanceClippedUnderline, Control.WidthAndFontReplacement)
    private val negative = row.control == Control.NegativeAdvanceClippedUnderline
    private val viewport = IntSize(if (row.control == Control.WidthAndFontReplacement) 88 else 80, 20)
    private val profile = TextFieldPreeditProfile.create(if (resourceFonts) snapshot() else null)
    private val states = List(2) { TextFieldState("A", maxLength = if (row.control == Control.PreeditBeyondRemainingCapacity) 1 else 32767) }
    private val settings = List(2) { variant ->
        Settings(
            state = states[if (row.control == Control.StateReplacement) variant else 0],
            appearance = appearance(variant),
            width = if (row.control == Control.WidthAndFontReplacement && variant == 1) 88 else 80,
            font = ResourceId(if (row.control == Control.WidthAndFontReplacement && variant == 1) "strata_benchmark" else "minecraft", if (row.control == Control.WidthAndFontReplacement && variant == 1) "wide" else "default"),
        )
    }
    private val disabled = settings[0].copy(enabled = false)
    private val source = StressStateSource(settings[0])
    private val events = List(2, ::event)
    private val empty = TextInputEvent.Preedit("", 0, emptyList(), -1)
    private val reference = if (verify) TextFieldPreeditReference(resourceFonts, negative) else null
    private var host = open()
    private var monitor: UiRenderMonitor? = null
    private var phase = 0
    private var closedHost = false
    private var current = settings[0]
    private var lastInput: InputResult? = null

    init {
        if (row.control != Control.NoCompositionDirtyField && row.control != Control.DisposeCloseRelease) {
            input(TextInputEvent.Preedit("B", 1, listOf("B"), 0))
            input(events[0])
        }
        host.frame(viewport)
        if (verify) monitor = host.startRenderMonitoring()
    }

    /**
     * Runs exactly the declared transition through the real focused dispatch/publication and completed host frame.
     */
    fun next(): RuntimeUiFrame {
        phase = 1 - phase
        when (row.operation) {
            Operation.InputAndCompletedFrame -> input(events[phase])
            Operation.UnchangedPreeditDirtyFieldFrame -> publish(settings[phase])
            Operation.Control -> control(checkNotNull(row.control))
        }
        return if (row.control == Control.DisposeCloseRelease) lifetime() else host.frame(viewport)
    }

    private fun control(control: Control) {
        when (control) {
            Control.NoCompositionDirtyField, Control.AppearanceOnlyPaint -> publish(settings[phase])
            Control.CleanActiveComposition -> Unit
            Control.EmptyClear -> { input(events[phase]); input(empty) }
            Control.IdenticalPreeditNoOp -> input(events[0])
            Control.WidthAndFontReplacement -> { input(events[phase]); publish(settings[phase]) }
            Control.FocusLoss -> {
                refocus()
                input(events[phase])
                host.resetInputState()
                reference?.let { it.focused = false; it.composition = null }
            }
            Control.DisabledUpdate -> {
                publish(settings[0])
                host.frame(viewport)
                refocus()
                input(events[phase])
                publish(disabled)
            }
            Control.StateReplacement -> { input(events[phase]); publish(settings[phase]) }
            Control.ExternalStateMutation -> {
                input(events[phase])
                current.state.value = if (phase == 0) "A" else "D"
                reference?.let { it.value = current.state.value; it.cursor = minOf(it.cursor, it.value.length); it.composition = null }
            }
            Control.CommittedInputAndCursorClear -> {
                current.state.value = "A"
                reference?.let { it.value = "A"; it.cursor = minOf(it.cursor, 1); it.composition = null }
                input(events[phase])
                host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Home, 0))
                reference?.let { it.cursor = 0; it.composition = null }
                input(events[phase])
                check(host.dispatchTextInput(TextInputEvent.Character('D'.code)) == InputResult.Consumed)
                reference?.let { it.value = "DA"; it.cursor = 1; it.composition = null }
            }
            Control.DetachReattach -> {
                input(events[phase])
                host.detach()
                host.attach()
                reference?.let { it.focused = true; it.composition = null }
            }
            Control.DisposeCloseRelease -> Unit
            else -> input(events[phase])
        }
    }

    private fun refocus() {
        if (host.textInputFocus == null) {
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0))
            reference?.focused = true
        }
    }

    private fun publish(next: Settings) {
        source.publish(next)
        reference?.let {
            if (current.state !== next.state || next.enabled.not()) it.composition = null
            if (current.state !== next.state) {
                it.value = next.state.value
                it.cursor = minOf(it.cursor, it.value.length)
            }
            it.enabled = next.enabled
            if (next.enabled.not()) it.focused = false
            it.width = next.width
            it.wideFont = next.font == ResourceId("strata_benchmark", "wide")
            it.background = next.background
        }
        current = next
    }

    private fun input(event: TextInputEvent.Preedit) {
        val expected = reference?.input(event)
        lastInput = host.dispatchTextInput(event)
        if (expected != null) check(lastInput == expected) { "Input result changed for ${row.name}" }
    }

    private fun lifetime(): RuntimeUiFrame {
        if (closedHost) {
            monitor?.close()
            monitor = null
            host = open()
        }
        closedHost = false
        reference?.let { it.value = "A"; it.cursor = 1; it.focused = true; it.composition = null }
        input(events[phase])
        val frame = host.frame(viewport)
        host.close()
        closedHost = true
        check(source.subscriptions == 0)
        return frame
    }

    private fun open(): MinecraftUiHost {
        val created = createMinecraftUiHost(
            UiDefinition {
                Observe(source) { config ->
                    TextField(config.state, config.appearance, IntSize(config.width, 20), config.font, enabled = config.enabled, textStyle = TextStyle.ContainerLabel, modifier = Modifier.Empty.initialFocus(), key = ElementKey("field"))
                }
            },
            profile,
            MinecraftFontBackendFactory { SpaceBackend() },
        )
        created.attach()
        created.frame(viewport)
        return created
    }

    /**
     * Verifies current whole-frame pixels, ordered decorations, semantics, focus and bounded external observation.
     */
    fun verify(frame: RuntimeUiFrame) {
        val expected = checkNotNull(reference)
        check(frame.size == IntSize(expected.width, 20))
        val rectangles = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
        check(rectangles.map { it.bounds } == expected.rectangles()) { "Underline/caret order changed for ${row.name}" }
        check(rectangles.all { it.color.value == if (it.bounds.height == 1) TextFieldPreeditReference.underline else TextFieldPreeditReference.caretColor })
        val images = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        check(images.map { Triple(it.image.size, it.source, it.destination) } == expected.images()) { "Ordered image submissions changed for ${row.name}" }
        check(frame.drawCommands == images + rectangles) { "Field command ordering changed for ${row.name}" }
        check(rasterizeHeadless(frame.drawCommands, viewport).copyArgb().contentEquals(expected.pixels(viewport))) { "Independent pixels changed for ${row.name}" }
        val field = frame.semantics.single { it.semantics.role == SemanticsRole.TextField }
        check(field.semantics.label == UiText.Literal(expected.value) && field.semantics.disabled == expected.enabled.not())
        check(current.state.value == expected.value)
        check(source.subscriptions == if (closedHost) 0 else 1)
        if (closedHost.not()) check((host.textInputFocus != null) == (expected.focused && expected.enabled))
    }

    /**
     * Proves dirty appearance really paints without measuring or laying out and clean composition frames reuse identity.
     */
    fun verifyWork(previous: RuntimeUiFrame, frame: RuntimeUiFrame) {
        if (row.control == Control.DisposeCloseRelease) return
        val counts = checkNotNull(monitor).snapshot().counts
        if (row.operation == Operation.UnchangedPreeditDirtyFieldFrame || row.control in setOf(Control.NoCompositionDirtyField, Control.AppearanceOnlyPaint)) {
            check(0L < counts.getValue(UiRenderMetric.Paint))
            check(counts.getValue(UiRenderMetric.Measure) == 0L && counts.getValue(UiRenderMetric.Layout) == 0L)
        }
        if (row.control in setOf(Control.CleanActiveComposition, Control.IdenticalPreeditNoOp)) {
            check(previous === frame)
            check(counts.getValue(UiRenderMetric.Paint) == 0L)
        }
    }

    /**
     * Begins one bounded untimed verification interval.
     */
    fun checkpoint() { monitor?.checkpoint() }

    /**
     * Current completed clean frame for identity and parity assertions outside timing.
     */
    fun frame(): RuntimeUiFrame = host.frame(viewport)

    override fun close() {
        try { monitor?.close() } finally {
            monitor = null
            host.close()
            check(source.subscriptions == 0)
        }
    }

    private fun event(variant: Int): TextInputEvent.Preedit {
        val letter = if (variant == 0) "B" else "C"
        if (0 < row.length) {
            val text = letter.repeat(row.length)
            val blocks = when (row.partition) {
                Partition.TwoHalves -> listOf(text.take(row.length / 2), text.drop(row.length / 2))
                Partition.Fixed8Blocks -> text.chunked(row.length / 8)
                Partition.OneScalarPerBlock -> text.map(Char::toString)
                Partition.EmptyBoundaryBlocks -> listOf("", text.take(row.length / 2), "", text.drop(row.length / 2), "")
            }
            val focus = if (row.focus == TextFieldPreeditRow.Focus.FirstNonempty) blocks.indexOfFirst(String::isNotEmpty) else blocks.indexOfLast(String::isNotEmpty)
            return TextInputEvent.Preedit(text, text.length, blocks, focus)
        }
        return when (row.control) {
            Control.EmptyClear -> TextInputEvent.Preedit(letter, 1, listOf(letter), 0)
            Control.NoFocusedBlock -> TextInputEvent.Preedit(letter, 1, listOf(letter), -1)
            Control.MismatchedTotalLength -> TextInputEvent.Preedit(letter, 1, listOf(letter + letter), 0)
            Control.MismatchedSameLength -> TextInputEvent.Preedit(letter, 1, listOf(if (variant == 0) "C" else "B"), 0)
            Control.FocusedEmptyInterior -> TextInputEvent.Preedit(letter + letter, 2, listOf(letter, "", letter), 1)
            Control.FocusedEmptyLeading -> TextInputEvent.Preedit(letter + letter, 2, listOf("", letter + letter), 0)
            Control.FocusedEmptyTrailing -> TextInputEvent.Preedit(letter + letter, 2, listOf(letter + letter, ""), 1)
            Control.ValidSupplementaryBlocks -> TextInputEvent.Preedit("🙂🙂", 4, listOf("🙂", "🙂"), variant)
            Control.SplitSupplementaryCaret -> TextInputEvent.Preedit("🙂🙂", 1 + variant * 2, listOf("🙂", "🙂"), 0)
            Control.IsolatedSurrogateFull -> TextInputEvent.Preedit("\uD800" + letter, 2, listOf(letter), 0)
            Control.IsolatedSurrogateBlock -> TextInputEvent.Preedit(letter, 1, listOf("\uD800"), 0)
            Control.RejectedFullControl -> TextInputEvent.Preedit("\n" + letter, 2, listOf(letter), 0)
            Control.RejectedBlockControl -> TextInputEvent.Preedit(letter, 1, listOf("\n"), 0)
            Control.PreeditBeyondRemainingCapacity -> TextInputEvent.Preedit(letter.repeat(4), 4, listOf(letter.repeat(4)), 0)
            Control.CaretOnlyChange -> TextInputEvent.Preedit("BBBB", 3 + variant, listOf("BB", "BB"), 1)
            Control.FocusedIndexOnlyChange -> TextInputEvent.Preedit("BBBB", 4, listOf("BB", "BB"), variant)
            Control.BlocksOnlyChange -> TextInputEvent.Preedit("BBBB", 4, if (variant == 0) listOf("BB", "BB") else listOf("B", "BBB"), 1)
            Control.NegativeAdvanceClippedUnderline -> TextInputEvent.Preedit("🙂", variant * 2, listOf("🙂"), 0)
            else -> TextInputEvent.Preedit(letter, 1, listOf(letter), 0)
        }
    }

    private fun snapshot(): MinecraftFontSnapshot {
        val ordinary = """{"providers":[{"type":"space","advances":{"A":2,"B":2,"C":2,"D":2,"_":2,"🙂":${if (negative) -10 else 2}}}]}"""
        val wide = """{"providers":[{"type":"space","advances":{"A":3,"B":3,"C":3,"D":3,"_":3,"🙂":3}}]}"""
        val source = MinecraftMemoryFontAssetSource("preedit-space-v1", mapOf("assets/minecraft/font/default.json" to ordinary.encodeToByteArray(), "assets/strata_benchmark/font/wide.json" to wide.encodeToByteArray()))
        return MinecraftFontSnapshot.load(listOf(source), MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 0)).also { check(it.diagnostics.isEmpty()) }
    }

    private fun appearance(variant: Int): TextInputAppearance.Custom {
        val image = ImageSource.Pixels(createDrawImage(IntSize(3, 3), IntArray(9) { if (variant == 0) 0xFF426789.toInt() else 0xFF526789.toInt() }))
        return TextInputAppearance.Custom(image, image, ArgbColor(TextFieldPreeditReference.caretColor), image, compositionUnderlineColor = ArgbColor(TextFieldPreeditReference.underline))
    }

    /**
     * Detached producer settings; two main variants are constructed once outside both timing boundaries.
     */
    private data class Settings(
        val state: TextFieldState,
        val appearance: TextInputAppearance.Custom,
        val width: Int = 80,
        val font: ResourceId,
        val enabled: Boolean = true,
    ) {
        val background: Int get() = (appearance.focused as ImageSource.Pixels).image.argbAt(0, 0)
    }

    /**
     * Space-only snapshots never decode images or open native font faces.
     */
    private class SpaceBackend : MinecraftFontBackend {
        override fun decodePng(bytes: ByteArray) = error("Space fixture must not decode pixels")
        override fun openTrueType(bytes: ByteArray, settings: MinecraftTrueTypeSettings): MinecraftTrueTypeFace = error("Space fixture must not open a native face")
        override fun close() = Unit
    }
}

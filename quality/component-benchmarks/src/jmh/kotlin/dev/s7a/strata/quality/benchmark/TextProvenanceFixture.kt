package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.quality.benchmark.TextProvenanceAssets.Family
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Case
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Operation
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Shape
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
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
import dev.s7a.strata.ui.UiDefinition
import java.util.Random

/**
 * Frozen prepared input and independently owned direct-layout/public-host services for one complete operation.
 * Timed cycles contain actual description/input/frame work and restore mutations before returning.
 * Dense oracles, reflection, glyph/retention counts and pixels are confined to verification outside timing.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught") // Constructor failure releases every owner while preserving the original throwable.
@OptIn(InternalStrataRuntimeApi::class)
internal class TextProvenanceFixture(
    private val case: Case,
) : AutoCloseable {
    private val assets = TextProvenanceAssets(case.shape, case.consumer)
    private val access = TextProvenanceAccess()
    private val multiline = case.consumer !== Consumer.SingleLineText
    private val content = access.create(assets.texts[0], Family.First.id, multiline)
    private val comparisonText = if (case.operation === Operation.StructureDifferent || case.operation === Operation.InheritedDifferent) UiText.concat(UiText.Literal(""), assets.texts[0]) else assets.texts[0]
    private val comparedContent =
        when (case.operation) {
            Operation.StructureDifferent -> access.create(comparisonText, Family.First.id, multiline)
            Operation.InheritedDifferent -> access.create(comparisonText, Family.Second.id, multiline)
            Operation.FontDifferent -> access.create(assets.texts[2], Family.First.id, multiline)
            Operation.Equivalent -> access.create(assets.texts[0], Family.First.id, multiline)
            else -> content
        }
    private val comparisonSource = if (case.operation === Operation.InheritedDifferent) access.create(comparisonText, Family.First.id, multiline) else content
    private val scalarOffsets = scalarOffsets(assets.values[0])
    private val random = Random(228L)
    private val lookups = IntArray(512) { if (assets.values[0].isEmpty()) 0 else scalarOffsets[random.nextInt(scalarOffsets.size - 1)] }
    private val slices =
        List(32) {
            val first = scalarOffsets[random.nextInt(scalarOffsets.size)]
            val last = scalarOffsets[random.nextInt(scalarOffsets.size)]
            minOf(first, last) to maxOf(first, last)
        }
    private val policy = TextLayout.Multiline(TextWrap.Character, maxLines = if (case.shape === Shape.Wrapped) 3 else Int.MAX_VALUE, overflow = if (case.shape === Shape.Wrapped) TextOverflow.Ellipsis else TextOverflow.Clip)
    private val firstSize = IntSize(if (case.consumer === Consumer.SingleLineText) Math.addExact(Math.multiplyExact(assets.values.maxOf(String::length), 5), 32) else 128, if (case.consumer === Consumer.SingleLineText) 9 else 40)
    private val singleViewports = if (case.consumer === Consumer.SingleLineText) assets.texts.map(::naturalViewport) else emptyList()
    private val secondSize = IntSize(firstSize.width + 11, firstSize.height)
    private val size = mutableStateOf(firstSize)
    private val owner = mutableStateOf(Side.First)
    private val selectedText = mutableStateOf(Side.First)
    private val selectedFont = mutableStateOf(Side.First)
    private val declaration = mutableStateOf(Side.First)
    private val keys = Side.entries.map(::ElementKey)
    private val states = List(2) { TextAreaState(assets.values[0], maxLength = assets.values.maxOf(String::length) + 64) }
    private val time = FrameTime(0)
    private val home = KeyboardEvent.Press(KeyCode.Home, 0, KeyboardModifiers(control = true))
    private val composition = TextInputEvent.Preedit("🙂Z", 2, listOf("🙂", "Z"), 0)
    private val clearComposition = TextInputEvent.Preedit("", 0, emptyList(), -1)
    private var composed = false
    private var closed = false
    private lateinit var renderer: Any
    private lateinit var description: () -> Element
    private lateinit var host: MinecraftUiHost

    init {
        initialize()
    }

    /**
     * Acquires owners outside sampling and rolls back each initialized owner if construction fails.
     */
    private fun initialize() {
        try {
            renderer = access.renderer(assets.profile, MinecraftFontBackendFactory { assets.backend() })
            description =
                access.evaluator(
                    assets.profile,
                    { scope ->
                        with(scope) {
                            if (case.consumer === Consumer.TextArea) {
                                TextArea(states[0], TextAreaViewport.Size(firstSize), Family.First.id, modifier = Modifier.Empty.initialFocus())
                            } else {
                                Text(assets.texts[0], if (case.consumer === Consumer.SingleLineText) TextLayout.SingleLine else policy, TextStyle.ContainerLabel)
                            }
                        }
                    },
                    renderer,
                )
            host =
                createMinecraftUiHost(
                    UiDefinition("Frozen font provenance public consumer") {
                        when (declaration.value) {
                            Side.First, Side.Second -> {
                                val textIndex = if (selectedFont.value === Side.Second) 2 else selectedText.value.ordinal
                                if (case.consumer === Consumer.TextArea) {
                                    TextArea(states[owner.value.ordinal], TextAreaViewport.Size(size.value), font(), wrap = TextWrap.Character, modifier = Modifier.Empty.initialFocus(), key = keys[owner.value.ordinal])
                                } else {
                                    Text(assets.texts[textIndex], if (case.consumer === Consumer.SingleLineText) TextLayout.SingleLine else policy, TextStyle.ContainerLabel, key = keys[owner.value.ordinal])
                                }
                            }
                        }
                    },
                    assets.profile,
                    fontBackend = MinecraftFontBackendFactory { assets.backend() },
                )
            host.attach()
            frame()
            if (case.consumer === Consumer.TextArea) {
                resetCaret()
                host.dispatchTextInput(composition)
                frame()
                host.dispatchTextInput(clearComposition)
                frame()
                resetCaret()
            }
            access.create(assets.texts[1], Family.First.id, multiline)
            directLayout()
            description()
            check(assets.backends == 2)
        } catch (failure: Throwable) {
            try {
                release()
            } catch (release: Throwable) {
                if (failure !== release) failure.addSuppressed(release)
            }
            throw failure
        }
    }

    /**
     * Executes complete target work with all prepared inputs and handle bindings already fixed.
     */
    internal fun perform(): Any =
        when (case.operation) {
            Operation.Content -> access.create(assets.texts[0], Family.First.id, multiline)
            Operation.Lookup -> lookupTrace()
            Operation.Slice -> slices.map { (first, last) -> access.slice(content, first, last) }
            Operation.Equivalent, Operation.StructureDifferent, Operation.InheritedDifferent, Operation.FontDifferent -> access.equivalent(comparisonSource, comparedContent)
            Operation.Description -> description()
            Operation.Layout -> directLayout()
            Operation.Clean -> frame()
            else -> changedCycle()
        }

    private fun lookupTrace(): Int {
        var result = 1
        for (offset in lookups) result = 31 * result + access.fontAt(content, offset).hashCode()
        return result
    }

    private fun directLayout(): Any =
        if (case.consumer === Consumer.SingleLineText) {
            access.run(assets.texts[0], renderer, Family.First.id, false)
        } else {
            access.layout(content, renderer, policy, if (case.consumer === Consumer.TextArea) firstSize.width - 8 else firstSize.width, if (case.consumer === Consumer.TextArea) TextStyle.TextField else TextStyle.ContainerLabel, case.consumer === Consumer.TextArea, if (case.consumer === Consumer.TextArea) Int.MAX_VALUE else firstSize.height)
        }

    private fun changedCycle(): RuntimeUiFrame {
        mutate()
        frame()
        mutate()
        return frame()
    }

    private fun mutate() {
        when (case.operation) {
            Operation.Initial -> owner.value = opposite(owner.value)
            Operation.Edit -> {
                selectedText.value = opposite(selectedText.value)
                states[owner.value.ordinal].value = assets.values[selectedText.value.ordinal]
            }

            Operation.Reflow -> size.value = if (size.value == firstSize) secondSize else firstSize
            Operation.FontChange -> selectedFont.value = opposite(selectedFont.value)
            Operation.Redeclaration -> declaration.value = opposite(declaration.value)
            Operation.Preedit -> {
                composed = composed.not()
                host.dispatchTextInput(if (composed) composition else clearComposition)
            }

            else -> error("Only declared restoring retained operations mutate the public fixture.")
        }
    }

    /**
     * Checks all real content contracts and complete consumer work against independently built dense provenance.
     */
    internal fun verify() {
        val dense = TextProvenanceDenseReference.create(assets.texts[0], Family.First.id, multiline)
        verifyContent(dense)
        when (case.operation) {
            Operation.Equivalent -> check(access.equivalent(comparisonSource, comparedContent))
            Operation.StructureDifferent, Operation.InheritedDifferent, Operation.FontDifferent -> check(access.equivalent(comparisonSource, comparedContent).not())
            Operation.Layout -> reference().verify(directLayout())
            Operation.Initial, Operation.Edit, Operation.Reflow, Operation.FontChange, Operation.Preedit, Operation.Redeclaration -> verifyChanged()
            else -> perform()
        }
        verifyCurrent()
        verifyDetach()
        verifyClean()
        println("text-provenance=" + case.name + ",utf16=" + dense.value.length + ",fonts=" + access.retainedFontSlots(content) + ",boundaries=" + access.retainedBoundarySlots(content) + ",measured=false")
    }

    private fun verifyContent(reference: TextProvenanceDenseReference) {
        for (offset in scalarOffsets.dropLast(1)) check(access.fontAt(content, offset) == reference.fontAt(offset))
        if (reference.value.isEmpty()) check(access.fontAt(content, 0) == reference.fontAt(0))
        for ((first, last) in slices) check(access.slice(content, first, last) == reference.slice(first, last))
        check(access.slice(content, 0, reference.value.length) == reference.slice(0, reference.value.length))
        var runs = 0
        val expectedStarts = ArrayList<Int>()
        var previous: ResourceId? = null
        for (offset in scalarOffsets.dropLast(1)) {
            val font = reference.fontAt(offset)
            if (font != previous) {
                if (0 < runs) expectedStarts.add(offset)
                runs++
            }
            previous = font
        }
        val expectedFonts = if (access.usesRunStarts()) runs else reference.value.length
        check(access.retainedFontSlots(content) == expectedFonts)
        check(access.retainedBoundarySlots(content) == if (access.usesRunStarts()) (runs - 1).coerceAtLeast(0) else 0)
        val actualStarts = access.retainedStarts(content)
        check(actualStarts.contentEquals(if (access.usesRunStarts()) expectedStarts.toIntArray() else IntArray(0)))
        var lookupComparisons = 0
        for (offset in lookups) {
            if (access.usesRunStarts()) {
                var first = 0
                var last = expectedStarts.size
                while (first < last) {
                    val middle = first + (last - first) / 2
                    lookupComparisons++
                    if (offset < expectedStarts[middle]) last = middle else first = middle + 1
                }
            } else if (reference.value.isNotEmpty()) {
                lookupComparisons++
            }
        }
        val boundaryCount = (runs - 1).coerceAtLeast(0)
        var boundaryCapacity = 0L
        var boundaryGrowthCopied = 0L
        var boundaryAllocated = boundaryCount.toLong()
        while (boundaryCapacity < boundaryCount) {
            boundaryGrowthCopied += boundaryCapacity
            boundaryCapacity = maxOf(8L, boundaryCapacity * 2L).coerceAtMost(Int.MAX_VALUE.toLong())
            boundaryAllocated += boundaryCapacity
        }
        val literalRuns = nonemptyLiteralCount(assets.texts[0])
        println("text-provenance-construction=" + case.name + ",sourceOnly=true,denseFontWrites=" + reference.value.length + ",denseFontSnapshotMembership=" + reference.value.length + ",runFontWrites=" + runs + ",runFontSnapshotMembership=" + runs + ",runSelectionCalls=" + literalRuns + ",runCoalescingComparisons=" + (literalRuns - 1).coerceAtLeast(0) + ",boundaryWrites=" + boundaryCount + ",boundaryGrowthCopied=" + boundaryGrowthCopied + ",boundarySnapshotCopied=" + boundaryCount + ",boundaryAllocatedSlots=" + boundaryAllocated + ",fontListGrowthAndVmAllocation=requires-gc-profiler")
        val sourceSliceScalars = slices.sumOf { (first, last) -> reference.value.codePointCount(first, last) }
        val sourceSliceRuns = slices.sumOf { (first, last) -> if (first == last) 0 else 1 + expectedStarts.count { first < it && it < last } }
        println("text-provenance-work=" + case.name + ",codeUnitMembership=" + reference.value.length + ",runMembership=" + runs + ",boundaryMembership=" + expectedStarts.size + ",lookupTrace=" + lookups.size + ",independentLookupComparisons=" + lookupComparisons + ",denseSliceScalars=" + sourceSliceScalars + ",intersectedSliceRuns=" + sourceSliceRuns + ",temporaryAllocation=requires-gc-profiler")
    }

    private fun nonemptyLiteralCount(text: UiText): Int =
        when (text) {
            is UiText.Literal -> if (text.value.isEmpty()) 0 else 1
            is UiText.WithFont -> nonemptyLiteralCount(text.text)
            is UiText.Concatenated -> text.parts.sumOf(::nonemptyLiteralCount)
            is UiText.Translated, is UiText.Platform -> error("Frozen provenance inputs must contain resolved literal text.")
        }

    private fun verifyChanged() {
        val previousOwner = currentOwner()
        val previous = presentation()
        val before = frame()
        val oldPixels = pixels(before)
        mutate()
        frame()
        if (case.operation === Operation.Initial) {
            check(currentOwner() !== previousOwner)
            TextProvenanceOwnerAccess.released(previousOwner, case.consumer)
        } else {
            check(currentOwner() === previousOwner)
        }
        verifyCurrent(if (case.operation === Operation.Initial && case.consumer === Consumer.TextArea) states[owner.value.ordinal].value.length else 0)
        mutate()
        frame()
        if (case.consumer === Consumer.TextArea) resetCaret()
        verifyCurrent()
        check(pixels(before).contentEquals(oldPixels))
        reference().verify(previous)
    }

    private fun verifyCurrent(committedCaret: Int = 0) {
        val expected = reference()
        expected.verify(presentation())
        val role = if (case.consumer === Consumer.TextArea) SemanticsRole.TextArea else SemanticsRole.Text
        val semantic = frame().semantics.single { it.semantics.role === role }.semantics
        if (case.consumer === Consumer.TextArea) {
            check(semantic.value == UiText.Literal(states[owner.value.ordinal].value))
            checkNotNull(host.textInputFocus)
        } else {
            check(semantic.label == assets.texts[if (selectedFont.value === Side.Second) 2 else selectedText.value.ordinal])
            check(host.textInputFocus == null)
        }
        expected.verifyFrame(frame(), currentOwner(), states[owner.value.ordinal].scrollState.metrics.offset.toInt(), committedCaret, composed)
    }

    private fun verifyDetach() {
        val previousOwner = currentOwner()
        val previous = presentation()
        val expected = reference()
        val before = frame()
        val oldPixels = pixels(before)
        host.detach()
        check(host.textInputFocus == null)
        host.attach()
        frame()
        check(currentOwner() === previousOwner)
        verifyCurrent()
        expected.verify(previous)
        check(pixels(before).contentEquals(oldPixels))
    }

    private fun verifyClean() {
        val oldFrame = frame()
        val oldPresentation = presentation()
        val calls = assets.glyphCalls
        repeat(100) {
            check(frame() === oldFrame)
            check(presentation() === oldPresentation)
        }
        check(assets.glyphCalls == calls)
    }

    private fun reference(): TextProvenanceReference {
        val textIndex = if (selectedFont.value === Side.Second) 2 else selectedText.value.ordinal
        val text =
            if (case.consumer === Consumer.TextArea) {
                val value = (if (composed) "🙂Z" else "") + states[owner.value.ordinal].value
                UiText.Literal(value)
            } else {
                assets.texts[textIndex]
            }
        return TextProvenanceReference(TextProvenanceDenseReference.create(text, if (case.consumer === Consumer.TextArea) font() else Family.First.id, multiline), assets, case.consumer, policy, size.value)
    }

    private fun font() = if (selectedFont.value === Side.First) Family.First.id else Family.Second.id

    private fun resetCaret() {
        host.dispatchKeyboard(home)
        frame()
    }

    private fun currentOwner(): Any = TextProvenanceOwnerAccess.owner(host, case.consumer)

    private fun presentation(): Any = TextProvenanceOwnerAccess.presentation(currentOwner(), case.consumer)

    /**
     * Prepares exact fixed-host dimensions from the independent original logical scalar widths.
     * The three immutable text/font variants are resolved before sampling, with no target metric or layout reads.
     */
    private fun naturalViewport(text: UiText): IntSize {
        val original = TextProvenanceDenseReference.create(text, Family.First.id, false)
        var width = 0f
        var offset = 0
        while (offset < original.value.length) {
            val scalar = original.value.codePointAt(offset)
            width += assets.advance(original.fontAt(offset), scalar)
            offset += Character.charCount(scalar)
        }
        return IntSize(maxOf(0, assets.compatibility.roundedWidth(width)), 9)
    }

    private fun frame(): RuntimeUiFrame {
        val viewport =
            if (case.consumer === Consumer.SingleLineText) {
                singleViewports[if (selectedFont.value === Side.Second) 2 else selectedText.value.ordinal]
            } else {
                size.value
            }
        return host.frame(viewport, time)
    }

    private fun pixels(frame: RuntimeUiFrame): IntArray = rasterizeHeadless(frame.drawCommands, IntSize(minOf(64, size.value.width), minOf(40, size.value.height))).copyArgb()

    private fun opposite(value: Side): Side = if (value === Side.First) Side.Second else Side.First

    private fun scalarOffsets(value: String): List<Int> =
        buildList {
            var offset = 0
            add(offset)
            while (offset < value.length) {
                offset += Character.charCount(value.codePointAt(offset))
                add(offset)
            }
        }

    private fun release() {
        val hostFailure = if (this::host.isInitialized) runCatching { host.close() }.exceptionOrNull() else null
        val rendererFailure = if (this::renderer.isInitialized) runCatching { access.closeRenderer(renderer) }.exceptionOrNull() else null
        if (hostFailure != null) {
            if (rendererFailure != null && rendererFailure !== hostFailure) hostFailure.addSuppressed(rendererFailure)
            throw hostFailure
        }
        if (rendererFailure != null) throw rendererFailure
    }

    override fun close() {
        if (closed) return
        closed = true
        val oldOwner = currentOwner()
        val oldPresentation = presentation()
        val expected = reference()
        val oldFrame = frame()
        val oldPixels = pixels(oldFrame)
        release()
        TextProvenanceOwnerAccess.released(oldOwner, case.consumer)
        expected.verify(oldPresentation)
        check(pixels(oldFrame).contentEquals(oldPixels))
        check(runCatching { host.textInputFocus }.exceptionOrNull() is IllegalStateException)
        check(assets.faces == 0 && assets.releases == assets.backends)
        states.forEach { state -> state.observe {}.close() }
    }

    private enum class Side {
        First,
        Second,
    }
}

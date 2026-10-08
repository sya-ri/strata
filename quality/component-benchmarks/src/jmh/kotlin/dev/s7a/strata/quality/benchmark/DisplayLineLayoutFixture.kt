package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Case
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Operation
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Shape
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Consumer as LineConsumer

/**
 * One real retained public consumer with prepared immutable source, geometry, policies and resources.
 * Every changed score performs two restoring mutations and two frames; clean reuse performs one frame.
 * Reference rows, reflection, pixels, traces and diagnostics are invoked only by explicit untimed verification.
 */
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal class DisplayLineLayoutFixture(
    private val workload: Case,
) : AutoCloseable {
    private val assets = TextLineLayoutAssets(workload.shape.metrics)
    private val firstValue = value()
    private val secondValue = if (firstValue.isEmpty()) "Z" else firstValue.substring(0, firstValue.offsetByCodePoints(firstValue.length, -1)) + "Z"
    private val values = listOf(firstValue, secondValue)
    private val fontSplits = values.map { value -> value.offsetByCodePoints(0, value.codePointCount(0, value.length) / 2) }
    private val texts = values.map(::text)
    private val states = List(2) { TextAreaState(firstValue, maxLength = maxOf(firstValue.length, secondValue.length) + 16) }
    private val selected = mutableStateOf(Side.First)
    private val content = mutableStateOf(Side.First)
    private val firstSize = IntSize(workload.viewport.width, workload.viewport.height)
    private val secondSize =
        if (workload.operation === Operation.Height) {
            IntSize(workload.viewport.width, workload.viewport.height + 11)
        } else {
            IntSize(workload.viewport.width + 3, workload.viewport.height)
        }
    private val size = mutableStateOf(firstSize)
    private val keys = Side.entries.map(::ElementKey)
    private val time = FrameTime(0)
    private val policy = workload.policy
    private val accessConsumer = if (workload.consumer === Consumer.TextArea) LineConsumer.TextArea else LineConsumer.MultilineText
    private var closed = false
    private val host =
        createMinecraftUiHost(
            UiDefinition("Bounded display line ranges") {
                val index = selected.value.ordinal
                when (workload.consumer) {
                    Consumer.Display -> Text(texts[content.value.ordinal], policy, style = TextStyle.TextField, key = keys[index])
                    Consumer.SingleLine -> Text(texts[content.value.ordinal], style = TextStyle.TextField, key = keys[index])
                    Consumer.TextArea -> TextArea(states[index], TextAreaViewport.Size(size.value), wrap = workload.policy.wrap, lineSpacing = 2, modifier = Modifier.Empty.initialFocus(), key = keys[index])
                }
            },
            assets.profile,
            fontBackend = MinecraftFontBackendFactory { assets.backend() },
        )

    init {
        try {
            host.attach()
            frame()
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
     * Includes each complete declared cycle without oracle preparation or allocation hidden at invocation setup.
     */
    internal fun operation(): RuntimeUiFrame {
        if (workload.operation === Operation.Clean) return frame()
        mutate()
        frame()
        mutate()
        return frame()
    }

    /**
     * Requires independent complete-layout output, declared mutation/reuse, observed phases, old snapshots and release.
     * Printed range work belongs to a separate private-helper probe, not an instrumented timed host callback.
     */
    internal fun verify() {
        verifyCurrent()
        val before = frame()
        val commands = before.drawCommands.toList()
        val semantics = before.semantics.toList()
        val beforePixels = pixels(before)
        val owner = if (workload.consumer === Consumer.SingleLine) null else owner()
        val oldLayout = owner?.let { currentLayout() }
        val snapshot = oldLayout?.let(TextLineLayoutAccess::snapshot)
        val calls = assets.glyphCalls
        val focus = host.textInputFocus
        host.startRenderMonitoring(16).use { monitor ->
            val after = operation()
            val afterCalls = assets.glyphCalls
            verifyCurrent()
            check(after.drawCommands == commands)
            check(after.semantics == semantics)
            check(pixels(after).contentEquals(beforePixels))
            val interval = monitor.snapshot()
            check(interval.overflowed.not())
            if (workload.operation === Operation.Clean) {
                check(after === before)
                check(afterCalls == calls)
                check(host.textInputFocus === focus)
                check(interval.nodes.all { node -> node.counts.values.all { count -> count == 0L } })
                if (oldLayout != null) check(currentLayout() === oldLayout)
            } else {
                check(interval.nodes.sumOf { node -> node.counts[UiRenderMetric.Measure] ?: 0L } != 0L)
                if (owner != null) {
                    check(currentLayout() !== oldLayout)
                    if (workload.operation === Operation.Initial) {
                        check(owner() !== owner)
                        TextLineLayoutAccess.verifyReleased(owner, accessConsumer)
                    } else {
                        check(owner() === owner)
                    }
                }
            }
            if (oldLayout != null && snapshot != null) TextLineLayoutAccess.verifySnapshot(oldLayout, snapshot)
            check(before.drawCommands == commands && before.semantics == semantics)
            check(pixels(before).contentEquals(beforePixels))
            println("DisplayLineLayout " + workload.name + ": measure=" + interval.nodes.sumOf { node -> node.counts[UiRenderMetric.Measure] ?: 0L } + " scalars=" + firstValue.codePointCount(0, firstValue.length))
        }
        val clean = frame()
        val layout = if (workload.consumer === Consumer.SingleLine) null else currentLayout()
        val cleanCalls = assets.glyphCalls
        repeat(100) {
            check(frame() === clean)
            if (layout != null) check(currentLayout() === layout)
        }
        check(assets.glyphCalls == cleanCalls)
    }

    private fun mutate() {
        when (workload.operation) {
            Operation.Initial -> {
                val next = opposite(selected.value)
                states[next.ordinal].value = values[content.value.ordinal]
                selected.value = next
            }
            Operation.Edit -> {
                val next = opposite(content.value)
                states[selected.value.ordinal].value = values[next.ordinal]
                content.value = next
            }
            Operation.Reflow, Operation.Height -> {
                size.value = if (size.value === firstSize) secondSize else firstSize
            }
            Operation.Clean -> Unit
        }
    }

    private fun verifyCurrent() {
        val value = values[content.value.ordinal]
        val reference = DisplayLineLayoutReference(workload, value, assets, size.value) { offset -> fontAt(content.value, offset) }
        if (workload.consumer !== Consumer.SingleLine) {
            val layout = currentLayout()
            reference.verify(layout)
            val ranges = reference.verifyRangeWork(owner(), layout)
            println("DisplayLineLayout " + workload.name + ": private-helper-ranges=" + ranges)
        }
        reference.verifyPixels(frame())
        val role = if (workload.consumer === Consumer.TextArea) SemanticsRole.TextArea else SemanticsRole.Text
        val semantics = frame().semantics.single { entry -> entry.semantics.role === role }.semantics
        if (workload.consumer === Consumer.TextArea) {
            check(semantics.value == UiText.Literal(value))
            check(host.textInputFocus != null)
        } else {
            check(semantics.label == texts[content.value.ordinal])
            check(host.textInputFocus == null)
        }
    }

    private fun value(): String =
        when (workload.shape) {
            Shape.Empty -> ""
            Shape.Short -> "A🙂BZ"
            Shape.Long -> "A".repeat(10_000)
            Shape.Word -> "AB🙂CD ".repeat(1666) + "ABCD"
            Shape.Supplementary -> "🙂".repeat(10_000)
            Shape.HardBreaks -> "AB🙂\r\n".repeat(2000)
            Shape.MandatoryBreaks -> "A\r\nB\nC\rD\u000BE\u000CF\u0085G\u2028H\u2029".repeat(600)
            Shape.MixedFonts -> "AB🙂C ".repeat(2000)
            Shape.DisplayOrder -> "אב🙂CD ".repeat(1666) + "אבCD"
            Shape.Signed, Shape.Zero -> "AB".repeat(5000)
            Shape.Exceptional -> "AB🙂CDZ"
        }

    private fun text(value: String): UiText {
        if (workload.shape !== Shape.MixedFonts) return UiText.Literal(value)
        val split = value.offsetByCodePoints(0, value.codePointCount(0, value.length) / 2)
        return UiText.concat(UiText.Literal(value.substring(0, split)).withFont(assets.defaultFont), UiText.Literal(value.substring(split)).withFont(assets.alternateFont))
    }

    private fun fontAt(side: Side, offset: Int): ResourceId =
        if (workload.shape === Shape.MixedFonts && fontSplits[side.ordinal] <= offset) assets.alternateFont else assets.defaultFont

    private fun owner(): Any = TextLineLayoutAccess.owner(host, accessConsumer)

    private fun currentLayout(): Any = TextLineLayoutAccess.layout(owner(), accessConsumer)

    private fun frame(): RuntimeUiFrame = host.frame(size.value, time)

    private fun pixels(frame: RuntimeUiFrame): IntArray {
        val window = IntSize(minOf(64, size.value.width).coerceAtLeast(1), minOf(64, size.value.height).coerceAtLeast(1))
        return rasterizeHeadless(frame.drawCommands, window).copyArgb()
    }

    private fun opposite(side: Side): Side = if (side === Side.First) Side.Second else Side.First

    /**
     * Releases the current layout and borrowed font inputs, preserving detached old frames while the fixture is reachable.
     */
    override fun close() {
        if (closed) return
        closed = true
        val current = if (workload.consumer === Consumer.SingleLine) null else owner()
        val oldLayout = current?.let { currentLayout() }
        val snapshot = oldLayout?.let(TextLineLayoutAccess::snapshot)
        val before = frame()
        val commands = before.drawCommands.toList()
        try {
            host.close()
        } finally {
            check(assets.backends == 0 && assets.faces == 0)
        }
        if (current != null) TextLineLayoutAccess.verifyReleased(current, accessConsumer)
        if (oldLayout != null && snapshot != null) TextLineLayoutAccess.verifySnapshot(oldLayout, snapshot)
        check(before.drawCommands == commands)
        check(host.textInputFocus == null)
        states.forEach { state -> state.observe { }.close() }
    }

    /**
     * Two prepared identities and values; no string or numeric domain discriminator enters runtime dispatch.
     */
    private enum class Side {
        First,
        Second,
    }
}

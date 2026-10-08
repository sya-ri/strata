@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition

/**
 * Owns a real public Minecraft-profile host and independent sibling source for one immutable matrix tuple.
 * The existing core session is borrowed once through the verified private host field outside timing to separate its public declaration-cutoff operation without adding production API.
 * Reflection does not replace the evaluator, runtime, state, assets or measured methods, and the host remains the sole terminal owner.
 */
internal class StatefulControlFixture(
    private val count: Int,
    private val kind: StatefulControlBenchmark.Kind,
    private val labels: StatefulControlBenchmark.Labels,
    choices: StatefulControlBenchmark.Choices,
    private val change: StatefulControlBenchmark.Change,
) : AutoCloseable {
    init {
        require(count in setOf(1, 64, 512))
    }

    private val revision = mutableStateOf(0)
    private val status = StressStateSource(0)
    private val checkbox = List(count) { CheckboxState() }
    private val slider = List(count) { SliderState(0.0) }
    private val cycle = List(count) { CycleButtonState((0 until choices.size).toList()) }
    private val statuses = listOf(0xFF707080.toInt(), 0xFF808090.toInt()).map { StatefulControlProfile.image(150, 1, it) }
    private val viewport = IntSize(150, count * 20 + 1)
    private var cachedControls: List<Element>? = null
    private var current = 0
    private val host =
        createMinecraftUiHost(
            UiDefinition("stateful-control fixture") {
                val version = revision.value
                Column {
                    for (index in 0 until count) {
                        val retained = cachedControls
                        if (change == StatefulControlBenchmark.Change.Reuse && retained != null) {
                            element(retained[index])
                        } else {
                            control(index, version)
                        }
                    }
                    Observe(status) { value -> Image(statuses[value % 2]) }
                }
            },
            StatefulControlProfile.create(),
        )
    private val session: RuntimeUiSession

    init {
        session =
            try {
                val field = host.javaClass.getDeclaredField("session")
                field.isAccessible = true
                field.get(host) as RuntimeUiSession
            } catch (failure: Throwable) {
                runCatching { host.close() }.exceptionOrNull()?.let { cleanup -> if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        try {
            host.attach()
            host.frame(viewport)
            val controls = session.projectDeclarations { it.children.take(count).map { child -> child.element } }
            check(controls.size == count)
            cachedControls = controls
            verifyFrame(host.frame(viewport))
        } catch (failure: Throwable) {
            runCatching { host.close() }.exceptionOrNull()?.let { cleanup -> if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /**
     * Applies the fixed source action and commits the actual declaration cutoff without painting.
     */
    fun nextDeclaration(): RuntimeDeclaration {
        advance()
        return session.projectDeclarations { it }
    }

    /**
     * Applies the same source action and commits the complete actual public-host frame.
     */
    fun nextFrame(): RuntimeUiFrame {
        advance()
        return host.frame(viewport)
    }

    /**
     * Checks independently calculated pixels and semantics, exact reuse controls, work bounds and old-frame immutability.
     * Historical extra control painting is admitted only here; the runtime tests require candidate paint elimination.
     */
    fun verifyWork() {
        val original = host.frame(viewport)
        val commands = original.drawCommands.toList()
        val semantics = original.semantics.toList()
        val monitor = host.startRenderMonitoring()
        try {
            repeat(2) {
                val before = monitor.snapshot().counts
                val frame = nextFrame()
                verifyFrame(frame)
                val work = monitor.snapshot().counts
                check(work.getValue(UiRenderMetric.NodeCreate) == before.getValue(UiRenderMetric.NodeCreate))
                check(work.getValue(UiRenderMetric.Measure) == before.getValue(UiRenderMetric.Measure))
                val paints = work.getValue(UiRenderMetric.Paint) - before.getValue(UiRenderMetric.Paint)
                val required = requiredPaints()
                val historical = if (change == StatefulControlBenchmark.Change.Clean) 0L else controlCount().toLong() + 1L
                check(paints == required || paints == historical) { "$count/$kind/$labels/$change: paint $paints, expected $required or historical $historical" }
                if (change == StatefulControlBenchmark.Change.Clean) check(frame === original)
                if (change in setOf(StatefulControlBenchmark.Change.Fresh, StatefulControlBenchmark.Change.Reuse, StatefulControlBenchmark.Change.Clean)) {
                    val rowCommands = frame.drawCommands.filter { command -> (command as? DrawCommand.SampledImage)?.destination?.top != (count * 20).toFloat() }
                    val originalRows = commands.filter { command -> (command as? DrawCommand.SampledImage)?.destination?.top != (count * 20).toFloat() }
                    check(rowCommands == originalRows)
                }
            }
            val beforeProjection = monitor.snapshot().counts
            val declaration = nextDeclaration()
            check(declaration.children.size == count + 1)
            val projected = monitor.snapshot().counts
            check(projected.getValue(UiRenderMetric.Paint) == beforeProjection.getValue(UiRenderMetric.Paint))
            check(projected.getValue(UiRenderMetric.Measure) == beforeProjection.getValue(UiRenderMetric.Measure))
            verifyFrame(host.frame(viewport))
        } finally {
            monitor.close()
        }
        check(original.drawCommands == commands && original.semantics == semantics)
        val committed = host.frame(viewport)
        host.detach()
        host.attach()
        val reattached = host.frame(viewport)
        check(committed.drawCommands == reattached.drawCommands && committed.semantics == reattached.semantics)
        verifyFrame(reattached)
        check(status.subscriptions == 1)
    }

    private fun advance() {
        if (change == StatefulControlBenchmark.Change.Clean) return
        current += 1
        revision.value = current
        status.publish(current)
    }

    private fun UiScope.control(
        index: Int,
        version: Int,
    ) {
        val key = ElementKey(index)
        val text = label(index, version)
        when (rowKind(index)) {
            StatefulControlBenchmark.Kind.Checkbox -> Checkbox(text, checkbox[index], key = key)
            StatefulControlBenchmark.Kind.Slider -> Slider(text, slider[index], key = key)
            StatefulControlBenchmark.Kind.Cycle -> CycleButton(cycle[index], key = key) { value -> UiText.Literal(text + (0x21 + value).toChar()) }
            StatefulControlBenchmark.Kind.NoControls -> Spacer(Modifier.Empty.size(150, 20), key)
            StatefulControlBenchmark.Kind.Mixed -> error("Mixed rows must resolve to a primitive")
        }
    }

    private fun rowKind(index: Int): StatefulControlBenchmark.Kind =
        if (kind == StatefulControlBenchmark.Kind.Mixed) {
            listOf(StatefulControlBenchmark.Kind.Checkbox, StatefulControlBenchmark.Kind.Slider, StatefulControlBenchmark.Kind.Cycle)[index % 3]
        } else {
            kind
        }

    private fun label(
        index: Int,
        version: Int,
    ): String {
        val changes = change == StatefulControlBenchmark.Change.AllLabels || (change == StatefulControlBenchmark.Change.OneLabel && index == 0)
        return (if (changes && version % 2 == 1) "B" else "A").repeat(labels.length)
    }

    private fun controlCount(): Int = if (kind == StatefulControlBenchmark.Kind.NoControls) 0 else count

    private fun requiredPaints(): Long =
        when (change) {
            StatefulControlBenchmark.Change.Clean -> 0L
            StatefulControlBenchmark.Change.OneLabel -> if (controlCount() == 0) 1L else 2L
            StatefulControlBenchmark.Change.AllLabels -> controlCount().toLong() + 1L
            else -> 1L
        }

    private fun verifyFrame(frame: RuntimeUiFrame) {
        check(frame.size == viewport)
        check(frame.semantics.size == controlCount())
        val expected = IntArray(viewport.width * viewport.height)
        for (index in 0 until count) {
            val row = rowKind(index)
            if (row == StatefulControlBenchmark.Kind.NoControls) continue
            val top = index * 20
            val checkboxRow = row == StatefulControlBenchmark.Kind.Checkbox
            val width = if (checkboxRow) 20 else 150
            for (y in top until top + 20) {
                for (x in 0 until width) expected[y * 150 + x] = 0xFF102030.toInt()
            }
            if (row == StatefulControlBenchmark.Kind.Slider) {
                for (y in top until top + 20) {
                    for (x in 0 until 8) expected[y * 150 + x] = 0xFF405060.toInt()
                }
            }
            val text = label(index, current) + if (row == StatefulControlBenchmark.Kind.Cycle) "!" else ""
            val left = if (checkboxRow) 24 else (150 - text.length * 2) / 2
            val textTop = top + if (checkboxRow) 5 else 6
            for (glyph in text.indices) {
                expected[(textTop + 1) * 150 + left + glyph * 2 + 1] = 0xFF3F3F3F.toInt()
                expected[textTop * 150 + left + glyph * 2] = -1
            }
            val semantic = frame.semantics[index]
            check(semantic.bounds == IntRect(0, top, 150, top + 20))
            check(semantic.semantics.label == UiText.Literal(text))
            check(semantic.semantics.disabled.not())
            val role =
                when (row) {
                    StatefulControlBenchmark.Kind.Checkbox -> SemanticsRole.Checkbox
                    StatefulControlBenchmark.Kind.Slider -> SemanticsRole.Slider
                    StatefulControlBenchmark.Kind.Cycle -> SemanticsRole.CycleButton
                    else -> error("Missing primitive role")
                }
            check(semantic.semantics.role == role)
            if (checkboxRow) check(semantic.semantics.checked == false)
            if (row == StatefulControlBenchmark.Kind.Slider) check(semantic.semantics.value == UiText.Literal("0.0"))
            if (row == StatefulControlBenchmark.Kind.Cycle) check(semantic.semantics.value == UiText.Literal(text))
        }
        val statusColor = if (current % 2 == 0) 0xFF707080.toInt() else 0xFF808090.toInt()
        for (x in 0 until 150) expected[count * 20 * 150 + x] = statusColor
        val actual = rasterizeHeadless(frame.drawCommands, viewport).copyArgb()
        check(expected.contentEquals(actual)) { "$count/$kind/$labels/$change: independent original-asset pixels differ" }
    }

    override fun close() {
        cachedControls = null
        host.close()
        check(status.subscriptions == 0)
        host.close()
    }
}

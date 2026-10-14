package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.ComponentObserverBenchmark.Scenario
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Prepares public component state and optional retained controls on one execution owner.
 * Assertions and independent reference hosts run outside the sampled operation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ComponentObserverFixture(
    private val observers: Int,
    private val scenario: Scenario,
) : AutoCloseable {
    private val state = CheckboxState()
    private val callbacks: List<(Boolean) -> Unit> = List(observers) { index -> { _ -> notifications += index + 1L } }
    private val extra: (Boolean) -> Unit = { _ -> notifications += 1L }
    private val handles: List<ComponentStateSubscription>
    private val profile: MinecraftUiProfile?
    private val host: MinecraftUiHost?
    private var lastFrame: RuntimeUiFrame? = null
    private var closed = false
    private val viewport = IntSize(100, 20)
    private val time = FrameTime(0)

    /**
     * Weighted callback checksum supplements the independent ordered publication controls.
     */
    var notifications: Long = 0
        private set

    /**
     * Current public value, inspected only outside operation collection.
     */
    val checked: Boolean get() = state.checked

    init {
        val frame = scenario in setOf(Scenario.ChangedFrame, Scenario.CleanFrame)
        handles = if (frame || scenario == Scenario.FirstWrite) emptyList() else callbacks.map(state::observe)
        profile = if (frame) ComponentProfile.create(null) else null
        host = profile?.let { createHost(state, it) }
        host?.attach()
        lastFrame = host?.frame(viewport, time)
        if (scenario == Scenario.Changed64) {
            state.toggle()
            state.toggle()
            notifications = 0
        }
    }

    /**
     * Executes every write, callback and specified registration/release or retained frame inside the boundary.
     */
    fun execute(): Long {
        when (scenario) {
            Scenario.Changed64 -> {
                repeat(64) { state.toggle() }
            }

            Scenario.Equal64 -> {
                repeat(64) { state.checked = state.checked }
            }

            Scenario.FirstWrite -> {
                val current = callbacks.map(state::observe)
                state.toggle()
                current.forEach(ComponentStateSubscription::close)
            }

            Scenario.Churn64 -> {
                repeat(64) {
                    val handle = state.observe(extra)
                    state.toggle()
                    handle.close()
                }
            }

            Scenario.ChangedFrame -> {
                state.toggle()
                lastFrame = checkNotNull(host).frame(viewport, time)
            }

            Scenario.CleanFrame -> {
                lastFrame = checkNotNull(host).frame(viewport, time)
            }
        }
        return notifications
    }

    /**
     * Checks the expected callback checksum and final value outside collection; common controls independently verify exact membership and order.
     */
    fun verifyOperation(
        before: Long,
        checked: Boolean,
    ) {
        val weight = observers.toLong() * (observers + 1) / 2
        val expected =
            when (scenario) {
                Scenario.Changed64 -> weight * 64
                Scenario.Equal64, Scenario.ChangedFrame, Scenario.CleanFrame -> 0
                Scenario.FirstWrite -> weight
                Scenario.Churn64 -> (weight + 1) * 64
            }
        check(notifications - before == expected)
        val changed = scenario in setOf(Scenario.FirstWrite, Scenario.ChangedFrame)
        check(state.checked == if (changed) checked.not() else checked)
    }

    /**
     * Compares complete retained output with a separately constructed state and fresh independent host.
     */
    fun verifyFrame() {
        val actual = lastFrame ?: return
        check(actual.semantics.size == observers)
        check(actual.semantics.all { it.semantics.checked == state.checked })
        createHost(CheckboxState(state.checked), checkNotNull(profile)).use { reference ->
            reference.attach()
            val expected = reference.frame(viewport, time)
            check(actual.size == expected.size)
            check(actual.drawCommands == expected.drawCommands)
            check(actual.semantics.map { it.semantics } == expected.semantics.map { it.semantics })
            check(rasterizeHeadless(actual.drawCommands, viewport).copyArgb().contentEquals(rasterizeHeadless(expected.drawCommands, viewport).copyArgb()))
        }
    }

    /**
     * Proves a still-live state no longer notifies this fixture after its resources close.
     */
    fun verifyRelease() {
        check(closed)
        val before = notifications
        state.toggle()
        check(notifications == before)
    }

    private fun createHost(
        selected: CheckboxState,
        assets: MinecraftUiProfile,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Component observer fixture") {
                Stack { repeat(observers) { Checkbox("", selected, width = 80) } }
            },
            assets,
        )

    override fun close() {
        if (closed) return
        closed = true
        try {
            host?.close()
        } finally {
            handles.forEach(ComponentStateSubscription::close)
        }
    }
}

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.remote.RemoteValueCodec
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Untimed completeness, deterministic byte/schema parity and retained release for every field-snapshot fixture family.
 * This verifier reports work and ownership only; it does not estimate elapsed time or allocated bytes.
 */
internal object ProjectionFieldWorkEvidence {
    /**
     * Admits all compiled 73 rows, including the direct sequence, pointer, empty/singleton and owner controls.
     */
    fun verify() {
        check(JmhWorkloadInventory.capture(listOf(ProjectionFieldSnapshotBenchmark::class.java), setOf("avgt")).size == 73)
        val benchmark = ProjectionFieldSnapshotBenchmark()
        val codec = RemoteValueCodec()
        listOf(0, 1, 2, 4, 8, 128).forEach { count ->
            val state = ProjectionFieldSnapshotBenchmark.Fields()
            state.size = count
            state.setup()
            val expected = ProjectionValue.Sequence(List(count) { ProjectionValue.Integer(it.toLong()) })
            check(benchmark.properties(state) == expected && benchmark.directSequence(state) == expected)
            check(codec.encode(benchmark.properties(state)).contentEquals(codec.encode(expected)))
        }
        ProjectionFieldInputCase.entries.forEach { input ->
            val prepared = input.prepare()
            val value = prepared.encode()
            check(prepared.decode(value) == prepared.expected)
            check(codec.decode(codec.encode(value)) == value)
            check(codec.encode(prepared.encode()).contentEquals(codec.encode(value)))
        }
        ProjectionFieldModifierCase.entries.forEach { selected ->
            val state = ProjectionFieldSnapshotBenchmark.Modifiers()
            state.workload = selected.name
            state.setup()
            check(benchmark.modifierPolicy(state).elements().size == 1)
            val old = benchmark.modifierEndpoints(state)
            val fresh = benchmark.modifierEndpoints(state)
            check(old == fresh && old.hashCode() == fresh.hashCode())
            val fields = (old as ProjectionValue.Sequence).values
            val endpoints = if (selected == ProjectionFieldModifierCase.Capture) 2 else 1
            check(fields.size == 1 + endpoints)
            check(fields.takeLast(endpoints) == List(endpoints) { ProjectionValue.Integer(it + 1L) })
            check(codec.encode(old).contentEquals(codec.encode(fresh)))
        }
        val profileSizes =
            mapOf(
                ProjectionFieldProfileCase.MenuBackground to 0,
                ProjectionFieldProfileCase.LoadingIndicator to 1,
                ProjectionFieldProfileCase.ProgressBar to 2,
                ProjectionFieldProfileCase.Button to 3,
                ProjectionFieldProfileCase.Text to 3,
                ProjectionFieldProfileCase.TextArea to 10,
            )
        ProjectionFieldProfileCase.entries.forEach { selected ->
            val state = ProjectionFieldSnapshotBenchmark.Profiles()
            state.workload = selected.name
            state.setup()
            val old = benchmark.profileProjection(state)
            val complete = benchmark.profileEvaluation(state)
            check(old == complete)
            check((old as ProjectionValue.Sequence).values.size == profileSizes.getValue(selected))
            check(codec.encode(old).contentEquals(codec.encode(complete)))
            check(codec.decode(codec.encode(old)) == old)
        }
        verifyOwners()
    }

    private fun verifyOwners() {
        val inputs = ProjectionFieldOwner.Inputs()
        val owner = ProjectionFieldOwner(inputs)
        try {
            owner.verifyOpen()
            val initial = checkNotNull(inputs.latest) as RemoteMessage.Snapshot
            val hash = initial.tree.hashCode()
            val emissions = inputs.emissions
            check(owner.idle() == 2 && inputs.emissions == emissions)
            repeat(8) {
                check(owner.update() == 2)
                owner.verifyOpen()
                check(inputs.latest is RemoteMessage.Update)
            }
            check(initial.tree.hashCode() == hash)
            check(owner.control() == 2)
            owner.verifyOpen()
        } finally {
            owner.close()
        }
        owner.verifyClosed()
        repeat(8) {
            val lifecycle = ProjectionFieldOwner(inputs)
            lifecycle.use {
                check(it.update() == 2)
                it.verifyOpen()
            }
            lifecycle.verifyClosed()
        }
        val failingInputs = ProjectionFieldOwner.Inputs()
        val failure = IllegalStateException("Failed snapshot transport")
        val failing = RemoteServerSession(1, ProjectionValue.Absent, failingInputs.types, send = { throw failure }, content = failingInputs.content)
        try {
            check(runCatching(failing::tick).exceptionOrNull() === failure)
            check(failing.status is RemoteSessionStatus.Closed && failing.nodeCount == 0)
            check(failingInputs.source.subscriptions == 0)
        } finally {
            failing.close()
        }
    }
}

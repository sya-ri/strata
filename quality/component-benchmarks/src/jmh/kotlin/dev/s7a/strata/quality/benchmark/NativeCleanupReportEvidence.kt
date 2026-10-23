package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Runs the real cleanup report or injects one callback failure in a fresh JVM with its one terminal registry lifetime.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object NativeCleanupReportEvidence {
    private enum class Scenario { Success, Registry, Pending, Settled }

    /**
     * Checks complete reports, original admission work and terminal release without substituting a report adapter.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val scenario = Scenario.valueOf(args.single())
        if (scenario == Scenario.Success) verifyReports() else verifyFailure(scenario)
        check(NativeCanvasDevices.retainedTargetCount() == 0)
        check(NativeCanvasDevices.retainedGuiResourceSetCount() == 0)
        check(NativeCanvasDevices.retainedManagedGuiResourceCount() == 0)
    }

    private fun verifyReports() {
        val reports =
            NativeCleanupOperationReport.collectVerified(PerformancePlan(warmup = 0, samples = 2)) {
                error("The real native verifier must run inside collection's one registry lifetime")
            }
        val expected =
            setOf(
                "registryPoll-0",
                "registryPoll-1",
                "registryPoll-32",
                "registryConsumed-0",
                "registryConsumed-1",
                "registryConsumed-32",
                "pendingFencePoll-1",
                "pendingFencePoll-32",
                "settledFenceCycle-1",
                "settledFenceCycle-32",
            )
        check(reports.keys == expected)
        reports.values.forEach { report ->
            check(report.get("samples").asInt == 2)
            val wall = report.getAsJsonObject("wall_distribution")
            check(wall.get("samples").asInt == 2 && wall.getAsJsonArray("raw").size() == 2)
        }
    }

    private fun verifyFailure(scenario: Scenario) {
        val failure = IllegalStateException("Injected real cleanup report callback failure")
        val calls = mutableListOf<Pair<Scenario, Int>>()
        var registryOwner: NativeCleanupBenchmark.Registry? = null
        var pendingOwner: NativeCleanupBenchmark.Pending? = null
        var settledOwner: NativeCleanupBenchmark.Settled? = null
        val result =
            runCatching {
                NativeCleanupBenchmark.withVerifiedOwners(
                    registry = { owner ->
                        registryOwner = owner
                        val prior = if (owner.owners == 32) 1 else 0
                        check(owner.observedWork.polls == (owner.owners + prior) * 256L)
                        check(owner.observedWork.consumptions == (owner.owners + prior) * 128L)
                        calls.add(Scenario.Registry to owner.owners)
                        failSelectedCallback(scenario, Scenario.Registry, owner.owners, failure)
                    },
                    pending = { owner ->
                        pendingOwner = owner
                        check(owner.observedWork.queries == owner.owners.toLong() * (128 + owner.owners)) { "Pending admission: ${owner.observedWork}" }
                        calls.add(Scenario.Pending to owner.owners)
                        failSelectedCallback(scenario, Scenario.Pending, owner.owners, failure)
                    },
                    settled = { owner ->
                        settledOwner = owner
                        check(owner.observedWork.resourceCloses == owner.owners * 128L)
                        calls.add(Scenario.Settled to owner.owners)
                        failSelectedCallback(scenario, Scenario.Settled, owner.owners, failure)
                    },
                )
            }
        result.exceptionOrNull()?.let { if (it !== failure) throw it }
        check(result.exceptionOrNull() === failure)
        val complete =
            listOf(
                Scenario.Registry to 0,
                Scenario.Registry to 1,
                Scenario.Registry to 32,
                Scenario.Pending to 1,
                Scenario.Settled to 1,
                Scenario.Pending to 32,
                Scenario.Settled to 32,
            )
        check(calls == complete.take(complete.indexOf(scenario to 32) + 1))
        verifyReleased(checkNotNull(registryOwner), pendingOwner, settledOwner)
    }

    private fun failSelectedCallback(
        scenario: Scenario,
        boundary: Scenario,
        owners: Int,
        failure: Throwable,
    ) {
        if (scenario == boundary && owners == 32) throw failure
    }

    private fun verifyReleased(
        registryOwner: NativeCleanupBenchmark.Registry,
        pendingOwner: NativeCleanupBenchmark.Pending?,
        settledOwner: NativeCleanupBenchmark.Settled?,
    ) {
        val registry = registryOwner.observedWork
        check(registry.finishes == 32L && registry.drains == 32L && registry.closes == 32L && registry.acknowledgements == 32L)
        pendingOwner?.let { owner ->
            check(owner.observedWork.finishes == 1 && owner.observedWork.drains == 1)
            check(owner.observedWork.resourceCloses == owner.owners.toLong())
        }
        settledOwner?.let { owner -> check(owner.observedWork.finishes == 1 && owner.observedWork.drains == 1 && owner.retainedSets == 0) }
    }
}

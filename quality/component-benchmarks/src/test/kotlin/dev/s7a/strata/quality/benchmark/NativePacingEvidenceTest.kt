package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertFails

/**
 * Detached admission inputs contain no timings and cannot serve as actual collected native evidence.
 */
internal class NativePacingEvidenceTest {
    @Test
    internal fun missingRestorationModeOrBoundaryObservationsRejectSuccess() {
        NativePacingEvidence.verify(complete())
        listOf("inactivity_mode", "borrowed_options_restored").forEach { field ->
            val report = complete()
            report.remove(field)
            assertFails { NativePacingEvidence.verify(report) }
        }
        val mutations: List<(JsonObject) -> Unit> =
            listOf(
                { it.addProperty("borrowed_options_restored", false) },
                { it.addProperty("borrowed_options_restored", "true") },
                { it.addProperty("inactivity_mode", "unknown") },
                { phase(it).remove("pacing") },
                { pacing(it).remove("boundaries") },
                { pacing(it).remove("sample_boundaries") },
                { pacing(it).addProperty("sample_boundaries", 2) },
                { pacing(it).getAsJsonArray("boundaries").remove(0) },
                { pacing(it).getAsJsonArray("boundaries").add(boundary()) },
                { pacing(it).addProperty("scope", "") },
            )
        mutations.forEach { mutate ->
            val report = complete()
            mutate(report)
            assertFails { NativePacingEvidence.verify(report) }
        }
    }

    @Test
    internal fun everyLiveBoundaryRequiresTypedSafeAndConsistentConditions() {
        listOf("inactivity", "reason", "reason_available", "selected_limit", "applied_limit", "applied_limit_source", "iconified").forEach { field ->
            val report = complete()
            observation(report).remove(field)
            assertFails { NativePacingEvidence.verify(report) }
        }
        val mutations: List<(JsonObject) -> Unit> =
            listOf(
                { it.addProperty("iconified", true) },
                { it.addProperty("reason", Reason.WINDOW_ICONIFIED.name) },
                { it.addProperty("reason", Reason.SHORT_AFK.name) },
                { it.addProperty("reason", Reason.LONG_AFK.name) },
                { it.addProperty("inactivity", Inactivity.AFK.name) },
                { it.addProperty("reason", "unknown") },
                { it.addProperty("reason_available", false) },
                { it.addProperty("applied_limit_source", "unknown") },
                { it.addProperty("selected_limit", 0) },
                { it.addProperty("selected_limit", 121) },
                { it.addProperty("selected_limit", 60.5) },
                { it.addProperty("selected_limit", "60") },
                { it.addProperty("applied_limit", 120) },
                { it.addProperty("applied_limit", -1) },
                { it.addProperty("reason", Reason.NONE.name) },
                { it.addProperty("applied_limit_source", AppliedLimitSource.DIRECT_SELECTOR.name) },
            )
        mutations.forEach { mutate ->
            val report = complete()
            mutate(observation(report))
            assertFails { NativePacingEvidence.verify(report) }
        }
    }

    @Test
    internal fun legacySelectorsExposeUnavailableReasonsAndNoIndependentAppliedState() {
        val legacy = complete(Inactivity.UNAVAILABLE, Reason.UNAVAILABLE, AppliedLimitSource.DIRECT_SELECTOR)
        NativePacingEvidence.verify(legacy)
        val unavailableReason = complete(Inactivity.MINIMIZED, Reason.UNAVAILABLE, AppliedLimitSource.DIRECT_SELECTOR)
        NativePacingEvidence.verify(unavailableReason)
        val mutations: List<(JsonObject) -> Unit> =
            listOf(
                { it.addProperty("inactivity_mode", Inactivity.AFK.name) },
                { observation(it).addProperty("reason", Reason.NONE.name) },
                { observation(it).addProperty("reason_available", true) },
                { observation(it).addProperty("applied_limit_source", AppliedLimitSource.GAME_RENDER_STATE.name) },
            )
        mutations.forEach { mutate ->
            val report = legacy.deepCopy()
            mutate(report)
            assertFails { NativePacingEvidence.verify(report) }
        }
    }

    @Test
    internal fun explicitAfkDiagnosticsPreserveActualCapTransitionsWithoutCertifyingFormalStability() {
        val report = complete(Inactivity.AFK)
        report.addProperty("inactivity_mode", Inactivity.AFK.name)
        observation(report).apply {
            addProperty("reason", Reason.SHORT_AFK.name)
            addProperty("selected_limit", 30)
            addProperty("applied_limit", 60)
        }
        NativePacingEvidence.verify(report)
        report.addProperty("inactivity_mode", Inactivity.MINIMIZED.name)
        assertFails { NativePacingEvidence.verify(report) }
        report.addProperty("inactivity_mode", Inactivity.AFK.name)
        observation(report).addProperty("reason", Reason.WINDOW_ICONIFIED.name)
        assertFails { NativePacingEvidence.verify(report) }
    }

    private fun complete(
        mode: Inactivity = Inactivity.MINIMIZED,
        reason: Reason = Reason.OUT_OF_LEVEL_MENU,
        source: AppliedLimitSource = AppliedLimitSource.GAME_RENDER_STATE,
    ): JsonObject =
        JsonObject().apply {
            addProperty("inactivity_mode", Inactivity.MINIMIZED.name)
            addProperty("framerate_limit", 120)
            addProperty("borrowed_options_restored", true)
            add(
                "phases",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("samples", 2)
                            add(
                                "pacing",
                                JsonObject().apply {
                                    addProperty("scope", "Detached admission observations without timing evidence")
                                    addProperty("sample_boundaries", 3)
                                    add("boundaries", JsonArray().apply { repeat(3) { add(boundary(mode, reason, source)) } })
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun boundary(
        mode: Inactivity = Inactivity.MINIMIZED,
        reason: Reason = Reason.OUT_OF_LEVEL_MENU,
        source: AppliedLimitSource = AppliedLimitSource.GAME_RENDER_STATE,
    ): JsonObject =
        JsonObject().apply {
            addProperty("inactivity", mode.name)
            addProperty("reason", reason.name)
            addProperty("reason_available", reason != Reason.UNAVAILABLE)
            addProperty("selected_limit", 60)
            addProperty("applied_limit", 60)
            addProperty("applied_limit_source", source.name)
            addProperty("iconified", false)
        }

    private fun phase(report: JsonObject): JsonObject = report.getAsJsonArray("phases")[0].asJsonObject

    private fun pacing(report: JsonObject): JsonObject = phase(report).getAsJsonObject("pacing")

    private fun observation(report: JsonObject): JsonObject = pacing(report).getAsJsonArray("boundaries")[1].asJsonObject

    private enum class Inactivity {
        MINIMIZED,
        AFK,
        UNAVAILABLE,
    }

    private enum class Reason {
        NONE,
        WINDOW_ICONIFIED,
        LONG_AFK,
        SHORT_AFK,
        OUT_OF_LEVEL_MENU,
        UNAVAILABLE,
    }

    private enum class AppliedLimitSource {
        DIRECT_SELECTOR,
        GAME_RENDER_STATE,
    }
}

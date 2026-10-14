package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject

/**
 * Admits detached pacing observations without reconstructing native policy from a numeric frame cap.
 */
internal object NativePacingEvidence {
    /**
     * Actual supported native options; unavailable selectors can only satisfy the formal default fixture.
     */
    @Suppress("unused") // Receipt-boundary decoding enumerates every serialized native option.
    private enum class Inactivity {
        MINIMIZED,
        AFK,
        UNAVAILABLE,
    }

    /**
     * Actual compiled native reasons; a missing reason is explicit rather than inferred from frame duration.
     */
    @Suppress("unused") // Receipt-boundary decoding enumerates every serialized native reason.
    private enum class Reason {
        NONE,
        WINDOW_ICONIFIED,
        LONG_AFK,
        SHORT_AFK,
        OUT_OF_LEVEL_MENU,
        UNAVAILABLE,
    }

    /**
     * The direct selector is not an independent observation of extracted game render state.
     */
    private enum class AppliedLimitSource {
        DIRECT_SELECTOR,
        GAME_RENDER_STATE,
    }

    /**
     * One immutable observation at an existing complete presentation boundary.
     */
    private data class Boundary(
        val inactivity: Inactivity,
        val reason: Reason,
        val selectedLimit: Int,
        val appliedLimit: Int,
        val appliedLimitSource: AppliedLimitSource,
        val iconified: Boolean,
    )

    /**
     * Requires restored borrowed options and exactly one live observation per existing sampled boundary.
     */
    internal fun verify(report: JsonObject) {
        val requested = enumValue<Inactivity>(report, "inactivity_mode")
        require(requested != Inactivity.UNAVAILABLE) { "Unavailable is not a borrowed fixture option" }
        require(boolean(report, "borrowed_options_restored")) { "Native options were not restored before publishing success" }
        val configuredLimit = integer(report, "framerate_limit")
        report.getAsJsonArray("phases").forEach { verifyPhase(it.asJsonObject, requested, configuredLimit) }
    }

    private fun verifyPhase(
        phase: JsonObject,
        requested: Inactivity,
        configuredLimit: Int,
    ) {
        val pacing = requireNotNull(phase.getAsJsonObject("pacing")) { "Missing live native pacing observations" }
        require(pacing.get("scope").asString.isNotBlank()) { "Missing native pacing observation scope" }
        val samples = integer(phase, "samples")
        require(0 < samples) { "Native pacing observations require sampled presentations" }
        val expectedBoundaries = Math.addExact(samples, 1)
        require(integer(pacing, "sample_boundaries") == expectedBoundaries) { "Incomplete native pacing boundary count" }
        val boundaries = requireNotNull(pacing.getAsJsonArray("boundaries")) { "Missing native pacing boundaries" }
        require(boundaries.size() == expectedBoundaries) { "Incomplete native pacing observations" }
        val observations = boundaries.map { boundary(it.asJsonObject) }
        val initial = observations.first()
        observations.forEach { verifyBoundary(it, initial, requested, configuredLimit) }
    }

    private fun verifyBoundary(
        observation: Boundary,
        initial: Boundary,
        requested: Inactivity,
        configuredLimit: Int,
    ) {
        require(observation.inactivity == initial.inactivity && observation.appliedLimitSource == initial.appliedLimitSource && (observation.reason == Reason.UNAVAILABLE) == (initial.reason == Reason.UNAVAILABLE)) {
            "Native pacing observation capabilities changed during a phase"
        }
        require(observation.iconified.not() && observation.reason != Reason.WINDOW_ICONIFIED) { "Native window was iconified or safety-throttled" }
        require(observation.selectedLimit in 1..configuredLimit && observation.appliedLimit in 1..configuredLimit) { "Invalid actual native limiter cap" }
        if (observation.inactivity == Inactivity.UNAVAILABLE) {
            require(requested == Inactivity.MINIMIZED && observation.reason == Reason.UNAVAILABLE && observation.appliedLimitSource == AppliedLimitSource.DIRECT_SELECTOR) {
                "Unavailable native pacing must retain its actual direct selector contract"
            }
        } else {
            require(observation.inactivity == requested) { "The borrowed inactivity option changed" }
        }
        if (observation.appliedLimitSource == AppliedLimitSource.GAME_RENDER_STATE) {
            require(observation.reason != Reason.UNAVAILABLE) { "Extracted native frame state requires the actual modern throttle reason" }
        }
        if (requested == Inactivity.MINIMIZED) {
            require(observation.reason != Reason.SHORT_AFK && observation.reason != Reason.LONG_AFK) { "Formal native collection was AFK-throttled" }
            require(observation.selectedLimit == observation.appliedLimit && observation == initial) { "Native pacing changed during a formal sampled interval" }
        }
    }

    /**
     * Decodes serialized native enums once at the receipt boundary and checks declared availability.
     */
    private fun boundary(value: JsonObject): Boundary {
        val reason = enumValue<Reason>(value, "reason")
        require(boolean(value, "reason_available") == (reason != Reason.UNAVAILABLE)) { "Inconsistent native throttle reason availability" }
        return Boundary(
            enumValue(value, "inactivity"),
            reason,
            integer(value, "selected_limit"),
            integer(value, "applied_limit"),
            enumValue(value, "applied_limit_source"),
            boolean(value, "iconified"),
        )
    }

    /**
     * Rejects missing, nonnumeric or fractional limits instead of silently rounding a serialized condition.
     */
    private fun integer(
        value: JsonObject,
        field: String,
    ): Int {
        val element = requireNotNull(value.get(field)) { "Missing native pacing field: $field" }
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "Invalid numeric native pacing field: $field" }
        return element.asBigDecimal.intValueExact()
    }

    /**
     * A malformed or absent boolean cannot certify a successful cleanup or safe native window.
     */
    private fun boolean(
        value: JsonObject,
        field: String,
    ): Boolean {
        val element = requireNotNull(value.get(field)) { "Missing native pacing field: $field" }
        require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) { "Invalid boolean native pacing field: $field" }
        return element.asBoolean
    }

    /**
     * Rejects unknown external values before applying any domain rules.
     */
    private inline fun <reified T : Enum<T>> enumValue(
        value: JsonObject,
        field: String,
    ): T {
        val element = requireNotNull(value.get(field)) { "Missing native pacing field: $field" }
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "Invalid native pacing enum: $field" }
        return requireNotNull(enumValues<T>().singleOrNull { it.name.contentEquals(element.asString) }) { "Unknown native pacing enum: $field" }
    }
}

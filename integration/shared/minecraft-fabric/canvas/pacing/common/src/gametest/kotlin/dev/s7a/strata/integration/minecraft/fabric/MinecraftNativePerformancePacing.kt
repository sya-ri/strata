package dev.s7a.strata.integration.minecraft.fabric

import java.util.Locale

/**
 * Detached actual limiter observations; direct selectors and extracted frame state remain distinct.
 * Transient selected/applied disagreement can delay readiness but cannot certify a stable formal interval.
 * Unavailable native reasons and options remain explicit, never inferred from numeric caps.
 */
internal data class MinecraftNativePerformancePacing(
    val inactivity: Inactivity,
    val reason: Reason,
    val selectedLimit: Int,
    val appliedLimit: Int,
    val appliedLimitSource: AppliedLimitSource,
    val iconified: Boolean,
) {
    /**
     * Actual option values; unavailable is used only where no native option exists.
     */
    enum class Inactivity {
        MINIMIZED,
        AFK,
        UNAVAILABLE,
        ;

        /**
         * Decodes external inactivity settings without inventing unavailable native options.
         */
        companion object {
            /**
             * Decodes the fixture property; only an absent value defaults to formal collection.
             */
            fun fromProperty(value: String?): Inactivity {
                if (value == null) return MINIMIZED
                return listOf(MINIMIZED, AFK).singleOrNull { it.name.lowercase(Locale.ROOT) == value }
                    ?: throw IllegalArgumentException("strata.performance.inactivity must be minimized or afk.")
            }
        }
    }

    /**
     * Compiled native reasons; legacy selectors report unavailable instead of a fabricated reason.
     */
    enum class Reason {
        NONE,
        WINDOW_ICONIFIED,
        LONG_AFK,
        SHORT_AFK,
        OUT_OF_LEVEL_MENU,
        UNAVAILABLE,
    }

    /**
     * Distinguishes the direct selector from an independently extracted limiter observation.
     */
    enum class AppliedLimitSource {
        DIRECT_SELECTOR,
        GAME_RENDER_STATE,
    }

    /**
     * Checks window safety, borrowed option and selected cap before readiness or measurement.
     */
    fun validate(requested: Inactivity = Inactivity.MINIMIZED) {
        check(iconified.not() && reason != Reason.WINDOW_ICONIFIED) { "The native performance window is iconified or safety-throttled." }
        check(inactivity != Inactivity.UNAVAILABLE || requested == Inactivity.MINIMIZED) { "This native selector has no requested inactivity option." }
        check(inactivity == requested || inactivity == Inactivity.UNAVAILABLE) { "The borrowed native inactivity option changed." }
        check(0 < selectedLimit) { "The native selector did not provide a positive limit." }
        check(requested != Inactivity.MINIMIZED || (reason != Reason.SHORT_AFK && reason != Reason.LONG_AFK)) { "Formal native performance collection was AFK-throttled." }
    }

    /**
     * Requires actual selected/applied agreement before warmup starts.
     */
    fun ready(): Boolean = 0 < selectedLimit && selectedLimit == appliedLimit

    /**
     * Rejects any pacing change or unready observation during a formal phase.
     */
    fun verifyStable(expected: MinecraftNativePerformancePacing) {
        check(this == expected && ready()) { "Native frame pacing changed during a formal performance phase." }
    }
}

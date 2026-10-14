package dev.s7a.strata.integration.minecraft.fabric;

import java.util.Locale;

/**
 * Detached actual limiter observations; direct selectors and extracted frame state remain separate values.
 * A transient selected/applied disagreement may delay readiness but cannot satisfy a stable formal interval.
 * Unavailable native reasons/options are explicit and never inferred from numeric caps.
 */
record MinecraftNativePerformancePacing(
    Inactivity inactivity,
    Reason reason,
    int selectedLimit,
    int appliedLimit,
    AppliedLimitSource appliedLimitSource,
    boolean iconified
) {
    /** Actual option values; UNAVAILABLE is used only where no native option exists. */
    enum Inactivity {
        MINIMIZED, AFK, UNAVAILABLE;

        /** Decodes the explicit fixture option at its external property boundary; formal collection defaults to MINIMIZED. */
        static Inactivity fromProperty(String value) {
            if (value == null) {
                return MINIMIZED;
            }
            for (Inactivity mode : new Inactivity[] { MINIMIZED, AFK }) {
                if (mode.name().toLowerCase(Locale.ROOT).equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("strata.performance.inactivity must be minimized or afk.");
        }
    }

    /** Compiled native reasons; older selectors expose UNAVAILABLE instead of a fabricated reason. */
    enum Reason {
        NONE, WINDOW_ICONIFIED, LONG_AFK, SHORT_AFK, OUT_OF_LEVEL_MENU, UNAVAILABLE
    }

    /** Distinguishes a direct selector observation from an independently extracted limiter input. */
    enum AppliedLimitSource {
        DIRECT_SELECTOR, GAME_RENDER_STATE
    }

    /** Checks window safety, actual borrowed option and positive selected cap before readiness or measurement. */
    void validate(Inactivity requested) {
        if (iconified || reason == Reason.WINDOW_ICONIFIED) {
            throw new IllegalStateException("The native performance window is iconified or safety-throttled.");
        }
        if (inactivity == Inactivity.UNAVAILABLE && requested != Inactivity.MINIMIZED) {
            throw new IllegalStateException("This native selector has no requested inactivity option.");
        }
        if (inactivity != requested && inactivity != Inactivity.UNAVAILABLE) {
            throw new IllegalStateException("The borrowed native inactivity option changed.");
        }
        if (selectedLimit <= 0) {
            throw new IllegalStateException("The native selector did not provide a positive limit.");
        }
        if (requested == Inactivity.MINIMIZED && (reason == Reason.SHORT_AFK || reason == Reason.LONG_AFK)) {
            throw new IllegalStateException("Formal native performance collection was AFK-throttled.");
        }
    }

    /** Requires agreement with the actual applied frame-state value before the existing warmup starts. */
    boolean ready() {
        return 0 < selectedLimit && selectedLimit == appliedLimit;
    }

    /** Rejects option, reason, window or selected/applied cap changes during a formal phase. */
    void verifyStable(MinecraftNativePerformancePacing expected) {
        if (equals(expected) == false || ready() == false) {
            throw new IllegalStateException("Native frame pacing changed during a formal performance phase.");
        }
    }
}

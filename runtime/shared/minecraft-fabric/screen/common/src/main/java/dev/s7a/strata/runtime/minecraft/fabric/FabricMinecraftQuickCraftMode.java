package dev.s7a.strata.runtime.minecraft.fabric;

/** Native menu distribution modes decoded once from the adapter's normalized mouse input. */
enum FabricMinecraftQuickCraftMode {
    /** Distributes the carried stack across eligible slots. */
    DISTRIBUTE(0),
    /** Places one carried item in each eligible slot. */
    ONE_PER_SLOT(1),
    /** Fills eligible slots through the native creative clone protocol. */
    FILL(2);

    private final int nativeCode;

    FabricMinecraftQuickCraftMode(int nativeCode) {
        this.nativeCode = nativeCode;
    }

    /** Encodes this mode only when submitting a native menu quick-craft mask. */
    int nativeCode() {
        return nativeCode;
    }

    /**
     * Decodes normalized primary/secondary protocol buttons, then the adapter-validated creative pick binding.
     * Returns null for an unsupported button; native primary and secondary distribution take precedence over a rebound pick key.
     */
    static FabricMinecraftQuickCraftMode fromButton(int button, boolean creativePick) {
        return switch (button) {
            case 0 -> DISTRIBUTE;
            case 1 -> ONE_PER_SLOT;
            default -> creativePick ? FILL : null;
        };
    }
}

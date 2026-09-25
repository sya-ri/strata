package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Describes a native input rejection check available for a particular loaded adapter family.
 *
 * Instances contain no native resources and may be read on the runner thread.
 * A check marshals native operations through the borrowed context and releases every owned source before returning.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun interface MinecraftCanvasInputValidation {
    /**
     * Verifies a family's actual invalid native input without allowing an expected failure to terminate the client.
     *
     * The runner owns [context], [profile], and all resulting evidence; neither is retained after return.
     * Native, assertion, scheduling, and cleanup failures propagate with their original primary exception.
     */
    fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    )
}

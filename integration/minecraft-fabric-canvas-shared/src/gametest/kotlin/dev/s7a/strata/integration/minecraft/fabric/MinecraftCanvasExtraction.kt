package dev.s7a.strata.integration.minecraft.fabric

/**
 * Catches an expected native screen failure while still checking its independently owned GUI extraction state.
 *
 * Both callbacks are borrowed only for this client-thread invocation and must not retain a native context afterwards.
 * [validate] also clears any local deferred references before returning or failing.
 * The exact [render] failure is returned, with a distinct validation failure suppressed; validation alone propagates unchanged.
 * A fully successful invocation returns null and owns no native resource after return.
 */
internal fun attemptMinecraftCanvasExtraction(
    render: () -> Unit,
    validate: () -> Unit,
): Throwable? {
    val failure = runCatching(render).exceptionOrNull()
    val validation = runCatching(validate).exceptionOrNull()
    if (validation != null) {
        if (failure == null) throw validation
        if (failure !== validation) failure.addSuppressed(validation)
    }
    return failure
}

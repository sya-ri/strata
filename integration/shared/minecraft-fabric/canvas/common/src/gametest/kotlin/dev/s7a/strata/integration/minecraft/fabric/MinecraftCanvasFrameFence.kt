package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import java.lang.reflect.Field

/**
 * Fences native acceptance scenes on completed render frames instead of fixed tick counts.
 *
 * A client tick proves time passed; it proves nothing about which frame the GPU presents. Reading the main
 * framebuffer between an old-scale and a new-scale frame has already produced one flaky failure
 * ([issue 40](https://github.com/sya-ri/strata/issues/40)), so screenshot assertions fence on the screen's own
 * host-frame bookkeeping: both shipped runtimes increment a private [HOST_FRAME_FIELD] counter once per
 * committed common host frame, and extraction is serialized on the client thread, so one increment past the
 * baseline proves the baseline frame completed its render call. Screenshot tasks run between frames and read
 * the last completed frame, so waiting for one committed frame after the scene signal orders the capture
 * strictly after the presentation the signal observed.
 *
 * The counter is private runtime state, so the fence reads it with the same reflection the suites already use
 * for receipts (`retainedPresentation`, `readRenderWork`) and keeps it out of the public SPI. Both read paths
 * run on the client thread: the writing thread's own reads are coherent, and the context's wait and
 * client-thread scheduling already marshal evaluations. An unreadable counter fails with the holder type name
 * instead of degrading to the fixed-tick behavior this fence replaces.
 */
internal object MinecraftCanvasFrameFence {
    /**
     * Private runtime counter incremented once per committed common host frame.
     *
     * The unobfuscated runtime declares it directly on [FabricMinecraftScreen]; the remapped runtime declares
     * it on the presentation collaborator held by the [PRESENTATION_FIELD] field. Both increment at the same
     * extraction points.
     */
    private const val HOST_FRAME_FIELD = "hostFrameCount"

    /**
     * Remapped-runtime field holding the presentation collaborator that owns the counter.
     */
    private const val PRESENTATION_FIELD = "presentation"

    /**
     * Observes the current host-frame count as the fence baseline.
     *
     * Callers read the baseline after the scene predicate fires and before the triggering work they fence on,
     * so the awaited increment orders the screenshot after the observed presentation.
     *
     * @param context coordinator marshalling the read to the client thread.
     * @param screen the presented screen whose runtime owns the counter.
     * @return the number of host frames committed so far on the screen's runtime.
     * @throws Throwable when neither runtime layout exposes a readable [HOST_FRAME_FIELD] counter.
     */
    internal fun hostFrameCount(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
    ): Long = context.onClient { readHostFrameCount(screen) }

    /**
     * Waits until one committed host frame has started on top of [baseline].
     *
     * Frame extraction is serialized on the client thread, so the awaited increment proves the baseline frame
     * completed its render call, and the next screenshot reads a frame at or after the baseline's presentation.
     *
     * @param context coordinator owning the bounded wait.
     * @param screen the presented screen whose runtime owns the counter.
     * @param baseline count observed by [hostFrameCount] before the fenced work.
     * @throws Throwable when no further host frame is committed before the context's timeout, or the counter
     *   becomes unreadable.
     */
    internal fun awaitCompletedFrame(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        baseline: Long,
    ) {
        context.waitFor { baseline < readHostFrameCount(screen) }
    }

    private fun readHostFrameCount(screen: FabricMinecraftScreen): Long {
        val direct = accessibleHostFrameField(screen.javaClass)
        if (direct != null) return direct.getLong(screen)
        val presentationHolder = presentationHolder(screen)
        val counter =
            checkNotNull(accessibleHostFrameField(presentationHolder.javaClass)) {
                "The Canvas frame fence found no $HOST_FRAME_FIELD counter on ${presentationHolder.javaClass.name}."
            }
        return counter.getLong(presentationHolder)
    }

    private fun accessibleHostFrameField(
        holder: Class<*>,
    ): Field? {
        val field =
            holder.declaredFields.singleOrNull { candidate -> candidate.name == HOST_FRAME_FIELD && candidate.type == Long::class.java }
        if (field != null) {
            check(field.trySetAccessible()) { "The Canvas frame fence cannot access the $HOST_FRAME_FIELD counter of ${holder.name}." }
        }
        return field
    }

    private fun presentationHolder(screen: FabricMinecraftScreen): Any {
        val field =
            screen.javaClass.declaredFields.singleOrNull { candidate -> candidate.name == PRESENTATION_FIELD }
                ?: error("The Canvas frame fence found no $HOST_FRAME_FIELD or $PRESENTATION_FIELD field on ${screen.javaClass.name}.")
        check(field.trySetAccessible()) { "The Canvas frame fence cannot access the $PRESENTATION_FIELD field of ${screen.javaClass.name}." }
        return checkNotNull(field.get(screen)) { "The Canvas frame fence requires a presentation collaborator on ${screen.javaClass.name}." }
    }
}

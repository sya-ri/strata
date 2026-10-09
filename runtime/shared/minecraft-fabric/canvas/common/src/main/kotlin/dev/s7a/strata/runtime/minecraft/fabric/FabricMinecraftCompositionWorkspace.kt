package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Shares one intermediate per admitted physical extent during one render-thread preparation.
 * Device, format, usage flags and queue are fixed by the receiving adapter and generation, and size is the complete varying key.
 * At most 128 shapes are selected by the unchanged 256-output ledger; no previous generation or authoritative state is retained.
 * Final tile textures remain independent immutable GUI outputs, while scratch reads and writes follow the original queue order.
 * The workspace itself transfers before native allocation and closes only after initialization and GUI-consumption retirement.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("unused", "TooGenericExceptionCaught") // Synthetic native factories borrow this owner; independent partial allocations must close after failures.
internal class FabricMinecraftCompositionWorkspace(
    private val plan: FabricMinecraftCompositionTargetPlan,
) : NativeGuiResource {
    private val scratch = LinkedHashMap<IntSize, FabricMinecraftCompositionScratch>()
    private var closed = false

    /**
     * Borrows the selected extent, recording its empty owner before initializing it for the first tile.
     * Unselected shapes use the original private pair of destinations.
     */
    @JvmSynthetic
    internal fun borrow(size: IntSize): AbstractTexture? {
        check(closed.not()) { "A closed composition workspace cannot prepare outputs." }
        if (size !in plan.shapes) return null
        val existing = scratch[size]
        if (existing != null) return existing.texture
        val created = FabricMinecraftCompositionScratch()
        scratch[size] = created
        created.initialize(size)
        return created.texture
    }

    @JvmSynthetic
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        for (owner in scratch.values) {
            try {
                owner.close()
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
            }
        }
        failure?.let { throw it }
        closed = true
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        check(closed) { "Workspace destruction follows successful close." }
        return scratch.values.all { it.isDestroyed() }
    }
}

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Owns one RGBA8 intermediate which is never submitted as a GUI image.
 * The enclosing generation retains this empty render-thread owner before allocation and fences every use before close.
 * A borrowed native view retains no source or command, and partial initialization stays owned through physical destruction.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftCompositionScratch : NativeGuiResource {
    private var storage: NativeGuiResource? = null
    private var borrowed: AbstractTexture? = null
    private var closed = false

    /**
     * Allocates one exact-extent intermediate after this owner has entered its reserved generation.
     * The adapter transfers its empty native owner before allocating the texture or view.
     */
    @JvmSynthetic
    internal fun initialize(size: IntSize) {
        check(storage == null && closed.not()) { "A composition intermediate initializes only once." }
        initializeFabricMinecraftCompositionScratch(size) { view, resource ->
            check(storage == null) { "A composition intermediate already owns native storage." }
            storage = resource
            borrowed = view
        }
    }

    /**
     * Borrows an initialized intermediate only within the generation's ordered synchronous preparation.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture
        get() {
            check(closed.not()) { "A closed composition intermediate cannot be borrowed." }
            return checkNotNull(borrowed)
        }

    @JvmSynthetic
    override fun close() {
        if (closed) return
        storage?.close()
        borrowed = null
        closed = true
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        check(closed) { "Intermediate destruction follows successful close." }
        return storage?.isDestroyed() ?: true
    }
}

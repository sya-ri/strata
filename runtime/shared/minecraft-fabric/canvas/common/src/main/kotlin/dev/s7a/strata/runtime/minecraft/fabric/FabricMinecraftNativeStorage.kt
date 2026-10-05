package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns at most five native allocations: two texture/view pairs and an optional fullscreen vertex buffer.
 * Render-thread callers transfer this empty owner before allocation and fence initialization and use before closing it.
 * Successful closes are never repeated, every independent close is attempted, and original objects survive until destruction acknowledgement.
 * The synchronous destruction factory stays with this owner and must not release objects or retain a screen.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal class FabricMinecraftNativeStorage(
    private val destructionFactory: (List<AutoCloseable>) -> FabricNativeCanvasDestruction = ::trackPortableDestruction,
) : NativeGuiResource {
    private val resources = arrayOfNulls<AutoCloseable>(5)
    private val closed = BooleanArray(resources.size)
    private var count = 0
    private var destruction: FabricNativeCanvasDestruction? = null
    private var closeRequested = false
    private var retiring = false

    /**
     * Reserves an ownership slot before invoking one borrowed allocator and records its result before returning.
     * Allocator failures propagate unchanged; previous successful allocations remain owned for fenced cleanup.
     */
    @JvmSynthetic
    internal fun <T : AutoCloseable> allocate(factory: () -> T): T {
        check(retiring.not()) { "Retiring native storage cannot allocate." }
        check(count < resources.size) { "Portable native storage exceeds its checked allocation count." }
        val resource = factory()
        resources[count] = resource
        count += 1
        return resource
    }

    @JvmSynthetic
    override fun close() {
        if (closeRequested) return
        retiring = true
        if (destruction == null) destruction = destructionFactory((0 until count).map { checkNotNull(resources[it]) })
        var failure: Throwable? = null
        for (index in count - 1 downTo 0) {
            if (closed[index]) continue
            try {
                checkNotNull(resources[index]).close()
                closed[index] = true
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
            }
        }
        failure?.let { throw it }
        closeRequested = true
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        check(closeRequested) { "Native destruction is queried only after every close succeeded." }
        return checkNotNull(destruction).isDestroyed()
    }
}

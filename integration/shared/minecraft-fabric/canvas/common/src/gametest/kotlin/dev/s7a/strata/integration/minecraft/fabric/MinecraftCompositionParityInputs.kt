@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Test-only access to the presenter's immutable preparation inputs, without extending the runtime SPI or native ownership.
 * Reads and replacement happen on the render thread after a stable frame; production source, tile and fence owners stay intact.
 */
internal object MinecraftCompositionParityInputs {
    /**
     * Returns each currently prepared portable image and its corresponding generation-owned texture owner.
     */
    internal fun portable(screen: FabricMinecraftScreen): List<Pair<Any, Any>> {
        val frames = member(presenter(screen), "portableFrames")
        val prepared = member(frames, "current")
        val images = member(prepared, "images") as? List<*> ?: error("Prepared portable image descriptions are unavailable.")
        val textures = member(prepared, "textures") as? List<*> ?: error("Prepared portable texture owners are unavailable.")
        check(images.size == textures.size)
        return images.indices.map { checkNotNull(images[it]) to checkNotNull(textures[it]) }
    }

    /**
     * Replaces only GPU admission with the previous CPU preparation path for the same complete commands, viewport and scale.
     * The next ordinary frame reserves and seals a fresh generation; no native object is changed or closed by this helper.
     */
    internal fun selectCpu(screen: FabricMinecraftScreen) {
        val presenter = presenter(screen)
        val field = presenter.javaClass.getDeclaredField("preparedInputs")
        check(field.trySetAccessible())
        val inputs = checkNotNull(field.get(presenter))
        val constructor = inputs.javaClass.declaredConstructors.single { it.parameterCount == 6 }
        check(constructor.trySetAccessible())
        field.set(presenter, constructor.newInstance(member(inputs, "layers"), member(inputs, "scale"), 0L, 0L, false, null))
    }

    /**
     * Reads a known presenter-owned field without retaining its native value beyond the synchronous diagnostic callback.
     */
    internal fun member(
        owner: Any,
        name: String,
    ): Any {
        val field = owner.javaClass.getDeclaredField(name)
        check(field.trySetAccessible())
        return checkNotNull(field.get(owner)) { "Prepared composition member is absent: $name" }
    }

    /**
     * Checks whole-tile GPU admission using the exact internal description, including nullable CPU fallback.
     */
    internal fun composed(image: Any): Boolean {
        val field = image.javaClass.getDeclaredField("composition")
        check(field.trySetAccessible())
        return field.get(image) != null
    }

    /**
     * Returns the version-owned presentation holder for synchronous fixture inspection only.
     */
    internal fun presenter(screen: FabricMinecraftScreen): Any = if (runCatching { screen.javaClass.getDeclaredField("portableFrames") }.isSuccess) screen else member(screen, "presentation")
}

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.performance.FabricFrameObserver
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.WeakHashMap

/**
 * Test-owned framework progress and snapshots of actual presentation ownership, with no production counters.
 * All access belongs to the client/render thread. Registry values retain no screen; snapshots are scoped to one assertion.
 */
internal object MinecraftPresentationObservation {
    private val frames = WeakHashMap<FabricMinecraftScreen, FabricFrameObserver>()

    /**
     * Reads actual state; object identity proves reuse, never an inferred number of rasterizations or uploads.
     */
    fun read(screen: FabricMinecraftScreen): Snapshot {
        val observer = frames.getOrPut(screen) { FabricFrameObserver(screen) }
        val presenter = if (optional(screen, "portableFrames") != null) screen else member(screen, "presentation")
        val prepared = optional(member(presenter, "portableFrames"), "current")
        val textures =
            prepared
                ?.let { member(it, "textures") as List<*> }
                .orEmpty()
                .map { checkNotNull(it) }
        val owner = member(member(presenter, "sampledImages"), "owner")
        val images = member(owner, "images") as Map<*, *>
        val sources = images.values.map { member(checkNotNull(it), "entry") }
        return Snapshot(
            observer.frames,
            optional(presenter, "preparedCommands"),
            optional(presenter, "preparedInputs"),
            textures,
            sources,
            member(owner, "bytes") as Long,
            images.keys.map { checkNotNull(it) },
            optional(presenter, "pointerFrameCommands"),
        )
    }

    /**
     * Borrows a real existing owner field for synthetic fixture identity checks.
     */
    fun member(
        owner: Any,
        name: String,
    ): Any = checkNotNull(optional(owner, name)) { "Missing presentation state: $name" }

    /**
     * Uses the existing application projection contract to observe retained identities outside presentation.
     */
    fun declarations(screen: FabricMinecraftScreen): RuntimeDeclaration {
        val session = member(member(screen, "host"), "session") as RuntimeUiSession
        return session.projectDeclarations { it }
    }

    /**
     * Returns genuine retained declaration identities for a fixture's logical key.
     */
    fun identities(
        screen: FabricMinecraftScreen,
        key: ElementKey<*>,
    ): List<Long> {
        fun find(declaration: RuntimeDeclaration): List<Long> = (if (declaration.element.identity == ElementIdentity.Keyed(key)) listOf(declaration.identity) else emptyList()) + declaration.children.flatMap(::find)
        return find(declarations(screen))
    }

    private fun optional(
        owner: Any,
        name: String,
    ): Any? {
        val field = generateSequence(owner.javaClass as Class<*>?) { it.superclass }.firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } } ?: return null
        check(field.trySetAccessible())
        return field.get(owner)
    }

    /**
     * Current live identities plus observed framework progress; no field represents cumulative runtime work.
     */
    data class Snapshot(
        val renderExtractions: Long,
        val commands: Any?,
        val inputs: Any?,
        val portableTextures: List<Any>,
        val sources: List<Any>,
        val sourceBytes: Long,
        val sourceImages: List<Any>,
        val pointerCommands: Any?,
    ) {
        /**
         * Requires ordered reuse of every actual portable texture owner.
         */
        fun samePortable(other: Snapshot): Boolean = portableTextures.size == other.portableTextures.size && portableTextures.indices.all { portableTextures[it] === other.portableTextures[it] }

        /**
         * Requires the same actual source entries regardless of identity-map iteration order.
         */
        fun sameSources(other: Snapshot): Boolean = sources.size == other.sources.size && sources.all { source -> other.sources.any { it === source } }
    }
}

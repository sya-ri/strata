@file:JvmName("MinecraftRuntimeFactories")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Creates a reusable Minecraft screen definition.
 *
 * Construction does not evaluate [content].
 * The returned definition retains [content] for its own lifetime.
 * Each host created from the result owns an independent core runtime session while sharing the caller-owned captured values.
 *
 * @param title the unresolved screen title.
 * @param pausesGame whether the screen pauses the game.
 * @param content the element description evaluator invoked on each host's owner thread during its first attach.
 * @return a reusable screen definition with a private content evaluator.
 */
public fun createMinecraftScreenDefinition(
    title: UiText,
    pausesGame: Boolean,
    content: () -> Element,
): MinecraftScreenDefinition = MinecraftRuntimeImplementation.createDefinition(title, pausesGame, content)

/**
 * Creates one independent owner-thread host from a reusable screen definition.
 *
 * The definition is read synchronously during construction, remains reusable, and is not directly retained by the host.
 * The content evaluator is retained by the core runtime session until its lifecycle reaches failure or close.
 * Objects captured by that evaluator, including a definition reference, remain caller-owned.
 *
 * @param definition the reusable screen definition created by [createMinecraftScreenDefinition].
 * @return a distinct private implementation exposing only the opt-in Minecraft host contract.
 */
@InternalStrataRuntimeApi
public fun createMinecraftUiHost(definition: MinecraftScreenDefinition): MinecraftUiHost = MinecraftRuntimeImplementation.createHost(definition)

private object MinecraftRuntimeImplementation {
    fun createDefinition(
        title: UiText,
        pausesGame: Boolean,
        content: () -> Element,
    ): MinecraftScreenDefinition = ScreenDefinitionImpl.create(title, pausesGame, content)

    @OptIn(InternalStrataRuntimeApi::class)
    fun createHost(definition: MinecraftScreenDefinition): MinecraftUiHost =
        when (definition) {
            is ScreenDefinitionImpl -> definition.createHost()
        }

    private class ScreenDefinitionImpl private constructor(
        override val title: UiText,
        override val pausesGame: Boolean,
        private val content: () -> Element,
    ) : MinecraftScreenDefinition {
        @OptIn(InternalStrataRuntimeApi::class)
        fun createHost(): MinecraftUiHost = UiHostImpl.create(createRuntimeUiSession(content))

        companion object {
            /**
             * Creates one reusable private definition without evaluating its content.
             *
             * @param title the exact unresolved title retained by the definition.
             * @param pausesGame whether the definition pauses the game.
             * @param content the caller-owned evaluator retained privately for later host creation.
             * @return a new definition with referential identity.
             */
            @JvmSynthetic
            internal fun create(
                title: UiText,
                pausesGame: Boolean,
                content: () -> Element,
            ): ScreenDefinitionImpl = ScreenDefinitionImpl(title, pausesGame, content)
        }
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private class UiHostImpl private constructor(
        private val session: RuntimeUiSession,
    ) : MinecraftUiHost {
        override fun attach() {
            session.attach()
        }

        override fun detach() {
            session.detach()
        }

        override fun frame(viewport: IntSize): RuntimeUiFrame = session.frame(Constraints.fixed(viewport.width, viewport.height))

        override fun dispatchPointer(event: PointerEvent): InputResult = session.dispatchPointer(event)

        override fun close() {
            session.close()
        }

        companion object {
            /**
             * Wraps one independently owned core session in the Minecraft host boundary.
             *
             * @param session the session whose owner-thread lifecycle is delegated unchanged.
             * @return a new private host with referential identity.
             */
            @JvmSynthetic
            internal fun create(session: RuntimeUiSession): UiHostImpl = UiHostImpl(session)
        }
    }
}

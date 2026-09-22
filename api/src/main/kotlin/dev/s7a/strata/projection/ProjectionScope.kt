package dev.s7a.strata.projection

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.text.UiText

/**
 * Callback-lifetime capability for projecting one retained declaration.
 * Implementations scope registered actions to the current session, node, and generation.
 * Projection callbacks must not retain this scope or mutate authoritative state.
 */
public interface ProjectionScope {
    /**
     * Binds an event endpoint and returns its opaque positive session-local identity.
     */
    public fun action(action: ProjectionAction<*>): Long

    /**
     * Projects a typed value with stable source identity, replacement generation, acknowledged edit, and endpoint.
     * Runtime bindings preserve the latest local edit until it is acknowledged or explicitly replaced by a newer generation.
     */
    public fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue

    /**
     * Encodes immutable CPU pixels under the current negotiated resource limit.
     * The result owns detached data and retains no source subscription or graphics handle.
     */
    public fun image(image: DrawImage): ProjectionValue

    /**
     * Encodes unresolved portable text, preserving translation arguments and resource-font identifiers.
     */
    public fun text(text: UiText): ProjectionValue

    /**
     * Rejects a declaration when a nested typed behavior is absent from the negotiated client capabilities.
     */
    public fun requireType(type: ProjectionType)
}

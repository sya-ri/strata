package dev.s7a.strata.component

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId

/**
 * Platform-neutral source for an image component or image-backed modifier.
 *
 * Pixel sources retain an immutable snapshot directly.
 * Resource sources retain only an identifier and are resolved through the active runtime resource manager during screen evaluation, preserving resource-pack replacement.
 */
public sealed interface ImageSource {
    /**
     * Uses one immutable caller-provided pixel snapshot.
     *
     * @property image immutable source pixels retained without copying.
     */
    public data class Pixels(
        public val image: DrawImage,
    ) : ImageSource

    /**
     * Resolves pixels from the active runtime resource manager.
     *
     * @property id immutable resource-pack identifier.
     */
    public data class Resource(
        public val id: ResourceId,
    ) : ImageSource
}

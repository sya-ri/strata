package dev.s7a.strata.text

/**
 * Opaque platform-owned text payload retained until a runtime adapter resolves it.
 *
 * Implementations must be immutable snapshots with value-based equality and hash semantics.
 *
 * They carry typed payloads and do not expose a string namespace discriminator to the API.
 */
public interface PlatformText

package dev.s7a.strata.projection

/**
 * Typed owner-thread binding between a server-owned value and an optimistic client control.
 * [identity] is a stable referential key, normally the caller-owned state object, and is never serialized.
 * Reads and encoding occur during declaration projection; decoding validates input before [write] can run.
 * The write callback runs only inside the server's ordinary input boundary.
 */
public class ProjectionBinding<T : Any>(
    public val type: ProjectionType,
    public val identity: Any,
    public val read: () -> T,
    public val encode: (T) -> ProjectionValue,
    public val decode: (ProjectionValue) -> T,
    public val write: (T) -> Unit,
)

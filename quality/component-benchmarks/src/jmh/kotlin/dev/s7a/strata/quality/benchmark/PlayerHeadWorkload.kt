package dev.s7a.strata.quality.benchmark

/**
 * Fixed public-profile corpus: independent sync/async sources, one or 32 heads and filtered/nearest controls.
 * Every operation uses the same two immutable patterned skins and complete synthetic profile on both archive sets.
 */
public enum class PlayerHeadWorkload(
    internal val source: Source,
    internal val count: Int,
    internal val size: Int,
    internal val entry: Entry,
    internal val replacementSize: Int,
) {
    SyncOne10(Source.Sync, 1, 10, Entry.Compatibility, 11),
    SyncOne31(Source.Sync, 1, 31, Entry.Compatibility, 33),
    SyncOne127(Source.Sync, 1, 127, Entry.Compatibility, 129),
    SyncOne16(Source.Sync, 1, 16, Entry.Compatibility, 24),
    SyncMany10(Source.Sync, 32, 10, Entry.Compatibility, 11),
    SyncMany31(Source.Sync, 32, 31, Entry.Compatibility, 33),
    SyncMany127(Source.Sync, 32, 127, Entry.Compatibility, 129),
    SyncMany16(Source.Sync, 32, 16, Entry.Compatibility, 24),
    AsyncOne10(Source.Async, 1, 10, Entry.Compatibility, 11),
    AsyncOne31(Source.Async, 1, 31, Entry.Compatibility, 33),
    AsyncOne127(Source.Async, 1, 127, Entry.Compatibility, 129),
    AsyncOne16(Source.Async, 1, 16, Entry.Compatibility, 24),
    AsyncMany10(Source.Async, 32, 10, Entry.Compatibility, 11),
    AsyncMany31(Source.Async, 32, 31, Entry.Compatibility, 33),
    AsyncMany127(Source.Async, 32, 127, Entry.Compatibility, 129),
    AsyncMany16(Source.Async, 32, 16, Entry.Compatibility, 24),
    SyncOneScale2(Source.Sync, 1, 16, Entry.Scale, 24),
    SyncManyScale2(Source.Sync, 32, 16, Entry.Scale, 24),
    AsyncOneScale2(Source.Async, 1, 16, Entry.Scale, 24),
    AsyncManyScale2(Source.Async, 32, 16, Entry.Scale, 24),

    /**
     * Public immediate pixels and deterministic asynchronous lookup routes.
     */
    internal enum class Source {
        Sync,
        Async,
    }

    /**
     * Supported arbitrary-size compatibility entry and preferred typed integer-scale entry.
     */
    internal enum class Entry {
        Compatibility,
        Scale,
    }
}

package dev.s7a.strata.integration.docs.recheck

/**
 * Immutable history row supplied by the caller and safe to share between source notifications.
 *
 * Construction performs no validation or I/O; the caller guarantees unique, stable IDs in each history snapshot.
 *
 * @property id immutable message identity, retained when the message moves or its text changes.
 * @property text original message text, displayed as a bounded preview without modifying this value.
 */
public data class HistoryEntry(
    public val id: Long,
    public val text: String,
)

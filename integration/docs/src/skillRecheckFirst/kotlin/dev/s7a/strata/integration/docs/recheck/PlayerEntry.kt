package dev.s7a.strata.integration.docs.recheck

/**
 * Immutable player result supplied by the caller and safe to share between source notifications.
 *
 * Construction performs no validation or I/O; the caller guarantees unique, stable IDs in each result snapshot.
 *
 * @property id player identity, independent of display name and result position.
 * @property name display name, preserved in full even when its visual preview is truncated.
 */
public data class PlayerEntry(
    public val id: String,
    public val name: String,
)

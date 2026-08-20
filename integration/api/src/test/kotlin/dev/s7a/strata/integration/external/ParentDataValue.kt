package dev.s7a.strata.integration.external

/**
 * Non-primitive value used to verify that parent-data lookup preserves exact identity.
 *
 * @property value the fixture payload.
 */
public data class ParentDataValue public constructor(
    public val value: Int,
)

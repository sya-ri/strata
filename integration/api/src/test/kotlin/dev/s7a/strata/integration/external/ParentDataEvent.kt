package dev.s7a.strata.integration.external

/**
 * Typed ordering observations for parent-data lookup and child callbacks.
 */
internal enum class ParentDataEvent {
    /**
     * A selected provider returned its value.
     */
    ProviderRead,

    /**
     * The queried component entered its measurement callback.
     */
    ComponentMeasure,

    /**
     * The queried component entered its layout callback.
     */
    ComponentLayout,
}

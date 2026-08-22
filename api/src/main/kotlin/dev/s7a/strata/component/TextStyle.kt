package dev.s7a.strata.component

/**
 * Closed profile-backed color and shadow policy for text components.
 *
 * Values contain no platform resource and are resolved by the active runtime profile during screen evaluation.
 */
public enum class TextStyle {
    /**
     * Opaque white foreground with the profile's ordinary dark shadow.
     */
    Normal,

    /**
     * Muted foreground and shadow used by inactive labels.
     */
    Inactive,

    /**
     * Dark foreground without a shadow for labels inside container panels.
     */
    ContainerLabel,

    /**
     * Enabled or disabled text-field colors selected by the field state.
     */
    TextField,
}

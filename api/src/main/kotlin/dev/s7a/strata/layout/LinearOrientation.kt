package dev.s7a.strata.layout

/**
 * Internal type-safe orientation and default cross-axis policy for a linear layout.
 */
internal sealed interface LinearOrientation {
    /**
     * The main axis derived from this orientation.
     */
    val axis: LinearAxis

    /**
     * Row orientation with a vertical default child alignment.
     *
     * @property alignment the default vertical child alignment.
     */
    data class Row(
        val alignment: VerticalAlignment,
    ) : LinearOrientation {
        override val axis: LinearAxis
            get() = LinearAxis.Horizontal
    }

    /**
     * Column orientation with a horizontal default child alignment.
     *
     * @property alignment the default horizontal child alignment.
     */
    data class Column(
        val alignment: HorizontalAlignment,
    ) : LinearOrientation {
        override val axis: LinearAxis
            get() = LinearAxis.Vertical
    }
}

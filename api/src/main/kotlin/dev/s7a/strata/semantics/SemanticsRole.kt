package dev.s7a.strata.semantics

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.resource.ResourceId

/**
 * A value-typed semantics role model.
 *
 * Unknown application-defined roles remain intact for adapters rather than being rejected by the API.
 * Custom implementations must be immutable and provide stable value-based equality and hash semantics.
 */
public interface SemanticsRole {
    /**
     * Optional versioned client role identity for remote declarations.
     * Local application roles need no registration; remote consumers explicitly register the same type on their client.
     */
    public val projectionType: ProjectionType? get() = null

    /**
     * A button-like control.
     */
    public data object Button : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/button"))
    }

    /**
     * A text presentation.
     */
    public data object Text : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/text"))
    }

    /**
     * An editable single-line text field.
     */
    public data object TextField : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/text_field"))
    }

    /**
     * An editable multiline text area with independently controlled vertical scrolling.
     */
    public data object TextArea : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/text_area"))
    }

    /**
     * One selectable tab in an externally controlled tab group.
     */
    public data object Tab : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/tab"))
    }

    /**
     * A binary checkable control.
     */
    public data object Checkbox : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/checkbox"))
    }

    /**
     * A bounded numeric adjustment control.
     */
    public data object Slider : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/slider"))
    }

    /**
     * A button that cycles through a finite option set.
     */
    public data object CycleButton : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/cycle_button"))
    }

    /**
     * A read-only progress indicator.
     */
    public data object ProgressBar : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/progress_bar"))
    }

    /**
     * A selectable list container.
     */
    public data object SelectionList : SemanticsRole {
        override val projectionType: ProjectionType = ProjectionType(ResourceId("strata", "role/selection_list"))
    }
}

@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.FlowRowElement
import dev.s7a.strata.layout.GridElement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LinearElement
import dev.s7a.strata.layout.LinearOrientation
import dev.s7a.strata.layout.SpacerElement
import dev.s7a.strata.layout.StackElement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier

/**
 * Emits one row container with the descriptions produced by its callback.
 *
 * The callback scope is confined to the constructing thread and is closed immediately after the callback returns.
 *
 * @param modifier ordered active behavior applied to the row itself, including sizing, padding, paint, semantics, focus, and input behavior.
 * @param key optional stable identity among direct siblings.
 * @param spacing non-negative fixed spacing between direct children.
 * @param horizontalArrangement main-axis child arrangement.
 * @param verticalAlignment default cross-axis child alignment.
 * @param content callback that emits zero or more direct children.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 * @throws IllegalArgumentException when [spacing] is negative.
 * @throws Throwable when [content] fails; the exact callback failure is propagated.
 */
public fun UiScope.Row(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    spacing: Int = 0,
    horizontalArrangement: Arrangement = Arrangement.Start,
    verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
    content: RowScope.() -> Unit,
) {
    checkUsable()
    require(0 <= spacing) { "Linear layout spacing must be non-negative." }
    val scope = RowScope.create()
    val children =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(
        LinearElement(
            orientation = LinearOrientation.Row(verticalAlignment),
            spacing = spacing,
            arrangement = horizontalArrangement,
            key = key,
            children = children,
            modifier = modifier,
        ),
    )
}

/**
 * Emits horizontal children that wrap into rows when their measured widths and gaps exceed a bounded width.
 *
 * Children are measured once with zero minimums and the full incoming maximums, never the remaining row width.
 * Exact fits remain in the current row; an unbounded width produces one row.
 * The constrained natural size uses the widest row and the sum of row heights and vertical gaps.
 * Use a sizing modifier to fill the available width; rows are arranged within the resulting container width and stacked from the top.
 * Children keep their logical parent across reflow, and paint or input overflow is not implicitly clipped.
 * The callback scope is confined to the constructing thread and is closed immediately after the callback returns.
 *
 * @param modifier ordered active behavior applied to the flow row itself.
 * @param key optional stable identity among direct siblings.
 * @param horizontalSpacing non-negative fixed spacing between children in the same row.
 * @param verticalSpacing non-negative fixed spacing between rows.
 * @param horizontalArrangement horizontal slack distribution applied separately to every row.
 * @param verticalAlignment default child placement within the maximum measured height of its row.
 * @param content callback that emits zero or more direct children.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 * @throws IllegalArgumentException when either spacing value is negative.
 * @throws Throwable when [content] fails; the exact callback failure is propagated.
 */
public fun UiScope.FlowRow(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    horizontalSpacing: Int = 0,
    verticalSpacing: Int = 0,
    horizontalArrangement: Arrangement = Arrangement.Start,
    verticalAlignment: VerticalAlignment = VerticalAlignment.Top,
    content: FlowRowScope.() -> Unit,
) {
    checkUsable()
    require(0 <= horizontalSpacing) { "FlowRow horizontal spacing must be non-negative." }
    require(0 <= verticalSpacing) { "FlowRow vertical spacing must be non-negative." }
    val scope = FlowRowScope.create()
    val children =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(
        FlowRowElement(
            horizontalSpacing = horizontalSpacing,
            verticalSpacing = verticalSpacing,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            key = key,
            children = children,
            modifier = modifier,
        ),
    )
}

/**
 * Emits one column container with the descriptions produced by its callback.
 *
 * The callback scope is confined to the constructing thread and is closed immediately after the callback returns.
 *
 * @param modifier ordered active behavior applied to the column itself, including sizing, padding, paint, semantics, focus, and input behavior.
 * @param key optional stable identity among direct siblings.
 * @param spacing non-negative fixed spacing between direct children.
 * @param verticalArrangement main-axis child arrangement.
 * @param horizontalAlignment default cross-axis child alignment.
 * @param content callback that emits zero or more direct children.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 * @throws IllegalArgumentException when [spacing] is negative.
 * @throws Throwable when [content] fails; the exact callback failure is propagated.
 */
public fun UiScope.Column(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    spacing: Int = 0,
    verticalArrangement: Arrangement = Arrangement.Start,
    horizontalAlignment: HorizontalAlignment = HorizontalAlignment.Start,
    content: ColumnScope.() -> Unit,
) {
    checkUsable()
    require(0 <= spacing) { "Linear layout spacing must be non-negative." }
    val scope = ColumnScope.create()
    val children =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(
        LinearElement(
            orientation = LinearOrientation.Column(horizontalAlignment),
            spacing = spacing,
            arrangement = verticalArrangement,
            key = key,
            children = children,
            modifier = modifier,
        ),
    )
}

/**
 * Emits one overlay stack with the descriptions produced by its callback.
 *
 * The callback scope is confined to the constructing thread and is closed immediately after the callback returns.
 *
 * @param modifier ordered active behavior applied to the stack itself, including sizing, padding, paint, semantics, focus, and input behavior.
 * @param key optional stable identity among direct siblings.
 * @param contentAlignment default two-axis child alignment.
 * @param content callback that emits zero or more direct children.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 * @throws Throwable when [content] fails; the exact callback failure is propagated.
 */
public fun UiScope.Stack(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: StackScope.() -> Unit,
) {
    checkUsable()
    val scope = StackScope.create()
    val children =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(
        StackElement(
            contentAlignment = contentAlignment,
            key = key,
            children = children,
            modifier = modifier,
        ),
    )
}

/**
 * Emits one fixed-column grid with the descriptions produced by its callback.
 *
 * Children are assigned to cells in row-major order.
 * Every column uses the greatest measured width in that column and every row uses the greatest measured height in that row.
 * An incomplete final row is supported without creating placeholder children.
 *
 * @param columns positive number of columns.
 * @param modifier ordered active behavior applied to the grid itself, including sizing, padding, paint, semantics, focus, and input behavior.
 * @param key optional stable identity among direct siblings.
 * @param horizontalSpacing non-negative fixed spacing between adjacent columns.
 * @param verticalSpacing non-negative fixed spacing between adjacent rows.
 * @param contentAlignment default placement of each child inside its measured cell.
 * @param content callback that emits zero or more direct children.
 * @throws IllegalArgumentException when [columns] is not positive or either spacing value is negative.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 * @throws Throwable when [content] fails; the exact callback failure is propagated.
 */
public fun UiScope.Grid(
    columns: Int,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    horizontalSpacing: Int = 0,
    verticalSpacing: Int = 0,
    contentAlignment: Alignment = Alignment.TopStart,
    content: GridScope.() -> Unit,
) {
    checkUsable()
    require(0 < columns) { "Grid columns must be positive." }
    require(0 <= horizontalSpacing) { "Grid horizontal spacing must be non-negative." }
    require(0 <= verticalSpacing) { "Grid vertical spacing must be non-negative." }
    val scope = GridScope.create()
    val children =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(
        GridElement(
            columns = columns,
            horizontalSpacing = horizontalSpacing,
            verticalSpacing = verticalSpacing,
            contentAlignment = contentAlignment,
            key = key,
            children = children,
            modifier = modifier,
        ),
    )
}

/**
 * Emits one empty spacer component.
 *
 * @param modifier active behavior applied to the spacer.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 */
public fun UiScope.Spacer(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(SpacerElement(key = key, modifier = modifier))
}

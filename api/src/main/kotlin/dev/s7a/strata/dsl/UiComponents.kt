@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.dsl

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.BoxElement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LinearElement
import dev.s7a.strata.layout.LinearOrientation
import dev.s7a.strata.layout.SpacerElement
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
 * Emits one box container with the descriptions produced by its callback.
 *
 * The callback scope is confined to the constructing thread and is closed immediately after the callback returns.
 *
 * @param modifier ordered active behavior applied to the box itself, including sizing, padding, paint, semantics, focus, and input behavior.
 * @param key optional stable identity among direct siblings.
 * @param contentAlignment default two-axis child alignment.
 * @param content callback that emits zero or more direct children.
 * @throws IllegalStateException when the enclosing scope has escaped its callback or constructing thread.
 * @throws Throwable when [content] fails; the exact callback failure is propagated.
 */
public fun UiScope.Box(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: BoxScope.() -> Unit,
) {
    checkUsable()
    val scope = BoxScope.create()
    val children =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(
        BoxElement(
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

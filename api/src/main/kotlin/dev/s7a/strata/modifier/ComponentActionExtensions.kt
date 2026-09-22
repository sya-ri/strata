package dev.s7a.strata.modifier

import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.action.ListLoadRequest

/**
 * Handles and consumes Checkbox selected-value changes.
 */
public fun Modifier.onCheckedChange(action: (Boolean) -> Unit): Modifier =
    onRemoteComponentAction(ComponentActions.CheckedChange) { value ->
        action(value)
    }

/**
 * Handles and consumes Slider numeric-value changes.
 */
public fun Modifier.onSliderChange(action: (Double) -> Unit): Modifier =
    onRemoteComponentAction(ComponentActions.SliderChange) { value ->
        action(value)
    }

/**
 * Handles and consumes typed CycleButton value changes.
 */
public fun <T : Any> Modifier.onCycle(action: (T) -> Unit): Modifier =
    onRemoteComponentAction(ComponentActions.Cycle) { value ->
        @Suppress("UNCHECKED_CAST")
        action(value as T)
    }

/**
 * Handles and consumes typed SelectionList key changes.
 */
public fun <K : Any> Modifier.onSelectionChange(action: (K) -> Unit): Modifier =
    onRemoteComponentAction(ComponentActions.SelectionChange) { value ->
        @Suppress("UNCHECKED_CAST")
        action(value as K)
    }

/**
 * Handles and consumes demand for rows before a virtual list's loaded window.
 */
public fun Modifier.onLeadingItemsRequested(action: (ListLoadRequest) -> Unit): Modifier =
    onRemoteComponentAction(ComponentActions.LeadingItemsRequested) { request ->
        action(request)
    }

/**
 * Handles and consumes demand for rows after a virtual list's loaded window.
 */
public fun Modifier.onTrailingItemsRequested(action: (ListLoadRequest) -> Unit): Modifier =
    onRemoteComponentAction(ComponentActions.TrailingItemsRequested) { request ->
        action(request)
    }

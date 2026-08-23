package dev.s7a.strata.modifier

import dev.s7a.strata.action.ActionResult
import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.action.ListLoadRequest

/**
 * Handles and consumes Checkbox selected-value changes.
 */
public fun Modifier.onCheckedChange(action: (Boolean) -> Unit): Modifier =
    onAction(ComponentActions.CheckedChange) { value ->
        action(value)
        ActionResult.Consumed
    }

/**
 * Handles and consumes Slider numeric-value changes.
 */
public fun Modifier.onSliderChange(action: (Double) -> Unit): Modifier =
    onAction(ComponentActions.SliderChange) { value ->
        action(value)
        ActionResult.Consumed
    }

/**
 * Handles and consumes typed CycleButton value changes.
 */
public fun <T : Any> Modifier.onCycle(action: (T) -> Unit): Modifier =
    onAction(ComponentActions.Cycle) { value ->
        @Suppress("UNCHECKED_CAST")
        action(value as T)
        ActionResult.Consumed
    }

/**
 * Handles and consumes typed SelectionList key changes.
 */
public fun <K : Any> Modifier.onSelectionChange(action: (K) -> Unit): Modifier =
    onAction(ComponentActions.SelectionChange) { value ->
        @Suppress("UNCHECKED_CAST")
        action(value as K)
        ActionResult.Consumed
    }

/**
 * Handles and consumes demand for rows before a virtual list's loaded window.
 */
public fun Modifier.onLeadingItemsRequested(action: (ListLoadRequest) -> Unit): Modifier =
    onAction(ComponentActions.LeadingItemsRequested) { request ->
        action(request)
        ActionResult.Consumed
    }

/**
 * Handles and consumes demand for rows after a virtual list's loaded window.
 */
public fun Modifier.onTrailingItemsRequested(action: (ListLoadRequest) -> Unit): Modifier =
    onAction(ComponentActions.TrailingItemsRequested) { request ->
        action(request)
        ActionResult.Consumed
    }

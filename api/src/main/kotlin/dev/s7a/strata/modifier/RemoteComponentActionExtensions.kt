package dev.s7a.strata.modifier

import dev.s7a.strata.action.ActionHandler
import dev.s7a.strata.action.ActionKey
import dev.s7a.strata.action.ActionResult
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.ui.UiSession
import kotlin.jvm.JvmSynthetic

/**
 * Marks ordinary consuming component notifications for delivery by the authoritative server primitive.
 * The handler remains in the original active chain; only its empty client-side marker is transferable.
 */
@JvmSynthetic
internal fun <T : Any> Modifier.onRemoteComponentAction(
    key: ActionKey<T>,
    action: UiSession.(T) -> Unit,
): Modifier =
    then(
        ActionModifier.Element(
            ActionHandler(key) { value ->
                @Suppress("UNCHECKED_CAST")
                action(value as T)
                ActionResult.Consumed
            },
            BuiltinProjection.ComponentAction.properties(),
        ),
    )

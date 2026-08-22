@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor

/**
 * Emits one application-specific energy gauge by composing only public Strata primitives.
 *
 * The function demonstrates that downstream components may encode their own domain without becoming Strata standard built-ins or registering a concrete kind.
 * It retains no scope or callback after synchronous emission and follows the active scope's owner-thread and callback-lifetime contract.
 *
 * @receiver active external application scope.
 * @param energy current non-negative energy amount no larger than [capacity].
 * @param capacity positive maximum energy amount.
 * @param modifier active behavior surrounding the fixed 20 by 4 gauge.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when the energy range is invalid.
 * @throws ArithmeticException when checked fill-width arithmetic overflows.
 * @throws IllegalStateException when the receiver is used from another thread or outside its callback lifetime.
 */
public fun UiScope.EnergyGauge(
    energy: Int,
    capacity: Int,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    require(0 < capacity) { "Energy capacity must be positive." }
    require(0 <= energy && energy <= capacity) { "Energy must be inside zero through capacity." }
    val fillWidth = Math.multiplyExact(energy, 20) / capacity
    Stack(
        modifier = modifier.size(20, 4).background(ArgbColor(0xFF202020.toInt())),
        key = key,
    ) {
        Spacer(
            modifier =
                Modifier.Empty
                    .size(fillWidth, 4)
                    .background(ArgbColor(0xFF00D4FF.toInt())),
        )
    }
}

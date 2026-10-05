package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionContext.isComposition
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Rejects known retained-state factories directly inside resolved Strata declaration functions.
 * It does not guess through ordinary lambdas, helper calls, unresolved calls, or arbitrary factories.
 */
internal class StateCreatedDuringComposition(
    config: Config,
) : Rule(
        config,
        "Retain UI state and source projections outside reevaluated Strata content.",
    ),
    RequiresAnalysisApi {
    /**
     * Reports resolved state creation at the nearest immediate declaration boundary.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val owner = expression.parents.filterIsInstance<KtFunction>().firstOrNull() ?: return
        val forbidden =
            analyze(expression) {
                if (isComposition(owner).not()) return@analyze false
                val call = expression.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>() ?: return@analyze false
                when (val symbol = call.partiallyAppliedSymbol.signature.symbol) {
                    is KaConstructorSymbol -> (symbol.returnType as? KaClassType)?.classId?.asSingleFqName()?.asString() in STATE_TYPES
                    is KaNamedFunctionSymbol -> symbol.callableId?.asSingleFqName()?.asString() in STATE_FACTORIES
                    else -> false
                }
            }
        if (forbidden) report(Finding(Entity.from(expression), "Create and retain this state or projection in the screen owner, then pass it into content."))
    }

    private companion object {
        val STATE_TYPES =
            setOf(
                "dev.s7a.strata.component.ScrollState",
                "dev.s7a.strata.component.TextFieldState",
                "dev.s7a.strata.component.TextAreaState",
                "dev.s7a.strata.component.VirtualListState",
                "dev.s7a.strata.component.PanZoomState",
                "dev.s7a.strata.component.CheckboxState",
                "dev.s7a.strata.component.CycleButtonState",
                "dev.s7a.strata.component.SliderState",
                "dev.s7a.strata.component.SelectionListState",
            )
        val STATE_FACTORIES = setOf("dev.s7a.strata.state.mutableStateOf", "dev.s7a.strata.state.map")
    }
}

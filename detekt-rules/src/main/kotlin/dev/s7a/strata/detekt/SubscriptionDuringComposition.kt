package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.functionCall
import dev.s7a.strata.detekt.CompositionCalls.identity
import dev.s7a.strata.detekt.CompositionCalls.isType
import dev.s7a.strata.detekt.CompositionContext.isEvaluated
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Rejects manual subscriptions acquired during reevaluation; runtime-managed bindings remain valid.
 */
internal class SubscriptionDuringComposition(
    config: Config,
) : Rule(config, "Own manual subscriptions outside reevaluated content and close them."),
    RequiresAnalysisApi {
    /**
     * Checks public source interfaces, including overrides, and standard state observation methods.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val forbidden =
            analyze(expression) {
                if (isEvaluated(expression).not()) return@analyze false
                val call = functionCall(expression) ?: return@analyze false
                val symbol = call.signature.symbol
                val subscribes = (symbol as? KaNamedFunctionSymbol)?.name == Name.identifier("subscribe") && isType(call.dispatchReceiver?.type, "dev.s7a.strata.state.StateSource")
                subscribes || identity(expression) in OBSERVERS
            }
        if (forbidden) report(Finding(Entity.from(expression), "Use supported StateSource inputs or Observe; retain and close manual subscriptions in their owner."))
    }

    private companion object {
        val OBSERVERS =
            listOf("CheckboxState", "CycleButtonState", "SliderState", "ScrollState", "TextFieldState", "TextAreaState", "PanZoomState")
                .map { "dev.s7a.strata.component.$it.observe" }
                .toSet()
    }
}

package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.functionCall
import dev.s7a.strata.detekt.CompositionCalls.isType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelectorOrThis

/**
 * Rejects a discarded immutable Modifier result without rejecting returned modifier factories.
 */
internal class DiscardedModifier(
    config: Config,
) : Rule(config, "Use the new immutable Modifier returned by an operation."),
    RequiresAnalysisApi {
    /**
     * Checks resolved operations using compiler expression usage, including branch and lambda results.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val discarded =
            analyze(expression) {
                val call = functionCall(expression) ?: return@analyze false
                if (isType(call.signature.returnType, "dev.s7a.strata.modifier.Modifier").not()) return@analyze false
                val receiver = call.extensionReceiver ?: call.dispatchReceiver
                if (isType(receiver?.type, "dev.s7a.strata.modifier.Modifier").not()) return@analyze false
                expression.getQualifiedExpressionForSelectorOrThis().isUsedAsExpression.not()
            }
        if (discarded) report(Finding(Entity.from(expression), "Modifier operations return a new chain. Pass, assign, or return this result."))
    }
}

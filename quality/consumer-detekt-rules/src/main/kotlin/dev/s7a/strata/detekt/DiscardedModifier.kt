package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.functionCall
import dev.s7a.strata.detekt.CompositionCalls.isType
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Rejects a discarded immutable Modifier result without rejecting returned modifier factories.
 */
internal class DiscardedModifier(
    config: Config,
) : Rule(config, "Use the new immutable Modifier returned by an operation."),
    RequiresAnalysisApi {
    /**
     * Checks direct block statements; returned values and used chains remain valid.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        var statement: KtExpression = expression
        if (statement.parent is KtQualifiedExpression) {
            val qualified = statement.parent as KtQualifiedExpression
            if (qualified.selectorExpression != statement) return
            statement = qualified
        }
        val block = statement.parent as? KtBlockExpression ?: return
        val discarded =
            analyze(expression) {
                val call = functionCall(expression) ?: return@analyze false
                if (isType(call.signature.returnType, "dev.s7a.strata.modifier.Modifier").not()) return@analyze false
                val receiver = call.extensionReceiver ?: call.dispatchReceiver
                if (isType(receiver?.type, "dev.s7a.strata.modifier.Modifier").not()) return@analyze false
                val lambda = block.parent as? KtFunctionLiteral
                val returnsModifier = lambda?.symbol?.returnType?.let { isType(it, "dev.s7a.strata.modifier.Modifier") } == true
                (returnsModifier && block.statements.lastOrNull() == statement).not()
            }
        if (discarded) report(Finding(Entity.from(expression), "Modifier operations return a new chain. Pass, assign, or return this result."))
    }
}

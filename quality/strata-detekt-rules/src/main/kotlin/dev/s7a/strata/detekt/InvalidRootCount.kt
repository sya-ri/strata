package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.functionCall
import dev.s7a.strata.detekt.CompositionCalls.isBuiltinComponent
import dev.s7a.strata.detekt.CompositionCalls.isType
import dev.s7a.strata.detekt.CompositionCalls.matchesIdentity
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Checks proven flat root cardinality in UiDefinition and Observe callbacks, not arbitrary helpers.
 */
internal class InvalidRootCount(
    config: Config,
) : Rule(config, "Emit one root for a definition and at most one for Observe."),
    RequiresAnalysisApi {
    /**
     * Limits counting to flat blocks consisting entirely of known standard component calls.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val violation =
            analyze(expression) {
                val call = functionCall(expression) ?: return@analyze false
                val definition = isType(call.signature.returnType, "dev.s7a.strata.ui.UiDefinition")
                val observed = matchesIdentity(expression, FqName("dev.s7a.strata.component.Observe"))
                if ((definition || observed).not()) return@analyze false
                val lambda =
                    call.valueArgumentMapping.entries
                        .firstOrNull {
                            it.value.symbol.name == Name.identifier("content")
                        }?.key as? KtLambdaExpression ?: return@analyze false
                val statements = lambda.bodyExpression?.statements ?: return@analyze false
                if (statements.all { it is KtCallExpression && isBuiltinComponent(it) }.not()) return@analyze false
                1 < statements.size || (definition && statements.isEmpty())
            }
        if (violation) report(Finding(Entity.from(expression), "Wrap sibling roots in an intentional Row, Column, Grid, or Stack; a definition also needs a root."))
    }
}

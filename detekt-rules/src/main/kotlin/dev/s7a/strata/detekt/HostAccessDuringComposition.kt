package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.identity
import dev.s7a.strata.detekt.CompositionCalls.matchesIdentity
import dev.s7a.strata.detekt.CompositionContext.isEvaluated
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Keeps concrete runtime operations and screen opening at integration or event boundaries.
 * Public Element/Node SPI compositions are permitted.
 */
internal class HostAccessDuringComposition(
    config: Config,
) : Rule(config, "Keep declaration code independent of opening and concrete runtimes."),
    RequiresAnalysisApi {
    /**
     * Checks resolved runtime calls and the one-shot public screen opening operation.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val forbidden =
            analyze(expression) {
                val call = identity(expression) ?: return@analyze false
                isEvaluated(expression) && (call.startsWith("dev.s7a.strata.runtime.") || matchesIdentity(expression, FqName("dev.s7a.strata.ui.UiDefinition.open")))
            }
        if (forbidden) report(Finding(Entity.from(expression), "Move opening and concrete runtime access to an integration boundary or deferred event."))
    }
}

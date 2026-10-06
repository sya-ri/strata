package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.functionCall
import dev.s7a.strata.detekt.CompositionCalls.identity
import dev.s7a.strata.detekt.CompositionCalls.isBuiltinComponent
import dev.s7a.strata.detekt.CompositionCalls.modifiers
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Checks direct standard-tree parent-data placement; custom forwarding and retained aliases need review.
 */
internal class ParentDataOnWrongParent(
    config: Config,
) : Rule(config, "Put parent data on a direct child of the layout that consumes it."),
    RequiresAnalysisApi {
    /**
     * Reports only a resolved provider in a directly forwarded Modifier chain with a known parent.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val wrong =
            analyze(expression) {
                val allowed = PARENTS[identity(expression)] ?: return@analyze false
                val component =
                    expression.parents.filterIsInstance<KtCallExpression>().firstOrNull { call ->
                        modifiers(call).any { argument -> expression == argument || expression.parents.any { it == argument } }
                    } ?: return@analyze false
                val parent = directParent(component) ?: return@analyze false
                parent !in allowed
            }
        if (wrong) report(Finding(Entity.from(expression), "This parent-data operation is attached below a layout that does not consume it. Move it to that layout's direct child."))
    }

    private fun KaSession.directParent(component: KtCallExpression): String? {
        if (functionCall(component)?.extensionReceiver is KaExplicitReceiverValue) return null
        for (ancestor in component.parents.filterIsInstance<KtFunction>()) {
            if (ancestor is KtFunctionLiteral) {
                val call = ancestor.parents.filterIsInstance<KtCallExpression>().firstOrNull() ?: return null
                return if (isBuiltinComponent(call)) identity(call) else null
            }
            return null
        }
        return null
    }

    private companion object {
        val PARENTS =
            mapOf(
                "dev.s7a.strata.component.RowScope.weight" to setOf("Row", "Column"),
                "dev.s7a.strata.component.ColumnScope.weight" to setOf("Row", "Column"),
                "dev.s7a.strata.component.RowScope.align" to setOf("Row"),
                "dev.s7a.strata.component.ColumnScope.align" to setOf("Column"),
                "dev.s7a.strata.component.StackScope.align" to setOf("Stack"),
                "dev.s7a.strata.component.TiledImageScope.atContentPosition" to setOf("TiledImage"),
            ).mapValues { (_, names) -> names.map { "dev.s7a.strata.component.$it" }.toSet() }
    }
}

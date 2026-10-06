package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.identity
import dev.s7a.strata.detekt.CompositionContext.isEvaluated
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression

/**
 * Detects writes to known retained Strata state during synchronous declaration evaluation.
 */
internal class StateMutationDuringComposition(
    config: Config,
) : Rule(config, "Change retained state in events or the owner update phase."),
    RequiresAnalysisApi {
    /**
     * Detects resolved mutation methods while preserving ordinary queries and deferred actions.
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val forbidden = analyze(expression) { isEvaluated(expression) && identity(expression) in MUTATIONS }
        if (forbidden) report(Finding(Entity.from(expression), "Move this state change out of declaration evaluation."))
    }

    /**
     * Detects direct and compound property assignments through their resolved property symbols.
     */
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken !in setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)) return
        if (isStateWrite(expression, expression.left)) report(Finding(Entity.from(expression), "Assign this state in an event or owner update, not while declaring the tree."))
    }

    /**
     * Treats prefix and postfix increments or decrements as state writes.
     */
    override fun visitUnaryExpression(expression: KtUnaryExpression) {
        super.visitUnaryExpression(expression)
        if (expression.operationToken !in setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)) return
        if (isStateWrite(expression, expression.baseExpression)) report(Finding(Entity.from(expression), "Increment or decrement this state in an event or owner update."))
    }

    private fun isStateWrite(
        expression: KtElement,
        target: KtExpression?,
    ): Boolean {
        val reference = (if (target is KtQualifiedExpression) target.selectorExpression else target) as? KtNameReferenceExpression ?: return false
        return analyze(expression) {
            val symbol = reference.mainReference.resolveToSymbol() as? KaCallableSymbol
            isEvaluated(expression) && symbol?.callableId?.asSingleFqName()?.asString() in WRITABLE_STATE
        }
    }

    private companion object {
        val WRITABLE_STATE =
            setOf(
                "dev.s7a.strata.state.MutableState.value",
                "dev.s7a.strata.component.CheckboxState.checked",
                "dev.s7a.strata.component.CycleButtonState.value",
                "dev.s7a.strata.component.SliderState.value",
                "dev.s7a.strata.component.TextFieldState.value",
                "dev.s7a.strata.component.TextAreaState.value",
            )
        val MUTATIONS =
            mapOf(
                "CheckboxState" to listOf("toggle"),
                "CycleButtonState" to listOf("next", "previous"),
                "ScrollState" to listOf("scrollBy", "scrollTo", "updateGeometry"),
                "SelectionListState" to listOf("select", "clearSelection"),
                "VirtualListState" to listOf("jumpToIndex", "jumpToKey", "refresh"),
                "PanZoomState" to listOf("panBy", "centerOn", "zoomBy", "zoomTo", "reset", "updateGeometry"),
            ).flatMap { (type, methods) -> methods.map { "dev.s7a.strata.component.$type.$it" } }.toSet()
    }
}

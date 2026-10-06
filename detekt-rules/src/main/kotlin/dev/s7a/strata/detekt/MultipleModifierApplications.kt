package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionCalls.functionCall
import dev.s7a.strata.detekt.CompositionCalls.isType
import dev.s7a.strata.detekt.CompositionCalls.modifiers
import dev.s7a.strata.detekt.CompositionContext.isComposition
import dev.s7a.strata.detekt.CompositionContext.isEvaluated
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Detects a caller Modifier forwarded to multiple declarations on compatible branch paths.
 * Immutable local aliases are followed; mutually exclusive if/when roots are allowed.
 */
internal class MultipleModifierApplications(
    config: Config,
) : Rule(config, "Apply the caller modifier once at the component boundary."),
    RequiresAnalysisApi {
    /**
     * Reports each reused parameter once, including reuse on a root and its descendant.
     */
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val body = function.bodyExpression ?: return
        val calls =
            (body.collectDescendantsOfType<KtCallExpression>() + listOfNotNull(body as? KtCallExpression)).filter { call ->
                call.parents.takeWhile { it != function }.none { it is KtNamedFunction }
            }
        val reused =
            analyze(function) {
                if (isComposition(function).not()) return@analyze emptyList()
                function.valueParameters.filter { parameter ->
                    val symbol = parameter.symbol
                    if (isType(symbol.returnType, "dev.s7a.strata.modifier.Modifier").not()) return@filter false
                    val applications =
                        calls.filter { call ->
                            val separateDefinition =
                                call.parents.takeWhile { it != function }.filterIsInstance<KtCallExpression>().any { ancestor ->
                                    isType(functionCall(ancestor)?.signature?.returnType, "dev.s7a.strata.ui.UiDefinition")
                                }
                            separateDefinition.not() && isEvaluated(call) && modifiers(call).any { uses(it, symbol, mutableSetOf()) }
                        }
                    applications.any { left -> applications.any { right -> left != right && compatibleBranches(left, right) } }
                }
            }
        reused.forEach { report(Finding(Entity.from(it), "This caller modifier reaches multiple nodes on the same branch. Apply it to one outer root and use internal modifiers for children.")) }
    }

    private fun KaSession.uses(
        expression: KtExpression,
        parameter: KaSymbol,
        visited: MutableSet<KtProperty>,
    ): Boolean {
        val references = expression.collectDescendantsOfType<KtNameReferenceExpression>() + listOfNotNull(expression as? KtNameReferenceExpression)
        return references.filter { reference -> reference == expression || reference.parents.takeWhile { it != expression }.none { it is KtFunction } }.any { reference ->
            val symbol = reference.mainReference.resolveToSymbol()
            if (symbol == parameter) {
                true
            } else {
                val alias = symbol?.psi as? KtProperty
                val initializer = alias?.initializer
                alias != null && alias.isVar.not() && initializer != null && visited.add(alias) && uses(initializer, parameter, visited)
            }
        }
    }

    private fun compatibleBranches(
        left: KtCallExpression,
        right: KtCallExpression,
    ): Boolean {
        val leftPath = listOf(left) + left.parents.toList()
        val rightPath = listOf(right) + right.parents.toList()
        if (leftPath.filterIsInstance<KtWhenEntry>().any { entry -> rightPath.filterIsInstance<KtWhenEntry>().any { it.parent == entry.parent && it != entry } }) return false
        return leftPath.filterIsInstance<KtIfExpression>().none { branch ->
            (leftPath.any { it == branch.then } && rightPath.any { it == branch.`else` }) ||
                (leftPath.any { it == branch.`else` } && rightPath.any { it == branch.then })
        }
    }
}

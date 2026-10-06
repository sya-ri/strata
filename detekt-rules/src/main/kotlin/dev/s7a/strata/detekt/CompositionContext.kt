package dev.s7a.strata.detekt

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Recognizes resolved Strata DSL receivers rather than functions with similar names.
 * Known immediate library lambdas preserve evaluation context; ordinary actions and local helpers do not.
 */
internal object CompositionContext {
    /**
     * Determines whether a function executes directly in a Strata declaration scope.
     */
    internal fun KaSession.isComposition(function: KtFunction): Boolean {
        val symbol = function.symbol as? KaCallableSymbol ?: return false
        val receiver = symbol.receiverParameter?.returnType ?: return false
        return isUiScope(receiver)
    }

    /**
     * Follows only known synchronous standard-library lambdas through to their declaration owner.
     * Deferred actions and arbitrary helpers remain separate execution boundaries.
     */
    internal fun KaSession.isEvaluated(element: KtElement): Boolean {
        for (function in element.parents.filterIsInstance<KtFunction>()) {
            if (isComposition(function)) return true
            if (function is KtFunctionLiteral) {
                val call = function.parents.filterIsInstance<KtCallExpression>().firstOrNull()
                val resolved = call?.resolveToCall()?.successfulCallOrNull<KaFunctionCall<*>>()
                val identity =
                    resolved
                        ?.signature
                        ?.symbol
                        ?.callableId
                        ?.asSingleFqName()
                        ?.asString()
                if (identity in SYNCHRONOUS_LAMBDAS) continue
            }
            return false
        }
        return false
    }

    private fun KaSession.isUiScope(type: KaType): Boolean {
        val classType = type as? KaClassType ?: return false
        return classType.classId == ClassId.topLevel(FqName("dev.s7a.strata.component.UiScope")) ||
            (classType.expandedSymbol?.superTypes?.any { isUiScope(it) } ?: false)
    }

    private val SYNCHRONOUS_LAMBDAS =
        setOf(
            "kotlin.run",
            "kotlin.with",
            "kotlin.let",
            "kotlin.also",
            "kotlin.apply",
            "kotlin.repeat",
            "kotlin.collections.forEach",
            "kotlin.collections.forEachIndexed",
            "kotlin.collections.map",
            "kotlin.collections.mapIndexed",
        )
}

package dev.s7a.strata.detekt

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFunction

/**
 * Recognizes resolved Strata DSL receivers rather than functions with similar names.
 * The nearest function boundary deliberately excludes ordinary action callbacks and local helpers.
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

    private fun KaSession.isUiScope(type: KaType): Boolean {
        val classType = type as? KaClassType ?: return false
        if (classType.classId == ClassId.topLevel(FqName("dev.s7a.strata.component.UiScope"))) return true
        return classType.expandedSymbol?.superTypes?.any { isUiScope(it) } ?: false
    }
}

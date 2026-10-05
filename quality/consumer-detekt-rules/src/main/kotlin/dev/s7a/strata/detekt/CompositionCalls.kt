package dev.s7a.strata.detekt

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Shares resolved public-call and argument contracts between consumer authoring checks.
 */
internal object CompositionCalls {
    /**
     * Resolves function arguments including positional arguments and import aliases.
     */
    internal fun KaSession.functionCall(expression: KtCallExpression): KaFunctionCall<*>? = expression.resolveToCall()?.successfulCallOrNull<KaFunctionCall<*>>()

    /**
     * Returns the declared callable identity rather than source spelling.
     */
    internal fun KaSession.identity(expression: KtCallExpression): String? {
        val symbol = functionCall(expression)?.signature?.symbol ?: return null
        return if (symbol is KaConstructorSymbol) {
            (symbol.returnType as? KaClassType)?.classId?.asSingleFqName()?.asString()
        } else {
            symbol.callableId?.asSingleFqName()?.asString()
        }
    }

    /**
     * Compares a resolved external callable identifier through its typed name.
     */
    internal fun KaSession.matchesIdentity(
        expression: KtCallExpression,
        name: FqName,
    ): Boolean =
        functionCall(expression)
            ?.signature
            ?.symbol
            ?.callableId
            ?.asSingleFqName() == name

    /**
     * Recognizes the selected public type and its resolved subclasses or implementations.
     */
    internal fun KaSession.isType(
        type: KaType?,
        name: String,
    ): Boolean {
        val resolved = type as? KaClassType ?: return false
        return resolved.classId.asSingleFqName().asString() == name || resolved.expandedSymbol?.superTypes?.any { isType(it, name) } == true
    }

    /**
     * Selects only arguments forwarded to a resolved declaration's Modifier parameter.
     */
    internal fun KaSession.modifiers(expression: KtCallExpression): List<KtExpression> {
        val call = functionCall(expression) ?: return emptyList()
        val symbol = call.signature.symbol as? KaNamedFunctionSymbol ?: return emptyList()
        if (isType(symbol.receiverParameter?.returnType, "dev.s7a.strata.component.UiScope").not()) return emptyList()
        if (isType(call.signature.returnType, "kotlin.Unit").not()) return emptyList()
        return call.valueArgumentMapping
            .filterValues { isType(it.returnType, "dev.s7a.strata.modifier.Modifier") }
            .keys
            .toList()
    }

    /**
     * Identifies standard calls which emit exactly one component.
     */
    internal fun KaSession.isBuiltinComponent(expression: KtCallExpression): Boolean = identity(expression) in BUILTIN_COMPONENTS

    private val BUILTIN_COMPONENTS =
        setOf(
            "Row",
            "Column",
            "FlowRow",
            "Stack",
            "Grid",
            "Spacer",
            "Text",
            "TextField",
            "TextArea",
            "Button",
            "Tab",
            "Image",
            "TiledImage",
            "Canvas",
            "PlayerHead",
            "Slot",
            "ProgressBar",
            "LoadingIndicator",
            "Checkbox",
            "CycleButton",
            "Slider",
            "ScrollArea",
            "Scrollbar",
            "VirtualList",
            "SelectionList",
            "Observe",
        ).map { "dev.s7a.strata.component.$it" }.toSet()
}

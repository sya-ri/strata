package dev.s7a.strata.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.s7a.strata.detekt.CompositionContext.isComposition
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a Strata Modifier parameter that is never read by a resolved composition function.
 * Reading a parameter is not proof that it reaches the root once; that remains a tree review.
 */
internal class UnusedComponentModifier(
    config: Config,
) : Rule(
        config,
        "Use the caller's modifier at the component boundary instead of silently discarding it.",
    ),
    RequiresAnalysisApi {
    /**
     * Compares resolved references with parameter symbols, preserving alias and shadowing semantics.
     */
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val body = function.bodyExpression ?: return
        val references = body.collectDescendantsOfType<KtNameReferenceExpression>()
        val ignored =
            analyze(function) {
                if (isComposition(function).not()) return@analyze emptyList()
                function.valueParameters.filter { parameter ->
                    val symbol = parameter.symbol
                    val type = (symbol.returnType as? KaClassType)?.classId
                    type == ClassId.topLevel(FqName("dev.s7a.strata.modifier.Modifier")) && references.none { it.mainReference.resolveToSymbol() == symbol }
                }
            }
        ignored.forEach { parameter -> report(Finding(Entity.from(parameter), "Forward this modifier to the component's outer root.")) }
    }
}

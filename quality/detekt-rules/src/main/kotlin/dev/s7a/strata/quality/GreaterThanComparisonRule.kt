package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import java.net.URI

/**
 * Requires comparison expressions to use the operand-reversed less-than form.
 */
internal class GreaterThanComparisonRule(
    config: Config,
) : Rule(
        config = config,
        description = "Reports greater-than comparisons in favor of less-than comparisons.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    /**
     * Reports a greater-than or greater-than-or-equal comparison without rewriting it.
     *
     * @param expression the binary expression currently being visited.
     */
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        if (expression.operationToken == KtTokens.GT || expression.operationToken == KtTokens.GTEQ) {
            report(
                Finding(
                    entity = Entity.from(expression.operationReference),
                    message = "Use the operand-reversed less-than form (< or <=); preserve side-effect evaluation order manually.",
                ),
            )
        }
        super.visitBinaryExpression(expression)
    }
}

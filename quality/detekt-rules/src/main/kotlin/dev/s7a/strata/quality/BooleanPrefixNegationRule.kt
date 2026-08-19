package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPrefixExpression
import java.net.URI

/**
 * Requires the explicit Boolean `not` operation instead of prefix negation.
 */
internal class BooleanPrefixNegationRule(
    config: Config,
) : Rule(
        config = config,
        description = "Requires Boolean.not() instead of prefix Boolean negation.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    /**
     * Reports Kotlin prefix negation while leaving inequality, `!in`, and `!is` syntax unchanged.
     *
     * @param expression the prefix expression currently being visited.
     */
    override fun visitPrefixExpression(expression: KtPrefixExpression) {
        if (expression.operationToken == KtTokens.EXCL) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "Use Boolean.not() instead of prefix Boolean negation.",
                ),
            )
        }
        super.visitPrefixExpression(expression)
    }
}

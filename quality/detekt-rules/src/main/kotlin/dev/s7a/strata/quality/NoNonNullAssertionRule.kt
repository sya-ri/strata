package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPostfixExpression
import java.net.URI

/**
 * Reports Kotlin's non-null assertion operator so nullable state remains explicit.
 */
internal class NoNonNullAssertionRule(
    config: Config,
) : Rule(
        config = config,
        description = "Reports the non-null assertion operator.",
        url = URI("https://github.com/sya-ri/strata/blob/master/docs/architecture.md"),
    ) {
    /**
     * Reports a postfix expression whose operator is the non-null assertion.
     *
     * @param expression the postfix expression currently being visited.
     */
    override fun visitPostfixExpression(expression: KtPostfixExpression) {
        if (expression.operationToken == KtTokens.EXCLEXCL) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "Avoid the non-null assertion operator; model the nullable state explicitly.",
                ),
            )
        }
        super.visitPostfixExpression(expression)
    }
}

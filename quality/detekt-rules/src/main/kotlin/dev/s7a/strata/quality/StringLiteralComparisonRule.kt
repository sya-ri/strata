package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import java.net.URI

/**
 * Reports direct string-literal comparisons and string-discriminating when branches.
 */
internal class StringLiteralComparisonRule(
    config: Config,
) : Rule(
        config = config,
        description = "Reports direct string comparisons and string-discriminating when branches.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    /**
     * Reports equality or inequality when either operand is a plain string literal.
     *
     * @param expression the binary expression currently being visited.
     */
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        if (expression.operationToken == KtTokens.EQEQ || expression.operationToken == KtTokens.EXCLEQ) {
            val left = expression.left
            val right = expression.right
            if (isPlainStringLiteral(left) || isPlainStringLiteral(right)) {
                report(
                    Finding(
                        entity = Entity.from(expression.operationReference),
                        message = "Parse external strings into a typed state before comparison.",
                    ),
                )
            }
        }
        super.visitBinaryExpression(expression)
    }

    /**
     * Reports a when branch whose condition directly discriminates on a string literal.
     *
     * @param condition the when condition currently being visited.
     */
    override fun visitWhenConditionWithExpression(condition: KtWhenConditionWithExpression) {
        if (isPlainStringLiteral(condition.expression) && condition.parent is KtWhenEntry) {
            report(
                Finding(
                    entity = Entity.from(condition),
                    message = "Parse external strings into a typed state before when discrimination.",
                ),
            )
        }
        super.visitWhenConditionWithExpression(condition)
    }

    private fun isPlainStringLiteral(expression: KtExpression?): Boolean = expression is KtStringTemplateExpression && expression.entries.all { it is KtLiteralStringTemplateEntry }
}

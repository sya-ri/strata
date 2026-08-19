package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import java.net.URI

/**
 * Reports qualified type and static references in executable Kotlin bodies.
 */
internal class BodyQualifiedNameRule(
    config: Config,
) : Rule(
        config = config,
        description = "Reports body-qualified references that should use imports.",
        url = URI("https://github.com/sya-ri/strata/blob/master/docs/architecture.md"),
    ) {
    /**
     * Reports a qualified user type when it is written directly in a body.
     *
     * @param typeReference the type reference currently being visited.
     */
    override fun visitTypeReference(typeReference: KtTypeReference) {
        val userType = typeReference.typeElement as? KtUserType
        if (userType != null && isQualifiedType(userType)) {
            report(
                Finding(
                    entity = Entity.from(typeReference),
                    message = "Use an import instead of a body-qualified type reference.",
                ),
            )
        }
        super.visitTypeReference(typeReference)
    }

    /**
     * Reports a qualified static call while leaving ordinary member chains alone.
     *
     * @param expression the qualified expression currently being visited.
     */
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        if (expression.parent !is KtQualifiedExpression && isQualifiedStaticReference(expression)) {
            report(
                Finding(
                    entity = Entity.from(expression),
                    message = "Use an import instead of a body-qualified static reference.",
                ),
            )
        }
        super.visitDotQualifiedExpression(expression)
    }

    private fun isQualifiedType(userType: KtUserType): Boolean {
        val segments = collectTypeSegments(userType)
        return 2 <= segments.size && isPackageRoot(segments.first())
    }

    private fun collectTypeSegments(userType: KtUserType): List<String> {
        val qualifier = userType.qualifier
        val current = userType.referencedName
        return if (qualifier == null) {
            listOfNotNull(current)
        } else {
            collectTypeSegments(qualifier) + listOfNotNull(current)
        }
    }

    private fun isQualifiedStaticReference(expression: KtDotQualifiedExpression): Boolean {
        val segments = collectExpressionSegments(expression)
        return 3 <= segments.size && isPackageRoot(segments.first())
    }

    private fun collectExpressionSegments(expression: KtExpression): List<String> =
        when (expression) {
            is KtNameReferenceExpression -> {
                listOf(expression.getReferencedName())
            }

            is KtDotQualifiedExpression -> {
                val selector = expression.selectorExpression
                val selectorName = (selector as? KtNameReferenceExpression)?.getReferencedName()
                collectExpressionSegments(expression.receiverExpression) + listOfNotNull(selectorName)
            }

            else -> {
                emptyList()
            }
        }

    private fun isPackageRoot(segment: String): Boolean = segment in PACKAGE_ROOTS

    private companion object {
        val PACKAGE_ROOTS: Set<String> =
            setOf(
                "com",
                "dev",
                "io",
                "java",
                "javax",
                "kotlin",
                "kotlinx",
                "net",
                "org",
            )
    }
}

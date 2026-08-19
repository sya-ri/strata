package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.net.URI

/**
 * Requires multiline KDoc on externally visible classes and methods.
 */
internal class MultilineKDocRule(
    config: Config,
) : Rule(
        config = config,
        description = "Requires KDoc on visible classes and methods and multiline syntax for any existing KDoc.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    /**
     * Reports a visible class without multiline KDoc.
     *
     * @param declaration the class or object currently being visited.
     */
    override fun visitClassOrObject(declaration: KtClassOrObject) {
        reportIfMissingDocumentation(declaration)
        super.visitClassOrObject(declaration)
    }

    /**
     * Reports a visible method without multiline KDoc.
     *
     * @param declaration the function currently being visited.
     */
    override fun visitNamedFunction(declaration: KtNamedFunction) {
        reportIfMissingDocumentation(declaration)
        super.visitNamedFunction(declaration)
    }

    /**
     * Reports a declaration that has a one-line KDoc regardless of its visibility.
     *
     * @param declaration the declaration currently being visited.
     */
    override fun visitDeclaration(declaration: KtDeclaration) {
        val documentation = declaration.docComment
        if (documentation != null && documentation.text.contains('\n').not()) {
            report(
                Finding(
                    entity = Entity.from(declaration),
                    message = "Use multiline KDoc for declarations that have documentation.",
                ),
            )
        }
        super.visitDeclaration(declaration)
    }

    private fun reportIfMissingDocumentation(declaration: KtDeclaration) {
        if (requiresDocumentation(declaration) && declaration.docComment == null) {
            report(
                Finding(
                    entity = Entity.from(declaration),
                    message = "Add multiline KDoc describing this declaration's contract.",
                ),
            )
        }
    }

    private fun requiresDocumentation(declaration: KtDeclaration): Boolean {
        val owner = declaration.parent
        if (owner !is KtFile && owner !is KtClassBody) {
            return false
        }
        if (hasPrivateEnclosingDeclaration(declaration)) {
            return false
        }
        if (declaration.modifierList?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true) {
            return false
        }
        if (declaration.modifierList?.hasModifier(KtTokens.OVERRIDE_KEYWORD) == true) {
            return false
        }
        if (declaration is KtNamedFunction && declaration.annotationEntries.any(::isTestAnnotation)) {
            return false
        }
        return true
    }

    private fun hasPrivateEnclosingDeclaration(declaration: KtDeclaration): Boolean {
        var ancestor = declaration.parent
        while (ancestor != null) {
            if (ancestor is KtDeclaration &&
                ancestor.modifierList?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true
            ) {
                return true
            }
            ancestor = ancestor.parent
        }
        return false
    }

    private fun isTestAnnotation(annotation: KtAnnotationEntry): Boolean {
        val names =
            listOfNotNull(
                annotation.shortName?.asString(),
                annotation.calleeExpression?.text,
            )
        return names.any { name -> name in JUNIT_TEST_ANNOTATION_NAMES }
    }

    private companion object {
        val JUNIT_TEST_ANNOTATION_NAMES: Set<String> =
            setOf(
                "Test",
                "ParameterizedTest",
                "RepeatedTest",
                "TestFactory",
                "TestTemplate",
                "org.junit.jupiter.api.Test",
                "org.junit.jupiter.api.RepeatedTest",
                "org.junit.jupiter.api.TestFactory",
                "org.junit.jupiter.api.TestTemplate",
                "org.junit.jupiter.params.ParameterizedTest",
            )
    }
}

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
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.net.URI

/**
 * Requires multiline KDoc on visible classes and methods.
 * Enum values may rely on their enclosing contract; their methods still require documentation.
 * Existing KDoc must have no edge padding and must mark paragraph separators with an asterisk.
 */
internal class MultilineKDocRule(
    config: Config,
) : Rule(
        config = config,
        description = "Requires documented visible declarations and multiline KDoc without empty edge padding or unmarked blank lines.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    override fun visitClassOrObject(declaration: KtClassOrObject) {
        reportIfMissingDocumentation(declaration)
        super.visitClassOrObject(declaration)
    }

    override fun visitNamedFunction(declaration: KtNamedFunction) {
        reportIfMissingDocumentation(declaration)
        super.visitNamedFunction(declaration)
    }

    override fun visitDeclaration(declaration: KtDeclaration) {
        val violation = declaration.docComment?.let { formattingViolation(it.text) }
        if (violation != null) {
            report(
                Finding(
                    entity = Entity.from(declaration),
                    message = violation,
                ),
            )
        }
        super.visitDeclaration(declaration)
    }

    private fun formattingViolation(documentation: String): String? {
        val lines = documentation.lines()
        if (lines.size == 1) {
            return "Use multiline KDoc for declarations that have documentation."
        }
        val content = lines.drop(1).dropLast(1)
        if (content.any(String::isBlank)) {
            return "Remove unmarked blank lines from KDoc; use an asterisk-only line between paragraphs."
        }
        if (content.firstOrNull()?.trim() == "*" || content.lastOrNull()?.trim() == "*") {
            return "Remove empty lines immediately after the KDoc opening or before its closing delimiter."
        }
        return null
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
        if (declaration is KtEnumEntry) {
            return false
        }
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

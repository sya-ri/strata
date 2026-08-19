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
        description = "Requires multiline KDoc on public, protected, and internal declarations.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    /**
     * Reports a visible class without multiline KDoc.
     *
     * @param declaration the class or object currently being visited.
     */
    override fun visitClassOrObject(declaration: KtClassOrObject) {
        reportIfUndocumented(declaration)
        super.visitClassOrObject(declaration)
    }

    /**
     * Reports a visible method without multiline KDoc.
     *
     * @param declaration the function currently being visited.
     */
    override fun visitNamedFunction(declaration: KtNamedFunction) {
        reportIfUndocumented(declaration)
        super.visitNamedFunction(declaration)
    }

    private fun reportIfUndocumented(declaration: KtDeclaration) {
        if (isDocumentedDeclaration(declaration).not()) {
            report(
                Finding(
                    entity = Entity.from(declaration),
                    message = "Add multiline KDoc describing this declaration's contract.",
                ),
            )
        }
    }

    private fun isDocumentedDeclaration(declaration: KtDeclaration): Boolean {
        val owner = declaration.parent
        if (owner !is KtFile && owner !is KtClassBody) {
            return true
        }
        if (declaration.modifierList?.hasModifier(KtTokens.PRIVATE_KEYWORD) == true) {
            return true
        }
        if (declaration.modifierList?.hasModifier(KtTokens.OVERRIDE_KEYWORD) == true) {
            return true
        }
        if (declaration is KtNamedFunction && declaration.annotationEntries.any(::isTestAnnotation)) {
            return true
        }
        val text = declaration.docComment?.text ?: return false
        return text.contains('\n')
    }

    private fun isTestAnnotation(annotation: KtAnnotationEntry): Boolean {
        val name = annotation.shortName?.asString() ?: return false
        return name in TEST_ANNOTATION_NAMES || TEST_ANNOTATION_SUFFIXES.any(name::endsWith)
    }

    private companion object {
        val TEST_ANNOTATION_NAMES: Set<String> = setOf("Test")
        val TEST_ANNOTATION_SUFFIXES: Set<String> = setOf("Test")
    }
}

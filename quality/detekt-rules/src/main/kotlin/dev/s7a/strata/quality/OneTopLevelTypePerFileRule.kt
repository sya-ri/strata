package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.net.URI

/**
 * Requires each Kotlin file to contain at most one named top-level type.
 */
internal class OneTopLevelTypePerFileRule(
    config: Config,
) : Rule(
        config = config,
        description = "Requires one named top-level type per Kotlin file.",
        url = URI("https://github.com/sya-ri/strata/blob/master/AGENTS.md"),
    ) {
    /**
     * Reports a file containing multiple named top-level types.
     *
     * @param file the Kotlin file currently being visited.
     */
    override fun visitKtFile(file: KtFile) {
        val typeCount =
            file.declarations.count { declaration ->
                (declaration is KtClassOrObject || declaration is KtTypeAlias) && declaration.name != null
            }
        if (1 < typeCount) {
            report(
                Finding(
                    entity = Entity.from(file),
                    message = "Keep one named top-level type per Kotlin file; found $typeCount.",
                ),
            )
        }
        super.visitKtFile(file)
    }
}

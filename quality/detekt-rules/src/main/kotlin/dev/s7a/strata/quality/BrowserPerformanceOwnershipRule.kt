package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.net.URI

/**
 * Reserves direct browser clocks, timestamp callbacks and raw JavaScript for the shared collector.
 * JS analysis has no type resolution: these names are deliberately forbidden in integration fixtures,
 * including references that would alias a clock before calling it.
 * This is a source ownership policy, not a sandbox for adversarial dynamically computed JavaScript.
 */
internal class BrowserPerformanceOwnershipRule(
    config: Config,
) : Rule(
        config = config,
        description = "Browser fixtures must use the shared performance meter instead of direct clocks or timestamp callbacks.",
        url = URI("https://github.com/sya-ri/strata/blob/master/docs/development/performance-testkit.md"),
    ) {
    /**
     * Rejects imported clock aliases before their original names disappear from executable code.
     */
    override fun visitImportDirective(importDirective: KtImportDirective) {
        val path = importDirective.importedFqName?.asString()
        val clockImport = path?.let { isClockImport(it, importDirective.isAllUnder) } ?: false
        if (clockImport) {
            report(Finding(Entity.from(importDirective), "Import the shared browser meter instead of a clock or raw JavaScript entry point."))
        }
        super.visitImportDirective(importDirective)
    }

    /**
     * Rejects clock reads, callable references and aliases without requiring JVM-only type analysis.
     */
    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        if (expression.parents.none { it is KtImportDirective || it is KtPackageDirective } && expression.getReferencedName() in CLOCK_NAMES) {
            report(Finding(Entity.from(expression), "Keep clocks and timestamp callbacks inside BrowserPerformanceMeter."))
        }
        super.visitSimpleNameExpression(expression)
    }

    /**
     * Applies the same reserved-name policy to literal dynamic property access.
     */
    override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
        expression.indexExpressions.forEach { index ->
            val key = ((index as? KtStringTemplateExpression)?.entries?.singleOrNull() as? KtLiteralStringTemplateEntry)?.text
            if (key in CLOCK_NAMES) report(Finding(Entity.from(index), "Keep dynamic clock access inside BrowserPerformanceMeter."))
        }
        super.visitArrayAccessExpression(expression)
    }

    private fun isClockImport(
        path: String,
        allUnder: Boolean,
    ): Boolean {
        val reservedName = path.substringAfterLast('.') in CLOCK_NAMES
        val declaredClock = CLOCK_IMPORTS.any { path == it || path.startsWith("$it.") }
        val clockWildcard = allUnder && path in CLOCK_PACKAGES
        return reservedName || declaredClock || clockWildcard
    }

    private companion object {
        val CLOCK_NAMES = setOf("performance", "Date", "TimeSource", "markNow", "elapsedNow", "measureTime", "measureTimedValue", "measureNanoTime", "measureTimeMillis", "nanoTime", "currentTimeMillis", "requestAnimationFrame", "js")
        val CLOCK_PACKAGES = setOf("kotlin.js", "kotlin.time", "kotlin.system")
        val CLOCK_IMPORTS = setOf("kotlin.js.Date", "kotlin.js.js", "kotlin.time.TimeSource", "kotlin.time.measureTime", "kotlin.time.measureTimedValue", "kotlin.system.measureNanoTime", "kotlin.system.measureTimeMillis")
    }
}

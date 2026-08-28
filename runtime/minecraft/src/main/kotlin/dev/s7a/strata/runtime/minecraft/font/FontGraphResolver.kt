package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import java.util.Collections

/**
 * Resolves ordered provider references with outer-filter precedence and whole-bundle failure semantics.
 *
 * @param declarations complete parsed font definitions.
 * @param diagnostics caller-owned append-only load diagnostics.
 * @param budget loader-owned reference-depth and expansion counters.
 */
internal class FontGraphResolver(
    private val declarations: Map<ResourceId, List<FontProviderEntry>>,
    private val diagnostics: MutableList<MinecraftFontDiagnostic>,
    private val budget: FontLoadBudget,
) {
    private val results = LinkedHashMap<ResourceId, Resolution>()

    /**
     * Returns only fully resolved bundles, retaining no mutable resolver state in each provider list.
     */
    fun resolve(): Map<ResourceId, List<FontProviderEntry>> {
        for (font in declarations.keys) {
            if (resolve(font, 1) === Resolution.DepthLimited) {
                diagnostics += MinecraftFontDiagnostic(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, font, declarations[font]?.firstOrNull()?.source.orEmpty(), "Font reference depth exceeds ${budget.limits.maxReferenceDepth} for $font.")
                results[font] = Resolution.Failed
            }
        }
        return results.mapNotNull { (id, result) -> (result as? Resolution.Ready)?.let { id to it.providers } }.toMap(LinkedHashMap())
    }

    private fun resolve(
        font: ResourceId,
        depth: Int,
    ): Resolution {
        if (budget.limits.maxReferenceDepth < depth) return Resolution.DepthLimited
        when (val existing = results[font]) {
            is Resolution.Ready -> {
                return if (budget.limits.maxReferenceDepth.toLong() < depth.toLong() + existing.depth - 1) Resolution.DepthLimited else existing
            }

            Resolution.Failed -> {
                return existing
            }

            Resolution.Visiting -> {
                diagnostics += MinecraftFontDiagnostic(MinecraftFontDiagnostic.Kind.CyclicReference, font, "", "Font reference cycle contains $font.")
                return Resolution.Failed
            }

            Resolution.DepthLimited, null -> {}
        }
        val entries = declarations[font]
        if (entries == null) {
            diagnostics += MinecraftFontDiagnostic(MinecraftFontDiagnostic.Kind.MissingReference, font, "", "Referenced font is missing: $font.")
            results[font] = Resolution.Failed
            return Resolution.Failed
        }
        results[font] = Resolution.Visiting
        val result =
            runCatching {
                resolveEntries(entries, depth)
            }.getOrElse { failure ->
                if ((failure is MinecraftFontLoadLimitException).not()) throw failure
                diagnostics += MinecraftFontDiagnostic(MinecraftFontDiagnostic.Kind.ProviderLoadFailure, font, entries.firstOrNull()?.source.orEmpty(), failure.message.orEmpty())
                Resolution.Failed
            }
        if (result === Resolution.DepthLimited) results.remove(font) else results[font] = result
        return result
    }

    private fun resolveEntries(
        entries: List<FontProviderEntry>,
        depth: Int,
    ): Resolution {
        val resolved = ArrayList<FontProviderEntry>()
        var failed = false
        var graphDepth = 1
        for (entry in entries) {
            when (val provider = entry.provider) {
                is FontProvider.Reference -> {
                    when (val target = resolve(provider.font, depth + 1)) {
                        is Resolution.Ready -> {
                            graphDepth = maxOf(graphDepth, target.depth + 1)
                            budget.claim(FontLoadBudget.Kind.ResolvedProviders, target.providers.size.toLong())
                            for (nested in target.providers) {
                                resolved += nested.copy(filter = Collections.unmodifiableMap(nested.filter + entry.filter))
                            }
                        }

                        Resolution.Failed, Resolution.Visiting -> {
                            failed = true
                        }

                        Resolution.DepthLimited -> {
                            return Resolution.DepthLimited
                        }
                    }
                }

                is FontProvider.Failed -> {
                    failed = true
                }

                else -> {
                    budget.claim(FontLoadBudget.Kind.ResolvedProviders, 1)
                    resolved += entry
                }
            }
        }
        return if (failed) Resolution.Failed else Resolution.Ready(Collections.unmodifiableList(resolved), graphDepth)
    }

    private sealed interface Resolution {
        data object Visiting : Resolution

        data object Failed : Resolution

        data object DepthLimited : Resolution

        data class Ready(
            val providers: List<FontProviderEntry>,
            val depth: Int,
        ) : Resolution
    }
}

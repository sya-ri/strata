package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import java.util.Collections

/**
 * Resolves ordered provider references with outer-filter precedence and whole-bundle failure semantics.
 *
 * @param declarations complete parsed font definitions.
 * @param diagnostics caller-owned append-only load diagnostics.
 */
internal class FontGraphResolver(
    private val declarations: Map<ResourceId, List<FontProviderEntry>>,
    private val diagnostics: MutableList<MinecraftFontDiagnostic>,
) {
    private val results = LinkedHashMap<ResourceId, Resolution>()

    /**
     * Returns only fully resolved bundles, retaining no mutable resolver state in each provider list.
     */
    fun resolve(): Map<ResourceId, List<FontProviderEntry>> {
        for (font in declarations.keys) resolve(font)
        return results.mapNotNull { (id, result) -> (result as? Resolution.Ready)?.let { id to it.providers } }.toMap(LinkedHashMap())
    }

    private fun resolve(font: ResourceId): Resolution {
        when (val existing = results[font]) {
            is Resolution.Ready -> {
                return existing
            }

            Resolution.Failed -> {
                return existing
            }

            Resolution.Visiting -> {
                diagnostics += MinecraftFontDiagnostic(MinecraftFontDiagnostic.Kind.CyclicReference, font, "", "Font reference cycle contains $font.")
                return Resolution.Failed
            }

            null -> {}
        }
        val entries = declarations[font]
        if (entries == null) {
            diagnostics += MinecraftFontDiagnostic(MinecraftFontDiagnostic.Kind.MissingReference, font, "", "Referenced font is missing: $font.")
            results[font] = Resolution.Failed
            return Resolution.Failed
        }
        results[font] = Resolution.Visiting
        val resolved = ArrayList<FontProviderEntry>()
        var failed = false
        for (entry in entries) {
            when (val provider = entry.provider) {
                is FontProvider.Reference -> {
                    when (val target = resolve(provider.font)) {
                        is Resolution.Ready -> {
                            for (nested in target.providers) {
                                resolved += nested.copy(filter = Collections.unmodifiableMap(nested.filter + entry.filter))
                            }
                        }

                        Resolution.Failed, Resolution.Visiting -> {
                            failed = true
                        }
                    }
                }

                is FontProvider.Failed -> {
                    failed = true
                }

                else -> {
                    resolved += entry
                }
            }
        }
        val result = if (failed) Resolution.Failed else Resolution.Ready(Collections.unmodifiableList(resolved))
        results[font] = result
        return result
    }

    private sealed interface Resolution {
        data object Visiting : Resolution

        data object Failed : Resolution

        data class Ready(
            val providers: List<FontProviderEntry>,
        ) : Resolution
    }
}

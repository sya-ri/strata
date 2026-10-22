package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.BufferedReader
import java.io.InputStream
import java.util.Optional
import java.util.function.Predicate

/**
 * Observes native resource enumeration and stream ownership for the shared font contract tests.
 */
internal fun observedFontResources(
    manager: ResourceManager,
    reads: MutableList<Identifier>,
    transform: (Resource) -> Resource = { resource -> resource },
    checkReading: () -> Unit,
): ResourceManager =
    object : ResourceManager by manager {
        // Keep inherited convenience methods on this wrapper so they observe and transform getResource reads.
        override fun getResourceOrThrow(location: Identifier): Resource = super.getResourceOrThrow(location)

        override fun open(location: Identifier): InputStream = super.open(location)

        override fun openAsReader(location: Identifier): BufferedReader = super.openAsReader(location)

        override fun listResourceStacks(
            path: String,
            predicate: Predicate<Identifier>,
        ): Map<Identifier, List<Resource>> {
            checkReading()
            assertEquals("font", path)
            return manager.listResourceStacks(path, predicate).mapValues { (_, resources) -> resources.map(transform) }
        }

        override fun getResource(location: Identifier): Optional<Resource> {
            checkReading()
            reads.add(location)
            return manager.getResource(location).map { resource -> transform(resource) }
        }
    }

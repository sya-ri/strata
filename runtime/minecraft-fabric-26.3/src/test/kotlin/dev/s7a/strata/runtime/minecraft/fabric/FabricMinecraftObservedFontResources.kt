package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Optional

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
        override fun listResourceStacks(
            path: String,
            predicate: ResourceManager.Selector,
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

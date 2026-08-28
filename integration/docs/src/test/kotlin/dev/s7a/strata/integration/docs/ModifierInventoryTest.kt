package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that the public skill sees the complete compiled modifier surface.
 */
internal class ModifierInventoryTest {
    @Test
    fun compiledInventoryContainsEveryTopLevelAndParentScopeOverload() {
        val inventory = ModifierInventory.discover(listOf(apiClasses()))

        assertEquals(40, inventory.modifiers.size)
        assertEquals(50, inventory.modifiers.values.sum())
        assertEquals(ModifierDocumentationCatalog.entries.keys, inventory.modifiers.keys)
        assertEquals(ModifierInventory.ParentScopeModifier.entries.toSet(), inventory.parentScopeModifiers.keys)
        assertEquals(7, inventory.parentScopeModifiers.values.sum())
        assertEquals(4, inventory.modifiers.getValue("padding"))
        assertEquals(2, inventory.modifiers.getValue("imageBackground"))
        assertEquals(2, inventory.modifiers.getValue("tooltip"))
    }

    private fun apiClasses(): Path {
        val root = repositoryRoot()
        val classes = root.resolve("api/build/classes/kotlin/main")
        assertTrue(Files.isDirectory(classes))
        return classes
    }

    private fun repositoryRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
    }
}

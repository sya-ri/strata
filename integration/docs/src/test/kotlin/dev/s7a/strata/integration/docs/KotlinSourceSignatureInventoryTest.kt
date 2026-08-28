package dev.s7a.strata.integration.docs

import dev.s7a.strata.integration.docs.KotlinSourceSignatureInventory.OwnedDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies exact human-readable signature extraction and consumer-internal filtering.
 */
internal class KotlinSourceSignatureInventoryTest {
    @Test
    fun sourceSignaturesMatchCompiledComponentAndModifierGroups() {
        val root = repositoryRoot()
        val classes = root.resolve("api/build/classes/kotlin/main")
        val source = KotlinSourceSignatureInventory.discover(root.resolve("api/src/main/kotlin"))
        val components = ShowcaseInventory.discoverOverloads(listOf(classes))
        val modifiers = ModifierInventory.discover(listOf(classes))

        assertEquals(components.keys, source.components.keys)
        components.forEach { (component, count) -> assertEquals(count, source.components.getValue(component).size) }
        assertEquals(modifiers.modifiers.keys, source.modifiers.keys)
        modifiers.modifiers.forEach { (name, count) -> assertEquals(count, source.modifiers.getValue(name).size) }
        assertTrue(
            source.components
                .getValue(DocumentedComponent.CycleButton)
                .single()
                .contains("CycleButtonState<T>"),
        )
        assertTrue(source.modifiers.getValue("padding").any { signature -> signature.contains("left: Int") })
    }

    @Test
    fun stateAndBindingCatalogIncludesGenericFactoriesAndOmitsRuntimeOnlyMembers() {
        val root = repositoryRoot()
        val source = KotlinSourceSignatureInventory.discover(root.resolve("api/src/main/kotlin"))
        val cycle = source.stateAndBindings.getValue("CycleButtonState")
        val loadRequest = source.stateAndBindings.getValue("ListLoadRequest")
        val scroll = source.stateAndBindings.getValue("ScrollState")
        val binding = source.stateAndBindings.getValue("SlotBinding")
        val canvas = source.stateAndBindings.getValue("CanvasSource")

        assertTrue(cycle.any { declaration -> declaration.signature.contains("inline operator fun <reified E : Enum<E>> invoke") })
        assertTrue(cycle.any { declaration -> declaration.signature.startsWith("constructor(values: Collection<T>") })
        assertTrue(loadRequest.map(OwnedDeclaration::signature).contains("data class ListLoadRequest(public val suggestedCount: Int)"))
        assertTrue(loadRequest.map(OwnedDeclaration::signature).contains("val suggestedCount: Int"))
        assertTrue(scroll.map(OwnedDeclaration::signature).contains("fun scrollTo(offset: Double): Double"))
        assertTrue(scroll.none { declaration -> declaration.signature.contains("updateGeometry") })
        assertTrue(binding.none { declaration -> declaration.signature.contains("val source") || declaration.signature.contains("val index") })
        assertEquals(listOf("fun interface CanvasSource"), canvas.map(OwnedDeclaration::signature))
    }

    @Test
    fun nestedStateDeclarationsRetainExactSourceOwners() {
        val source = KotlinSourceSignatureInventory.discover(repositoryRoot().resolve("api/src/main/kotlin"))

        assertEquals(
            linkedMapOf(
                "ImageSource" to listOf("sealed interface ImageSource"),
                "ImageSource.Pixels" to
                    listOf(
                        "data class Pixels(public val image: DrawImage) : ImageSource",
                        "val image: DrawImage",
                    ),
                "ImageSource.Resource" to
                    listOf(
                        "data class Resource(public val id: ResourceId) : ImageSource",
                        "val id: ResourceId",
                    ),
            ),
            signaturesByOwner(source.stateAndBindings.getValue("ImageSource")),
        )
        assertEquals(
            listOf("PlayerSkinSource.Name", "PlayerSkinSource.Pixels", "PlayerSkinSource.Uuid"),
            source.stateAndBindings
                .getValue("PlayerSkinSource")
                .filter { declaration -> declaration.signature.startsWith("val ") }
                .map(OwnedDeclaration::ownerPath),
        )
        assertEquals(
            listOf(
                "companion object",
                "inline operator fun <reified E : Enum<E>> invoke(initialValue: E, noinline toString: (E) -> String = { value -> value.name }): CycleButtonState<E>",
            ),
            signaturesByOwner(source.stateAndBindings.getValue("CycleButtonState")).getValue("CycleButtonState.Companion"),
        )
    }

    @Test
    fun binaryPairingRejectsAPropertyFingerprintMovedToItsSiblingOwner() {
        val root = repositoryRoot()
        val classes = listOf(root.resolve("api/build/classes/kotlin/main"))
        val entry = StateBindingDocumentationCatalog.entries.associateBy(StateBindingDocumentationCatalog.Entry::typeName).getValue("ImageSource")
        val declarations = KotlinSourceSignatureInventory.discover(root.resolve("api/src/main/kotlin")).stateAndBindings.getValue(entry.typeName)
        val fingerprints = StateBindingBinaryInventory.discover(classes).getValue(entry.typeName)
        val pixelOwner = "dev.s7a.strata.component.ImageSource\$Pixels"
        val resourceOwner = "dev.s7a.strata.component.ImageSource\$Resource"
        val moved =
            fingerprints.map { fingerprint ->
                fingerprint.replace("$pixelOwner.getImage(", "$resourceOwner.getImage(")
            }
        assertFalse(moved == fingerprints)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                StrataSkillPipeline.validateStateBinaryPair(entry, declarations, moved)
            }

        assertTrue(failure.message.orEmpty().contains("ImageSource.Pixels"))
    }

    private fun signaturesByOwner(
        declarations: List<OwnedDeclaration>,
    ): Map<String, List<String>> =
        declarations
            .groupBy(OwnedDeclaration::ownerPath)
            .mapValues { (_, owned) -> owned.map(OwnedDeclaration::signature) }

    private fun repositoryRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
    }
}

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Locks the runtime integration surface after application authoring moved to the API artifact.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftRuntimeApiContractTest {
    @Test
    fun runtimeFactoryFacadeContainsOnlyHostAndProfileFactories() {
        val methods =
            Class
                .forName("dev.s7a.strata.runtime.minecraft.MinecraftRuntimeFactories")
                .declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) && method.isSynthetic.not() }

        assertEquals(
            listOf(
                "createMinecraftUiHost",
                "createMinecraftUiHost",
                "createMinecraftUiProfile",
            ),
            methods.map { method -> method.name }.sorted(),
        )
    }

    @Test
    fun runtimePackageContainsNoApplicationAuthoringFacades() {
        listOf(
            "dev.s7a.strata.runtime.minecraft.MinecraftUiComponents",
            "dev.s7a.strata.runtime.minecraft.MinecraftUiModifiers",
        ).forEach { name ->
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }

    @Test
    fun applicationTypesAreOwnedByApiRolePackages() {
        assertEquals("dev.s7a.strata.component", Class.forName("dev.s7a.strata.component.ProfileComponents").packageName)
        assertEquals("dev.s7a.strata.component", ImageSource::class.java.packageName)
        assertEquals("dev.s7a.strata.component", SlotBinding::class.java.packageName)
        assertEquals("dev.s7a.strata.component", TextFieldState::class.java.packageName)
        assertEquals("dev.s7a.strata.resource", ResourceId::class.java.packageName)
        assertEquals("dev.s7a.strata.screen", ScreenDefinition::class.java.packageName)
    }

    @Test
    fun platformBridgeExposesOnlyVersionServicesNeededByStandardComponents() {
        val methods =
            MinecraftUiPlatform::class.java.declaredMethods
                .filter { method -> method.isSynthetic.not() }
                .map { method -> method.name }
                .toSet()

        assertEquals(setOf("inventorySlot", "image", "playerSkin", "refresh", "close"), methods)
    }

    @Test
    fun retainedImplementationFactoriesRemainJvmSynthetic() {
        listOf(
            "dev.s7a.strata.runtime.minecraft.MinecraftImageElementKt",
            "dev.s7a.strata.runtime.minecraft.MinecraftPlayerHeadElementKt",
            "dev.s7a.strata.runtime.minecraft.MinecraftAsyncPlayerHeadElementKt",
            "dev.s7a.strata.runtime.minecraft.MinecraftTabElementKt",
        ).forEach { name ->
            val implementation = Class.forName(name)
            assertTrue(
                implementation.declaredMethods
                    .filter { method -> Modifier.isPublic(method.modifiers) }
                    .all { method -> method.isSynthetic },
            )
        }
    }
}

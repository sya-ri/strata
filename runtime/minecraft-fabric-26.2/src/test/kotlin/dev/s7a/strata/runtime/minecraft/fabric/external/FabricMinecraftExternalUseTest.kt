package dev.s7a.strata.runtime.minecraft.fabric.external

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.loadCurrentMinecraftPlayerSkin
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.screens.Screen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Compiles ordinary external use of the Fabric screen type without opting into the common internal runtime bridge.
 */
internal class FabricMinecraftExternalUseTest {
    @Test
    fun screenCanBePassedThroughOrdinaryMinecraftCode() {
        assertEquals(Screen::class.java, FabricMinecraftScreen::class.java.superclass)
    }

    @OptIn(InternalStrataRuntimeApi::class)
    @Test
    fun adapterFactoriesCompileFromAnExternalPackage() {
        val profileFactory: () -> MinecraftUiProfile = ::extractMinecraftUiProfile
        val skinFactory: () -> DrawImage = ::loadCurrentMinecraftPlayerSkin
        val explicitScreenFactory: (ScreenDefinition, MinecraftUiProfile, Screen?) -> FabricMinecraftScreen = ::createMinecraftScreen
        val defaultParentFactory: (ScreenDefinition, MinecraftUiProfile) -> FabricMinecraftScreen = { definition, profile ->
            createMinecraftScreen(definition, profile)
        }
        assertNotNull(profileFactory)
        assertNotNull(skinFactory)
        assertNotNull(explicitScreenFactory)
        assertNotNull(defaultParentFactory)
    }
}

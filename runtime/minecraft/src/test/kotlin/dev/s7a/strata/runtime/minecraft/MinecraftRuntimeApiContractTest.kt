package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.concurrent.Callable

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
    fun legacyAsciiHostDoesNotRequireGsonOrNativeFontDependencies() {
        val classes =
            listOf(
                ScreenDefinition::class.java,
                RuntimeUiFrame::class.java,
                MinecraftUiProfile::class.java,
                LegacyAsciiHostProbe::class.java,
                Unit::class.java,
                Class.forName("kotlinx.coroutines.Job"),
            )
        val classPath = classes.map { type -> requireNotNull(type.protectionDomain.codeSource).location }.distinct().toTypedArray()
        URLClassLoader(classPath, ClassLoader.getPlatformClassLoader()).use { loader ->
            listOf(
                "com.google.gson.JsonObject",
                "org.lwjgl.system.MemoryUtil",
                "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
            ).forEach { name ->
                assertThrows(ClassNotFoundException::class.java) { Class.forName(name, false, loader) }
            }
            val constructor = loader.loadClass(LegacyAsciiHostProbe::class.java.name).getDeclaredConstructor()
            constructor.isAccessible = true
            val probe = constructor.newInstance() as Callable<*>
            assertEquals(10, probe.call())
        }
    }

    @Test
    fun compatibilityProfilesDoNotOpenAnExplicitFontBackend() {
        val definition = ScreenDefinition("ASCII") { Text("ASCII") }
        val backend = MinecraftFontBackendFactory { error("Compatibility glyphs must not open a font backend.") }

        createMinecraftUiHost(definition, MinecraftProfileFixture.create(), backend).use { host ->
            host.attach()
            assertEquals(10, host.frame(IntSize(14, 9)).drawCommands.size)
        }
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

    /**
     * Runs the real compatibility host inside the isolated loader without requiring JUnit in that loader.
     * Construction, frame evaluation, and terminal cleanup all run on the invoking test thread.
     */
    private class LegacyAsciiHostProbe : Callable<Int> {
        override fun call(): Int {
            val definition = ScreenDefinition("ASCII") { Text("ASCII") }
            return createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
                host.attach()
                host.frame(IntSize(14, 9)).drawCommands.size
            }
        }
    }
}

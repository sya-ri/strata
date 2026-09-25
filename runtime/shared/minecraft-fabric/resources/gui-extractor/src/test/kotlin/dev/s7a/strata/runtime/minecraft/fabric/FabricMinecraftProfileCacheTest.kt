package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.server.packs.resources.ResourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * Verifies one-generation profile reuse and release without native resources or a running Minecraft client.
 */
internal class FabricMinecraftProfileCacheTest {
    @Test
    fun repeatedOpensExtractOnlyOnceForEqualInputs() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()
        var extractions = 0
        val profile =
            cache.get(manager, compatibility, options) {
                extractions++
                Any()
            }

        repeat(100) {
            assertSame(
                profile,
                cache.get(manager, compatibility.copy(), options.copy()) {
                    extractions++
                    Any()
                },
            )
        }

        assertEquals(1, extractions)
    }

    @Test
    fun everyFontOptionAndCompatibilityChangeReplacesTheSingleEntry() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()
        var previous = cache.get(manager, compatibility, options, ::Any)
        val selections =
            listOf(
                options.copy(uniform = true),
                options.copy(japaneseVariants = true),
                options.copy(rightToLeft = true),
                options,
            )
        selections.forEach { selection ->
            val current = cache.get(manager, compatibility, selection, ::Any)
            assertNotSame(previous, current)
            assertSame(current, retainedValue(cache))
            previous = current
        }
        val changed = cache.get(manager, compatibility.copy(fractionalUnihexAdvance = true), options, ::Any)
        assertNotSame(previous, changed)
        assertSame(changed, retainedValue(cache))
    }

    @Test
    fun resourceManagersUseIdentityEvenWhenTheirEqualityMatches() {
        val cache = FabricMinecraftProfileCache<Any>()
        val first = EqualManager()
        val second = EqualManager()
        assertEquals(first, second)
        val old = cache.get(first, compatibility, options, ::Any)
        val current = cache.get(second, compatibility, options, ::Any)

        assertNotSame(old, current)
        cache.invalidate(first, false)
        assertSame(current, cache.get(second, compatibility, options) { error("A foreign manager invalidated the current profile.") })
        cache.invalidate(second, true)
        val empty = retainedState(cache)
        cache.close(first, false)
        assertSame(empty, retainedState(cache))
    }

    @Test
    fun reloadAndCloseDropTheValueWithoutChangingExistingOwners() {
        val cache = FabricMinecraftProfileCache<ByteArray>()
        val manager = Any()
        val old = cache.get(manager, compatibility, options) { byteArrayOf(1, 2, 3) }

        cache.invalidate(manager, true)
        assertNull(retainedEntry(cache))
        val current = cache.get(manager, compatibility, options) { byteArrayOf(4, 5, 6) }
        assertNotSame(old, current)
        assertTrue(old.contentEquals(byteArrayOf(1, 2, 3)))
        cache.close(manager, false)
        assertNull(retainedEntry(cache))
        assertTrue(current.contentEquals(byteArrayOf(4, 5, 6)))
        val reopened = cache.get(manager, compatibility, options) { byteArrayOf(7, 8, 9) }
        assertNotSame(current, reopened)
        cache.close(manager, true)
        assertNull(retainedEntry(cache))
        assertTrue(reopened.contentEquals(byteArrayOf(7, 8, 9)))
        assertThrows(IllegalStateException::class.java) { cache.get(manager, compatibility, options) { error("Closed cache extraction must not run.") } }
    }

    @Test
    fun reloadStormsRetainOnlyTheCurrentGenerationAndNoEmptyGenerationKeys() {
        val cache = FabricMinecraftProfileCache<Any>()
        repeat(512) {
            val manager = Any()
            val value = cache.get(manager, compatibility, options, ::Any)
            assertSame(value, retainedValue(cache))
            cache.invalidate(manager, true)
            cache.invalidate(manager, true)
            assertNull(retainedEntry(cache))
        }
        val state = retainedState(cache)
        repeat(512) { cache.invalidate(Any(), true) }
        assertNull(retainedEntry(cache))
        val finalState = retainedState(cache)
        assertNotSame(state, finalState)
        val epoch =
            finalState.javaClass
                .getDeclaredField("epoch")
                .apply { isAccessible = true }
                .getLong(finalState)
        assertEquals(1536L, epoch)
        assertEquals(setOf("epoch", "entry", "terminal"), finalState.javaClass.declaredFields.mapTo(HashSet()) { it.name })
    }

    @Test
    fun failedReplacementDropsOldAndPendingOwnershipAndPropagatesTheSameFailure() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()
        cache.get(manager, compatibility, options, ::Any)
        val failure = AssertionError("extraction")

        assertSame(
            failure,
            assertThrows(AssertionError::class.java) {
                cache.get(manager, compatibility, options.copy(uniform = true)) { throw failure }
            },
        )
        assertNull(retainedEntry(cache))
        val recovered = cache.get(manager, compatibility, options, ::Any)
        assertSame(recovered, retainedValue(cache))
    }

    @Test
    fun invalidationDuringExtractionPreventsStalePublication() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()

        assertThrows(IllegalStateException::class.java) {
            cache.get(manager, compatibility, options) {
                cache.invalidate(manager, true)
                Any()
            }
        }
        assertNull(retainedEntry(cache))
        assertSame(cache.get(manager, compatibility, options, ::Any), retainedValue(cache))
    }

    @Test
    fun reentrantExtractionIsRejectedAndReleasesThePendingEntry() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()

        listOf(false, true).forEach { invalidateFirst ->
            assertThrows(IllegalStateException::class.java) {
                cache.get(manager, compatibility, options) {
                    if (invalidateFirst) cache.invalidate(manager, true)
                    cache.get(manager, compatibility, options, ::Any)
                }
            }
            assertNull(retainedEntry(cache))
        }
    }

    @Test
    fun invalidationFromAnotherThreadFencesPublicationWithoutWaitingForExtraction() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()
        val executor = Executors.newSingleThreadExecutor()
        try {
            ClaimPhase.entries.forEach { phase ->
                var extractions = 0
                val invalidate = Runnable { executor.submit { cache.invalidate(manager, true) }.get(5, TimeUnit.SECONDS) }
                assertThrows(IllegalStateException::class.java) {
                    cache.get(
                        manager,
                        compatibility,
                        options,
                        Supplier {
                            extractions++
                            if (phase == ClaimPhase.DuringExtraction) invalidate.run()
                            Any()
                        },
                        Runnable { if (phase == ClaimPhase.BeforeClaim) invalidate.run() },
                    )
                }
                assertEquals(if (phase == ClaimPhase.BeforeClaim) 0 else 1, extractions)
                assertNull(retainedEntry(cache))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun foreignThreadCannotReuseAnEntryAndForeignManagerCloseDoesNotClearIt() {
        val cache = FabricMinecraftProfileCache<Any>()
        val manager = Any()
        val profile = cache.get(manager, compatibility, options, ::Any)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val rejected =
                executor.submit<Boolean> {
                    cache.close(Any(), false)
                    assertThrows(IllegalStateException::class.java) { cache.get(manager, compatibility, options, ::Any) }
                    true
                }
            assertTrue(rejected.get(5, TimeUnit.SECONDS))
            assertSame(profile, retainedValue(cache))
            assertFalse(executor.isShutdown)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun terminalCloseFencesClaimsAndPermanentlyRejectsReopening() {
        val manager = Any()
        ClaimPhase.entries.forEach { phase ->
            val cache = FabricMinecraftProfileCache<Any>()
            assertThrows(IllegalStateException::class.java) {
                cache.get(
                    manager,
                    compatibility,
                    options,
                    Supplier {
                        if (phase == ClaimPhase.DuringExtraction) cache.close(manager, true)
                        Any()
                    },
                    Runnable { if (phase == ClaimPhase.BeforeClaim) cache.close(manager, true) },
                )
            }
            val terminal = retainedState(cache)
            cache.invalidate(manager, true)
            cache.close(Any(), false)
            assertSame(terminal, retainedState(cache))
            assertNull(retainedEntry(cache))
            assertThrows(IllegalStateException::class.java) { cache.get(Any(), compatibility, options) { error("Terminal extraction must never run.") } }
        }
    }

    @Test
    @OptIn(InternalStrataRuntimeApi::class)
    fun capturedKeyInputsArePassedUnchangedToTheProfileExtractor() {
        val manager =
            Proxy.newProxyInstance(ResourceManager::class.java.classLoader, arrayOf(ResourceManager::class.java)) { _, _, _ ->
                error("The key-forwarding test must not read native resources.")
            } as ResourceManager
        val failure = IllegalStateException("Expected key-forwarding probe")
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                cachedFabricMinecraftProfile(manager, compatibility, options) { capturedManager, capturedCompatibility, capturedOptions ->
                    assertSame(manager, capturedManager)
                    assertSame(compatibility, capturedCompatibility)
                    assertSame(options, capturedOptions)
                    throw failure
                }
            },
        )
    }

    private fun retainedState(cache: FabricMinecraftProfileCache<*>): Any {
        val field = cache.javaClass.getDeclaredField("current").apply { isAccessible = true }
        return checkNotNull((field.get(cache) as AtomicReference<*>).get())
    }

    private fun retainedEntry(cache: FabricMinecraftProfileCache<*>): Any? {
        val state = retainedState(cache)
        return state.javaClass
            .getDeclaredField("entry")
            .apply { isAccessible = true }
            .get(state)
    }

    private fun retainedValue(cache: FabricMinecraftProfileCache<*>): Any? {
        val entry = retainedEntry(cache) ?: return null
        return entry.javaClass
            .getDeclaredField("value")
            .apply { isAccessible = true }
            .get(entry)
    }

    private class EqualManager {
        override fun equals(other: Any?): Boolean = other is EqualManager

        override fun hashCode(): Int = javaClass.hashCode()
    }

    private enum class ClaimPhase {
        BeforeClaim,
        DuringExtraction,
    }

    private companion object {
        private val compatibility = MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, packFormat = 88)
        private val options = MinecraftFontOptions()
    }
}

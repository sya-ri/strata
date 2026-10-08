package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Proves exact source reads, fresh snapshots, one-entry bounds, and lifecycle fencing without a loaded client.
 */
internal class FabricMinecraftImageDecodeCacheTest {
    @Test
    fun hotInputStillOpensReadsAndClosesEachSourceAndReturnsIndependentImages() {
        val events = ArrayList<Event>()
        val input = byteArrayOf(1, 2, 3, 4)
        val cache = decoder(events)
        val manager = Any()
        val images =
            List(100) {
                cache.load(manager) { Source(input, events) }
            }
        assertEquals(List(100) { input.toList() }, events.filterIsInstance<Event.Read>().map { it.bytes })
        assertEquals(200, events.count { it == Event.Close })
        assertEquals(listOf(input.toList()), events.filterIsInstance<Event.Decode>().map { it.bytes })
        images.forEach { image ->
            assertEquals(IntSize(4, 1), image.size)
            assertArrayEquals(intArrayOf(1, 2, 3, 4), image.copyArgb())
            image.copyArgb().fill(99)
        }
        images.zipWithNext().forEach { (previous, next) -> assertNotSame(previous, next) }
        assertArrayEquals(intArrayOf(1, 2, 3, 4), images.first().copyArgb())
        assertRetained(cache, encodedBytes = 4, payloadBytes = 16)
    }

    @Test
    fun everyChangedByteAndManagerIdentityReplacesOnlyOneEntry() {
        val events = ArrayList<Event>()
        val cache = decoder(events)
        val manager = EqualManager()
        val first = cache.load(manager) { Source(byteArrayOf(1, 2, 3), events) }
        val replacement = cache.load(manager) { Source(byteArrayOf(1, 2, 4), events) }
        val current = cache.load(EqualManager()) { Source(byteArrayOf(1, 2, 4), events) }
        assertNotSame(first, replacement)
        assertNotSame(replacement, current)
        assertArrayEquals(intArrayOf(1, 2, 3), first.copyArgb())
        assertArrayEquals(intArrayOf(1, 2, 4), current.copyArgb())
        repeat(512) { index ->
            cache.load(manager) { Source(byteArrayOf(index.toByte(), (index / 256).toByte()), events) }
            assertRetained(cache, encodedBytes = 2, payloadBytes = 8)
        }
        assertEquals(515, events.count { it is Event.Decode })
    }

    @Test
    fun encodedBoundaryIncludesExactLimitAndReplaysEveryOversizedByteWithoutRetention() {
        val maximum = 8 * 1024 * 1024
        for (count in listOf(maximum - 1, maximum, maximum + 1, maximum * 2 + 3)) {
            val bytes = ByteArray(count) { index -> (index * 31).toByte() }
            var decodes = 0
            val cache =
                FabricMinecraftImageDecodeCache { input ->
                    decodes++
                    assertArrayEquals(bytes, input.readAllBytes())
                    input.close()
                    FabricMinecraftImageDecodeCache.Decoded(IntSize(1, 1), intArrayOf(count))
                }
            val manager = Any()
            val events = ArrayList<Event>()
            repeat(2) { assertEquals(count, cache.load(manager) { Source(bytes, events) }.argbAt(0, 0)) }
            assertEquals(if (count <= maximum) 1 else 2, decodes)
            assertEquals(4, events.count { it == Event.Close })
            if (count <= maximum) {
                assertRetained(cache, count, 4)
            } else {
                assertNull(entry(cache))
            }
        }
    }

    @Test
    fun combinedBoundaryChargesBothArraysAndOversizedPayloadEvictsPreviousEntry() {
        val manager = Any()
        val encoded = byteArrayOf(1, 2, 3, 4)
        val maximum = 16 * 1024 * 1024
        for (payloadBytes in listOf(maximum - 8, maximum - 4, maximum)) {
            var decodes = 0
            val pixels = IntArray(payloadBytes / Int.SIZE_BYTES) { 0x7F123456 }
            val cache =
                FabricMinecraftImageDecodeCache { stream ->
                    stream.readAllBytes()
                    stream.close()
                    decodes++
                    FabricMinecraftImageDecodeCache.Decoded(IntSize(pixels.size, 1), pixels.copyOf())
                }
            repeat(2) { assertEquals(0x7F123456, cache.load(manager) { encoded.inputStream() }.argbAt(0, 0)) }
            assertEquals(if (payloadBytes + encoded.size <= maximum) 1 else 2, decodes)
            if (payloadBytes + encoded.size <= maximum) {
                assertRetained(cache, encoded.size, payloadBytes)
            } else {
                assertNull(entry(cache))
            }
        }
    }

    @Test
    fun openReadDecodeAndOuterCloseFailuresDropTheCapturedEntryAndRetryNormally() {
        val manager = Any()
        val bytes = byteArrayOf(5, 6)
        for (kind in FailureKind.entries) {
            val events = ArrayList<Event>()
            var decodeFailure: IOException? = null
            val cache =
                FabricMinecraftImageDecodeCache { stream ->
                    val decoded = stream.readAllBytes()
                    stream.close()
                    decodeFailure?.let { throw it }
                    FabricMinecraftImageDecodeCache.Decoded(IntSize(decoded.size, 1), decoded.map { it.toInt() }.toIntArray())
                }
            val old = cache.load(manager) { Source(bytes, events) }
            val failure = IOException(kind.name)
            val thrown =
                assertThrows(IOException::class.java) {
                    cache.load(manager) {
                        when (kind) {
                            FailureKind.Open -> throw failure

                            FailureKind.Read -> Source(bytes, events, readFailure = failure)

                            FailureKind.Decode -> {
                                decodeFailure = failure
                                Source(byteArrayOf(7, 8), events)
                            }

                            FailureKind.Close -> Source(bytes, events, outerCloseFailure = failure)
                        }
                    }
                }
            assertSame(failure, thrown)
            assertNull(entry(cache))
            decodeFailure = null
            val recovered = cache.load(manager) { Source(bytes, events) }
            assertNotSame(old, recovered)
            assertArrayEquals(intArrayOf(5, 6), old.copyArgb())
            assertRetained(cache, 2, 8)
        }
    }

    @Test
    fun reloadCloseAndReentryDuringSourceCallbacksCannotPublishStaleState() {
        val manager = Any()
        val foreign = Any()
        val events = ArrayList<Event>()
        val cache = decoder(events)
        val old = cache.load(manager) { Source(byteArrayOf(1, 2), events) }
        val retained = entry(cache)
        cache.invalidate(foreign, false)
        cache.close(foreign, false)
        assertSame(retained, entry(cache))
        cache.load(manager) {
            cache.invalidate(manager, true)
            Source(byteArrayOf(3, 4), events)
        }
        assertNull(entry(cache))
        assertArrayEquals(intArrayOf(1, 2), old.copyArgb())
        cache.load(manager) { Source(byteArrayOf(5, 6), events) }
        cache.load(manager) {
            cache.load(manager) { Source(byteArrayOf(7, 8), events) }
            Source(byteArrayOf(9, 10), events)
        }
        assertRetained(cache, 2, 8)
        val decodes = events.count { it is Event.Decode }
        assertArrayEquals(intArrayOf(7, 8), cache.load(manager) { Source(byteArrayOf(7, 8), events) }.copyArgb())
        assertEquals(decodes, events.count { it is Event.Decode })
        cache.load(manager) {
            cache.close(manager, true)
            Source(byteArrayOf(11, 12), events)
        }
        assertNull(entry(cache))
        repeat(3) { cache.load(manager) { Source(byteArrayOf(11, 12), events) } }
        assertNull(entry(cache))
        assertEquals(decodes + 4, events.count { it is Event.Decode })
    }

    @Test
    fun decodedValueIdentityCannotSubstituteForTheCurrentEncodedKeyOrAdmissionCharge() {
        val manager = Any()
        val decoded = FabricMinecraftImageDecodeCache.Decoded(IntSize(1, 1), intArrayOf(0xFF123456.toInt()))
        val cache =
            FabricMinecraftImageDecodeCache { input ->
                input.readAllBytes()
                input.close()
                decoded
            }
        cache.load(manager) { byteArrayOf(1, 2).inputStream() }
        cache.load(manager) { byteArrayOf(3, 4, 5).inputStream() }
        assertRetained(cache, 3, 4)
        cache.load(manager) { ByteArray(8 * 1024 * 1024 + 1).inputStream() }
        assertNull(entry(cache))
    }

    @Test
    fun workerInvalidationFencesPublicationAndCrossThreadWarmAccessFailsBeforeOpening() {
        val manager = Any()
        val events = ArrayList<Event>()
        val cache = decoder(events)
        cache.load(manager) { Source(byteArrayOf(1, 2), events) }
        val worker =
            CompletableFuture.supplyAsync {
                assertThrows(IllegalStateException::class.java) {
                    cache.load(manager) { error("Wrong-thread reuse must not open a source.") }
                }
            }
        worker.get(10, TimeUnit.SECONDS)
        cache.load(manager) {
            CompletableFuture.runAsync { cache.invalidate(manager, true) }.get(10, TimeUnit.SECONDS)
            Source(byteArrayOf(3, 4), events)
        }
        assertNull(entry(cache))
        cache.load(manager) { Source(byteArrayOf(5, 6), events) }
        cache.close(manager, true)
        assertNull(entry(cache))
    }

    private fun decoder(events: MutableList<Event>): FabricMinecraftImageDecodeCache =
        FabricMinecraftImageDecodeCache { input ->
            val bytes = input.readAllBytes()
            input.close()
            events += Event.Decode(bytes.toList())
            FabricMinecraftImageDecodeCache.Decoded(IntSize(bytes.size, 1), bytes.map { it.toInt() }.toIntArray())
        }

    private fun entry(cache: FabricMinecraftImageDecodeCache): Any? {
        val current =
            cache.javaClass
                .getDeclaredField("current")
                .apply { isAccessible = true }
                .get(cache) as AtomicReference<*>
        val state = current.get()
        assertEquals(setOf("entry", "terminal"), state.javaClass.declaredFields.map { it.name }.toSet())
        return state.javaClass.getDeclaredField("entry").apply { isAccessible = true }.get(state)
    }

    private fun assertRetained(
        cache: FabricMinecraftImageDecodeCache,
        encodedBytes: Int,
        payloadBytes: Int,
    ) {
        val retained = checkNotNull(entry(cache))
        val encoded = retained.javaClass.getDeclaredField("encoded").apply { isAccessible = true }.get(retained) as ByteArray
        val decoded = retained.javaClass.getDeclaredField("decoded").apply { isAccessible = true }.get(retained)
        val pixels = decoded.javaClass.getDeclaredField("pixels").apply { isAccessible = true }.get(decoded) as IntArray
        assertEquals(encodedBytes, encoded.size)
        assertEquals(payloadBytes, pixels.size * Int.SIZE_BYTES)
        assertEquals(setOf("manager", "encoded", "decoded", "owner"), retained.javaClass.declaredFields.map { it.name }.toSet())
    }

    private class Source(
        bytes: ByteArray,
        private val events: MutableList<Event>,
        private val readFailure: IOException? = null,
        private val outerCloseFailure: IOException? = null,
    ) : ByteArrayInputStream(bytes) {
        private var closes = 0

        override fun readNBytes(length: Int): ByteArray {
            readFailure?.let { throw it }
            return super.readNBytes(length).also { events += Event.Read(it.toList()) }
        }

        override fun close() {
            closes++
            events += Event.Close
            if (closes == 2) outerCloseFailure?.let { throw it }
            super.close()
        }
    }

    private class EqualManager {
        override fun equals(other: Any?): Boolean = other is EqualManager

        override fun hashCode(): Int = 1
    }

    private enum class FailureKind { Open, Read, Decode, Close }

    private sealed interface Event {
        data class Read(val bytes: List<Byte>) : Event

        data class Decode(val bytes: List<Byte>) : Event

        data object Close : Event
    }
}

package dev.s7a.strata.gradle.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Compiles the unchanged actual fixture helper against the JDK alone and exercises its owner and failure lifetime.
 */
internal class MinecraftNativePerformanceOptionLeaseTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun capturesAllValuesWithoutMutationAndRestoresOnce() {
        fixture().use { fixture ->
            var first = false
            var second = 60
            var reads = 0
            var writes = 0
            val lease = fixture.create {}
            fixture.capture(
                lease,
                {
                    reads++
                    first
                },
                {
                    first = it
                    writes++
                },
                true,
            )
            fixture.capture(
                lease,
                {
                    reads++
                    second
                },
                {
                    second = it
                    writes++
                },
                120,
            )
            assertEquals(2, reads)
            assertEquals(0, writes)
            fixture.apply(lease)
            assertTrue(first)
            assertEquals(120, second)
            fixture.closeLease(lease)
            assertEquals(false, first)
            assertEquals(60, second)
            assertEquals(4, writes)
            fixture.closeLease(lease)
            assertEquals(4, writes)
        }
    }

    @Test
    fun rejectsTheWrongOwnerBeforeReadingWritingOrChangingLifetime() {
        fixture().use { fixture ->
            var owner = true
            var reads = 0
            var writes = 0
            var value = 60
            val guard = { check(owner) { "wrong owner" } }
            val lease = fixture.create(guard)
            owner = false
            assertThrows(IllegalStateException::class.java) { fixture.create(guard) }
            assertThrows(IllegalStateException::class.java) {
                fixture.capture(
                    lease,
                    {
                        reads++
                        value
                    },
                    {
                        writes++
                        value = it
                    },
                    120,
                )
            }
            assertEquals(0, reads)
            assertEquals(0, writes)
            owner = true
            fixture.capture(
                lease,
                {
                    reads++
                    value
                },
                {
                    writes++
                    value = it
                },
                120,
            )
            owner = false
            assertThrows(IllegalStateException::class.java) { fixture.apply(lease) }
            assertThrows(IllegalStateException::class.java) { fixture.closeLease(lease) }
            assertEquals(0, writes)
            owner = true
            fixture.apply(lease)
            owner = false
            assertThrows(IllegalStateException::class.java) { fixture.closeLease(lease) }
            assertEquals(120, value)
            owner = true
            fixture.closeLease(lease)
            assertEquals(60, value)
            assertEquals(2, writes)
        }
    }

    @Test
    fun restoresEverySnapshotAfterASetterMutatesAndThrows() {
        fixture().use { fixture ->
            val values = mutableListOf(15, 30, 60)
            val writes = mutableListOf<Pair<Int, Int>>()
            val failure = IllegalStateException("apply after mutation")
            val lease = fixture.create {}
            values.indices.forEach { index ->
                fixture.capture(
                    lease,
                    { values[index] },
                    { value ->
                        values[index] = value
                        writes.add(index to value)
                        if (index == 1 && value == 120) throw failure
                    },
                    120,
                )
            }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.apply(lease) })
            assertEquals(listOf(120, 120, 60), values)
            fixture.closeLease(lease)
            assertEquals(listOf(15, 30, 60), values)
            assertEquals(listOf(0 to 120, 1 to 120, 0 to 15, 1 to 30, 2 to 60), writes)
        }
    }

    @Test
    fun attemptsIndependentRestorationsAndPreservesFailureOrder() {
        fixture().use { fixture ->
            val first = IllegalStateException("first restore")
            val second = AssertionError("second restore")
            val restored = mutableListOf<Int>()
            val lease = fixture.create {}
            listOf(first, second, null).forEachIndexed { index, failure ->
                fixture.capture(
                    lease,
                    { index },
                    { value ->
                        if (value != 120) {
                            restored.add(index)
                            failure?.let { throw it }
                        }
                    },
                    120,
                )
            }
            fixture.apply(lease)
            val failure = assertThrows(IllegalStateException::class.java) { fixture.closeLease(lease) }
            assertSame(first, failure)
            assertEquals(listOf(second), failure.suppressed.toList())
            assertEquals(listOf(0, 1, 2), restored)
            fixture.closeLease(lease)
            assertEquals(listOf(0, 1, 2), restored)
        }
    }

    @Test
    fun restoresAfterCheckedCallbackFailuresWithoutReplacingTheCause() {
        fixture().use { fixture ->
            val first = IOException("checked restore")
            val second = Throwable("native restore")
            var restored = 0
            val lease = fixture.create {}
            listOf(first, second, null).forEach { failure ->
                fixture.capture(
                    lease,
                    { 60 },
                    { value ->
                        if (value == 60) {
                            restored++
                            failure?.let { throw it }
                        }
                    },
                    120,
                )
            }
            fixture.apply(lease)
            assertSame(first, assertThrows(IOException::class.java) { fixture.closeLease(lease) })
            assertEquals(listOf(second), first.suppressed.toList())
            assertEquals(3, restored)
        }
    }

    @Test
    fun doesNotSelfSuppressARepeatedFailure() {
        fixture().use { fixture ->
            val failure = IllegalStateException("same restore")
            var restored = 0
            val lease = fixture.create {}
            repeat(2) {
                fixture.capture(
                    lease,
                    { 60 },
                    { value ->
                        if (value == 60) {
                            restored++
                            throw failure
                        }
                    },
                    120,
                )
            }
            fixture.apply(lease)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.closeLease(lease) })
            assertEquals(2, restored)
            assertTrue(failure.suppressed.isEmpty())
        }
    }

    @Test
    fun aSilentlyRejectedRestoreCannotSkipOtherOptionsOrCertifySuccess() {
        fixture().use { fixture ->
            var rejected = 60
            var restored = 30
            val lease = fixture.create {}
            fixture.capture(lease, { rejected }, { value -> if (value == 120) rejected = value }, 120)
            fixture.capture(lease, { restored }, { restored = it }, 120)
            fixture.apply(lease)
            assertThrows(IllegalStateException::class.java) { fixture.closeLease(lease) }
            assertEquals(120, rejected)
            assertEquals(30, restored)
            fixture.closeLease(lease)
        }
    }

    @Test
    fun closesAnUnappliedOrFailedCaptureWithoutWriting() {
        fixture().use { fixture ->
            val failure = IllegalArgumentException("capture")
            var writes = 0
            val lease = fixture.create {}
            fixture.capture(lease, { 60 }, { writes++ }, 120)
            assertSame(
                failure,
                assertThrows(IllegalArgumentException::class.java) {
                    fixture.capture<Int>(lease, { throw failure }, { writes++ }, 120)
                },
            )
            fixture.closeLease(lease)
            assertEquals(0, writes)
            assertThrows(IllegalStateException::class.java) { fixture.apply(lease) }
            assertThrows(IllegalStateException::class.java) { fixture.capture(lease, { 60 }, { writes++ }, 120) }
            assertEquals(0, writes)
        }
    }

    /**
     * Loads the actual fixture helper independently of Minecraft and Strata.
     */
    private fun fixture(): CompiledLease = CompiledLease(directory.compileNativePerformanceFixture("MinecraftNativePerformanceOptionLease"))

    /**
     * Owns only the fresh JDK-compiled helper loader and unwraps reflection failures to their actual fixture cause.
     */
    private class CompiledLease(
        private val loader: URLClassLoader,
    ) : AutoCloseable {
        private val type = loader.loadClass("dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformanceOptionLease")
        private val constructor = type.getDeclaredConstructor(Runnable::class.java).apply { isAccessible = true }
        private val capture = type.getDeclaredMethod("capture", Supplier::class.java, Consumer::class.java, Any::class.java).apply { isAccessible = true }
        private val apply = type.getDeclaredMethod("apply").apply { isAccessible = true }
        private val close = type.getDeclaredMethod("close").apply { isAccessible = true }

        /**
         * Constructs the actual helper with the supplied owner guard.
         */
        fun create(owner: () -> Unit): Any = invoke { constructor.newInstance(Runnable { owner() }) }

        /**
         * Passes typed callbacks to the actual generic capture method.
         */
        fun <T> capture(
            lease: Any,
            read: () -> T,
            write: (T) -> Unit,
            borrowed: T,
        ) {
            invoke { capture.invoke(lease, Supplier { read() }, Consumer<T> { write(it) }, borrowed) }
        }

        /**
         * Applies the actual helper's captured settings.
         */
        fun apply(lease: Any) {
            invoke { apply.invoke(lease) }
        }

        /**
         * Exercises the helper's independent restoration and idempotent close.
         */
        fun closeLease(lease: Any) {
            invoke { close.invoke(lease) }
        }

        private fun <T> invoke(action: () -> T): T =
            try {
                action()
            } catch (failure: InvocationTargetException) {
                throw checkNotNull(failure.cause)
            }

        override fun close() {
            loader.close()
        }
    }
}

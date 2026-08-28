package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Exercises the process registry's single terminal transition without using it in standalone device fixtures.
 * All access belongs to this test thread; shutdown is intentionally permanent for this registry instance.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasDevicesTest {
    @Test
    fun terminalShutdownRejectsAcquisitionBeforeProducerCallbacksAndAfterRemoval() {
        val driver = NativeCanvasFixture.Driver()
        driver.destroyOnClose = false
        val peer = NativeCanvasFixture.Driver()
        val replacement = NativeCanvasFixture.Driver()
        val device = NativeCanvasDevices.device(driver)
        NativeCanvasDevices.device(peer)
        assertSame(device, NativeCanvasDevices.device(driver))
        assertEquals(0, NativeCanvasDevices.retainedTargetCount())
        assertEquals(0, NativeCanvasDevices.retainedGuiResourceSetCount())
        val portableSet = device.guiResources.reserve(device.guiResources.createOwnerId(), listOf(IntSize(2, 2)))
        val portable = NativeGuiResourceFixture.Resource()
        device.guiResources.add(portableSet, portable)
        device.guiResources.seal(portableSet)
        assertEquals(1, NativeCanvasDevices.retainedGuiResourceSetCount())
        var callbackChecks = 0
        val source =
            device.source({
                object : NativeCanvasProducer {
                    override fun capture(): NativeCanvasCapture? = null

                    override fun close() {
                        assertEquals(1, NativeCanvasDevices.retainedTargetCount())
                        assertEquals(1, NativeCanvasDevices.retainedGuiResourceSetCount())
                        assertThrows(IllegalStateException::class.java) { NativeCanvasDevices.device(driver) }
                        assertThrows(IllegalStateException::class.java) { NativeCanvasDevices.device(replacement) }
                        callbackChecks += 1
                    }
                }
            })
        UiTree().use { tree ->
            tree.update(evaluateComponentTree { Canvas(source, IntSize(2, 2)) })
            tree.measure(Constraints.fixed(2, 2))
            tree.layout()
            device.cancel(device.prepare(tree.paint(), FrameTime(1L), 1))
            assertEquals(1, NativeCanvasDevices.retainedTargetCount())
            NativeCanvasDevices.closeAfterGuiDiscarded()
            NativeCanvasDevices.closeAfterGuiDiscarded()
            assertEquals(1, callbackChecks)
            assertEquals(1, driver.finishCalls)
            assertEquals(1, driver.drainCalls)
            assertEquals(1, peer.finishCalls)
            assertEquals(1, peer.drainCalls)
            assertEquals(0, replacement.finishCalls)
            assertEquals(0, NativeCanvasDevices.retainedTargetCount())
            assertEquals(0, NativeCanvasDevices.retainedGuiResourceSetCount())
            assertEquals(1, portable.closeCalls)
            assertThrows(IllegalStateException::class.java) { NativeCanvasDevices.device(driver) }
            assertThrows(IllegalStateException::class.java) { NativeCanvasDevices.device(replacement) }
        }
    }
}

package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask

/**
 * Verifies generic device-owned GUI manager registration, ordering, diagnostics, and terminal failure handling.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiResourceManagerTest {
    @Test
    fun registeredManagersShareConsumptionReloadAndTerminalOrderingWithinASeparateBound() {
        NativeCanvasFixture().use { fixture ->
            val managers = List(8) { GuiManager(fixture.driver) }
            managers.forEach(fixture.device::registerGuiResourceManager)
            assertEquals(8, fixture.device.retainedManagedGuiResourceCount())
            assertEquals(32L, fixture.device.retainedManagedGuiResourceBytes())
            assertThrows(IllegalStateException::class.java) { fixture.device.registerGuiResourceManager(managers.first()) }
            assertThrows(IllegalStateException::class.java) { fixture.device.registerGuiResourceManager(GuiManager(fixture.driver)) }

            fixture.device.consumed()
            fixture.device.reload()
            fixture.device.failedGui()
            fixture.device.closeAfterGuiDiscarded()

            managers.forEach { manager ->
                assertEquals(
                    listOf("consumed", "poll", "reload", "poll", "failedGui", "poll", "beginShutdown", "closeAfterFinish", "acknowledgeAfterDrain"),
                    manager.events,
                )
                assertEquals(1, manager.finishCallsAtClose)
                assertEquals(0, manager.drainCallsAtClose)
                assertEquals(1, manager.drainCallsAtAcknowledge)
            }
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
            assertEquals(0, fixture.device.retainedManagedGuiResourceCount())
            assertEquals(0L, fixture.device.retainedManagedGuiResourceBytes())
        }
    }

    @Test
    fun registrationRequiresTheDeviceOwnerThread() {
        NativeCanvasFixture().use { fixture ->
            val task =
                FutureTask {
                    assertThrows(IllegalStateException::class.java) {
                        fixture.device.registerGuiResourceManager(GuiManager(fixture.driver))
                    }
                }
            Thread(task).start()
            task.get()
            assertEquals(0, fixture.device.retainedManagedGuiResourceCount())
        }
    }

    @Test
    fun terminalFailuresRemainPrimaryWhileIndependentManagersAndDrainContinue() {
        val fixture = NativeCanvasFixture()
        val primary = IllegalArgumentException("manager close")
        val acknowledgement = IllegalStateException("manager acknowledgement")
        val failing =
            GuiManager(fixture.driver).also {
                it.closeFailure = primary
                it.acknowledgeFailure = acknowledgement
            }
        val independent = GuiManager(fixture.driver)
        fixture.device.registerGuiResourceManager(failing)
        fixture.device.registerGuiResourceManager(independent)

        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(listOf("beginShutdown", "closeAfterFinish", "acknowledgeAfterDrain"), failing.events)
        assertEquals(listOf("beginShutdown", "closeAfterFinish", "acknowledgeAfterDrain"), independent.events)
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(1, fixture.driver.drainCalls)
        assertEquals(listOf(acknowledgement), primary.suppressed.toList())
        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
    }

    private class GuiManager(
        private val driver: NativeCanvasFixture.Driver,
    ) : NativeGuiResourceManager {
        val events = ArrayList<String>()
        var closeFailure: Throwable? = null
        var acknowledgeFailure: Throwable? = null
        var finishCallsAtClose: Int = -1
        var drainCallsAtClose: Int = -1
        var drainCallsAtAcknowledge: Int = -1
        var retainedResources: Int = 1
        var retainedBytes: Long = 4L

        override fun retainedResourceCount(): Int = retainedResources

        override fun retainedResourceBytes(): Long = retainedBytes

        override fun consumed() {
            events += "consumed"
        }

        override fun poll() {
            events += "poll"
        }

        override fun failedGui() {
            events += "failedGui"
        }

        override fun reload() {
            events += "reload"
        }

        override fun beginShutdown() {
            events += "beginShutdown"
        }

        override fun closeAfterFinish() {
            events += "closeAfterFinish"
            finishCallsAtClose = driver.finishCalls
            drainCallsAtClose = driver.drainCalls
            closeFailure?.let { throw it }
        }

        override fun acknowledgeAfterDrain() {
            events += "acknowledgeAfterDrain"
            drainCallsAtAcknowledge = driver.drainCalls
            acknowledgeFailure?.let { throw it }
            retainedResources = 0
            retainedBytes = 0L
        }
    }
}

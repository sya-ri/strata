@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Compares every compiled standard-component example through binary transport with the same local profile and resources.
 * Native container transactions and OS composition have separate loaded-client acceptance boundaries.
 */
internal class RemoteShowcaseParityTest {
    @TempDir
    lateinit var temporary: Path

    @TestFactory
    fun everyStandardComponentPreservesPixels(): List<DynamicTest> {
        val assets = ShowcaseMinecraftAssetFixture(temporary).assets()
        return ShowcaseScenarioCatalog.components.map { scenario ->
            DynamicTest.dynamicTest(scenario.component.name) {
                assertArrayEquals(ShowcaseHeadlessRenderer.component(scenario, assets), remote(scenario, assets), scenario.component.name)
            }
        }
    }

    private fun remote(
        scenario: ComponentScenario,
        assets: ShowcaseMinecraftAssets,
    ): ByteArray {
        val limits = RemoteLimits(reconstructionMillis = 60_000)
        val codec = RemoteMessageCodec(limits)
        val serverMessages = ArrayDeque<RemoteMessage>()
        val clientMessages = ArrayDeque<RemoteMessage>()
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val runtime = RemoteComponentRuntime()
        val definition = ShowcaseHeadlessRenderer.componentDefinition(scenario, assets).transfer()
        val server =
            RemoteServerSession(1, ProjectionValue.Absent, registry.types, limits, { serverMessages.add(codec.decode(codec.encode(it))) }) {
                runtime.evaluate(definition.content)
            }
        return server.use {
            server.tick()
            RemoteClientSession(serverMessages.removeFirst() as RemoteMessage.Snapshot, registry, limits, { clientMessages.add(codec.decode(codec.encode(it))) }).use { client ->
                createMinecraftUiHost(client.definition(definition.title), assets.profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                    host.attach()
                    val pointer = if (scenario.component == DocumentedComponent.Slot) IntOffset(32, 32) else IntOffset.Zero
                    repeat(4) {
                        host.frame(scenario.viewport, FrameTime(0))
                        host.dispatchPointer(PointerEvent.Move(pointer))
                        client.flushEdits()
                        while (clientMessages.isNotEmpty()) server.receive(clientMessages.removeFirst())
                        server.tick()
                        while (serverMessages.isNotEmpty()) {
                            when (val message = serverMessages.removeFirst()) {
                                is RemoteMessage.Update -> client.receive(message)
                                is RemoteMessage.Acknowledgement -> client.receive(message)
                                else -> error("Unexpected remote showcase message: $message")
                            }
                        }
                    }
                    val frame = host.frame(scenario.viewport, FrameTime(0))
                    val clear = DrawCommand.FillRectangle(IntRect(0, 0, frame.size.width, frame.size.height), ArgbColor(0xFF000000.toInt()))
                    rasterizeHeadless(listOf(clear) + frame.drawCommands, frame.size, scenario.scale).encodePng()
                }
            }
        }
    }
}

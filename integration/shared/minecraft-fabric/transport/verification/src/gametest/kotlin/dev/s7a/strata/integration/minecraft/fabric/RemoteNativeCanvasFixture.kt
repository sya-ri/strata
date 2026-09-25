package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteScreens
import dev.s7a.strata.runtime.remote.RemoteCanvas
import net.fabricmc.api.ClientModInitializer
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Installs a typed native renderer reference before negotiation, with resources owned by the test's client thread.
 * Revisions must retain one renderer attachment, and terminal verification waits for its actual GPU release.
 */
public class RemoteNativeCanvasFixture : ClientModInitializer {
    override fun onInitializeClient() {
        RemoteCanvas.register(FabricRemoteScreens.registry, type, { value -> require(value === ProjectionValue.Absent) }) {
            checkNotNull(fixture).rendererSource
        }
    }

    /**
     * The server uses only the schema; all native resources remain exclusively client-owned.
     */
    public companion object {
        public val type: ProjectionType = ProjectionType(ResourceId("strata_test", "native_canvas"))
        private var fixture: MinecraftCanvasTestFixture? = null

        /**
         * Acquires the test resource on the client thread before the server sends its declaration.
         */
        internal fun open() {
            check(fixture == null)
            fixture =
                MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()).also {
                    it.snapshotMode = MinecraftCanvasSnapshotMode.Matching
                }
        }

        /**
         * Checks real native rendering and stable attachment ownership after unrelated remote revisions.
         */
        internal fun verifyRendering() {
            val active = checkNotNull(fixture)
            check(active.renderersOpened == 1 && 0 < active.renderCalls)
        }

        /**
         * Reads literal framebuffer texels rather than comparing two implementations of Canvas rendering.
         */
        internal fun verifyPixels(path: Path) {
            val image = requireNotNull(ImageIO.read(path.toFile()))
            check(image.getRGB(200, 100) == 0xFF0000FF.toInt()) { "The authoritative blue revision did not reach the framebuffer." }
            check(image.getRGB(8, 56) == 0xFF00807F.toInt()) { "The remote native Canvas did not composite half-alpha green over blue." }
        }

        /**
         * Observes renderer completion after closing the native screen; used from the client test scheduler.
         */
        internal fun released(): Boolean = checkNotNull(fixture).let { it.renderersOpened == it.renderersClosed }

        /**
         * Releases external resources only after the native presenter has retired its renderer.
         */
        internal fun close() {
            checkNotNull(fixture).close()
            fixture = null
        }
    }
}

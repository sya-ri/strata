@file:Suppress("DEPRECATION") // The supported compatibility entry point is an explicit parity control.
@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf

/**
 * Actual public PlayerHead and profile host with deterministic owner-thread publication and bounded active bindings.
 * Test input belongs to this fixture; runtime ownership is inspected independently by PlayerHeadCacheProbe.
 */
internal class PlayerHeadLayerFixture(
    internal val mode: Mode,
    skin: DrawImage = PlayerHeadPixelReference.skin(),
    size: Int = 10,
    showHat: Boolean = false,
    private val entry: Entry = Entry.Compatibility,
    lookupSource: PlayerSkinSource = PlayerSkinSource.Name("LayerFixture"),
    readyAtAttach: Boolean = true,
    private val probe: MinecraftHostProbe? = null,
) : AutoCloseable {
    /**
     * Independent immediate and asynchronous public source paths.
     */
    internal enum class Mode {
        Immediate,
        Async,
    }

    /**
     * Preferred integer-scale API and supported arbitrary-size compatibility API.
     */
    internal enum class Entry {
        Compatibility,
        TypedScale,
    }

    private data class Configuration(
        val image: DrawImage,
        val size: Int,
        val showHat: Boolean,
        val lookupSource: PlayerSkinSource,
        val revision: Int = 0,
        val present: Boolean = true,
    )

    private val configuration = mutableStateOf(Configuration(skin, size, showHat, lookupSource))
    private var revision = 0

    /**
     * Fixture-owned authoritative source commits only when the public host refreshes it.
     */
    internal val platform = Platform(if (readyAtAttach) MinecraftPlayerSkinBinding.Snapshot.Ready(skin) else MinecraftPlayerSkinBinding.Snapshot.Pending)

    /**
     * Public profile-backed host; closing it releases its platform and every active lookup.
     */
    internal val host: MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("PlayerHead layer ownership") {
                val current = configuration.value
                Row {
                    if (current.present) {
                        val source = if (mode == Mode.Immediate) PlayerSkinSource.Pixels(current.image) else current.lookupSource
                        if (entry == Entry.TypedScale) {
                            PlayerHead(source, PlayerHeadScale(current.size / 8), current.showHat, key = ElementKey(Key.Head))
                        } else {
                            PlayerHead(
                                source = source,
                                size = current.size,
                                showHat = current.showHat,
                                loadingContent = { Spacer(modifier = Modifier.Empty.size(4, 4).background(ArgbColor(0xFFFFAA00.toInt()))) },
                                failureContent = { Spacer(modifier = Modifier.Empty.size(6, 6).background(ArgbColor(0xFFAA0000.toInt()))) },
                                key = ElementKey(Key.Head),
                            )
                        }
                    }
                    probe?.let { element(it.element()) }
                }
            },
            MinecraftProfileFixture.create(),
            platform,
        )

    /**
     * Current declared skin for independent expected pixels.
     */
    internal val skin: DrawImage get() = configuration.value.image

    /**
     * Current requested square extent.
     */
    internal val size: Int get() = configuration.value.size

    /**
     * Current requested outer-layer visibility.
     */
    internal val showHat: Boolean get() = configuration.value.showHat

    /**
     * Attaches without drawing; async lookup ownership is acquired here.
     */
    internal fun attach() = host.attach()

    /**
     * Commits queued source publications and produces one actual host frame.
     */
    internal fun frame(): RuntimeUiFrame = host.frame(IntSize(size + if (probe == null) 0 else 8, size))

    /**
     * Changes only layer selection while keeping the source-image and logical-size key.
     */
    internal fun hat(visible: Boolean) = update { it.copy(showHat = visible) }

    /**
     * Forces public reevaluation even for value-equal pixels, and queues an async snapshot at the same cutoff.
     */
    internal fun replace(image: DrawImage) {
        update { it.copy(image = image) }
        if (mode == Mode.Async) platform.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(image))
    }

    /**
     * Invalidates size through the public declaration while preserving its skin identity.
     */
    internal fun resize(size: Int) = update { it.copy(size = size) }

    /**
     * Replaces a typed lookup locator, forcing independent binding ownership.
     */
    internal fun source(source: PlayerSkinSource) = update { it.copy(lookupSource = source) }

    /**
     * Forces a dirty declaration with the same complete visual key.
     */
    internal fun repaint() = update { it }

    /**
     * Suspends attachment and its derived images without closing this fixture.
     */
    internal fun detach() = host.detach()

    /**
     * Terminal public close is idempotent; all retained painters and active bindings must be empty afterward.
     */
    override fun close() = host.close()

    private fun update(transform: (Configuration) -> Configuration) {
        revision += 1
        configuration.value = transform(configuration.value).copy(revision = revision)
    }

    /**
     * Removes or reinserts the stable keyed head through actual reconciliation.
     */
    internal fun present(present: Boolean) = update { it.copy(present = present) }

    private enum class Key {
        Head,
    }

    /**
     * One current source snapshot and a bounded set of actually live bindings, with no completed-binding history.
     */
    internal class Platform(
        initial: MinecraftPlayerSkinBinding.Snapshot,
    ) : MinecraftUiPlatform {
        private var current = initial
        private var pending: MinecraftPlayerSkinBinding.Snapshot? = null
        private val active = LinkedHashSet<Binding>()
        private var closed = false

        /**
         * Successful lookup acquisitions across this fixture's lifecycle.
         */
        internal var acquisitions: Int = 0
            private set

        /**
         * Actually attempted binding releases across this fixture's lifecycle.
         */
        internal var releases: Int = 0
            private set

        /**
         * Current active binding count; completed generations are never retained.
         */
        internal val activeCount: Int get() = active.size

        /**
         * Owner callback used only for deterministic public-host reentry and source-cutoff tests.
         */
        internal var onRefresh: (() -> Unit)? = null

        /**
         * Current binding for one-head tests, borrowed without adding retention.
         */
        internal val binding: Binding get() = active.single()

        /**
         * Configures failures before a newly acquired binding is handed to the runtime.
         */
        internal var onAcquire: ((Binding) -> Unit)? = null

        /**
         * Queues source state; publication does not mutate any retained node before refresh.
         */
        internal fun enqueue(snapshot: MinecraftPlayerSkinBinding.Snapshot) {
            check(closed.not())
            pending = snapshot
        }

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = throw UnsupportedOperationException("No inventory in the PlayerHead fixture: " + binding)

        override fun image(resource: ResourceId): DrawImage = throw UnsupportedOperationException("No external image resource in the PlayerHead fixture: " + resource)

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding {
            check(closed.not())
            check((source is PlayerSkinSource.Pixels).not())
            acquisitions += 1
            val created =
                Binding(current) { binding ->
                    active.remove(binding)
                    releases += 1
                }
            active.add(created)
            onAcquire?.invoke(created)
            return created
        }

        override fun refresh() {
            check(closed.not())
            onRefresh?.invoke()
            val next = pending ?: return
            pending = null
            current = next
            active.toList().forEach { it.commit(next) }
        }

        override fun close() {
            if (closed) return
            closed = true
            current = MinecraftPlayerSkinBinding.Snapshot.Pending
            pending = null
            onRefresh = null
            onAcquire = null
            active.toList().forEach(Binding::close)
            check(active.isEmpty())
        }
    }

    /**
     * One owner-confined binding; release clears snapshot and observer before configurable failing callbacks.
     */
    internal class Binding(
        initial: MinecraftPlayerSkinBinding.Snapshot,
        private var release: ((Binding) -> Unit)?,
    ) : MinecraftPlayerSkinBinding {
        private var current = initial
        private var observer: (() -> Unit)? = null

        /**
         * Whether terminal ownership has been claimed.
         */
        internal var closed: Boolean = false
            private set

        /**
         * Optional exact subscription release failure, thrown after its observer has been dropped.
         */
        internal var subscriptionFailure: Throwable? = null

        /**
         * Optional exact binding release failure, thrown after ownership has been released.
         */
        internal var closeFailure: Throwable? = null

        /**
         * Optional callback observes early cache clearing and inert old observers during release.
         */
        internal var beforeClose: (() -> Unit)? = null

        /**
         * Borrows an old observer for deliberately superseded-completion tests.
         */
        internal fun savedObserver(): () -> Unit = checkNotNull(observer)

        /**
         * Commits one immutable source and notifies on the owner thread, including equal pixels with new identity.
         */
        internal fun commit(snapshot: MinecraftPlayerSkinBinding.Snapshot) {
            if (closed) return
            current = snapshot
            observer?.invoke()
        }

        override fun snapshot(): MinecraftPlayerSkinBinding.Snapshot {
            check(closed.not())
            return current
        }

        override fun observe(observer: () -> Unit): AutoCloseable {
            check(closed.not() && this.observer == null)
            this.observer = observer
            return AutoCloseable {
                if (this.observer === observer) {
                    this.observer = null
                    subscriptionFailure?.let { throw it }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            current = MinecraftPlayerSkinBinding.Snapshot.Pending
            observer = null
            val action = release
            release = null
            action?.invoke(this)
            val before = beforeClose
            beforeClose = null
            before?.invoke()
            closeFailure?.let { throw it }
        }
    }
}

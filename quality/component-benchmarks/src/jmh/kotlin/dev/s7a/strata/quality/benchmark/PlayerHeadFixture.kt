@file:Suppress("DEPRECATION") // Arbitrary-size public compatibility is a supported control.
@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import java.util.Collections
import java.util.IdentityHashMap
import java.lang.reflect.Modifier as ReflectionModifier

/**
 * Real profile-backed host with immutable source assets, fixed viewport and deterministic async publication.
 * It stores only current authority and active bindings, with no prior command, image or completed-binding history.
 */
internal class PlayerHeadFixture(
    internal val workload: PlayerHeadWorkload,
    private val assets: Assets,
    visible: Boolean,
) : AutoCloseable {
    private data class Declaration(
        val skin: DrawImage,
        val size: Int,
        val visible: Boolean,
        val revision: Int,
    )

    private val declared = mutableStateOf(Declaration(assets.skins.first(), workload.size, visible, 0))
    private val platform = Platform(declared.value.skin)
    private val viewport = IntSize(workload.count * workload.replacementSize, workload.replacementSize)

    /**
     * Current actual public runtime owner.
     */
    internal val host: MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Fixed PlayerHead corpus") {
                val current = declared.value
                Row {
                    repeat(workload.count) { index ->
                        val source =
                            when (workload.source) {
                                PlayerHeadWorkload.Source.Sync -> PlayerSkinSource.Pixels(current.skin)
                                PlayerHeadWorkload.Source.Async -> PlayerSkinSource.Name("FixturePlayer")
                            }
                        when (workload.entry) {
                            PlayerHeadWorkload.Entry.Compatibility -> PlayerHead(source, current.size, current.visible, key = ElementKey(index))
                            PlayerHeadWorkload.Entry.Scale -> PlayerHead(source, PlayerHeadScale(current.size / 8), current.visible, key = ElementKey(index))
                        }
                    }
                }
            },
            assets.profile,
            platform,
        )

    /**
     * Attaches actual retained nodes and acquires async bindings outside cold-frame timing.
     */
    internal fun attach(): Unit = host.attach()

    /**
     * Produces an actual detached frame; source changes commit only at the public refresh cutoff.
     */
    internal fun frame(): RuntimeUiFrame = host.frame(viewport)

    /**
     * Explicit public-host viewport for supplementary boundary controls outside the fixed timed matrix.
     */
    internal fun frame(viewport: IntSize): RuntimeUiFrame = host.frame(viewport)

    /**
     * Requests only a different set of layers for the same identity/size key.
     */
    internal fun hat(visible: Boolean) {
        declared.value = declared.value.copy(visible = visible, revision = declared.value.revision + 1)
    }

    /**
     * Alternates two prebuilt source identities, avoiding source-image construction in replacement timing.
     */
    internal fun replace(visible: Boolean) {
        val current = declared.value
        val skin = if (current.skin === assets.skins.first()) assets.skins.last() else assets.skins.first()
        declared.value = current.copy(skin = skin, visible = visible, revision = current.revision + 1)
        platform.enqueue(skin)
    }

    /**
     * Alternates fixed extents; nearest controls remain divisible by eight.
     */
    internal fun resize() {
        val current = declared.value
        declared.value = current.copy(size = if (current.size == workload.size) workload.replacementSize else workload.size, visible = true, revision = current.revision + 1)
    }

    /**
     * Supplementary untimed boundary control uses the public logical-size declaration.
     */
    internal fun resizeTo(size: Int) {
        val current = declared.value
        declared.value = current.copy(size = size, revision = current.revision + 1)
    }

    /**
     * A dirty frame with both layers already prepared; visibility alternation invalidates actual head painting.
     */
    internal fun dirty(): RuntimeUiFrame {
        hat(declared.value.visible.not())
        return frame()
    }

    /**
     * Independent command/pixel verification; excluded from all timed methods.
     */
    internal fun verify(frame: RuntimeUiFrame) {
        val current = declared.value
        PlayerHeadReference.verify(frame, current.skin, current.size, workload.count, current.visible)
    }

    /**
     * Current logical extent for actual-read construction counters.
     */
    internal val size: Int get() = declared.value.size

    /**
     * Live source ownership for deterministic terminal checks.
     */
    internal val activeBindings: Int get() = platform.activeCount

    /**
     * Borrows current painter handles outside timing; callers must not keep history between measurements.
     */
    internal fun caches(): List<Cache> {
        val hostOrigin = host.javaClass.protectionDomain.codeSource.location
        val coreClass = UiTree::class.java
        val origins = setOf(hostOrigin, coreClass.protectionDomain.codeSource.location)
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>()
        val result = ArrayList<Cache>()
        val painterType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftPlayerHeadPainter", false, host.javaClass.classLoader)
        pending.add(host)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (visited.add(current).not()) continue
            when {
                painterType.isInstance(current) -> {
                    result.add(Cache(current))
                }

                current is MinecraftUiPlatform -> {}

                current is Collection<*> -> {
                    current.filterNotNull().forEach(pending::add)
                }

                current is Map<*, *> -> {
                    current.keys.filterNotNull().forEach(pending::add)
                    current.values.filterNotNull().forEach(pending::add)
                }

                current is Array<*> -> {
                    current.filterNotNull().forEach(pending::add)
                }

                current.javaClass.protectionDomain.codeSource
                    ?.location in origins -> {
                    var type: Class<*>? = current.javaClass
                    while (type != null && type.protectionDomain.codeSource?.location in origins) {
                        type.declaredFields.filter { ReflectionModifier.isStatic(it.modifiers).not() }.forEach { field ->
                            field.isAccessible = true
                            field.get(current)?.let(pending::add)
                        }
                        type = type.superclass
                    }
                }
            }
        }
        check(result.size == workload.count)
        return result
    }

    override fun close() = host.close()

    /**
     * Detached borrowed owner for bounded current-image inspection only, using existing field identifiers.
     */
    internal class Cache(
        private val owner: Any,
    ) {
        /**
         * Current face/hat storage, excluding temporary sampling and construction arrays.
         */
        internal fun retainedPixels(): Long = listOf(Slot.Face, Slot.Hat).sumOf { read(it)?.size?.let { size -> size.width.toLong() * size.height } ?: 0L }

        /**
         * Current face identity, without cloning or dereferencing pixel storage.
         */
        internal val face: DrawImage? get() = read(Slot.Face)

        /**
         * Current hat identity, without adding a historical generation.
         */
        internal val hat: DrawImage? get() = read(Slot.Hat)

        /**
         * Requires terminal release of source and all derived layer references.
         */
        internal fun verifyEmpty() = Slot.entries.forEach { check(read(it) == null) }

        private fun read(slot: Slot): DrawImage? {
            val field = owner.javaClass.getDeclaredField(slot.field)
            field.isAccessible = true
            return field.get(owner) as? DrawImage
        }

        private enum class Slot(
            val field: String,
        ) {
            Skin("cachedSkin"),
            Face("cachedFace"),
            Hat("cachedHat"),
        }
    }

    /**
     * Complete fixed compatibility profile and patterned immutable skins constructed outside timing.
     */
    internal class Assets {
        /**
         * Two source identities shared by both archive sides and all selected operations.
         */
        internal val skins: List<DrawImage> = listOf(PlayerHeadReference.skin(0), PlayerHeadReference.skin(1))

        /**
         * Complete immutable profile; compatibility glyphs need no native or system font backend.
         */
        internal val profile: MinecraftUiProfile =
            createMinecraftUiProfile {
                menuBackground(image(16, 16))
                containerBackground(image(256, 256))
                slotHighlightBack(image(24, 24))
                slotHighlightFront(image(24, 24))
                listBackground(image(16, 16))
                listHeaderSeparator(image(32, 2))
                listFooterSeparator(image(32, 2))
                scrollbarBackground(image(6, 32))
                scrollbarThumb(image(6, 32))
                checkbox(image(20, 20))
                checkboxHighlighted(image(20, 20))
                checkboxSelected(image(20, 20))
                checkboxSelectedHighlighted(image(20, 20))
                slider(image(200, 20), 1, NineSliceCenterMode.Tiled)
                sliderHighlighted(image(200, 20), 1, NineSliceCenterMode.Tiled)
                sliderHandle(image(8, 20))
                sliderHandleHighlighted(image(8, 20))
                loadingIndicator(image(5, 6))
                progressBarBorder(image(12, 12))
                progressBarFill(image(6, 6))
                progressBarFull(image(6, 6))
                tooltipBackground(image(100, 100))
                tooltipFrame(image(100, 100))
                textFieldNormal(image(200, 20))
                textFieldHighlighted(image(200, 20))
                val glyphMask = createDrawImage(IntSize(8, 8), IntArray(64) { 0xFFFFFFFF.toInt() })
                for (codePoint in 0x21..0x7E) printableAsciiGlyph(codePoint, glyphMask)
                buttonNormal(image(200, 20), 3, NineSliceCenterMode.Tiled)
                buttonHighlighted(image(200, 20), 3, NineSliceCenterMode.Tiled)
                buttonDisabled(image(200, 20), 1, NineSliceCenterMode.Tiled)
            }

        private fun image(
            width: Int,
            height: Int,
        ): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height) { 0xFF426789.toInt() })
    }

    private class Platform(
        skin: DrawImage,
    ) : MinecraftUiPlatform {
        private var current: MinecraftPlayerSkinBinding.Snapshot = MinecraftPlayerSkinBinding.Snapshot.Ready(skin)
        private var pending: DrawImage? = null
        private val active = LinkedHashSet<Binding>()
        private var closed = false

        val activeCount: Int get() = active.size

        fun enqueue(skin: DrawImage) {
            check(closed.not())
            pending = skin
        }

        override fun refresh() {
            val next = pending ?: return
            pending = null
            current = MinecraftPlayerSkinBinding.Snapshot.Ready(next)
            active.toList().forEach { it.commit(current) }
        }

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding {
            check(closed.not() && (source is PlayerSkinSource.Pixels).not())
            return Binding(current) { active.remove(it) }.also { active.add(it) }
        }

        override fun image(resource: ResourceId): DrawImage = error("No external resource: " + resource)

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = error("No inventory: " + binding)

        override fun close() {
            if (closed) return
            closed = true
            current = MinecraftPlayerSkinBinding.Snapshot.Pending
            pending = null
            active.toList().forEach(Binding::close)
            check(active.isEmpty())
        }
    }

    private class Binding(
        private var current: MinecraftPlayerSkinBinding.Snapshot,
        private var release: ((Binding) -> Unit)?,
    ) : MinecraftPlayerSkinBinding {
        private var observer: (() -> Unit)? = null

        fun commit(next: MinecraftPlayerSkinBinding.Snapshot) {
            current = next
            observer?.invoke()
        }

        override fun snapshot(): MinecraftPlayerSkinBinding.Snapshot = current

        override fun observe(observer: () -> Unit): AutoCloseable {
            check(this.observer == null)
            this.observer = observer
            return AutoCloseable { this.observer = null }
        }

        override fun close() {
            current = MinecraftPlayerSkinBinding.Snapshot.Pending
            observer = null
            val action = release
            release = null
            action?.invoke(this)
        }
    }
}

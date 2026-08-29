package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained implementation of one asynchronously resolved layered player head.
 *
 * Only the child matching the committed pending or failure state is measured and placed.
 * Ready state paints the resolved face and optional hat directly in the same fixed square.
 */
@OptIn(InternalStrataRuntimeApi::class)
private class MinecraftAsyncPlayerHeadElement private constructor(
    internal val platform: MinecraftUiPlatform,
    internal val source: PlayerSkinSource,
    internal val size: Int,
    internal val showHat: Boolean,
    loading: Element?,
    failure: Element?,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = listOfNotNull(loading, failure),
        modifier = modifier,
    ) {
    internal val loadingIndex: Int? = if (loading == null) null else 0
    internal val failureIndex: Int? =
        if (failure == null) {
            null
        } else if (loading == null) {
            0
        } else {
            1
        }

    /**
     * Retained lookup observer, fixed-square layout, and layered painter.
     */
    private class Node(
        initialPlatform: MinecraftUiPlatform,
        initialSource: PlayerSkinSource,
        initialSize: Int,
        initialShowHat: Boolean,
        initialLoadingIndex: Int?,
        initialFailureIndex: Int?,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        LifecycleNode {
        private var platform: MinecraftUiPlatform? = initialPlatform
        private var source: PlayerSkinSource? = initialSource
        private var binding: MinecraftPlayerSkinBinding? = null
        private var snapshot: MinecraftPlayerSkinBinding.Snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending
        private var size: Int = initialSize
        private var showHat: Boolean = initialShowHat
        private var loadingIndex: Int? = initialLoadingIndex
        private var failureIndex: Int? = initialFailureIndex
        private var subscription: AutoCloseable? = null
        private var attached: Boolean = false
        private val painter = MinecraftPlayerHeadPainter()

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val measured = IntSize(size, size)
            require(constraints.isSatisfiedBy(measured)) {
                "PlayerHead constraints must contain its requested size."
            }
            activeChildIndex()?.let { index ->
                scope.measureChild(
                    index,
                    Constraints(
                        minWidth = 0,
                        maxWidth = size,
                        minHeight = 0,
                        maxHeight = size,
                    ),
                )
            }
            return measured
        }

        override fun layout(scope: LayoutScope) {
            activeChildIndex()?.let { index ->
                val childSize = scope.measuredChildSize(index)
                scope.placeChild(
                    index,
                    IntOffset(
                        Math.subtractExact(size, childSize.width) / 2,
                        Math.subtractExact(size, childSize.height) / 2,
                    ),
                )
            }
        }

        override fun paint(scope: PaintScope) {
            val ready = snapshot as? MinecraftPlayerSkinBinding.Snapshot.Ready ?: return
            painter.paint(scope, ready.skin, size, showHat)
        }

        override fun attach() {
            attached = true
            acquireBinding()
        }

        override fun detach() {
            releaseBinding()
            snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending
            painter.clear()
            attached = false
        }

        override fun dispose() {
            releaseBinding()
            painter.clear()
            platform = null
            source = null
            attached = false
        }

        /**
         * Reconciles lookup ownership and visual configuration.
         *
         * @param current incoming immutable description.
         * @return invalidation required by changed state, size, layers, or fallback mapping.
         */
        @JvmSynthetic
        internal fun updateFrom(current: MinecraftAsyncPlayerHeadElement): DirtyMask {
            val sourceChanged = platform !== current.platform || source != current.source
            val sizeChanged = size != current.size
            val fallbackChanged = loadingIndex != current.loadingIndex || failureIndex != current.failureIndex
            val hatChanged = showHat != current.showHat
            if (sourceChanged) {
                releaseBinding()
                platform = current.platform
                source = current.source
                snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending
                if (attached) {
                    acquireBinding()
                }
            }
            if (sourceChanged || sizeChanged) {
                painter.clear()
            }
            size = current.size
            showHat = current.showHat
            loadingIndex = current.loadingIndex
            failureIndex = current.failureIndex
            return when {
                sourceChanged || sizeChanged || fallbackChanged -> DirtyMask.of(DirtyPhase.Measure)
                hatChanged -> DirtyMask.of(DirtyPhase.Paint)
                else -> DirtyMask.None
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun acquireBinding() {
            check(binding == null) { "PlayerHead lookup binding is already acquired." }
            val acquired = checkNotNull(platform).playerSkin(checkNotNull(source))
            try {
                val initial = acquired.snapshot()
                validateSnapshot(initial)
                binding = acquired
                snapshot = initial
                subscription =
                    acquired.observe {
                        if (binding !== acquired) return@observe
                        val next = acquired.snapshot()
                        validateSnapshot(next)
                        if (snapshot != next) {
                            snapshot = next
                            painter.clear()
                            invalidate(DirtyMask.of(DirtyPhase.Measure))
                        }
                    }
            } catch (failure: Throwable) {
                acquired.close()
                throw failure
            }
        }

        private fun releaseBinding() {
            val currentSubscription = subscription
            subscription = null
            currentSubscription?.close()
            val currentBinding = binding
            binding = null
            currentBinding?.close()
        }

        private fun validateSnapshot(candidate: MinecraftPlayerSkinBinding.Snapshot) {
            val ready = candidate as? MinecraftPlayerSkinBinding.Snapshot.Ready ?: return
            require(ready.skin.size == skinSize) { "PlayerHead requires an exact 64 by 64 skin." }
        }

        private fun activeChildIndex(): Int? =
            when (snapshot) {
                MinecraftPlayerSkinBinding.Snapshot.Pending -> loadingIndex
                is MinecraftPlayerSkinBinding.Snapshot.Ready -> null
                MinecraftPlayerSkinBinding.Snapshot.Failed -> failureIndex
            }
    }

    companion object {
        private val skinSize = IntSize(64, 64)
        private val TYPE: ElementType<MinecraftAsyncPlayerHeadElement, Node> =
            ElementType(
                elementClass = MinecraftAsyncPlayerHeadElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    MinecraftPlayerHeadPainter.validateSize(element.size)
                },
                createNode = { element ->
                    Node(
                        element.platform,
                        element.source,
                        element.size,
                        element.showHat,
                        element.loadingIndex,
                        element.failureIndex,
                    )
                },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        @JvmSynthetic
        internal fun create(
            platform: MinecraftUiPlatform,
            source: PlayerSkinSource,
            size: Int,
            showHat: Boolean,
            loading: Element?,
            failure: Element?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftAsyncPlayerHeadElement(platform, source, size, showHat, loading, failure, modifier, key)
    }
}

/**
 * Creates one private asynchronous player-head description.
 *
 * @param platform host-owned lookup platform borrowed by the retained node.
 * @param source immutable profile locator resolved only after lifecycle attachment.
 * @param size positive logical square extent.
 * @param showHat whether the outer layer is painted after the face.
 * @param loading optional pending-state root.
 * @param failure optional failure-state root.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return retained asynchronous head element.
 */
@JvmSynthetic
@OptIn(InternalStrataRuntimeApi::class)
internal fun createMinecraftAsyncPlayerHeadElement(
    platform: MinecraftUiPlatform,
    source: PlayerSkinSource,
    size: Int,
    showHat: Boolean,
    loading: Element?,
    failure: Element?,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftAsyncPlayerHeadElement.create(platform, source, size, showHat, loading, failure, modifier, key)

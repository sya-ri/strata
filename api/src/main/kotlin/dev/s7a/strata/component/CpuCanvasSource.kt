package dev.s7a.strata.component

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource

/**
 * Immutable CPU source description whose independent bindings coalesce revisioned images.
 *
 * [frames] remains externally owned and is accessed only to acquire a subscription on the attaching tree thread.
 * The source itself owns no observer, pending image, or retained tree.
 * Acquisition failures propagate after any acquired close handle has been released.
 *
 * @param frames caller-owned revisioned immutable image source.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class CpuCanvasSource(
    private val frames: StateSource<DrawImage>,
) : CanvasSource {
    override fun open(canvasId: CanvasId): CanvasBinding {
        val binding = Binding()
        return runCatching {
            binding.subscribe(frames)
            binding
        }.getOrElse { failure ->
            val cleanupFailure = runCatching(binding::close).exceptionOrNull()
            if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            throw failure
        }
    }

    private class Binding : CanvasBinding {
        private val ownerThread: Thread = Thread.currentThread()
        private val monitor: Any = Any()
        private var committed: StateSnapshot<DrawImage>? = null
        private var pending: StateSnapshot<DrawImage>? = null
        private var captured: StateSnapshot<DrawImage>? = null
        private var frameCaptured: Boolean = false
        private var closed: Boolean = false
        private var closeAction: (() -> Unit)? = null

        /**
         * Acquires one source-owned observation and transfers its initial snapshot on the owner thread.
         * The retained close action shares the source subscription's close state without retaining its initial pixels.
         *
         * @param source externally owned image source; callbacks may race this call and only enqueue.
         * @throws Throwable when subscription or initial-image validation fails; the caller closes this binding.
         */
        fun subscribe(source: StateSource<DrawImage>) {
            val subscription = source.subscribe(::enqueue)
            closeAction = subscription.retainCloseAction()
            val initial = subscription.initialSnapshot
            requireImage(initial.value)
            synchronized(monitor) {
                committed = initial
                if (pending?.let { next -> next.revision <= initial.revision } == true) pending = null
            }
        }

        override fun captureFrame() {
            checkOwner()
            synchronized(monitor) {
                check(closed.not()) { "A closed canvas binding cannot capture a frame." }
                check(frameCaptured.not()) { "A canvas frame cutoff is already captured." }
                captured = pending
                pending = null
                frameCaptured = true
            }
        }

        override fun commitFrame(): Boolean {
            checkOwner()
            val previous: DrawImage?
            val next: StateSnapshot<DrawImage>
            synchronized(monitor) {
                check(frameCaptured) { "A canvas frame must be captured before commit." }
                frameCaptured = false
                next = captured ?: return false
                captured = null
                requireImage(next.value)
                previous = committed?.value
                committed = next
            }
            // A distinct image must replace the cached command even when pixels compare equal, so obsolete image storage is released.
            return previous !== next.value
        }

        override fun paint(scope: PaintScope) {
            checkOwner()
            val image = checkNotNull(committed) { "An inactive canvas binding cannot paint." }.value
            scope.blitImage(
                image,
                IntRect(0, 0, image.size.width, image.size.height),
                IntRect(0, 0, scope.size.width, scope.size.height),
            )
        }

        override fun close() {
            checkOwner()
            val action =
                synchronized(monitor) {
                    if (closed) return
                    closed = true
                    committed = null
                    pending = null
                    captured = null
                    frameCaptured = false
                    val action = closeAction
                    closeAction = null
                    action
                }
            action?.invoke()
        }

        private fun enqueue(snapshot: StateSnapshot<DrawImage>) {
            synchronized(monitor) {
                if (closed) return
                val committedRevision = committed?.revision
                val capturedRevision = captured?.revision
                val pendingRevision = pending?.revision
                if (committedRevision != null && snapshot.revision <= committedRevision) return
                if (capturedRevision != null && snapshot.revision <= capturedRevision) return
                if (pendingRevision != null && snapshot.revision <= pendingRevision) return
                pending = snapshot
            }
        }

        private fun checkOwner() {
            check(Thread.currentThread() === ownerThread) { "Canvas bindings are confined to their owner thread." }
        }

        private fun requireImage(image: DrawImage) {
            require(0 < image.size.width && 0 < image.size.height) { "Canvas source dimensions must be positive." }
        }
    }
}

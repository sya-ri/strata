package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Creates an owner-thread subscription release that drops its callback before its sole invocation.
 * Wrong-thread attempts reject without consuming the release, including after an owner-thread close.
 */
internal fun minecraftOwnerSubscription(
    ownerThread: Thread,
    release: () -> Unit,
): AutoCloseable {
    var pendingRelease: (() -> Unit)? = release
    return AutoCloseable {
        check(Thread.currentThread() === ownerThread) { "Minecraft inventory platform requires its creator thread." }
        val current = pendingRelease
        pendingRelease = null
        current?.invoke()
    }
}

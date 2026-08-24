package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.screen.ScreenOpenThreadException;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Enforces pre-transfer thread validation and post-transfer cleanup for one native screen installation.
 *
 * <p>The factory owns cleanup of any partial state when it fails. Once the factory returns a screen, this transaction closes it if native installation fails and preserves the installation failure as primary.</p>
 */
final class FabricScreenPresentationTransaction {
    private FabricScreenPresentationTransaction() {
    }

    /**
     * Creates and installs one screen under the Fabric presentation ownership contract.
     *
     * @param isClientThread reports whether the caller is the Minecraft client thread without transferring a definition.
     * @param create transfers the definition and returns its fully owned native screen.
     * @param install installs the created screen into the Minecraft client.
     * @param <S> terminally closeable native screen type.
     * @throws ScreenOpenThreadException before {@code create} when the caller is not the client thread.
     * @throws Throwable when creation or installation fails; a post-transfer installation failure remains primary over cleanup failure.
     */
    static <S extends AutoCloseable> void present(
            BooleanSupplier isClientThread,
            Supplier<S> create,
            Consumer<S> install) throws Throwable {
        if (isClientThread.getAsBoolean() == false) {
            throw new ScreenOpenThreadException("Strata screens must be opened on the Minecraft client thread.");
        }
        S screen = create.get();
        try {
            install.accept(screen);
        } catch (Throwable failure) {
            try {
                screen.close();
            } catch (Throwable cleanup) {
                FabricMinecraftFailures.addSuppressed(failure, cleanup);
            }
            throw failure;
        }
    }
}

package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Gives the legacy texture manager a non-owning view of one staged portable upload.
 *
 * <p>The supplied name belongs solely to its fenced GUI-generation resource.
 * Registration, resource reload, unregister, and texture-manager shutdown cannot allocate or destroy storage through this view.
 * The render-thread name callback retains only its native owner, never a screen or frame.</p>
 */
final class FabricPortableBorrowedTexture extends AbstractTexture {
    private final IntSupplier textureId;

    /**
     * Borrows a name provider without acquiring or allocating native storage.
     *
     * @param textureId render-thread provider for the independently owned texture name
     * @throws NullPointerException when the provider is absent
     */
    FabricPortableBorrowedTexture(IntSupplier textureId) {
        this.textureId = Objects.requireNonNull(textureId, "The portable texture name provider must be present.");
    }

    @Override
    public int getId() {
        return textureId.getAsInt();
    }

    /**
     * Preserves the immutable upload when older texture managers request a resource load.
     *
     * <p>This compatibility method deliberately lacks {@code @Override}, because Minecraft 1.21.4 moves reloadable content to a separate type.</p>
     *
     * @param resourceManager borrowed resource manager, never read or retained
     */
    public void load(ResourceManager resourceManager) {
    }

    @Override
    public void releaseId() {
    }

    @Override
    public void close() {
    }
}

package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Objects;

/**
 * Gives the texture manager a non-owning render-thread view of one Canvas target.
 *
 * <p>The Canvas device and its lifetime permit remain the sole owners of the target and its current color attachment.
 * Registration, resource reload, unregister, and texture-manager shutdown never allocate or destroy native storage here.
 * The wrapper borrows its target until the manager drops the wrapper; callers must keep the target alive through GUI consumption.</p>
 */
final class FabricCanvasBorrowedTexture extends AbstractTexture {
    private final RenderTarget target;

    /**
     * Borrows an existing target without acquiring or transferring its native ownership.
     *
     * @param target render-thread Canvas target whose color attachment supplies this view
     * @throws NullPointerException when the borrowed target is absent
     */
    FabricCanvasBorrowedTexture(RenderTarget target) {
        this.target = Objects.requireNonNull(target, "The borrowed Canvas target must be present.");
    }

    @Override
    public int getId() {
        return target.getColorTextureId();
    }

    /**
     * Preserves the target's existing pixels when a legacy texture manager requests a resource load.
     *
     * <p>The parameter is borrowed but unused because Canvas generations own all pixel production.
     * This intentionally lacks {@code @Override}: the abstract load method exists through Minecraft 1.21.3,
     * while 1.21.4 moves reloadable content to a separate texture type and never calls this compatibility entry.</p>
     *
     * @param resourceManager client-owned resources, never retained or read by this view
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

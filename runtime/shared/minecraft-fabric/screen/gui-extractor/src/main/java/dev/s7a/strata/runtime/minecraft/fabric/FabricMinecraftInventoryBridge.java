package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.s7a.strata.component.PlayerSkinSource;
import dev.s7a.strata.component.SlotBinding;
import dev.s7a.strata.input.InputResult;
import dev.s7a.strata.input.PointerEvent;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.render.PlatformDrawCommand;
import dev.s7a.strata.resource.ResourceId;
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding;
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding;
import dev.s7a.strata.runtime.minecraft.MinecraftPlatformCommandRenderer;
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSkinBindingLifecycle.SkinCompletion;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSkinBindingLifecycle.SkinFailed;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSkinBindingLifecycle.SkinReady;
import dev.s7a.strata.runtime.render.DrawCommand;
import dev.s7a.strata.runtime.remote.RemoteFailure;
import dev.s7a.strata.runtime.remote.RemoteProtocolException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Bridges retained Strata Slots to the active Minecraft menu and native item renderer.
 *
 * <p>The bridge is package-private, owner-thread confined, and owned by one common host. It polls copied ItemStack snapshots before frames and delegates every mutation through {@code MultiPlayerGameMode.handleContainerInput}; it never writes Inventory storage directly.</p>
 */
final class FabricMinecraftInventoryBridge extends FabricMinecraftInventoryPointerInput<FabricMinecraftInventoryBridge.NativeInput, FabricMinecraftInventoryBridge.Binding> implements MinecraftUiPlatform, MinecraftPlatformCommandRenderer<GuiGraphicsExtractor> {
    private final Thread ownerThread = Thread.currentThread();
    /** Whether any retained native Slot depends on the current container generation. */
    boolean hasBindings() {
        return bindings.isEmpty() == false;
    }

    private AbstractContainerMenu boundContainer;

    /** First native container used by a Slot; observation alone does not bind an unrelated HUD. */
    AbstractContainerMenu boundContainer() {
        return boundContainer;
    }

    private final Set<Binding> bindings = new LinkedHashSet<>();
    private final Set<SkinBinding> skinBindings = new LinkedHashSet<>();
    private Minecraft minecraft;
    private final BiFunction<Minecraft, PlayerSkinSource, CompletableFuture<Optional<FabricMinecraftSkinReference>>> skinLookup;
    private Binding hovered;
    private boolean closed;
    private int refreshBatchDepth;
    private boolean refreshedInBatch;

    private enum InputKind {
        MOVE,
        PRESS,
        DRAG,
        RELEASE
    }

    static final class NativeInput {
        private final InputKind kind;
        private final MouseButtonEvent mouse;
        private final boolean doubleClick;
        private Binding delivered;

        private NativeInput(InputKind kind, MouseButtonEvent mouse, boolean doubleClick) {
            this.kind = kind;
            this.mouse = mouse;
            this.doubleClick = doubleClick;
        }
    }

    /**
     * Immutable frame payload consumed only by this matching Fabric adapter.
     */
    private static final class ItemCommand implements PlatformDrawCommand {
        private final ItemStack stack;
        private final int seed;

        private ItemCommand(ItemStack stack, int seed) {
            this.stack = stack;
            this.seed = seed;
        }
    }

    final class Binding implements MinecraftInventorySlotBinding {
        private final SlotBinding locator;
        private ItemStack snapshot = ItemStack.EMPTY;
        private Function0<Unit> observer;
        private boolean released;

        private Binding(SlotBinding locator) {
            this.locator = locator;
        }

        @Override
        public PlatformDrawCommand drawCommand() {
            requireUsable();
            if (snapshot.isEmpty()) {
                return null;
            }
            return new ItemCommand(snapshot.copy(), resolveSlot().index);
        }

        @Override
        public @NotNull AutoCloseable observe(@NotNull Function0<Unit> callback) {
            requireUsable();
            checkState(observer == null, "Minecraft inventory Slot already has a retained observer.");
            observer = callback;
            return () -> {
                requireOwnerThread();
                if (observer == callback) {
                    observer = null;
                }
            };
        }

        @Override
        public @NotNull InputResult dispatchPointer(@NotNull PointerEvent event) {
            requireUsable();
            NativeInput current = input;
            if (event instanceof PointerEvent.Move || event instanceof PointerEvent.Drag) {
                if (offeredHover == null) {
                    offeredHover = this;
                }
            }
            if (current == null) {
                return InputResult.Ignored;
            }
            current.delivered = this;
            if (event instanceof PointerEvent.Press) {
                return handlePress(this);
            }
            if (event instanceof PointerEvent.Drag) {
                return handleDrag(this);
            }
            if (event instanceof PointerEvent.Release) {
                return handleRelease(this);
            }
            return InputResult.Ignored;
        }

        @Override
        public void close() {
            requireOwnerThread();
            if (released) {
                return;
            }
            released = true;
            releaseState();
            bindings.remove(this);
            releasePointerTarget(this);
            if (hovered == this) {
                hovered = null;
            }
            if (offeredHover == this) {
                offeredHover = null;
            }
        }

        private void refresh() {
            ItemStack current = resolveSlot().getItem();
            if (ItemStack.matches(snapshot, current)) {
                return;
            }
            snapshot = current.isEmpty() ? ItemStack.EMPTY : current.copy();
            Function0<Unit> callback = observer;
            if (callback != null) {
                callback.invoke();
            }
        }

        private void releaseFromPlatform() {
            released = true;
            releaseState();
        }

        private void releaseState() {
            observer = null;
            snapshot = ItemStack.EMPTY;
        }

        private void requireUsable() {
            requireOwnerThread();
            checkState(released == false, "Minecraft inventory Slot binding is closed.");
            FabricMinecraftInventoryBridge.this.requireUsable();
        }

        private Slot resolveSlot() {
            LocalPlayer player = requirePlayer();
            AbstractContainerMenu menu = activeMenu();
            if (boundContainer == null) boundContainer = activeMenu();
        int index = locator.getIndex();
            if (locator.getSource() == SlotBinding.Source.PlayerInventory) {
                Inventory inventory = player.getInventory();
                int menuIndex = menu.findSlot(inventory, index).orElseThrow(
                        () -> new IllegalArgumentException("Player inventory index is not exposed by the active menu: " + index));
                return menu.getSlot(menuIndex);
            }
            if (locator.getSource() == SlotBinding.Source.Container) {
                return resolveContainerSlot(menu, player.getInventory(), index);
            }
            if (locator.getSource() == SlotBinding.Source.ActiveMenu) {
                if (index < 0 || menu.slots.size() <= index) {
                    throw new IllegalArgumentException("Active menu slot index is outside the current menu: " + index);
                }
                return menu.getSlot(index);
            }
            throw new IllegalArgumentException("Unsupported Minecraft Slot source: " + locator.getSource());
        }
    }

    private final class SkinBinding implements MinecraftPlayerSkinBinding {
        private final FabricMinecraftSkinBindingLifecycle lifecycle = new FabricMinecraftSkinBindingLifecycle();
        private Function0<Unit> observer;

        private SkinBinding(PlayerSkinSource source) {
            FabricMinecraftSkinBindingLifecycle completionTarget = lifecycle;
            skinLookup.apply(requireMinecraft(), source).whenComplete((resolved, failure) -> {
                if (failure == null && resolved.isPresent()) {
                    completionTarget.publish(new SkinReady(resolved.get()));
                } else {
                    completionTarget.publish(SkinFailed.INSTANCE);
                }
            });
        }

        @Override
        public @NotNull MinecraftPlayerSkinBinding.Snapshot snapshot() {
            requireUsable();
            return lifecycle.retainedSnapshot();
        }

        @Override
        public @NotNull AutoCloseable observe(@NotNull Function0<Unit> callback) {
            requireUsable();
            checkState(observer == null, "Minecraft player skin already has a retained observer.");
            observer = callback;
            return () -> {
                requireOwnerThread();
                if (observer == callback) {
                    observer = null;
                }
            };
        }

        @Override
        public void close() {
            requireOwnerThread();
            if (releaseState()) {
                skinBindings.remove(this);
            }
        }

        private void refresh() {
            if (lifecycle.isClosed()) {
                return;
            }
            SkinCompletion completion = lifecycle.drainCompletion();
            if (completion == null) {
                return;
            }
            MinecraftPlayerSkinBinding.Snapshot next;
            if (completion instanceof SkinReady ready) {
                try {
                    next = new MinecraftPlayerSkinBinding.Snapshot.Ready(ready.skin.snapshot(requireMinecraft()));
                } catch (RuntimeException failure) {
                    next = MinecraftPlayerSkinBinding.Snapshot.Failed.INSTANCE;
                }
            } else {
                next = MinecraftPlayerSkinBinding.Snapshot.Failed.INSTANCE;
            }
            if (lifecycle.commitSnapshot(next) == false) {
                return;
            }
            Function0<Unit> callback = observer;
            if (callback != null) {
                callback.invoke();
            }
        }

        private void releaseFromPlatform() {
            releaseState();
        }

        private boolean releaseState() {
            boolean released = lifecycle.close();
            observer = null;
            return released;
        }

        private void requireUsable() {
            requireOwnerThread();
            checkState(lifecycle.isClosed() == false, "Minecraft player skin binding is closed.");
            FabricMinecraftInventoryBridge.this.requireUsable();
        }
    }

    private FabricMinecraftInventoryBridge(Minecraft minecraft, BiFunction<Minecraft, PlayerSkinSource, CompletableFuture<Optional<FabricMinecraftSkinReference>>> skinLookup) {
        this.minecraft = minecraft;
        this.skinLookup = skinLookup;
    }

    /**
     * Creates one bridge on the current Minecraft client thread.
     *
     * @param minecraft active client instance retained until close.
     * @param skinLookup compiler-selected resolver called on the client owner; completions retain descriptors or detached pixels only.
     * @return a new platform bridge.
     */
    static FabricMinecraftInventoryBridge create(Minecraft minecraft, BiFunction<Minecraft, PlayerSkinSource, CompletableFuture<Optional<FabricMinecraftSkinReference>>> skinLookup) {
        checkState(minecraft.isSameThread(), "Minecraft inventory Slots require the client thread.");
        return new FabricMinecraftInventoryBridge(minecraft, skinLookup);
    }

    @Override
    public @NotNull MinecraftInventorySlotBinding inventorySlot(@NotNull SlotBinding locator) {
        requireUsable();
        Objects.requireNonNull(locator, "Minecraft Slot binding must not be null.");
        if (boundContainer == null) boundContainer = activeMenu();
        int index = locator.getIndex();
        if (index < 0) {
            throw new IllegalArgumentException("Minecraft Slot binding index must be non-negative: " + index);
        }
        if (locator.getSource() == SlotBinding.Source.PlayerInventory) {
            Inventory inventory = requirePlayer().getInventory();
            if (inventory.getContainerSize() <= index) {
                throw new IllegalArgumentException("Player inventory index is outside the active inventory: " + index);
            }
        } else if (locator.getSource() == SlotBinding.Source.Container) {
            resolveContainerSlot(activeMenu(), requirePlayer().getInventory(), index);
        } else if (locator.getSource() == SlotBinding.Source.ActiveMenu) {
            if (activeMenu().slots.size() <= index) {
                throw new IllegalArgumentException("Active menu slot index is outside the current menu: " + index);
            }
        } else {
            throw new IllegalArgumentException("Unsupported Minecraft Slot source: " + locator.getSource());
        }
        Binding binding = new Binding(locator);
        bindings.add(binding);
        return binding;
    }

    @Override
    public @NotNull DrawImage image(@NotNull ResourceId resource) {
        requireUsable();
        return FabricMinecraftAssets.loadMinecraftUiImage(resource);
    }

    @Override
    public @NotNull MinecraftPlayerSkinBinding playerSkin(@NotNull PlayerSkinSource source) {
        requireUsable();
        if (source instanceof PlayerSkinSource.Pixels) {
            throw new IllegalArgumentException("Direct player skin pixels do not require a platform lookup.");
        }
        SkinBinding binding = new SkinBinding(source);
        skinBindings.add(binding);
        return binding;
    }

    @Override
    public void refresh() {
        requireUsable();
        if (0 < refreshBatchDepth && refreshedInBatch) {
            return;
        }
        for (Binding binding : bindings) {
            binding.refresh();
        }
        for (SkinBinding binding : skinBindings) {
            binding.refresh();
        }
        if (0 < refreshBatchDepth) {
            refreshedInBatch = true;
        }
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        releasePointerState();
        input = null;
        hovered = null;
        offeredHover = null;
        for (Binding binding : bindings) {
            binding.releaseFromPlatform();
        }
        bindings.clear();
        for (SkinBinding binding : skinBindings) {
            binding.releaseFromPlatform();
        }
        skinBindings.clear();
        boundContainer = null;
        minecraft = null;
    }

    /**
     * Coalesces repeated host-frame refreshes within one native render extraction.
     *
     * <p>The outermost batch performs at most one inventory and skin refresh. Nested batches share that refresh, and all batching state is released even when the operation fails.</p>
     *
     * @param operation owner-thread render extraction operation.
     * @param <T> operation result type.
     * @return the exact operation result.
     * @throws IllegalStateException when called from another thread or after platform close.
     * @throws Throwable when the operation or one of its host-frame refreshes fails; the exact failure escapes unchanged.
     */
    <T> T withRefreshBatch(Function0<T> operation) throws Throwable {
        requireUsable();
        Objects.requireNonNull(operation, "Minecraft refresh batch operation must not be null.");
        boolean outermost = refreshBatchDepth == 0;
        if (outermost) {
            refreshedInBatch = false;
        }
        refreshBatchDepth += 1;
        try {
            return operation.invoke();
        } finally {
            refreshBatchDepth -= 1;
            if (outermost) {
                refreshedInBatch = false;
            }
        }
    }

    /**
     * Runs one common pointer move while collecting the latest-painted synchronized Slot as the keyboard target.
     *
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withPointerMove(BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.MOVE, null, false), operation);
    }

    /**
     * Runs one native mouse press with modifier and double-click state available to the hit Slot.
     *
     * @param event native immutable mouse event.
     * @param doubleClick native double-click marker.
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withMousePress(MouseButtonEvent event, boolean doubleClick, BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.PRESS, event, doubleClick), operation);
    }

    /**
     * Runs one native drag and records any eligible synchronized Slot crossed by the pointer.
     *
     * @param event native immutable mouse event.
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withMouseDrag(MouseButtonEvent event, BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.DRAG, event, false), operation);
    }

    /**
     * Runs one native release and completes a pending pickup, double-click, or quick-craft transaction.
     *
     * @param event native immutable mouse event.
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withMouseRelease(MouseButtonEvent event, BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.RELEASE, event, false), operation);
    }

    /**
     * Handles native hotbar, offhand, pick, and drop keys for the topmost synchronized Slot under the last delivered pointer move.
     *
     * @param event native key event.
     * @return true when one authoritative container input was sent.
     */
    boolean handleKeyPressed(KeyEvent event) {
        requireUsable();
        Binding target = hovered;
        if (target == null) {
            return false;
        }
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        if (activeMenu().getCarried().isEmpty()) {
            if (client.options.keySwapOffhand.matches(event)) {
                click(target, 40, FabricMinecraftInventoryAction.SWAP);
                return true;
            }
            for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
                if (client.options.keyHotbarSlots[index].matches(event)) {
                    click(target, index, FabricMinecraftInventoryAction.SWAP);
                    return true;
                }
            }
        }
        Slot slot = target.resolveSlot();
        if (slot.hasItem() && client.options.keyPickItem.matches(event) && player.hasInfiniteMaterials()) {
            click(target, 0, FabricMinecraftInventoryAction.CLONE);
            return true;
        }
        if (slot.hasItem() && client.options.keyDrop.matches(event)) {
            click(target, event.hasControlDown() ? 1 : 0, FabricMinecraftInventoryAction.THROW);
            return true;
        }
        return false;
    }

    /**
     * Recognizes a synchronized Slot payload without rendering or mutating native state.
     *
     * @param command opaque platform payload being selected before or during ordered presentation.
     * @return whether this client-thread bridge owns the payload's native rendering family; unrelated payloads remain unclaimed after close.
     * @throws IllegalStateException when an owned Slot payload reaches a closed bridge, or the call is on another thread.
     */
    @Override
    public boolean accepts(@NotNull PlatformDrawCommand command) {
        requireOwnerThread();
        if ((command instanceof ItemCommand) == false) return false;
        requireUsable();
        return true;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, @NotNull DrawCommand.Platform command) {
        renderItem(graphics, requireMinecraft().font, command.getCommand(), command.getBounds().getLeft(), command.getBounds().getTop());
    }

    /**
     * Renders a validated Slot item at its logical tree-coordinate origin on the client thread.
     *
     * @param graphics borrowed native GUI target.
     * @param font active font for native item decorations.
     * @param command immutable synchronized Slot payload.
     * @param x logical item x coordinate.
     * @param y logical item y coordinate.
     * @throws IllegalArgumentException for a foreign payload, before drawing the item.
     */
    void renderItem(GuiGraphicsExtractor graphics, Font font, PlatformDrawCommand command, int x, int y) {
        requireUsable();
        if ((command instanceof ItemCommand) == false) {
            throw new IllegalArgumentException("Unsupported Fabric platform draw command: " + command.getClass().getName());
        }
        ItemCommand itemCommand = (ItemCommand) command;
        graphics.item(itemCommand.stack, x, y, itemCommand.seed);
        graphics.itemDecorations(font, itemCommand.stack, x, y);
    }

    /**
     * Presents this bridge's current inventory overlays through the shared copied-stack submission order.
     *
     * @param graphics borrowed native GUI target.
     * @param font active Minecraft font.
     * @param mouseX current logical pointer x.
     * @param mouseY current logical pointer y.
     */
    void renderOverlays(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        FabricMinecraftInventoryOverlays.render(
                () -> requireMinecraft().player == null ? null : activeMenu().getCarried(),
                () -> requireMinecraft().player == null || hovered == null ? null : hovered.snapshot,
                (snapshot, carried) -> FabricMinecraftSlotTooltipRenderer.render(graphics, font, snapshot, carried, mouseX, mouseY),
                snapshot -> {
                    int x = Math.subtractExact(mouseX, 8);
                    int y = Math.subtractExact(mouseY, 8);
                    graphics.nextStratum();
                    graphics.item(snapshot, x, y);
                    graphics.itemDecorations(font, snapshot, x, y);
                });
    }

    private boolean withInput(NativeInput current, BooleanSupplier operation) {
        requireUsable();
        return dispatchInput(current, operation);
    }

    @Override
    void finishInput(NativeInput current) {
        if (closed) return;
        if (current.kind == InputKind.PRESS && current.delivered == null) {
            missPointerPress();
        }
        if (current.kind == InputKind.MOVE || current.kind == InputKind.DRAG) {
            hovered = offeredHover;
        }
        if (current.kind == InputKind.RELEASE && current.delivered == null) {
            finishRelease(null, containerClickButton(current.mouse), false, false);
        }
    }

    private InputResult handlePress(Binding binding) {
        NativeInput current = requireInput(InputKind.PRESS);
        MouseButtonEvent mouse = current.mouse;
        boolean creativePick = isPickMouse(mouse) && requirePlayer().hasInfiniteMaterials();
        return pressInput(binding, containerClickButton(mouse), current.doubleClick, mouse.hasShiftDown(), creativePick);
    }

    private InputResult handleDrag(Binding binding) {
        requireInput(InputKind.DRAG);
        return dragInput(binding);
    }

    private InputResult handleRelease(Binding binding) {
        MouseButtonEvent mouse = requireInput(InputKind.RELEASE).mouse;
        return releaseInput(binding, containerClickButton(mouse), mouse.hasShiftDown(), isPickMouse(mouse));
    }

    @Override
    boolean checkHotbarMouse(Binding binding) {
        MouseButtonEvent event = requireInput(InputKind.PRESS).mouse;
        Minecraft client = requireMinecraft();
        if (activeMenu().getCarried().isEmpty() == false) {
            return false;
        }
        if (client.options.keySwapOffhand.matchesMouse(event)) {
            click(binding, 40, FabricMinecraftInventoryAction.SWAP);
            return true;
        }
        for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
            if (client.options.keyHotbarSlots[index].matchesMouse(event)) {
                click(binding, index, FabricMinecraftInventoryAction.SWAP);
                return true;
            }
        }
        return false;
    }

    private boolean isPickMouse(MouseButtonEvent event) {
        return requireMinecraft().options.keyPickItem.matchesMouse(event);
    }

    /**
     * Converts platform mouse identities to the menu protocol's stable primary and secondary button numbers.
     */
    static int containerClickButton(MouseButtonEvent event) {
        return switch (event.button()) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            default -> event.button();
        };
    }

    @Override
    Slot resolveSlot(Binding binding) {
        return binding.resolveSlot();
    }

    @Override
    void clickRaw(int slot, int button, FabricMinecraftInventoryAction action) {
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        checkState(client.gameMode != null, "Minecraft inventory input requires an active game mode.");
        ContainerInput nativeAction = switch (action) {
            case PICKUP -> ContainerInput.PICKUP;
            case QUICK_MOVE -> ContainerInput.QUICK_MOVE;
            case SWAP -> ContainerInput.SWAP;
            case CLONE -> ContainerInput.CLONE;
            case THROW -> ContainerInput.THROW;
            case QUICK_CRAFT -> ContainerInput.QUICK_CRAFT;
            case PICKUP_ALL -> ContainerInput.PICKUP_ALL;
        };
        client.gameMode.handleContainerInput(activeMenu().containerId, slot, button, nativeAction, player);
    }

    private NativeInput requireInput(InputKind expected) {
        NativeInput current = input;
        checkState(current != null && current.kind == expected, "Minecraft Slot input requires the matching native transaction.");
        return current;
    }

    @Override
    LocalPlayer requirePlayer() {
        Minecraft client = requireMinecraft();
        return Objects.requireNonNull(client.player, "Minecraft inventory Slots require an active player.");
    }

    @Override
    AbstractContainerMenu activeMenu() {
        AbstractContainerMenu menu = requirePlayer().containerMenu;
        if (boundContainer != null && boundContainer != menu) {
            throw new RemoteProtocolException(RemoteFailure.ContainerChanged, "UI Slot container generation changed.", null);
        }
        return menu;
    }

    private static Slot resolveContainerSlot(AbstractContainerMenu menu, Inventory playerInventory, int index) {
        Slot resolved = null;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory || slot.getContainerSlot() != index) {
                continue;
            }
            if (resolved != null && resolved.container != slot.container) {
                throw new IllegalArgumentException("Container slot index is ambiguous across the active menu: " + index);
            }
            resolved = slot;
        }
        if (resolved == null) {
            throw new IllegalArgumentException("Container slot index is not exposed by the active menu: " + index);
        }
        return resolved;
    }

    private Minecraft requireMinecraft() {
        requireUsable();
        return minecraft;
    }

    private void requireUsable() {
        requireOwnerThread();
        checkState(closed == false, "Minecraft inventory platform is closed.");
    }

    private void requireOwnerThread() {
        checkState(Thread.currentThread() == ownerThread, "Minecraft inventory platform requires its creator thread.");
    }

    private static void checkState(boolean condition, String message) {
        if (condition == false) {
            throw new IllegalStateException(message);
        }
    }
}

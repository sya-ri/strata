package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.component.PlayerSkinSource;
import dev.s7a.strata.component.SlotBinding;
import dev.s7a.strata.input.InputResult;
import dev.s7a.strata.input.PointerButton;
import dev.s7a.strata.input.PointerEvent;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.render.PlatformDrawCommand;
import dev.s7a.strata.resource.ResourceId;
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding;
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding;
import dev.s7a.strata.runtime.minecraft.MinecraftPlatformCommandRenderer;
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform;
import dev.s7a.strata.runtime.render.DrawCommand;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * Bridges retained Strata Slots to an active legacy Minecraft menu and native item renderer.
 *
 * <p>The bridge is package-private, owner-thread confined, and owned by one common host. It polls copied ItemStack snapshots before frames and delegates every mutation through {@code MultiPlayerGameMode.handleInventoryMouseClick}; it never writes Inventory storage directly.</p>
 */
final class FabricMinecraftInventoryBridge implements MinecraftUiPlatform, MinecraftPlatformCommandRenderer<GuiGraphics> {
    private final Thread ownerThread = Thread.currentThread();
    private final Set<Binding> bindings = new LinkedHashSet<>();
    private final Set<SkinBinding> skinBindings = new LinkedHashSet<>();
    private final Set<Binding> quickCraftSlots = new LinkedHashSet<>();
    private Minecraft minecraft;
    private NativeInput input;
    private Binding hovered;
    private Binding offeredHover;
    private boolean closed;
    private boolean quickCrafting;
    private boolean skipNextRelease;
    private int quickCraftButton;
    private int quickCraftType;
    private boolean pendingDoubleClick;
    private ItemStack lastQuickMoved = ItemStack.EMPTY;
    private int refreshBatchDepth;
    private boolean refreshedInBatch;
    private long refreshPassCount;

    private enum InputKind {
        MOVE,
        PRESS,
        DRAG,
        RELEASE
    }

    private static final class NativeInput {
        private final InputKind kind;
        private final FabricMinecraftNativeMouseInput mouse;
        private final boolean doubleClick;
        private Binding delivered;

        private NativeInput(InputKind kind, FabricMinecraftNativeMouseInput mouse, boolean doubleClick) {
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

    private final class Binding implements MinecraftInventorySlotBinding {
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
            if (event instanceof PointerEvent.Press press) {
                return handlePress(this, press);
            }
            if (event instanceof PointerEvent.Drag) {
                return handleDrag(this);
            }
            if (event instanceof PointerEvent.Release release) {
                return handleRelease(this, release);
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
            quickCraftSlots.remove(this);
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

    /**
     * One terminal result delivered by the asynchronous Minecraft skin lookup.
     */
    interface SkinCompletion {
    }

    /**
     * Successful Minecraft skin lookup retained only until the owner-thread frame boundary.
     */
    private static final class SkinReady implements SkinCompletion {
        private final FabricMinecraftSkinReference skin;

        private SkinReady(FabricMinecraftSkinReference skin) {
            this.skin = skin;
        }
    }

    /**
     * Unusable or failed Minecraft skin lookup result.
     */
    enum SkinFailed implements SkinCompletion {
        INSTANCE
    }

    private interface SkinLifecycleState {
    }

    private enum SkinLookingUp implements SkinLifecycleState {
        INSTANCE
    }

    private static final class SkinCompleted implements SkinLifecycleState {
        private final SkinCompletion completion;

        private SkinCompleted(SkinCompletion completion) {
            this.completion = completion;
        }
    }

    private enum SkinResolved implements SkinLifecycleState {
        INSTANCE
    }

    private enum SkinClosed implements SkinLifecycleState {
        INSTANCE
    }

    /**
     * Atomic lookup lifecycle shared by the owner thread and one asynchronous completion callback.
     *
     * <p>Only {@link #publish(SkinCompletion)} may run away from the owner thread. Closing atomically replaces every active state, drops a queued native completion, and clears the retained immutable snapshot.</p>
     */
    static final class SkinBindingLifecycle {
        private final AtomicReference<SkinLifecycleState> state = new AtomicReference<>(SkinLookingUp.INSTANCE);
        private MinecraftPlayerSkinBinding.Snapshot snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending.INSTANCE;

        /**
         * Publishes the sole asynchronous completion if lookup ownership remains active.
         *
         * @param completion terminal lookup result retained until the owner-thread frame boundary.
         * @return whether this lifecycle accepted the completion.
         */
        boolean publish(SkinCompletion completion) {
            Objects.requireNonNull(completion, "Minecraft player skin completion must not be null.");
            SkinLifecycleState current = state.get();
            while (current == SkinLookingUp.INSTANCE) {
                SkinCompleted completed = new SkinCompleted(completion);
                if (state.compareAndSet(current, completed)) {
                    return true;
                }
                current = state.get();
            }
            return false;
        }

        /**
         * Transfers one accepted completion to the owner thread exactly once.
         *
         * @return queued completion, or null while lookup is pending, already resolved, or closed.
         */
        SkinCompletion drainCompletion() {
            SkinLifecycleState current = state.get();
            while (current instanceof SkinCompleted completed) {
                if (state.compareAndSet(current, SkinResolved.INSTANCE)) {
                    return completed.completion;
                }
                current = state.get();
            }
            return null;
        }

        /**
         * Commits one immutable owner-thread snapshot after a completion has been drained.
         *
         * @param next normalized ready or failed snapshot.
         * @return whether a distinct active snapshot was retained.
         */
        boolean commitSnapshot(MinecraftPlayerSkinBinding.Snapshot next) {
            Objects.requireNonNull(next, "Minecraft player skin snapshot must not be null.");
            if (state.get() != SkinResolved.INSTANCE || snapshot.equals(next)) {
                return false;
            }
            snapshot = next;
            return true;
        }

        /**
         * Returns the currently retained owner-thread snapshot.
         *
         * @return pending, ready, or failed snapshot; close resets this value to pending.
         */
        MinecraftPlayerSkinBinding.Snapshot retainedSnapshot() {
            return snapshot;
        }

        /**
         * Atomically abandons lookup ownership and releases every queued or committed result.
         *
         * @return whether this call performed the active-to-closed transition.
         */
        boolean close() {
            SkinLifecycleState previous = state.getAndSet(SkinClosed.INSTANCE);
            if (previous == SkinClosed.INSTANCE) {
                return false;
            }
            snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending.INSTANCE;
            return true;
        }

        /**
         * Returns whether lookup ownership has been released.
         *
         * @return true only after the atomic closed transition.
         */
        boolean isClosed() {
            return state.get() == SkinClosed.INSTANCE;
        }
    }

    private final class SkinBinding implements MinecraftPlayerSkinBinding {
        private final SkinBindingLifecycle lifecycle = new SkinBindingLifecycle();
        private Function0<Unit> observer;

        private SkinBinding(PlayerSkinSource source) {
            SkinBindingLifecycle completionTarget = lifecycle;
            FabricMinecraftSkinBridge.lookup(requireMinecraft(), source).whenComplete((resolved, failure) -> {
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

    private FabricMinecraftInventoryBridge(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /**
     * Creates one bridge on the current Minecraft client thread.
     *
     * @param minecraft active client instance retained until close.
     * @return a new platform bridge.
     */
    static FabricMinecraftInventoryBridge create(Minecraft minecraft) {
        checkState(minecraft.isSameThread(), "Minecraft inventory Slots require the client thread.");
        return new FabricMinecraftInventoryBridge(minecraft);
    }

    @Override
    public @NotNull MinecraftInventorySlotBinding inventorySlot(@NotNull SlotBinding locator) {
        requireUsable();
        Objects.requireNonNull(locator, "Minecraft Slot binding must not be null.");
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
        refreshPassCount += 1L;
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
        input = null;
        hovered = null;
        offeredHover = null;
        quickCraftSlots.clear();
        lastQuickMoved = ItemStack.EMPTY;
        for (Binding binding : bindings) {
            binding.releaseFromPlatform();
        }
        bindings.clear();
        for (SkinBinding binding : skinBindings) {
            binding.releaseFromPlatform();
        }
        skinBindings.clear();
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
     * @param button native mouse button value.
     * @param modifiers native GLFW modifier bit field.
     * @param doubleClick native double-click marker.
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withMousePress(int button, int modifiers, boolean doubleClick, BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.PRESS, new FabricMinecraftNativeMouseInput(button, modifiers), doubleClick), operation);
    }

    /**
     * Runs one native drag and records any eligible synchronized Slot crossed by the pointer.
     *
     * @param button native mouse button value.
     * @param modifiers native GLFW modifier bit field.
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withMouseDrag(int button, int modifiers, BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.DRAG, new FabricMinecraftNativeMouseInput(button, modifiers), false), operation);
    }

    /**
     * Runs one native release and completes a pending pickup, double-click, or quick-craft transaction.
     *
     * @param button native mouse button value.
     * @param modifiers native GLFW modifier bit field.
     * @param operation host input dispatch.
     * @return the host consumption result.
     */
    boolean withMouseRelease(int button, int modifiers, BooleanSupplier operation) {
        return withInput(new NativeInput(InputKind.RELEASE, new FabricMinecraftNativeMouseInput(button, modifiers), false), operation);
    }

    /**
     * Handles native hotbar, offhand, pick, and drop keys for the topmost synchronized Slot under the last delivered pointer move.
     *
     * @param key native GLFW key value.
     * @param scanCode native platform scan code.
     * @param modifiers native GLFW modifier bit field.
     * @return true when one authoritative container input was sent.
     */
    boolean handleKeyPressed(int key, int scanCode, int modifiers) {
        requireUsable();
        Binding target = hovered;
        if (target == null) {
            return false;
        }
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        if (activeMenu().getCarried().isEmpty()) {
            if (FabricMinecraftKeyBindingBridge.matches(client.options.keySwapOffhand, key, scanCode, modifiers)) {
                click(target, 40, ClickType.SWAP);
                return true;
            }
            for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
                if (FabricMinecraftKeyBindingBridge.matches(client.options.keyHotbarSlots[index], key, scanCode, modifiers)) {
                    click(target, index, ClickType.SWAP);
                    return true;
                }
            }
        }
        Slot slot = target.resolveSlot();
        if (slot.hasItem()
                && FabricMinecraftKeyBindingBridge.matches(client.options.keyPickItem, key, scanCode, modifiers)
                && player.getAbilities().instabuild) {
            click(target, 0, ClickType.CLONE);
            return true;
        }
        if (slot.hasItem() && FabricMinecraftKeyBindingBridge.matches(client.options.keyDrop, key, scanCode, modifiers)) {
            click(target, (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 ? 1 : 0, ClickType.THROW);
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
    public void render(GuiGraphics graphics, @NotNull DrawCommand.Platform command) {
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
    void renderItem(GuiGraphics graphics, Font font, PlatformDrawCommand command, int x, int y) {
        requireUsable();
        if ((command instanceof ItemCommand) == false) {
            throw new IllegalArgumentException("Unsupported Fabric platform draw command: " + command.getClass().getName());
        }
        ItemCommand itemCommand = (ItemCommand) command;
        graphics.renderItem(itemCommand.stack, x, y, itemCommand.seed);
        graphics.renderItemDecorations(font, itemCommand.stack, x, y);
    }

    /**
     * Renders the authoritative carried stack in Minecraft's final item stratum.
     *
     * @param graphics native extraction target.
     * @param font active Minecraft font for item decorations.
     * @param mouseX current logical pointer x.
     * @param mouseY current logical pointer y.
     */
    void renderCarried(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        requireUsable();
        LocalPlayer player = requireMinecraft().player;
        if (player == null) {
            return;
        }
        ItemStack carried = activeMenu().getCarried();
        if (carried.isEmpty()) {
            return;
        }
        ItemStack snapshot = carried.copy();
        int x = Math.subtractExact(mouseX, 8);
        int y = Math.subtractExact(mouseY, 8);
        FabricMinecraftCarriedItemRenderer.render(graphics, font, snapshot, x, y);
    }

    private boolean withInput(NativeInput current, BooleanSupplier operation) {
        requireUsable();
        checkState(input == null, "Minecraft inventory input transactions are non-reentrant.");
        input = current;
        offeredHover = null;
        boolean completed = false;
        try {
            boolean result = operation.getAsBoolean();
            completed = true;
            return result;
        } finally {
            if (completed && closed == false) {
                finishInput(current);
            }
            input = null;
            offeredHover = null;
        }
    }

    private void finishInput(NativeInput current) {
        if (current.kind == InputKind.MOVE || current.kind == InputKind.DRAG) {
            hovered = offeredHover;
        }
        if (current.kind == InputKind.RELEASE && current.delivered == null) {
            finishRelease(null, current.mouse);
        }
    }

    private InputResult handlePress(Binding binding, PointerEvent.Press event) {
        NativeInput current = requireInput(InputKind.PRESS);
        FabricMinecraftNativeMouseInput mouse = current.mouse;
        int rawButton = mouse.button();
        pendingDoubleClick = current.doubleClick && rawButton == 0;
        if (checkHotbarMouse(binding, mouse)) {
            skipNextRelease = true;
            return InputResult.Consumed;
        }
        if (event.getButton() != PointerButton.Primary.INSTANCE && event.getButton() != PointerButton.Secondary.INSTANCE) {
            return InputResult.Ignored;
        }
        ItemStack carried = activeMenu().getCarried();
        if (carried.isEmpty()) {
            ClickType action = mouse.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP;
            if (action == ClickType.QUICK_MOVE) {
                ItemStack item = binding.resolveSlot().getItem();
                lastQuickMoved = item.isEmpty() ? ItemStack.EMPTY : item.copy();
            }
            click(binding, rawButton, action);
            skipNextRelease = true;
            return InputResult.Consumed;
        }
        quickCrafting = true;
        quickCraftButton = rawButton;
        quickCraftSlots.clear();
        if (rawButton == 0) {
            quickCraftType = 0;
        } else if (rawButton == 1) {
            quickCraftType = 1;
        } else if (isPickMouse(mouse) && requirePlayer().getAbilities().instabuild) {
            quickCraftType = 2;
        } else {
            quickCrafting = false;
            return InputResult.Ignored;
        }
        return InputResult.Consumed;
    }

    private InputResult handleDrag(Binding binding) {
        requireInput(InputKind.DRAG);
        if (quickCrafting == false) {
            return InputResult.Ignored;
        }
        Slot slot = binding.resolveSlot();
        ItemStack carried = activeMenu().getCarried();
        if (carried.isEmpty() == false
                && (quickCraftSlots.size() < carried.getCount() || quickCraftType == 2)
                && AbstractContainerMenu.canItemQuickReplace(slot, carried, true)
                && slot.mayPlace(carried)
                && activeMenu().canDragTo(slot)) {
            quickCraftSlots.add(binding);
        }
        return InputResult.Consumed;
    }

    private InputResult handleRelease(Binding binding, PointerEvent.Release event) {
        NativeInput current = requireInput(InputKind.RELEASE);
        if (event.getButton() != PointerButton.Primary.INSTANCE && event.getButton() != PointerButton.Secondary.INSTANCE) {
            return InputResult.Ignored;
        }
        finishRelease(binding, current.mouse);
        return InputResult.Consumed;
    }

    private void finishRelease(Binding binding, FabricMinecraftNativeMouseInput mouse) {
        int rawButton = mouse.button();
        if (pendingDoubleClick && binding != null && rawButton == 0) {
            if (mouse.hasShiftDown() && lastQuickMoved.isEmpty() == false) {
                quickMoveMatching(binding, rawButton);
            } else {
                click(binding, rawButton, ClickType.PICKUP_ALL);
            }
            pendingDoubleClick = false;
            resetQuickCraft();
            return;
        }
        if (quickCrafting && quickCraftButton != rawButton) {
            resetQuickCraft();
            skipNextRelease = true;
            return;
        }
        if (skipNextRelease) {
            skipNextRelease = false;
            resetQuickCraft();
            return;
        }
        if (quickCrafting && quickCraftSlots.isEmpty() == false) {
            sendQuickCraft();
        } else if (binding != null && activeMenu().getCarried().isEmpty() == false) {
            click(binding, rawButton, mouse.hasShiftDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP);
        }
        resetQuickCraft();
    }

    private void sendQuickCraft() {
        clickRaw(-999, AbstractContainerMenu.getQuickcraftMask(0, quickCraftType), ClickType.QUICK_CRAFT);
        for (Binding binding : quickCraftSlots) {
            click(binding, AbstractContainerMenu.getQuickcraftMask(1, quickCraftType), ClickType.QUICK_CRAFT);
        }
        clickRaw(-999, AbstractContainerMenu.getQuickcraftMask(2, quickCraftType), ClickType.QUICK_CRAFT);
    }

    private void quickMoveMatching(Binding binding, int button) {
        Slot reference = binding.resolveSlot();
        LocalPlayer player = requirePlayer();
        for (Slot slot : activeMenu().slots) {
            if (slot.mayPickup(player)
                    && slot.hasItem()
                    && slot.container == reference.container
                    && AbstractContainerMenu.canItemQuickReplace(slot, lastQuickMoved, true)) {
                clickRaw(slot.index, button, ClickType.QUICK_MOVE);
            }
        }
    }

    private boolean checkHotbarMouse(Binding binding, FabricMinecraftNativeMouseInput event) {
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        if (activeMenu().getCarried().isEmpty() == false) {
            return false;
        }
        if (FabricMinecraftKeyBindingBridge.matchesMouse(client.options.keySwapOffhand, event.button(), event.modifiers())) {
            click(binding, 40, ClickType.SWAP);
            return true;
        }
        for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
            if (FabricMinecraftKeyBindingBridge.matchesMouse(client.options.keyHotbarSlots[index], event.button(), event.modifiers())) {
                click(binding, index, ClickType.SWAP);
                return true;
            }
        }
        if (isPickMouse(event) && player.getAbilities().instabuild) {
            click(binding, event.button(), ClickType.CLONE);
            return true;
        }
        return false;
    }

    private boolean isPickMouse(FabricMinecraftNativeMouseInput event) {
        return FabricMinecraftKeyBindingBridge.matchesMouse(
                requireMinecraft().options.keyPickItem,
                event.button(),
                event.modifiers());
    }

    private void click(Binding binding, int button, ClickType action) {
        clickRaw(binding.resolveSlot().index, button, action);
    }

    private void clickRaw(int slot, int button, ClickType action) {
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        checkState(client.gameMode != null, "Minecraft inventory input requires an active game mode.");
        client.gameMode.handleInventoryMouseClick(activeMenu().containerId, slot, button, action, player);
    }

    private void resetQuickCraft() {
        quickCrafting = false;
        quickCraftSlots.clear();
    }

    private NativeInput requireInput(InputKind expected) {
        NativeInput current = input;
        checkState(current != null && current.kind == expected, "Minecraft Slot input requires the matching native transaction.");
        return current;
    }

    private LocalPlayer requirePlayer() {
        Minecraft client = requireMinecraft();
        return Objects.requireNonNull(client.player, "Minecraft inventory Slots require an active player.");
    }

    private AbstractContainerMenu activeMenu() {
        return requirePlayer().containerMenu;
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

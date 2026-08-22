package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import dev.s7a.strata.component.PlayerSkinSource;
import dev.s7a.strata.component.SlotBinding;
import dev.s7a.strata.geometry.IntSize;
import dev.s7a.strata.input.InputResult;
import dev.s7a.strata.input.PointerButton;
import dev.s7a.strata.input.PointerEvent;
import dev.s7a.strata.render.DrawImage;
import dev.s7a.strata.render.DrawImages;
import dev.s7a.strata.render.PlatformDrawCommand;
import dev.s7a.strata.resource.ResourceId;
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding;
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding;
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.NotNull;

/**
 * Bridges retained Strata Slots to the active 26.2 menu and native item renderer.
 *
 * <p>The bridge is package-private, owner-thread confined, and owned by one common host. It polls copied ItemStack snapshots before frames and delegates every mutation through {@code MultiPlayerGameMode.handleContainerInput}; it never writes Inventory storage directly.</p>
 */
final class FabricMinecraftInventoryBridge implements MinecraftUiPlatform {
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

    private enum InputKind {
        MOVE,
        PRESS,
        DRAG,
        RELEASE
    }

    private static final class NativeInput {
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

    private interface SkinCompletion {
    }

    private static final class SkinReady implements SkinCompletion {
        private final PlayerSkin skin;

        private SkinReady(PlayerSkin skin) {
            this.skin = skin;
        }
    }

    private enum SkinFailed implements SkinCompletion {
        INSTANCE
    }

    private final class SkinBinding implements MinecraftPlayerSkinBinding {
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicReference<SkinCompletion> pending = new AtomicReference<>();
        private MinecraftPlayerSkinBinding.Snapshot snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending.INSTANCE;
        private Function0<Unit> observer;

        private SkinBinding(PlayerSkinSource source) {
            lookupSkin(source).whenComplete((resolved, failure) -> {
                if (released.get()) {
                    return;
                }
                if (failure == null && resolved.isPresent()) {
                    pending.set(new SkinReady(resolved.get()));
                } else {
                    pending.set(SkinFailed.INSTANCE);
                }
            });
        }

        @Override
        public @NotNull MinecraftPlayerSkinBinding.Snapshot snapshot() {
            requireUsable();
            return snapshot;
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
            if (released.compareAndSet(false, true)) {
                pending.set(null);
                observer = null;
            }
        }

        private void refresh() {
            if (released.get()) {
                return;
            }
            SkinCompletion completion = pending.getAndSet(null);
            if (completion == null) {
                return;
            }
            MinecraftPlayerSkinBinding.Snapshot next;
            if (completion instanceof SkinReady ready) {
                try {
                    next = new MinecraftPlayerSkinBinding.Snapshot.Ready(snapshotSkin(ready.skin));
                } catch (RuntimeException failure) {
                    next = MinecraftPlayerSkinBinding.Snapshot.Failed.INSTANCE;
                }
            } else {
                next = MinecraftPlayerSkinBinding.Snapshot.Failed.INSTANCE;
            }
            if (snapshot.equals(next)) {
                return;
            }
            snapshot = next;
            Function0<Unit> callback = observer;
            if (callback != null) {
                callback.invoke();
            }
        }

        private void requireUsable() {
            requireOwnerThread();
            checkState(released.get() == false, "Minecraft player skin binding is closed.");
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
        java.util.Objects.requireNonNull(locator, "Minecraft Slot binding must not be null.");
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
        for (Binding binding : bindings) {
            binding.refresh();
        }
        for (SkinBinding binding : skinBindings) {
            binding.refresh();
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
            binding.close();
        }
        skinBindings.clear();
        minecraft = null;
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
                click(target, 40, ContainerInput.SWAP);
                return true;
            }
            for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
                if (client.options.keyHotbarSlots[index].matches(event)) {
                    click(target, index, ContainerInput.SWAP);
                    return true;
                }
            }
        }
        Slot slot = target.resolveSlot();
        if (slot.hasItem() && client.options.keyPickItem.matches(event) && player.hasInfiniteMaterials()) {
            click(target, 0, ContainerInput.CLONE);
            return true;
        }
        if (slot.hasItem() && client.options.keyDrop.matches(event)) {
            click(target, event.hasControlDown() ? 1 : 0, ContainerInput.THROW);
            return true;
        }
        return false;
    }

    /**
     * Validates and renders one retained platform command at its tree-coordinate item origin.
     *
     * @param graphics native extraction target.
     * @param font active Minecraft font for count and durability decorations.
     * @param command opaque payload from a synchronized Slot.
     * @param x item x coordinate.
     * @param y item y coordinate.
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
     * Renders the authoritative carried stack in Minecraft's final item stratum.
     *
     * @param graphics native extraction target.
     * @param font active Minecraft font for item decorations.
     * @param mouseX current logical pointer x.
     * @param mouseY current logical pointer y.
     */
    void renderCarried(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
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
        graphics.nextStratum();
        graphics.item(snapshot, x, y);
        graphics.itemDecorations(font, snapshot, x, y);
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
        MouseButtonEvent mouse = current.mouse;
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
            ContainerInput action = mouse.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
            if (action == ContainerInput.QUICK_MOVE) {
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
        } else if (isPickMouse(mouse) && requirePlayer().hasInfiniteMaterials()) {
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

    private void finishRelease(Binding binding, MouseButtonEvent mouse) {
        int rawButton = mouse.button();
        if (pendingDoubleClick && binding != null && rawButton == 0) {
            if (mouse.hasShiftDown() && lastQuickMoved.isEmpty() == false) {
                quickMoveMatching(binding, rawButton);
            } else {
                click(binding, rawButton, ContainerInput.PICKUP_ALL);
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
            click(binding, rawButton, mouse.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
        }
        resetQuickCraft();
    }

    private void sendQuickCraft() {
        clickRaw(-999, AbstractContainerMenu.getQuickcraftMask(0, quickCraftType), ContainerInput.QUICK_CRAFT);
        for (Binding binding : quickCraftSlots) {
            click(binding, AbstractContainerMenu.getQuickcraftMask(1, quickCraftType), ContainerInput.QUICK_CRAFT);
        }
        clickRaw(-999, AbstractContainerMenu.getQuickcraftMask(2, quickCraftType), ContainerInput.QUICK_CRAFT);
    }

    private void quickMoveMatching(Binding binding, int button) {
        Slot reference = binding.resolveSlot();
        LocalPlayer player = requirePlayer();
        for (Slot slot : activeMenu().slots) {
            if (slot.mayPickup(player)
                    && slot.hasItem()
                    && slot.container == reference.container
                    && AbstractContainerMenu.canItemQuickReplace(slot, lastQuickMoved, true)) {
                clickRaw(slot.index, button, ContainerInput.QUICK_MOVE);
            }
        }
    }

    private boolean checkHotbarMouse(Binding binding, MouseButtonEvent event) {
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        if (activeMenu().getCarried().isEmpty() == false) {
            return false;
        }
        if (client.options.keySwapOffhand.matchesMouse(event)) {
            click(binding, 40, ContainerInput.SWAP);
            return true;
        }
        for (int index = 0; index < client.options.keyHotbarSlots.length; index++) {
            if (client.options.keyHotbarSlots[index].matchesMouse(event)) {
                click(binding, index, ContainerInput.SWAP);
                return true;
            }
        }
        if (isPickMouse(event) && player.hasInfiniteMaterials()) {
            click(binding, event.button(), ContainerInput.CLONE);
            return true;
        }
        return false;
    }

    private boolean isPickMouse(MouseButtonEvent event) {
        return requireMinecraft().options.keyPickItem.matchesMouse(event);
    }

    private void click(Binding binding, int button, ContainerInput action) {
        clickRaw(binding.resolveSlot().index, button, action);
    }

    private void clickRaw(int slot, int button, ContainerInput action) {
        Minecraft client = requireMinecraft();
        LocalPlayer player = requirePlayer();
        checkState(client.gameMode != null, "Minecraft inventory input requires an active game mode.");
        client.gameMode.handleContainerInput(activeMenu().containerId, slot, button, action, player);
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
        return java.util.Objects.requireNonNull(client.player, "Minecraft inventory Slots require an active player.");
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

    private CompletableFuture<Optional<PlayerSkin>> lookupSkin(PlayerSkinSource source) {
        Minecraft client = requireMinecraft();
        CompletableFuture<GameProfile> profile;
        if (source == PlayerSkinSource.CurrentPlayer.INSTANCE) {
            profile = CompletableFuture.completedFuture(client.getGameProfile());
        } else if (source instanceof PlayerSkinSource.Name name) {
            profile = ResolvableProfile.createUnresolved(name.getValue()).resolveProfile(client.services().profileResolver());
        } else if (source instanceof PlayerSkinSource.Uuid uuid) {
            profile = ResolvableProfile.createUnresolved(uuid.getValue()).resolveProfile(client.services().profileResolver());
        } else {
            throw new IllegalArgumentException("Unsupported player skin source: " + source.getClass().getName());
        }
        return profile.thenCompose(client.getSkinManager()::get);
    }

    private DrawImage snapshotSkin(PlayerSkin skin) {
        ClientAsset.Texture body = skin.body();
        if (body instanceof ClientAsset.ResourceTexture resource) {
            var identifier = resource.texturePath();
            return FabricMinecraftAssets.loadMinecraftUiImage(new ResourceId(identifier.getNamespace(), identifier.getPath()));
        }
        if (body instanceof ClientAsset.DownloadedTexture downloaded) {
            var texture = requireMinecraft().getTextureManager().getTexture(downloaded.texturePath());
            if ((texture instanceof DynamicTexture) == false) {
                throw new IllegalStateException("The downloaded player skin is not backed by a dynamic texture.");
            }
            NativeImage image = ((DynamicTexture) texture).getPixels();
            if (image.isClosed()) {
                throw new IllegalStateException("The downloaded player skin has already been released.");
            }
            IntSize size = new IntSize(image.getWidth(), image.getHeight());
            if (size.equals(new IntSize(64, 64)) == false) {
                throw new IllegalArgumentException("Minecraft player skins must normalize to exactly 64 by 64 pixels.");
            }
            return DrawImages.createDrawImage(size, image.getPixels());
        }
        throw new IllegalArgumentException("Unsupported Minecraft player skin texture kind: " + body.getClass().getName());
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

package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import dev.scathiard.feedmepackages.client.PanelLayout;
import dev.scathiard.feedmepackages.client.SupplyCreativeScreen;
import dev.scathiard.feedmepackages.client.SupplyInventoryScreen;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.mixin.ContainerScreenAccess;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.service.AccessGate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/** Recovered from test.42; pixel-atlas and layout corrections only. */
public final class LogisticsPanel {
    private static final Minecraft MC = Minecraft.getInstance();
    private static AbstractContainerScreen<?> screen;
    private static UUID window;
    private static PanelPackets.Snapshot snapshot;
    private static PanelLayout layout;
    private static final Map<String, ItemStack> ICONS;
    private static int tick;
    private static int lastQuery;
    private static int sequence;
    private static int waiting;
    private static int waitingSince;
    private static int firstRow;
    private static int selected;
    private static int draftMinimum;
    private static long serial;
    private static boolean draggingSlider;
    private static boolean draggingMaximum;
    private static int capturedButton;
    private static boolean returnEditing;
    private static String returnBuffer;
    /** Client-side display prediction for the single in-flight panel intent. It only overrides one
     *  cell's rendered number during the pre-confirmation wait; it never creates item stacks, never
     *  touches the cache, backpack or cursor, and is cleared on ack/close/swap/timeout. */
    private static Predict predict;

    /** Local ownership of THIS client's unplaced preview, used to withdraw only our preview on close.
     *  Never derived from snapshot.reserved (which is the sum of every player's Hold for that cell):
     *  it would destroy an independent same-variant creative carry from another player's reservation. */
    private static String previewVariant;
    private static int previewRemaining;
    /** Whether the TAKE_CURSOR was confirmed by the server (vs. still awaiting) so an empty carry is
     *  never mistaken for a replaced preview during the request/confirm gap. */
    private static boolean previewConfirmed;

    private record Predict(UUID window, UUID session, int slot, String variant, int sequence, long baseSerial, int result) {}
    private static int draftMaximum;
    private static Component feedback;
    private static int feedbackUntil;
    private static List<Component> tooltip;
    private static int mouseX;
    private static int mouseY;
    private static Predicate<Screen> recipeOverlay;
    private static boolean retainTransition;
    private static int overlayBottomInset;
    private static final ResourceLocation PANEL;

    private LogisticsPanel() {
    }

    public static void recipeOverlay(Predicate<Screen> predicate) {
        recipeOverlay = Objects.requireNonNull(predicate);
    }

    public static void overlayBottomInset(int pixels) {
        overlayBottomInset = Math.clamp((long)pixels, (int)0, (int)64);
    }

    public static void register() {
        PanelNetwork.receiveOnClient(LogisticsPanel::receive);
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((ScreenEvent.Opening event) -> {
            boolean bl = retainTransition = window != null && (event.getNewScreen() == screen || recipeOverlay.test(event.getNewScreen()));
            if (LogisticsPanel.MC.player == null || event.getNewScreen() == null) {
                return;
            }
            ICuriosItemHandler inventory = CuriosApi.getCuriosInventory((LivingEntity)LogisticsPanel.MC.player).orElse(null);
            if (inventory == null || inventory.findCurios(new String[]{"necklace"}).stream().noneMatch(found -> !found.slotContext().cosmetic() && found.stack().getItem() instanceof PendantItem)) {
                return;
            }
            if (event.getNewScreen().getClass() == InventoryScreen.class) {
                event.setNewScreen((Screen)new SupplyInventoryScreen((Player)LogisticsPanel.MC.player));
            } else if (event.getNewScreen().getClass() == CreativeModeInventoryScreen.class) {
                event.setNewScreen((Screen)new SupplyCreativeScreen(LogisticsPanel.MC.player));
            }
        });
        bus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> LogisticsPanel.tick());
        bus.addListener((ScreenEvent.Render.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen())) {
                LogisticsPanel.updateLayout();
            }
        });
        bus.addListener(LogisticsPanel::foreground);
        bus.addListener(LogisticsPanel::postRender);
        bus.addListener((ScreenEvent.MouseButtonPressed.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen()) && LogisticsPanel.press(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.MouseButtonReleased.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen()) && LogisticsPanel.release(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.MouseDragged.Pre event) -> {
            if (!LogisticsPanel.current(event.getScreen()) || !LogisticsPanel.visible()) {
                return;
            }
            if (draggingSlider) {
                LogisticsPanel.setDraft(event.getMouseX());
            }
            if (draggingMaximum) {
                LogisticsPanel.setDraftMaximum(event.getMouseX());
            }
            if (waiting != 0 || draggingSlider || draggingMaximum || capturedButton >= 0 || layout.bounds().contains(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.MouseScrolled.Pre event) -> {
            if (LogisticsPanel.current(event.getScreen()) && LogisticsPanel.scroll(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (!LogisticsPanel.current(event.getScreen())) {
                return;
            }
            if (returnEditing && LogisticsPanel.editReturnKey(event.getKeyCode())) {
                event.setCanceled(true);
                return;
            }
            if (LogisticsPanel.key(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.CharacterTyped.Pre event) -> {
            if (!LogisticsPanel.current(event.getScreen())) {
                return;
            }
            if (returnEditing) {
                if (returnBuffer.length() < 128 && event.getCodePoint() >= ' ' && event.getCodePoint() <= '\uffff') {
                    returnBuffer = returnBuffer + new String(Character.toChars(event.getCodePoint()));
                }
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.Closing event) -> {
            if (event.getScreen() == screen && !retainTransition) {
                LogisticsPanel.close();
            }
        });
        // Operation boundary: if the player clicks a creative LIST slot while this client still holds an
        // FMP preview, release that preview to the server BEFORE the native pick-up replaces the carry.
        // Otherwise the server's restoreCreativeCursor re-applies the old preview after the clear, wiping
        // the fresh independent stone (the n=3 trace). This fires on the pick-up edge, not on a later tick.
        bus.addListener((ScreenEvent.MouseButtonPressed.Pre event) -> {
            if (screen instanceof CreativeModeInventoryScreen && previewRemaining > 0 && previewVariant != null
                    && onCreativeListSlot(event.getMouseX(), event.getMouseY())) {
                askServerReleasePreview();
                // Same-tick replacement: the pick-up replaces the carry, so the local preview ownership is
                // invalidated here (not only on a later tick) or a close in the same tick would still debit it.
                previewRemaining = 0; previewConfirmed = false; previewVariant = null;
            }
        });
    }

    private static boolean onCreativeListSlot(double x, double y) {
        if (!(screen instanceof CreativeModeInventoryScreen creative)) return false;
        try {
            var field = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER"); field.setAccessible(true);
            var listContainer = (net.minecraft.world.Container) field.get(null);
            for (var slot : creative.getMenu().slots) {
                if (slot.container == listContainer
                        && x >= creative.getGuiLeft() + slot.x && x < creative.getGuiLeft() + slot.x + 16
                        && y >= creative.getGuiTop() + slot.y && y < creative.getGuiTop() + slot.y + 16) return true;
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    private static boolean supported(Screen candidate) {
        return candidate instanceof InventoryScreen || candidate instanceof CreativeModeInventoryScreen || candidate instanceof CraftingScreen;
    }

    private static boolean current(Screen candidate) {
        return candidate == screen && LogisticsPanel.supported(candidate) && window != null;
    }

    private static boolean visible() {
        return snapshot != null && snapshot.status() != AccessGate.Status.NOT_WORN && layout != null;
    }

    private static boolean active() {
        return snapshot != null && snapshot.status() == AccessGate.Status.ACTIVE && snapshot.session() != null;
    }

    private static boolean bookOpen() {
        RecipeUpdateListener listener;
        AbstractContainerScreen<?> abstractContainerScreen = screen;
        return abstractContainerScreen instanceof RecipeUpdateListener && (listener = (RecipeUpdateListener)abstractContainerScreen).getRecipeBookComponent().isVisible();
    }

    private static void tick() {
        boolean overlay;
        ++tick;
        LogisticsPanel.updatePreviewOwnership();
        boolean bl = overlay = recipeOverlay.test(LogisticsPanel.MC.screen) && window != null;
        if (LogisticsPanel.MC.player == null || MC.getConnection() == null || !LogisticsPanel.supported(LogisticsPanel.MC.screen) && !overlay) {
            if (window != null) {
                LogisticsPanel.close();
            }
            return;
        }
        if (!overlay && screen != LogisticsPanel.MC.screen) {
            LogisticsPanel.mount((AbstractContainerScreen)LogisticsPanel.MC.screen);
        }
        if (waiting != 0 && tick - waitingSince > 60) {
            waiting = 0;
            predict = null;   // Timeout is an unknown result: drop the display prediction only, and refresh.
            LogisticsPanel.notice("timeout");
        }
        if (tick - lastQuery >= 10) {
            lastQuery = tick;
            PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Query(window, LogisticsPanel.MC.player.containerMenu.containerId, true), (CustomPacketPayload[])new CustomPacketPayload[0]);
        }
    }

    private static void updatePreviewOwnership() {
        // Only a CONFIRMED preview owns the carry. While pending, an empty carry is the normal
        // request/confirm gap and must not invalidate ownership. Once confirmed, an emptied carry
        // (e.g. a creative-list pickup that clears the cursor) or a different-variant carry replaces
        // the preview, so this client's ownership is dropped and a later same/similar carry is fresh.
        if (previewVariant == null || LogisticsPanel.MC.player == null || !previewConfirmed) return;
        var carried = LogisticsPanel.MC.player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            askServerReleasePreview();
            previewRemaining = 0; previewVariant = null; previewConfirmed = false; return;
        }
        try {
            var variant = ItemVariantKey.decode(previewVariant, (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess());
            if (!ItemStack.isSameItemSameComponents(carried, variant.stack((HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess(), 1))) {
                askServerReleasePreview();
                previewRemaining = 0; previewVariant = null; previewConfirmed = false;
            }
        } catch (IllegalArgumentException invalid) { previewRemaining = 0; previewVariant = null; previewConfirmed = false; }
    }

    /** Our preview was replaced/emptied on the creative list, so release our own panel-session hold on
     *  the server; otherwise creativeAfter would debit a later independent same-variant placement. */
    private static void askServerReleasePreview() {
        if (snapshot == null || window == null || LogisticsPanel.MC.player == null) return;
        try {
            var intent = new CacheActions.Intent(snapshot.session(), snapshot.revision(), CacheActions.Action.RELEASE_PREVIEW, -1, -1, -1, "");
            // Use an incrementing window sequence so PanelNetwork.command accepts it (0 <= lastSequence would
            // be rejected as STALE). The confirm is not awaited (we do not touch waiting/predict), so it can
            // never cover another in-flight intent's confirmation.
            int seq = ++sequence;
            PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Command(window, seq, intent, false, "", 0), (CustomPacketPayload[])new CustomPacketPayload[0]);
        } catch (IllegalArgumentException invalid) { /* stale template; nothing to release */ }
    }

    public static void mount(AbstractContainerScreen<?> value) {
        if (screen == value && window != null) {
            return;
        }
        LogisticsPanel.close();
        screen = value;
        window = UUID.randomUUID();
        lastQuery = tick - 10;
    }

    public static void unmount(AbstractContainerScreen<?> value) {
        if (screen == value && !retainTransition) {
            LogisticsPanel.close();
        }
    }

    private static void close() {
        dev.scathiard.feedmepackages.FeedMePackages.LOGGER.info("FMP_CLOSE_PREVIEW confirmed={} variant={} remaining={} carry={}",
                previewConfirmed, previewVariant, previewRemaining, LogisticsPanel.MC.player == null ? null : LogisticsPanel.MC.player.containerMenu.getCarried());
        // 只撤销本次 本地 FMP 预览（客户端维护的 previewRemaining），绝不按 snapshot.reserved(全局合计)撤。
        // 创造光标由客户端持有：TAKE_CURSOR 只发全量包、服务端 carried 为空；本地预览量由预览关联追踪，
        // 并在光标被创造列表替换（变体变化/清空）时失效（updatePreviewOwnership），保留真实/独立/新光标。
        if (screen instanceof CreativeModeInventoryScreen && LogisticsPanel.MC.player != null) {
            var carried = LogisticsPanel.MC.player.containerMenu.getCarried();
            if (previewConfirmed && previewRemaining > 0 && previewVariant != null && !carried.isEmpty()) {
                try {
                    var variant = ItemVariantKey.decode(previewVariant, (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess());
                    if (ItemStack.isSameItemSameComponents(carried, variant.stack((HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess(), 1))) {
                        ItemStack next = carried.copy();
                        next.setCount(Math.max(0, next.getCount() - previewRemaining));
                        LogisticsPanel.MC.player.containerMenu.setCarried(next.isEmpty() ? ItemStack.EMPTY : next);
                    }
                }
                catch (IllegalArgumentException invalid) { /* not our variant; leave the carry untouched */ }
            }
        }
        previewRemaining = 0; previewVariant = null; previewConfirmed = false;
        if (window != null && MC.getConnection() != null && LogisticsPanel.MC.player != null) {
            PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Query(window, LogisticsPanel.MC.player.containerMenu.containerId, false), (CustomPacketPayload[])new CustomPacketPayload[0]);
        }
        screen = null;
        window = null;
        snapshot = null;
        layout = null;
        waiting = 0;
        predict = null;
        serial = -1L;
        selected = -1;
        firstRow = 0;
        draggingSlider = false;
        draggingMaximum = false;
        capturedButton = -1;
        returnEditing = false;
        returnBuffer = "";
        draftMaximum = -1;
        tooltip = List.of();
        ICONS.clear();
        feedback = null;
    }

    private static void receive(PanelPackets.Snapshot incoming) {
        // A matching acknowledgement must clear the display prediction even if the carried snapshot
        // is stale (older serial) — otherwise a late ack leaves a ghosted number. Clearing the
        // prediction is display-only and never cancels or re-sends a server transaction.
        if (incoming != null && incoming.acknowledged() != 0 && incoming.acknowledged() == waiting
                && predict != null && predict.sequence() == incoming.acknowledged()
                && (predict.window().equals(incoming.window()) || predict.window() == null)) {
            predict = null;
            waiting = 0;
            if (incoming.result() != CacheActions.Result.OK) {
                LogisticsPanel.notice("result." + incoming.result().name().toLowerCase(Locale.ROOT));
                previewVariant = null; previewRemaining = 0; previewConfirmed = false;
            } else if (previewVariant != null) {
                previewConfirmed = true; // server confirmed the take; the carry is now our active preview
            }
        }
        if (window == null || LogisticsPanel.MC.screen != screen && !recipeOverlay.test(LogisticsPanel.MC.screen) || !window.equals(incoming.window()) || incoming.serial() <= serial) {
            return;
        }
        boolean wasVisible = snapshot != null && snapshot.status() != AccessGate.Status.NOT_WORN;
        serial = incoming.serial();
        snapshot = incoming;
        if (wasVisible && incoming.status() == AccessGate.Status.NOT_WORN) {
            screen.init(MC, LogisticsPanel.screen.width, LogisticsPanel.screen.height);
        }
        if (incoming.acknowledged() != 0 && incoming.acknowledged() == waiting) {
            waiting = 0;
            if (incoming.result() != CacheActions.Result.OK) {
                LogisticsPanel.notice("result." + incoming.result().name().toLowerCase(Locale.ROOT));
            }
        }
        if (!LogisticsPanel.active()) {
            selected = -1;
            draggingSlider = false;
            draggingMaximum = false;
        }
        if (selected >= incoming.cells().size() || selected >= 0 && incoming.cells().get(selected).template().isEmpty()) {
            selected = -1;
        }
        HashSet<String> present = new HashSet<String>();
        for (PanelPackets.CellView cell : incoming.cells()) {
            if (cell.template().isEmpty()) continue;
            present.add(cell.template());
            if (ICONS.containsKey(cell.template())) continue;
            try {
                ICONS.put(cell.template(), ItemVariantKey.decode(cell.template(), (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess()).stack((HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess(), 1));
            }
            catch (IllegalArgumentException invalid) {
                LogisticsPanel.notice("invalid_display");
            }
        }
        ICONS.keySet().retainAll(present);
        LogisticsPanel.updateLayout();
    }

    private static void updateLayout() {
        if (screen == null || snapshot == null || snapshot.status() == AccessGate.Status.NOT_WORN) {
            layout = null;
            return;
        }
        int count = LogisticsPanel.active() ? snapshot.cells().size() : 0;
        int availableColumns = Math.max(2, (LogisticsPanel.screen.width - screen.getXSize() - 12 - 2 * PanelLayout.SIDE) / PanelLayout.ROW);
        int width = Math.min(PanelLayout.preferredWidth(count), availableColumns * PanelLayout.ROW + 2 * PanelLayout.SIDE);
        if (!LogisticsPanel.bookOpen() && screen.getGuiLeft() < width + 8 && LogisticsPanel.screen.width >= screen.getXSize() + width + 12) {
            int old = screen.getGuiLeft();
            int next = width + 8;
            int delta = next - old;
            ((ContainerScreenAccess)screen).fmp$setLeft(next);
            for (GuiEventListener child : screen.children()) {
                AbstractWidget widget;
                if (!(child instanceof AbstractWidget) || (widget = (AbstractWidget)child).getX() < old || widget.getX() >= old + screen.getXSize()) continue;
                widget.setX(widget.getX() + delta);
            }
        }
        layout = PanelLayout.compute(LogisticsPanel.screen.height, screen.getGuiLeft(), screen.getGuiTop(), count, firstRow, LogisticsPanel.active() ? selected : -1, LogisticsPanel.bookOpen());
        int availableHeight = LogisticsPanel.screen.height - overlayBottomInset;
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget)) continue;
            AbstractWidget widget = (AbstractWidget)child;
            if (!widget.visible || widget.getY() < LogisticsPanel.screen.height / 2) continue;
            PanelLayout.Rect b = layout.bounds();
            if (widget.getX() >= b.x() + b.width() || widget.getX() + widget.getWidth() <= b.x()) continue;
            availableHeight = Math.min(availableHeight, widget.getY() - 2);
        }
        if (availableHeight != LogisticsPanel.screen.height) {
            layout = PanelLayout.compute(availableHeight, screen.getGuiLeft(), screen.getGuiTop(), count, firstRow, LogisticsPanel.active() ? selected : -1, LogisticsPanel.bookOpen());
        }
        firstRow = layout.firstRow();
    }

    private static void foreground(ContainerScreenEvent.Render.Foreground event) {
        if (!LogisticsPanel.current((Screen)event.getContainerScreen()) || !LogisticsPanel.visible() || layout.compact()) {
            return;
        }
        mouseX = event.getMouseX();
        mouseY = event.getMouseY();
        tooltip = List.of();
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate((float)(-screen.getGuiLeft()), (float)(-screen.getGuiTop()), 0.0f);
        LogisticsPanel.render(g);
        g.pose().popPose();
    }

    private static void postRender(ScreenEvent.Render.Post event) {
        if (!LogisticsPanel.current(event.getScreen()) || !LogisticsPanel.visible()) {
            return;
        }
        mouseX = event.getMouseX();
        mouseY = event.getMouseY();
        if (layout.compact()) {
            tooltip = List.of();
            LogisticsPanel.button(event.getGuiGraphics(), layout.bounds().x(), layout.bounds().y(), "+", "expand");
        }
        if (!tooltip.isEmpty() && LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
            event.getGuiGraphics().renderComponentTooltip(LogisticsPanel.MC.font, tooltip, mouseX, mouseY);
        }
    }

    private static void render(GuiGraphics g) {
        PanelLayout.Rect b = layout.bounds();
        int x = b.x();
        int y = b.y();
        LogisticsPanel.frame(g, b);
        PanelLayout.Rect copy = layout.address();
        String address = snapshot.address();
        int textWidth = copy.width() - 2;
        String displayed = LogisticsPanel.MC.font.width(address) <= textWidth ? address : LogisticsPanel.MC.font.plainSubstrByWidth(address, Math.max(0, textWidth - LogisticsPanel.MC.font.width("\u2026"))) + "\u2026";
        LogisticsPanel.text(g, displayed, copy.x() + 1, copy.y() + 3, copy.contains(mouseX, mouseY) ? -9419222 : 0xFF000000);
        if (copy.contains(mouseX, mouseY)) {
            tooltip = List.of(LogisticsPanel.tr("address", new Object[0]), Component.literal((String)address));
        }
        if (!LogisticsPanel.active() || !snapshot.bound()) {
            LogisticsPanel.text(g, "!", x + 10, y + 20, -9352640);
            if (new PanelLayout.Rect(x + 10, y + 20, 6, 9).contains(mouseX, mouseY)) {
                tooltip = List.of(LogisticsPanel.tr((String)(LogisticsPanel.active() ? "unbound" : "status." + snapshot.status().name().toLowerCase(Locale.ROOT)), new Object[0]));
            }
        }
        if (LogisticsPanel.active()) {
            for (PanelLayout.CellBox cell : layout.cells()) {
                LogisticsPanel.renderCell(g, cell);
            }
            if (layout.returnBar() != null) {
                LogisticsPanel.renderReturnBar(g);
            }
            if (layout.slider() != null) {
                LogisticsPanel.renderSlider(g);
            }
        }
        if (waiting != 0) {
            LogisticsPanel.overlay(g, x + 10, y + 31, 4, 2, -1654408);
        }
        if (feedback != null && tick < feedbackUntil) {
            LogisticsPanel.text(g, "!", x + 10, y + 20, -20117);
            if (new PanelLayout.Rect(x + 10, y + 20, 6, 9).contains(mouseX, mouseY)) {
                tooltip = List.of(feedback);
            }
        }
        if (layout.totalRows() > layout.visibleRows()) {
            PanelLayout.Rect rail = layout.scrollbar();
            g.fill(rail.x(), rail.y(), rail.x() + rail.width(), rail.y() + rail.height(), -13619412);
            int thumb = Math.max(7, rail.height() * layout.visibleRows() / layout.totalRows());
            int offset = (rail.height() - thumb) * firstRow / Math.max(1, layout.totalRows() - layout.visibleRows());
            g.fill(rail.x(), rail.y() + offset, rail.x() + rail.width(), rail.y() + offset + thumb, -4152474);
        }
    }

    private static void renderCell(GuiGraphics g, PanelLayout.CellBox box) {
        PanelLayout.Rect r = box.bounds();
        PanelPackets.CellView cell = snapshot.cells().get(box.slot());
        int x = r.x();
        int y = r.y();
        renderSlot(g, x, y);
        ItemStack icon = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
        if (!icon.isEmpty()) {
            // Display "in-cell" quantity = cache amount minus the live cursor preview (P). During the
            // pre-confirmation wait a client-side prediction overrides this one cell so the number
            // changes immediately; a matching ack clears the prediction and the authoritative value
            // (already S−P) is shown. The cache ledger (S) and restock/return decisions keep the real
            // amount; only display uses this.
            int display = Math.max(0, cell.amount() - cell.reserved());
            if (predict != null && predict.slot() == box.slot() && predict.window().equals(window)
                    && (predict.variant().equals(cell.template()))) display = Math.max(0, predict.result());
            if (display == 0) {
                g.setColor(1.0f, 1.0f, 1.0f, 0.45f);
            }
            g.renderItem(icon, x + 1, y + 1);
            g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            String amount = Integer.toString(display);
            float scale = Math.min(1.0f, 16.0f / (float)LogisticsPanel.MC.font.width(amount));
            g.pose().pushPose();
            PoseStack poseStack = g.pose();
            float f = (float)(x + 17) - (float)LogisticsPanel.MC.font.width(amount) * scale;
            float f2 = y + 17;
            Objects.requireNonNull(LogisticsPanel.MC.font);
            poseStack.translate(f, f2 - 9.0f * scale, 190.0f);
            g.pose().scale(scale, scale, 1.0f);
            g.drawString(LogisticsPanel.MC.font, amount, 0, 0, display == 0 ? -6909823 : -661306, true);
            g.pose().popPose();
        }
        boolean hover = r.contains(mouseX, mouseY);
        if (cell.residual()) {
            LogisticsPanel.overlay(g, x + 1, y + 13, 3, 3, -8206747);
            if (hover && new PanelLayout.Rect(x, y + 11, 6, 7).contains(mouseX, mouseY)) {
                tooltip = List.of(LogisticsPanel.tr("residual", new Object[0]));
            }
        }
        if (!cell.template().isEmpty() && cell.minimum() >= 0 && cell.amount() < cell.minimum() * cell.stackSize()) {
            int color = cell.pending() > 0 ? -8206747 : -7105653;
            LogisticsPanel.overlay(g, x + 3, y + 1, 1, 3, color);
            LogisticsPanel.overlay(g, x + 1, y + 3, 5, 1, color);
            LogisticsPanel.overlay(g, x + 2, y + 4, 3, 1, color);
        }
        if (!cell.template().isEmpty() && cell.maximum() >= 0 && cell.amount() > cell.maximum() * cell.stackSize()) {
            int color = -2058918;
            LogisticsPanel.overlay(g, x + 1, y + 1, 5, 1, color);
            LogisticsPanel.overlay(g, x + 2, y + 2, 3, 1, color);
            LogisticsPanel.overlay(g, x + 3, y + 3, 1, 3, color);
        }
        if (hover && !cell.template().isEmpty()) {
            LogisticsPanel.overlay(g, box.dot().x(), box.dot().y() + 1, 4, 4, -2047362);
        }
        if (hover) {
            tooltip = new ArrayList<Component>();
            if (!icon.isEmpty()) {
                tooltip.addAll(Screen.getTooltipFromItem((Minecraft)MC, (ItemStack)icon));
                int display = Math.max(0, cell.amount() - cell.reserved());
                if (predict != null && predict.slot() == box.slot() && predict.window().equals(window))
                    display = Math.max(0, predict.result());
                tooltip.add(LogisticsPanel.tr("stock", display, snapshot.groupCapacity() * cell.stackSize()));
                if (cell.stackSize() > 1) {
                    tooltip.add(LogisticsPanel.tr("stock_groups", cell.amount() / cell.stackSize(), snapshot.groupCapacity()));
                }
                if (display == 0 && cell.reserved() > 0) tooltip.add(LogisticsPanel.tr("held_preview", new Object[0]));
            }
            if (cell.minimum() >= 0 && cell.amount() < cell.minimum() * cell.stackSize() && mouseX < x + 7 && mouseY < y + 7) {
                tooltip = List.of(LogisticsPanel.tr(cell.pending() > 0 ? "requested" : "shortage", new Object[0]));
            }
            if (box.dot().contains(mouseX, mouseY) && !cell.template().isEmpty()) {
                tooltip = List.of(LogisticsPanel.tr("configure", new Object[0]));
            }
        }
    }

    private static void renderSlot(GuiGraphics g, int x, int y) {
        // This user-authored slot has its own stable source; panel.png may be reorganized independently.
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(ResourceLocation.fromNamespaceAndPath("create_feed_me_packages", "textures/gui/slot_source.png"),
                x, y, 101.0f, 65.0f, 18, 18, 256, 256);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    private static int thumbPx(int sliderX, int width, int value, int groupCap) {
        // Zero sits on the track's left edge (x+3), never pushed +1 past it. Non-zero uses the full
        // mapped span. Both rendering and the hit test read this same function, so they stay aligned.
        return sliderX + PanelLayout.TRACK_INSET + (value < 0 ? 0 : Math.max(0, Math.min(groupCap, value) * (width - 2 * PanelLayout.TRACK_INSET) / Math.max(1, groupCap)));
    }

    private static void panelBlit(GuiGraphics g, int sx, int sy, int sw, int sh, int u, int v, int w, int h) {
        // All pieces are pixel-sized. A partial repeat is cropped, never scaled.
        if (sw > 0 && sh > 0) {
            // The panel texture carries alpha (e.g. the endpoint corners). Enable standard alpha
            // blending for this sprite so a low-alpha pixel blends instead of being written flat,
            // then restore the prior state so the blend never leaks to later draws.
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            g.blit(PANEL, sx, sy, (float) u, (float) v, sw, sh, 256, 256);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
    }

    private static void renderSlider(GuiGraphics g) {
        int maxN;
        int minN;
        PanelLayout.Rect r = layout.slider();
        PanelPackets.CellView cell = snapshot.cells().get(selected);
        int groupCap = snapshot.groupCapacity();
        int x = r.x();
        int y = r.y();
        int w = r.width();
        int n = minN = draggingSlider ? draftMinimum : cell.minimum();
        if (minN < 0) {
            minN = 0;
        }
        int n2 = maxN = draggingMaximum ? draftMaximum : cell.maximum();
        if (maxN < 0) {
            maxN = groupCap;
        }
        int minAt = LogisticsPanel.thumbPx(x, w, minN, groupCap);
        int maxAt = LogisticsPanel.thumbPx(x, w, maxN, groupCap);
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, 250.0f);
        int tx = x + PanelLayout.TRACK_INSET;
        int ty = y + PanelLayout.TRACK_Y;
        int trackW = w - 2 * PanelLayout.TRACK_INSET;
        LogisticsPanel.panelBlit(g, tx, ty, 4, 5, 0, 1, 4, 5);
        int midX = tx + 4;
        int midEnd = tx + trackW - 4;
        while (midX + 3 <= midEnd) {
            LogisticsPanel.panelBlit(g, midX, ty, 3, 5, 17, 1, 3, 5);
            midX += 3;
        }
        if (midX < midEnd) {
            LogisticsPanel.panelBlit(g, midX, ty, midEnd - midX, 5, 17, 1, 3, 5);
        }
        LogisticsPanel.panelBlit(g, midEnd, ty, 4, 5, 36, 1, 4, 5);
        // Left endpoint = small triangle below the track; right endpoint = triangle above the track,
        // so both stay draggable even when they are at the same position.
        LogisticsPanel.panelBlit(g, minAt - 2, y + PanelLayout.MIN_THUMB_Y, 5, 5, 0, 15, 5, 5);
        LogisticsPanel.panelBlit(g, maxAt - 3, y + PanelLayout.MAX_THUMB_Y, 5, 5, 7, 11, 5, 5);
        String minLabel = String.valueOf(minN * cell.stackSize());
        float s1 = Math.min(8.0f / 9.0f, (w - 8) / 2.0f / (float)LogisticsPanel.MC.font.width(minLabel));
        g.pose().pushPose();
        g.pose().translate((float)(x + 3), (float)(y + PanelLayout.LABEL_Y), 190.0f);
        g.pose().scale(s1, s1, 1.0f);
        g.drawString(LogisticsPanel.MC.font, minLabel, 0, 0, -1713994, false);
        g.pose().popPose();
        String maxLabel = String.valueOf(maxN * cell.stackSize());
        float s2 = Math.min(8.0f / 9.0f, (w - 8) / 2.0f / (float)LogisticsPanel.MC.font.width(maxLabel));
        g.pose().pushPose();
        g.pose().translate((float)(x + w - 3) - (float)LogisticsPanel.MC.font.width(maxLabel) * s2, (float)(y + PanelLayout.LABEL_Y), 190.0f);
        g.pose().scale(s2, s2, 1.0f);
        g.drawString(LogisticsPanel.MC.font, maxLabel, 0, 0, -2058918, false);
        g.pose().popPose();
        if (r.contains(mouseX, mouseY) && mouseY < y + PanelLayout.TRACK_Y + 3) {
            tooltip = List.of(LogisticsPanel.tr(mouseX <= minAt ? "minimum_help" : "maximum_help", new Object[0]));
        }
        g.pose().popPose();
    }

    private static void renderReturnBar(GuiGraphics g) {
        boolean placeholder;
        PanelLayout.Rect r = layout.returnBar();
        int x = r.x();
        int y = r.y();
        int w = r.width();
        int h = r.height();
        // Background and hook were drawn once by frame(); only live text goes here.
        String value = returnEditing ? returnBuffer : (snapshot.returnAddress() == null ? "" : snapshot.returnAddress());
        int textWidth = Math.max(0, w - 12);
        boolean bl = placeholder = value.isEmpty() && !returnEditing;
        String shown = placeholder ? LogisticsPanel.tr("return_label", new Object[0]).getString() : (LogisticsPanel.MC.font.width(value) <= textWidth ? value : LogisticsPanel.MC.font.plainSubstrByWidth(value, Math.max(0, textWidth - LogisticsPanel.MC.font.width("\u2026"))) + "\u2026");
        int n = y + h / 2;
        Objects.requireNonNull(LogisticsPanel.MC.font);
        LogisticsPanel.text(g, shown, x + 9, n - 9 / 2, placeholder ? -7700888 : (returnEditing ? -11912408 : 0xFF000000));
        if (returnEditing) {
            int caretX = Math.min(x + w - 3, x + 9 + LogisticsPanel.MC.font.width(shown));
            g.fill(caretX, y + 4, caretX + 1, y + h - 4, -11912408);
        }
        if (r.contains(mouseX, mouseY)) {
            tooltip = List.of(LogisticsPanel.tr(returnEditing ? "return_editing" : "return_hint", new Object[0]));
        }
    }

    private static void frame(GuiGraphics g, PanelLayout.Rect b) {
        int x = b.x(), y = b.y(), w = b.width(), h = b.height();
        int bottom = y + h - 49;
        // Top caps include the ribbon and the first 18 pixels of wooden backing.
        panelBlit(g, x, y, 22, 36, 18, 47, 22, 36);
        panelBlit(g, x + w - 22, y, 22, 36, 53, 47, 22, 36);
        for (int tx = x + 22; tx < x + w - 22; tx++)
            panelBlit(g, tx, y, 1, 36, 44, 47, 1, 36);
        // The repeat strip is FOUR pixels tall and ONE pixel wide in its centre.
        // u=45 and v=89 are transparent atlas gutters, not repeatable wood.
        for (int ty = y + 36; ty < bottom; ty += 4) {
            int sh = Math.min(4, bottom - ty);
            panelBlit(g, x + 8, ty, 14, sh, 26, 85, 14, sh);
            panelBlit(g, x + w - 22, ty, 14, sh, 53, 85, 14, sh);
            for (int tx = x + 22; tx < x + w - 22; tx++)
                panelBlit(g, tx, ty, 1, sh, 44, 85, 1, sh);
        }
        // This single footer already contains the hook AND the blank address tag.
        panelBlit(g, x, bottom, 22, 49, 18, 91, 22, 49);
        panelBlit(g, x + w - 22, bottom, 22, 49, 53, 91, 22, 49);
        for (int tx = x + 22; tx < x + w - 22; tx++)
            panelBlit(g, tx, bottom, 1, 49, 44, 91, 1, 49);
    }

    private static void button(GuiGraphics g, int x, int y, String label, String help) {
        boolean hover = new PanelLayout.Rect(x, y, 18, 18).contains(mouseX, mouseY);
        (hover ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON).render(g, x, y);
        LogisticsPanel.text(g, label, x + (18 - LogisticsPanel.MC.font.width(label)) / 2, y + 5, -990269);
        if (hover) {
            tooltip = help.equals("address") ? List.of(LogisticsPanel.tr("address", new Object[0]), Component.literal((String)snapshot.address())) : List.of(LogisticsPanel.tr(help, new Object[0]));
        }
    }

    private static void text(GuiGraphics g, String value, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, 190.0f);
        g.drawString(LogisticsPanel.MC.font, value, x, y, color, false);
        g.pose().popPose();
    }

    private static void overlay(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, 190.0f);
        g.fill(x, y, x + w, y + h, color);
        g.pose().popPose();
    }

    private static boolean press(double x, double y, int button) {
        if (!LogisticsPanel.visible()) {
            return false;
        }
        if (waiting != 0) {
            capturedButton = button;
            return true;
        }
        if (!layout.bounds().contains(x, y)) {
            return false;
        }
        if (!(!returnEditing || LogisticsPanel.active() && layout.returnAddressContains(x, y))) {
            returnEditing = false;
        }
        capturedButton = button;
        if (button != 0 && button != 1) {
            return true;
        }
        if (LogisticsPanel.active() && layout.returnAddressContains(x, y)) {
            returnEditing = true;
            returnBuffer = snapshot.returnAddress() == null ? "" : snapshot.returnAddress();
            return true;
        }
        if (layout.compact()) {
            if (LogisticsPanel.bookOpen()) {
                ((RecipeUpdateListener)screen).getRecipeBookComponent().toggleVisibility();
                screen.init(MC, LogisticsPanel.screen.width, LogisticsPanel.screen.height);
            }
            LogisticsPanel.updateLayout();
            return true;
        }
        int px = layout.bounds().x();
        int py = layout.bounds().y();
        if (layout.address().contains(x, y)) {
            LogisticsPanel.MC.keyboardHandler.setClipboard(snapshot.address());
            LogisticsPanel.notice("copied");
            return true;
        }
        PanelLayout.Rect rail = layout.scrollbar();
        if (layout.totalRows() > layout.visibleRows() && rail.contains(x, y)) {
            firstRow = (int) Math.round((y - rail.y()) * (layout.totalRows() - layout.visibleRows())
                    / Math.max(1, rail.height() - 1));
            selected = -1;
            LogisticsPanel.updateLayout();
            return true;
        }
        if (!LogisticsPanel.active()) {
            return true;
        }
        PanelLayout.Rect slider = layout.slider();
        if (slider != null && slider.contains(x, y)) {
            int groupCap = snapshot.groupCapacity();
            PanelPackets.CellView cell = snapshot.cells().get(selected);
            int minAt = LogisticsPanel.thumbPx(slider.x(), slider.width(), cell.minimum() < 0 ? 0 : cell.minimum(), groupCap);
            int maxAt = LogisticsPanel.thumbPx(slider.x(), slider.width(), cell.maximum() < 0 ? groupCap : cell.maximum(), groupCap);
            // Hitboxes hug the triangles: upper band = right endpoint, lower band = left endpoint.
            if (y >= slider.y() + PanelLayout.MIN_THUMB_Y && y < slider.y() + PanelLayout.MIN_THUMB_Y + 5 && Math.abs(x - minAt) <= 3) {
                draggingSlider = true;
                LogisticsPanel.setDraft(x);
            } else if (y >= slider.y() + PanelLayout.MAX_THUMB_Y && y < slider.y() + PanelLayout.MAX_THUMB_Y + 5 && Math.abs(x - maxAt) <= 3) {
                draggingMaximum = true;
                LogisticsPanel.setDraftMaximum(x);
            } else {
                return true;
            }
            return true;
        }
        for (PanelLayout.CellBox box : layout.cells()) {
            if (!box.bounds().contains(x, y)) continue;
            PanelPackets.CellView cell = snapshot.cells().get(box.slot());
            if (box.dot().contains(x, y) && !cell.template().isEmpty() && LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                selected = selected == box.slot() ? -1 : box.slot();
                LogisticsPanel.updateLayout();
                return true;
            }
            if (button == 1 && Screen.hasControlDown()) {
                LogisticsPanel.send(CacheActions.Action.CLEAR_FILTER, box.slot(), 0, -1, "");
            } else if (!LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty()) {
                LogisticsPanel.send(CacheActions.Action.DEPOSIT, box.slot(), button == 1 ? 1 : 0, -1, "");
            } else {
                ItemStack item = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
                LogisticsPanel.send(Screen.hasShiftDown() ? CacheActions.Action.TAKE_INVENTORY : CacheActions.Action.TAKE_CURSOR, box.slot(), button == 1 ? 1 : item.getMaxStackSize(), -1, "");
            }
            return true;
        }
        return true;
    }

    private static boolean release(double x, double y, int button) {
        if (!LogisticsPanel.visible()) {
            capturedButton = -1;
            return false;
        }
        if (waiting != 0) {
            if (capturedButton == button) {
                capturedButton = -1;
            }
            return true;
        }
        if (draggingSlider) {
            LogisticsPanel.setDraft(x);
            draggingSlider = false;
            LogisticsPanel.sendMinimum(draftMinimum);
            capturedButton = -1;
            return true;
        }
        if (draggingMaximum) {
            LogisticsPanel.setDraftMaximum(x);
            draggingMaximum = false;
            LogisticsPanel.sendMaximum(draftMaximum);
            capturedButton = -1;
            return true;
        }
        if (capturedButton == button) {
            capturedButton = -1;
            return true;
        }
        if (layout.bounds().contains(x, y)) {
            if (!(!LogisticsPanel.active() || button != 0 && button != 1 || LogisticsPanel.MC.player.containerMenu.getCarried().isEmpty() || layout.slider() != null && layout.slider().contains(x, y))) {
                for (PanelLayout.CellBox box : layout.cells()) {
                    if (!box.bounds().contains(x, y)) continue;
                    LogisticsPanel.send(CacheActions.Action.DEPOSIT, box.slot(), button == 1 ? 1 : 0, -1, "");
                }
            }
            return true;
        }
        return false;
    }

    private static boolean scroll(double x, double y, double amount) {
        if (!LogisticsPanel.visible() || !layout.bounds().contains(x, y)) {
            return false;
        }
        if (waiting != 0 || layout.compact()) {
            return true;
        }
        if (layout.slider() == null || !layout.slider().contains(x, y)) {
            firstRow += amount > 0.0 ? -1 : 1;
            selected = -1;
            LogisticsPanel.updateLayout();
        }
        return true;
    }

    private static boolean key(int key, int scan, int modifiers) {
        return LogisticsPanel.visible() && waiting != 0 && key != 256;
    }

    private static boolean editReturnKey(int keyCode) {
        if (keyCode == 256) {
            returnEditing = false;
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            if (LogisticsPanel.send(CacheActions.Action.SET_RETURN_ADDRESS, -1, -1, -1, returnBuffer)) {
                returnEditing = false;
            } else {
                LogisticsPanel.notice("result.stale");
            }
            return true;
        }
        if (keyCode == 259) {
            if (!returnBuffer.isEmpty()) {
                returnBuffer = returnBuffer.substring(0, returnBuffer.length() - 1);
            }
            return true;
        }
        return false;
    }

    private static void setDraft(double x) {
        if (layout.slider() == null) {
            return;
        }
        int groupCap = snapshot.groupCapacity();
        double relative = x - (double)layout.slider().x() - PanelLayout.TRACK_INSET;
        int min = Math.clamp((long)((int)Math.round(relative * (double)groupCap / (double)(layout.slider().width() - 10))), (int)0, (int)groupCap);
        int cap = snapshot.cells().get(selected).maximum();
        if (cap < 0) {
            cap = groupCap;
        }
        if (draggingMaximum) {
            cap = draftMaximum;
        }
        draftMinimum = Math.min(min, cap);
    }

    private static void setDraftMaximum(double x) {
        if (layout.slider() == null) {
            return;
        }
        int groupCap = snapshot.groupCapacity();
        double relative = x - (double)layout.slider().x() - PanelLayout.TRACK_INSET;
        // "No return" only at the true far right, beyond the full-capacity position (width-10).
        if (relative >= (double)(layout.slider().width() - 9)) {
            draftMaximum = -1;
            return;
        }
        int max = Math.clamp((long)((int)Math.round(relative * (double)groupCap / (double)(layout.slider().width() - 10))), (int)0, (int)groupCap);
        int lower = snapshot.cells().get(selected).minimum();
        if (lower < 0) {
            lower = 0;
        }
        if (draggingSlider) {
            lower = draftMinimum;
        }
        draftMaximum = Math.max(max, lower);
    }

    private static void sendThresholds(int min, int max) {
        if (selected < 0 || !LogisticsPanel.active()) {
            return;
        }
        LogisticsPanel.send(CacheActions.Action.THRESHOLDS, selected, min < 0 ? 0 : min, max, "");
    }

    private static void sendMinimum(int minimum) {
        LogisticsPanel.sendThresholds(minimum, snapshot.cells().get(selected).maximum());
    }

    private static void sendMaximum(int maximum) {
        LogisticsPanel.sendThresholds(snapshot.cells().get(selected).minimum(), maximum);
    }

    public static boolean recipeReady() {
        return LogisticsPanel.active() && waiting == 0 && window != null;
    }

    public static boolean fillRecipe(ResourceLocation recipe, boolean maximum) {
        return LogisticsPanel.recipeReady() && LogisticsPanel.send(CacheActions.Action.FILL_RECIPE, 0, maximum ? 1 : 0, -1, recipe.toString());
    }

    public static List<PanelLayout.CellBox> visibleCells(Screen candidate) {
        if (!LogisticsPanel.current(candidate) || !LogisticsPanel.active() || layout == null || layout.compact() || waiting != 0) {
            return List.of();
        }
        return layout.cells();
    }

    public static List<PanelLayout.Rect> exclusions(Screen candidate) {
        return LogisticsPanel.current(candidate) && LogisticsPanel.visible() ? List.of(layout.bounds()) : List.of();
    }

    private static boolean send(CacheActions.Action action, int slot, int first, int second, String template) {
        ItemStack held;
        if (snapshot == null || snapshot.session() == null || waiting != 0 || window == null) {
            return false;
        }
        boolean creative = screen instanceof CreativeModeInventoryScreen && (action == CacheActions.Action.DEPOSIT || action == CacheActions.Action.TAKE_CURSOR);
        String cursor = "";
        int count = 0;
        if (creative && !(held = LogisticsPanel.MC.player.containerMenu.getCarried()).isEmpty()) {
            try {
                cursor = ItemVariantKey.of(held, (HolderLookup.Provider)LogisticsPanel.MC.player.registryAccess()).encoded();
                count = held.getCount();
            }
            catch (IllegalArgumentException invalid) {
                LogisticsPanel.notice("result.invalid_item");
                return false;
            }
        }
        CacheActions.Intent intent = new CacheActions.Intent(snapshot.session(), snapshot.revision(), action, slot, first, second, template);
        waiting = ++sequence;
        waitingSince = tick;
        recordPredict(action, slot, first, template, waiting);
        PacketDistributor.sendToServer((CustomPacketPayload)new PanelPackets.Command(window, waiting, intent, creative, cursor, count), (CustomPacketPayload[])new CustomPacketPayload[0]);
        return true;
    }

    /** Record a client-only display prediction for a {TAKE_CURSOR} (pick-up) or source-cell DEPOSIT
     *  (put-back) so the number changes immediately, before the server confirmation returns. It is a
     *  display alias only: it never creates items or writes inventory, and is cleared on ack/close. */
    private static void recordPredict(CacheActions.Action action, int slot, int first, String template, int sequence) {
        if (!(action == CacheActions.Action.TAKE_CURSOR || action == CacheActions.Action.DEPOSIT)) { predict = null; return; }
        if (snapshot == null || slot < 0 || slot >= snapshot.cells().size() || waiting == 0) { predict = null; return; }
        var cell = snapshot.cells().get(slot);
        if (cell.template().isEmpty()) { predict = null; return; }
        int base = Math.max(0, cell.amount() - cell.reserved());
        int delta;
        if (action == CacheActions.Action.TAKE_CURSOR) {
            // Worst-case pick-up amount is bounded by the request, the cell and the native stack cap.
            int cap = cell.stackSize();
            delta = Math.min(Math.max(0, base), Math.min(Math.max(0, first), cap));
        } else {
            // Source-cell put-back returns the carried stack to this cell; delta <= carried count.
            int carried = LogisticsPanel.MC.player.containerMenu.getCarried().getCount();
            delta = Math.min(carried, Math.max(0, cell.stackSize() - base));
        }
        int result = action == CacheActions.Action.TAKE_CURSOR ? Math.max(0, base - delta) : base + delta;
        predict = new Predict(window, snapshot.session(), slot, cell.template(), sequence, serial, result);
        // Track this client's local preview ownership for the withdrawn-amount bound of this take.
        // It is pending (previewConfirmed=false) until the server acknowledges; a rejected/expired take
        // never becomes confirmed and thus never claims a later independent same-variant carry.
        if (action == CacheActions.Action.TAKE_CURSOR) { previewVariant = cell.template(); previewRemaining = delta; previewConfirmed = false; }
    }

    private static Component tr(String key, Object ... args) {
        return Component.translatable((String)("gui.create_feed_me_packages." + key), (Object[])args);
    }

    private static void notice(String key) {
        feedback = LogisticsPanel.tr(key, new Object[0]);
        feedbackUntil = tick + 120;
    }

    static {
        ICONS = new HashMap<String, ItemStack>();
        selected = -1;
        serial = -1L;
        capturedButton = -1;
        returnBuffer = "";
        draftMaximum = -1;
        tooltip = List.of();
        recipeOverlay = candidate -> false;
        PANEL = ResourceLocation.fromNamespaceAndPath((String)"create_feed_me_packages", (String)"textures/gui/panel.png");
    }
}

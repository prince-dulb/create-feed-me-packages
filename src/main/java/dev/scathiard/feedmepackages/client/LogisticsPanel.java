package dev.scathiard.feedmepackages.client;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CacheActions.Action;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.mixin.ContainerScreenAccess;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.*;
import java.util.function.Predicate;

/** Inventory companion, not a second inventory menu. All writes wait for a server acknowledgement. */
public final class LogisticsPanel {
    private LogisticsPanel() {}
    private static final Minecraft MC = Minecraft.getInstance();
    private static AbstractContainerScreen<?> screen;
    private static UUID window;
    private static PanelPackets.Snapshot snapshot;
    private static PanelLayout layout;
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static int tick, lastQuery, sequence, waiting, waitingSince, firstRow, selected = -1, draftMinimum;
    private static long serial = -1;
    private static boolean draggingSlider;
    private static boolean draggingMaximum;
    private static int capturedButton = -1;
    private static EditBox number;
    private static boolean returnEditing;
    private static String returnBuffer = "";
    private static int draftMaximum = -1;
    private static Component feedback;
    private static int feedbackUntil;
    private static List<Component> tooltip = List.of();
    private static int mouseX, mouseY;
    private static Predicate<Screen> recipeOverlay = candidate -> false;
    private static boolean retainTransition;
    private static int overlayBottomInset;
    public static void recipeOverlay(Predicate<Screen> predicate) { recipeOverlay = Objects.requireNonNull(predicate); }
    public static void overlayBottomInset(int pixels) { overlayBottomInset = Math.clamp(pixels, 0, 64); }

    public static void register() {
        PanelNetwork.receiveOnClient(LogisticsPanel::receive);
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ScreenEvent.Opening event) -> {
            retainTransition = window != null && (event.getNewScreen() == screen || recipeOverlay.test(event.getNewScreen()));
            if (MC.player == null || event.getNewScreen() == null) return;
            var inventory = CuriosApi.getCuriosInventory(MC.player).orElse(null);
            if (inventory == null || inventory.findCurios("necklace").stream().noneMatch(found -> !found.slotContext().cosmetic() && found.stack().getItem() instanceof PendantItem)) return;
            if (event.getNewScreen().getClass() == InventoryScreen.class) event.setNewScreen(new SupplyInventoryScreen(MC.player));
            else if (event.getNewScreen().getClass() == CreativeModeInventoryScreen.class) event.setNewScreen(new SupplyCreativeScreen(MC.player));
        });
        bus.addListener((ClientTickEvent.Post event) -> tick());
        bus.addListener((ScreenEvent.Render.Pre event) -> { if (current(event.getScreen())) updateLayout(); });
        bus.addListener(LogisticsPanel::foreground);
        bus.addListener(LogisticsPanel::postRender);
        bus.addListener((ScreenEvent.MouseButtonPressed.Pre event) -> {
            if (current(event.getScreen()) && press(event.getMouseX(), event.getMouseY(), event.getButton())) event.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseButtonReleased.Pre event) -> {
            if (current(event.getScreen()) && release(event.getMouseX(), event.getMouseY(), event.getButton())) event.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseDragged.Pre event) -> {
            if (!current(event.getScreen()) || !visible()) return;
            if (draggingSlider) setDraft(event.getMouseX());
            if (draggingMaximum) setDraftMaximum(event.getMouseX());
            if (waiting != 0 || draggingSlider || draggingMaximum || capturedButton >= 0 || layout.bounds().contains(event.getMouseX(), event.getMouseY())) event.setCanceled(true);
        });
        bus.addListener((ScreenEvent.MouseScrolled.Pre event) -> {
            if (current(event.getScreen()) && scroll(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) event.setCanceled(true);
        });
        bus.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (!current(event.getScreen())) return;
            if (returnEditing && editReturnKey(event.getKeyCode())) { event.setCanceled(true); return; }
            if (key(event.getKeyCode(), event.getScanCode(), event.getModifiers())) event.setCanceled(true);
        });
        bus.addListener((ScreenEvent.CharacterTyped.Pre event) -> {
            if (!current(event.getScreen())) return;
            if (number != null) { number.charTyped(event.getCodePoint(), event.getModifiers()); event.setCanceled(true); }
            else if (returnEditing) {
                if (returnBuffer.length() < 128 && event.getCodePoint() >= 32 && event.getCodePoint() <= 0xFFFF) returnBuffer += new String(Character.toChars(event.getCodePoint()));
                event.setCanceled(true);
            }
        });
        bus.addListener((ScreenEvent.Closing event) -> { if (event.getScreen() == screen && !retainTransition) close(); });
    }

    private static boolean supported(Screen candidate) {
        return candidate instanceof InventoryScreen || candidate instanceof CreativeModeInventoryScreen || candidate instanceof CraftingScreen;
    }
    private static boolean current(Screen candidate) { return candidate == screen && supported(candidate) && window != null; }
    private static boolean visible() { return snapshot != null && snapshot.status() != AccessGate.Status.NOT_WORN && layout != null; }
    private static boolean active() { return snapshot != null && snapshot.status() == AccessGate.Status.ACTIVE && snapshot.session() != null; }
    private static boolean bookOpen() {
        return screen instanceof RecipeUpdateListener listener && listener.getRecipeBookComponent().isVisible();
    }
    private static void tick() {
        tick++;
        boolean overlay = recipeOverlay.test(MC.screen) && window != null;
        if (MC.player == null || MC.getConnection() == null || (!supported(MC.screen) && !overlay)) { if (window != null) close(); return; }
        if (!overlay && screen != MC.screen) mount((AbstractContainerScreen<?>) MC.screen);
        if (waiting != 0 && tick - waitingSince > 60) { waiting = 0; notice("timeout"); }
        if (tick - lastQuery >= 10) {
            lastQuery = tick; PacketDistributor.sendToServer(new PanelPackets.Query(window, MC.player.containerMenu.containerId, true));
        }
    }
    public static void mount(AbstractContainerScreen<?> value) {
        if (screen == value && window != null) return;
        close(); screen = value; window = UUID.randomUUID(); lastQuery = tick - 10;
    }
    public static void unmount(AbstractContainerScreen<?> value) { if (screen == value && !retainTransition) close(); }
    private static void close() {
        if (window != null && MC.getConnection() != null && MC.player != null)
            PacketDistributor.sendToServer(new PanelPackets.Query(window, MC.player.containerMenu.containerId, false));
        screen = null; window = null; snapshot = null; layout = null; waiting = 0; serial = -1;
        selected = -1; firstRow = 0; draggingSlider = false; draggingMaximum = false; capturedButton = -1; number = null; returnEditing = false; returnBuffer = ""; draftMaximum = -1;
        tooltip = List.of(); ICONS.clear(); feedback = null;
    }
    private static void receive(PanelPackets.Snapshot incoming) {
        if (window == null || (MC.screen != screen && !recipeOverlay.test(MC.screen)) || !window.equals(incoming.window()) || incoming.serial() <= serial) return;
        boolean wasVisible = snapshot != null && snapshot.status() != AccessGate.Status.NOT_WORN;
        serial = incoming.serial(); snapshot = incoming;
        if (wasVisible && incoming.status() == AccessGate.Status.NOT_WORN) screen.init(MC, screen.width, screen.height);
        if (incoming.acknowledged() != 0 && incoming.acknowledged() == waiting) {
            waiting = 0;
            if (incoming.result() != CacheActions.Result.OK) notice("result." + incoming.result().name().toLowerCase(Locale.ROOT));
        }
        if (!active()) { selected = -1; draggingSlider = false; number = null; }
        if (selected >= incoming.cells().size() || (selected >= 0 && incoming.cells().get(selected).template().isEmpty())) selected = -1;
        Set<String> present = new HashSet<>();
        for (var cell : incoming.cells()) if (!cell.template().isEmpty()) {
            present.add(cell.template());
            if (!ICONS.containsKey(cell.template())) {
                try { ICONS.put(cell.template(), ItemVariantKey.decode(cell.template(), MC.player.registryAccess()).stack(MC.player.registryAccess(), 1)); }
                catch (IllegalArgumentException invalid) { notice("invalid_display"); }
            }
        }
        ICONS.keySet().retainAll(present);
        updateLayout();
    }
    private static void updateLayout() {
        if (screen == null || snapshot == null || snapshot.status() == AccessGate.Status.NOT_WORN) { layout = null; return; }
        // Vanilla's minimum GUI width still has room for this panel + inventory. Move only visual coordinates.
        int count = active() ? snapshot.cells().size() : 0;
        int availableColumns = Math.max(2, (screen.width - screen.getXSize() - 22) / PanelLayout.ROW);
        int width = Math.min(PanelLayout.preferredWidth(count), availableColumns * PanelLayout.ROW + 10);
        if (!bookOpen() && screen.getGuiLeft() < width + 8 && screen.width >= screen.getXSize() + width + 12) {
            int old = screen.getGuiLeft(), next = width + 8, delta = next - old;
            ((ContainerScreenAccess) screen).fmp$setLeft(next);
            for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.getX() >= old && widget.getX() < old + screen.getXSize())
                widget.setX(widget.getX() + delta);
        }
        layout = PanelLayout.compute(screen.height, screen.getGuiLeft(), screen.getGuiTop(), count, firstRow, active() ? selected : -1, bookOpen());
        int availableHeight = screen.height - overlayBottomInset;
        for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible && widget.getY() >= screen.height / 2) {
            var b = layout.bounds();
            if (widget.getX() < b.x() + b.width() && widget.getX() + widget.getWidth() > b.x())
                availableHeight = Math.min(availableHeight, widget.getY() - 2);
        }
        if (availableHeight != screen.height)
            layout = PanelLayout.compute(availableHeight, screen.getGuiLeft(), screen.getGuiTop(), count, firstRow, active() ? selected : -1, bookOpen());
        firstRow = layout.firstRow();
    }

    private static void foreground(ContainerScreenEvent.Render.Foreground event) {
        if (!current(event.getContainerScreen()) || !visible() || layout.compact()) return;
        mouseX = event.getMouseX(); mouseY = event.getMouseY(); tooltip = List.of();
        var g = event.getGuiGraphics(); g.pose().pushPose();
        g.pose().translate(-screen.getGuiLeft(), -screen.getGuiTop(), 0);
        render(g); g.pose().popPose();
    }
    private static void postRender(ScreenEvent.Render.Post event) {
        if (!current(event.getScreen()) || !visible()) return;
        mouseX = event.getMouseX(); mouseY = event.getMouseY();
        if (layout.compact()) {
            tooltip = List.of(); button(event.getGuiGraphics(), layout.bounds().x(), layout.bounds().y(), "+", "expand");
        }
        if (!tooltip.isEmpty() && MC.player.containerMenu.getCarried().isEmpty())
            event.getGuiGraphics().renderComponentTooltip(MC.font, tooltip, mouseX, mouseY);
    }
    private static void render(GuiGraphics g) {
        var b = layout.bounds(); int x = b.x(), y = b.y();
        frame(g, b);
        var copy = layout.address();
        String address = snapshot.address(); int textWidth = copy.width() - 2;
        String displayed = MC.font.width(address) <= textWidth ? address
                : MC.font.plainSubstrByWidth(address, Math.max(0, textWidth - MC.font.width("…"))) + "…";
        text(g, displayed, copy.x() + 1, copy.y() + 5, copy.contains(mouseX, mouseY) ? 0xFF70462A : 0xFF514333);
        if (copy.contains(mouseX, mouseY)) tooltip = List.of(tr("address"), Component.literal(address));
        if (!active() || !snapshot.bound()) {
            text(g, "!", x + 4, y + 11, 0xFF714A40);
            if (new PanelLayout.Rect(x + 4, y + 11, 6, 9).contains(mouseX, mouseY))
                tooltip = List.of(tr(active() ? "unbound" : "status." + snapshot.status().name().toLowerCase(Locale.ROOT)));
        }
        if (active()) {
            for (var cell : layout.cells()) renderCell(g, cell);
            if (layout.slider() != null) renderSlider(g);
            if (layout.returnBar() != null) renderReturnBar(g);
        }
        int fy = layout.footerY();
        if (waiting != 0) overlay(g, x + 4, y + 20, 4, 2, 0xFFE6C178);
        if (feedback != null && tick < feedbackUntil) {
            text(g, "!", x + 4, y + 11, 0xFFFFB16B);
            if (new PanelLayout.Rect(x + 4, y + 11, 6, 9).contains(mouseX, mouseY)) tooltip = List.of(feedback);
        }
        if (layout.totalRows() > layout.visibleRows()) {
            int rail = fy - y - PanelLayout.HEADER;
            g.fill(x + b.width() - 4, y + PanelLayout.HEADER, x + b.width() - 2, y + PanelLayout.HEADER + rail, 0xFF302F2C);
            int thumb = Math.max(7, rail * layout.visibleRows() / layout.totalRows());
            int offset = (rail - thumb) * firstRow / Math.max(1, layout.totalRows() - layout.visibleRows());
            g.fill(x + b.width() - 4, y + PanelLayout.HEADER + offset, x + b.width() - 2, y + PanelLayout.HEADER + offset + thumb, 0xFFC0A366);
        }
    }
    private static void renderCell(GuiGraphics g, PanelLayout.CellBox box) {
        var r = box.bounds(); var cell = snapshot.cells().get(box.slot()); int x = r.x(), y = r.y();
        AllGuiTextures.STOCK_KEEPER_REQUEST_SLOT.render(g, x, y);
        var icon = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
        if (!icon.isEmpty()) {
            if (cell.amount() == 0) g.setColor(1, 1, 1, .45f);
            g.renderItem(icon, x + 1, y + 1); g.setColor(1, 1, 1, 1);
            String amount = Integer.toString(cell.amount()); float scale = Math.min(1f, 16f / MC.font.width(amount));
            g.pose().pushPose(); g.pose().translate(x + 17 - MC.font.width(amount) * scale, y + 17 - MC.font.lineHeight * scale, 190); g.pose().scale(scale, scale, 1);
            g.drawString(MC.font, amount, 0, 0, cell.amount() == 0 ? 0xFF969081 : 0xFFF5E8C6, true); g.pose().popPose();
        }
        boolean hover = r.contains(mouseX, mouseY);
        if (cell.residual()) {
            overlay(g, x + 1, y + 13, 3, 3, 0xFF82C665);
            if (hover && new PanelLayout.Rect(x, y + 11, 6, 7).contains(mouseX, mouseY)) tooltip = List.of(tr("residual"));
        }
        if (!cell.template().isEmpty() && cell.minimum() >= 0 && cell.amount() < cell.minimum() * cell.stackSize()) {
            int color = cell.pending() > 0 ? 0xFF82C665 : 0xFF93938B;
            overlay(g, x + 3, y + 1, 1, 3, color); overlay(g, x + 1, y + 3, 5, 1, color); overlay(g, x + 2, y + 4, 3, 1, color);
        }
        if (!cell.template().isEmpty() && cell.maximum() >= 0 && cell.amount() > cell.maximum() * cell.stackSize()) {
            int color = 0xFFE0955A;
            overlay(g, x + 1, y + 1, 5, 1, color); overlay(g, x + 2, y + 2, 3, 1, color); overlay(g, x + 3, y + 3, 1, 3, color);
        }
        if (hover && !cell.template().isEmpty()) overlay(g, box.dot().x(), box.dot().y() + 1, 4, 4, 0xFFE0C27E);
        if (hover) {
            tooltip = new ArrayList<>();
            if (!icon.isEmpty()) { tooltip.addAll(Screen.getTooltipFromItem(MC, icon)); tooltip.add(tr("stock", cell.amount(), snapshot.groupCapacity() * cell.stackSize())); if (cell.stackSize() > 1) tooltip.add(tr("stock_groups", cell.amount() / cell.stackSize(), snapshot.groupCapacity())); }
            if (cell.minimum() >= 0 && cell.amount() < cell.minimum() && mouseX < x + 7 && mouseY < y + 7)
                tooltip = List.of(tr(cell.pending() > 0 ? "requested" : "shortage"));
            if (box.dot().contains(mouseX, mouseY) && !cell.template().isEmpty()) tooltip = List.of(tr("configure"));
        }
    }
    private static int thumbPx(int sliderX, int width, int value, int groupCap) {
        return sliderX + 3 + (value < 0 ? 0 : Math.max(1, Math.min(groupCap, value) * (width - 10) / Math.max(1, groupCap)));
    }
    private static void renderSlider(GuiGraphics g) {
        var r = layout.slider(); var cell = snapshot.cells().get(selected);
        int groupCap = snapshot.groupCapacity();
        int x = r.x(), y = r.y(), w = r.width();
        int min = draggingSlider ? draftMinimum : cell.minimum();
        int max = draggingMaximum ? draftMaximum : cell.maximum();
        // Overlay layer: the slider floats above the cells it covers (z lifts past item icons at ~200).
        g.pose().pushPose(); g.pose().translate(0, 0, 250);
        g.fill(x - 1, y - 1, x + w + 1, y + 23, 0xFF262B26);
        g.fill(x, y, x + w, y + 22, 0xFF393C36);
        g.fill(x + 3, y + 4, x + w - 3, y + 6, 0xFF242723);
        int minAt = thumbPx(x, w, min, groupCap);
        int maxAt = max < 0 ? x + w - 3 : thumbPx(x, w, max, groupCap);
        // acceptable stock band between the supply minimum and the return maximum
        g.fill(Math.min(minAt, maxAt), y + 4, Math.max(minAt, maxAt) + 1, y + 6, 0xFFB4A36B);
        overlay(g, minAt - 1, y + 1, 3, 8, min < 0 ? 0xFF828378 : 0xFFE5C57D);
        overlay(g, maxAt - 1, y + 1, 3, 8, max < 0 ? 0xFF696C65 : 0xFFE0955A);
        if (number == null) {
            String minLabel = min < 0 ? "-" : String.valueOf(min * cell.stackSize());
            float s1 = Math.min(1f, 20f / MC.font.width(minLabel));
            g.pose().pushPose(); g.pose().translate(minAt - MC.font.width(minLabel) * s1 / 2, y + 12, 190); g.pose().scale(s1, s1, 1);
            g.drawString(MC.font, minLabel, 0, 0, 0xFFE5D8B6, false); g.pose().popPose();
            if (max >= 0) {
                String maxLabel = String.valueOf(max * cell.stackSize());
                float s2 = Math.min(1f, 20f / MC.font.width(maxLabel));
                g.pose().pushPose(); g.pose().translate(maxAt - MC.font.width(maxLabel) * s2 / 2, y + 13, 190); g.pose().scale(s2, s2, 1);
                g.drawString(MC.font, maxLabel, 0, 0, 0xFFE0955A, false); g.pose().popPose();
            }
        }
        else { number.setX(x + 2); number.setY(y + 11); number.render(g, mouseX, mouseY, 0); }
        text(g, "↶", x + w - 10, y + 12, 0xFFB9C4B0);
        if (r.contains(mouseX, mouseY)) {
            if (mouseY < y + 10) tooltip = List.of(tr(mouseX <= minAt ? "minimum_help" : (max < 0 ? "returns_later" : "maximum_help")));
            if (new PanelLayout.Rect(x + w - 11, y + 10, 11, 12).contains(mouseX, mouseY)) tooltip = List.of(tr("reset", cell.pending()));
        }
        g.pose().popPose();
    }
    private static void renderReturnBar(GuiGraphics g) {
        var r = layout.returnBar();
        int x = r.x(), y = r.y(), w = r.width(), h = r.height();
        // Dark bracket behind the field (matches the panel frame), then a white input box like the
        // dedicated delivery-address field. No send button: returns are automatic.
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF262B26);
        g.fill(x, y, x + w, y + h, 0xFF3A3E38);
        String label = tr("return_label").getString();
        int labelW = MC.font.width(label) + 12;
        int boxX = x + labelW, boxW = w - labelW - 4;
        g.fill(boxX, y + 3, boxX + boxW, y + h - 3, 0xFFEFE9D8);
        g.fill(boxX, y + 3, boxX + boxW, y + 4, 0xFFD8D0BC);
        g.fill(boxX, y + h - 4, boxX + boxW, y + h - 3, 0xFFD8D0BC);
        g.fill(boxX + boxW - 1, y + 3, boxX + boxW, y + h - 3, 0xFFD8D0BC);
        String value = returnEditing ? returnBuffer : (snapshot.returnAddress() == null ? "" : snapshot.returnAddress());
        int textWidth = boxW - 10;
        String shown = MC.font.width(value) <= textWidth ? value : MC.font.plainSubstrByWidth(value, Math.max(0, textWidth - MC.font.width("…"))) + "…";
        text(g, label, x + 6, y + h / 2 - MC.font.lineHeight / 2, 0xFFB9B0A0);
        text(g, shown, boxX + 5, y + h / 2 - MC.font.lineHeight / 2, returnEditing ? 0xFF4A3B28 : 0xFF5E523F);
        if (returnEditing) {
            int caretX = boxX + 5 + MC.font.width(shown);
            g.fill(caretX, y + 5, caretX + 1, y + h - 5, 0xFF4A3B28);
        }
        if (r.contains(mouseX, mouseY)) tooltip = List.of(tr(returnEditing ? "return_editing" : "return_hint"));
    }

    private static void frame(GuiGraphics g, PanelLayout.Rect b) {
        int x = b.x(), y = b.y(), w = b.width(), h = b.height();
        g.fill(x, y, x + w, y + h, 0xFF242521); g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF939487);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF55594F); g.fill(x + 3, y + PanelLayout.HEADER, x + w - 3, y + h - PanelLayout.FOOTER, 0xFF41453F);
        AllGuiTextures.STOCK_KEEPER_REQUEST_BANNER_L.render(g, x + 2, y + 3);
        for (int i = x + 10; i < x + w - 10; i++) AllGuiTextures.STOCK_KEEPER_REQUEST_BANNER_M.render(g, i, y + 3);
        AllGuiTextures.STOCK_KEEPER_REQUEST_BANNER_R.render(g, x + w - 10, y + 3);
        for (int dx : new int[]{2, w - 5}) for (int dy : new int[]{2, h - 5}) { g.fill(x + dx, y + dy, x + dx + 3, y + dy + 3, 0xFF262B26); g.fill(x + dx, y + dy, x + dx + 2, y + dy + 1, 0xFFB6B2A0); }
    }
    private static void button(GuiGraphics g, int x, int y, String label, String help) {
        boolean hover = new PanelLayout.Rect(x, y, 18, 18).contains(mouseX, mouseY);
        (hover ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON).render(g, x, y);
        text(g, label, x + (18 - MC.font.width(label)) / 2, y + 5, 0xFFF0E3C3);
        if (hover) tooltip = help.equals("address") ? List.of(tr("address"), Component.literal(snapshot.address())) : List.of(tr(help));
    }
    private static void text(GuiGraphics g, String value, int x, int y, int color) {
        g.pose().pushPose(); g.pose().translate(0, 0, 190); g.drawString(MC.font, value, x, y, color, false); g.pose().popPose();
    }
    private static void overlay(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.pose().pushPose(); g.pose().translate(0, 0, 190); g.fill(x, y, x + w, y + h, color); g.pose().popPose();
    }

    private static boolean press(double x, double y, int button) {
        if (!visible()) return false;
        if (waiting != 0) { capturedButton = button; return true; }
        if (number != null && (layout.slider() == null || !layout.slider().contains(x, y))) { commitNumber(); return true; }
        if (!layout.bounds().contains(x, y)) return false;
        // Clicking anywhere other than the return bar exits return-address editing, so typing never
        // leaks into the wrong field after the player changes focus.
        if (returnEditing && !(active() && layout.returnBar() != null && layout.returnBar().contains(x, y))) returnEditing = false;
        capturedButton = button;
        if (button != 0 && button != 1) return true;
        if (layout.compact()) {
            if (bookOpen()) { ((RecipeUpdateListener)screen).getRecipeBookComponent().toggleVisibility(); screen.init(MC, screen.width, screen.height); }
            updateLayout(); return true;
        }
        int px = layout.bounds().x(), py = layout.bounds().y();
        if (layout.address().contains(x, y)) { MC.keyboardHandler.setClipboard(snapshot.address()); notice("copied"); return true; }
        int fy = layout.footerY();
        if (x >= px + layout.bounds().width() - 4 && y >= py + PanelLayout.HEADER && y < fy) {
            firstRow = (int)((y - py - PanelLayout.HEADER) * Math.max(0, layout.totalRows() - layout.visibleRows()) / Math.max(1, fy - py - PanelLayout.HEADER));
            selected = -1; updateLayout(); return true;
        }
        if (active() && layout.returnBar() != null && layout.returnBar().contains(x, y)) {
            returnEditing = true; returnBuffer = snapshot.returnAddress() == null ? "" : snapshot.returnAddress(); return true;
        }
        if (!active()) return true;
        var slider = layout.slider();
        if (slider != null && slider.contains(x, y)) {
            if (y < slider.y() + 10) {
                int groupCap = snapshot.groupCapacity();
                var cell = snapshot.cells().get(selected);
                int minAt = thumbPx(slider.x(), slider.width(), cell.minimum(), groupCap);
                int maxAt = cell.maximum() < 0 ? slider.x() + slider.width() - 3 : thumbPx(slider.x(), slider.width(), cell.maximum(), groupCap);
                // Drag whichever thumb the player grabbed (left = supply minimum, right = return maximum).
                if (Math.abs(x - maxAt) < Math.abs(x - minAt)) { draggingMaximum = true; setDraftMaximum(x); }
                else { draggingSlider = true; setDraft(x); }
            } else if (x >= slider.x() + slider.width() - 11) {
                send(Action.RESET_REQUEST, selected, 0, -1, "");
            } else {
                number = new EditBox(MC.font, slider.x() + 2, slider.y() + 11, slider.width() - 15, 10, tr("minimum_help"));
                number.setBordered(false); number.setMaxLength(5); number.setFilter(value -> value.matches("-?[0-9]*"));
                number.setValue(Integer.toString(snapshot.cells().get(selected).minimum())); number.setFocused(true);
            }
            return true;
        }
        for (var box : layout.cells()) if (box.bounds().contains(x, y)) {
            var cell = snapshot.cells().get(box.slot());
            if (box.dot().contains(x, y) && !cell.template().isEmpty() && MC.player.containerMenu.getCarried().isEmpty()) {
                selected = selected == box.slot() ? -1 : box.slot(); number = null; updateLayout(); return true;
            }
            if (button == 1 && Screen.hasControlDown()) send(Action.CLEAR_FILTER, box.slot(), 0, -1, "");
            else if (!MC.player.containerMenu.getCarried().isEmpty()) send(Action.DEPOSIT, box.slot(), button == 1 ? 1 : 0, -1, "");
            else {
                var item = ICONS.getOrDefault(cell.template(), ItemStack.EMPTY);
                send(Screen.hasShiftDown() ? Action.TAKE_INVENTORY : Action.TAKE_CURSOR, box.slot(), button == 1 ? 1 : item.getMaxStackSize(), -1, "");
            }
            return true;
        }
        return true;
    }
    private static boolean release(double x, double y, int button) {
        if (!visible()) { capturedButton = -1; return false; }
        if (waiting != 0) { if (capturedButton == button) capturedButton = -1; return true; }
        if (draggingSlider) {
            setDraft(x); draggingSlider = false; sendMinimum(draftMinimum); capturedButton = -1; return true;
        }
        if (draggingMaximum) {
            setDraftMaximum(x); draggingMaximum = false; sendMaximum(draftMaximum); capturedButton = -1; return true;
        }
        if (capturedButton == button) { capturedButton = -1; return true; }
        if (layout.bounds().contains(x, y)) {
            if (active() && (button == 0 || button == 1) && !MC.player.containerMenu.getCarried().isEmpty()
                    && (layout.slider() == null || !layout.slider().contains(x, y)))
                for (var box : layout.cells()) if (box.bounds().contains(x, y)) send(Action.DEPOSIT, box.slot(), button == 1 ? 1 : 0, -1, "");
            return true;
        }
        return false;
    }
    private static boolean scroll(double x, double y, double amount) {
        if (!visible() || !layout.bounds().contains(x, y)) return false;
        if (waiting != 0 || layout.compact()) return true;
        if (layout.slider() != null && layout.slider().contains(x, y)) {
            int step = Screen.hasControlDown() ? 64 : Screen.hasShiftDown() ? 16 : 1;
            int groupCap = snapshot.groupCapacity();
            sendMinimum(Math.clamp(snapshot.cells().get(selected).minimum() + (amount > 0 ? step : -step), -1, groupCap));
        } else { firstRow += amount > 0 ? -1 : 1; selected = -1; number = null; updateLayout(); }
        return true;
    }
    private static boolean key(int key, int scan, int modifiers) {
        if (number != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) number = null;
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) commitNumber();
            else number.keyPressed(key, scan, modifiers);
            return true;
        }
        return visible() && waiting != 0 && key != GLFW.GLFW_KEY_ESCAPE;
    }
    private static boolean editReturnKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { returnEditing = false; return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            returnEditing = false; send(Action.SET_RETURN_ADDRESS, -1, -1, -1, returnBuffer); return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { if (!returnBuffer.isEmpty()) returnBuffer = returnBuffer.substring(0, returnBuffer.length() - 1); return true; }
        return false;
    }
    private static void commitNumber() {
        if (number == null) return;
        int groupCap = snapshot.groupCapacity();
        try { int value = Integer.parseInt(number.getValue()); if (value < -1 || value > groupCap) throw new NumberFormatException(); number = null; sendMinimum(value); }
        catch (NumberFormatException invalid) { notice("number_range"); }
    }
    private static void setDraft(double x) {
        if (layout.slider() == null) return;
        int groupCap = snapshot.groupCapacity();
        double relative = x - layout.slider().x() - 3;
        int min = relative <= 0 ? -1 : Math.clamp((int)Math.round(relative * groupCap / (layout.slider().width() - 10)), 0, groupCap);
        int cap = snapshot.cells().get(selected).maximum();
        if (draggingMaximum) cap = draftMaximum;
        draftMinimum = min < 0 ? -1 : Math.min(min, cap < 0 ? groupCap : cap);
    }
    private static void setDraftMaximum(double x) {
        if (layout.slider() == null) return;
        int groupCap = snapshot.groupCapacity();
        double relative = x - layout.slider().x() - 3;
        int max = relative <= 1 ? -1 : Math.clamp((int)Math.round(relative * groupCap / (layout.slider().width() - 10)), 0, groupCap);
        int lower = snapshot.cells().get(selected).minimum();
        if (draggingSlider) lower = draftMinimum;
        draftMaximum = max < 0 ? -1 : Math.max(max, Math.max(0, lower));
    }
    private static void sendMaximum(int maximum) {
        if (selected >= 0 && active()) send(Action.THRESHOLDS, selected, snapshot.cells().get(selected).minimum(), maximum, "");
    }
    private static void sendMinimum(int minimum) {
        if (selected >= 0 && active()) send(Action.THRESHOLDS, selected, minimum, snapshot.cells().get(selected).maximum(), "");
    }
    public static boolean recipeReady() { return active() && waiting == 0 && window != null; }
    public static boolean fillRecipe(ResourceLocation recipe, boolean maximum) {
        return recipeReady() && send(Action.FILL_RECIPE, 0, maximum ? 1 : 0, -1, recipe.toString());
    }
    public static List<PanelLayout.CellBox> visibleCells(Screen candidate) {
        if (!current(candidate) || !active() || layout == null || layout.compact() || waiting != 0) return List.of();
        return layout.cells();
    }
    public static List<PanelLayout.Rect> exclusions(Screen candidate) {
        return current(candidate) && visible() ? List.of(layout.bounds()) : List.of();
    }
    private static boolean send(Action action, int slot, int first, int second, String template) {
        if (snapshot == null || snapshot.session() == null || waiting != 0 || window == null) return false;
        boolean creative = screen instanceof CreativeModeInventoryScreen && (action == Action.DEPOSIT || action == Action.TAKE_CURSOR);
        String cursor = ""; int count = 0;
        if (creative) {
            var held = MC.player.containerMenu.getCarried();
            if (!held.isEmpty()) {
                try { cursor = ItemVariantKey.of(held, MC.player.registryAccess()).encoded(); count = held.getCount(); }
                catch (IllegalArgumentException invalid) { notice("result.invalid_item"); return false; }
            }
        }
        var intent = new CacheActions.Intent(snapshot.session(), snapshot.revision(), action, slot, first, second, template);
        waiting = ++sequence; waitingSince = tick;
        PacketDistributor.sendToServer(new PanelPackets.Command(window, waiting, intent, creative, cursor, count)); return true;
    }
    private static Component tr(String key, Object... args) { return Component.translatable("gui.create_feed_me_packages." + key, args); }
    private static void notice(String key) { feedback = tr(key); feedbackUntil = tick + 120; }
}

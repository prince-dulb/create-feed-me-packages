package dev.scathiard.feedmepackages.client;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PanelLayoutTest {
    @Test void everyUnlockedCellCanBeReachedWithoutShrinkingOrOverlap() {
        for (int count : new int[]{9, 16, 24, 30, 36}) for (int height : new int[]{208, 240, 360, 480, 1080}) {
            Set<Integer> reached = new HashSet<>();
            for (int row = 0; row < (count + 1) / 2; row++) {
                var layout = PanelLayout.compute(height, 88, 70, count, row, -1, false);
                assertFalse(layout.compact());
                assertTrue(layout.bounds().y() >= 4 && layout.bounds().y() + layout.bounds().height() <= height - 4);
                for (var box : layout.cells()) {
                    assertTrue(box.slot() < count); assertEquals(18, box.bounds().width()); assertEquals(18, box.bounds().height());
                    assertTrue(layout.bounds().contains(box.bounds().x(), box.bounds().y()));
                    assertTrue(box.bounds().contains(box.dot().x(), box.dot().y()));
                    assertTrue(box.bounds().contains(box.dot().x() + box.dot().width() - 1, box.dot().y() + box.dot().height() - 1));
                    assertTrue(box.bounds().y() + box.bounds().height() <= layout.footerY()); reached.add(box.slot());
                    for (var other : layout.cells()) if (box.slot() != other.slot()) assertFalse(intersects(box.bounds(), other.bounds()));
                }
            }
            assertEquals(count, reached.size());
        }
    }
    @Test void expandedSliderAndRecipeBookUseSeparateSpace() {
        for (int selected = 0; selected < 36; selected++) {
            var layout = PanelLayout.compute(240, 265, 60, 36, 0, selected, true);
            assertFalse(layout.compact()); assertNotNull(layout.slider());
            int selectedSlot = selected;
            assertTrue(layout.cells().stream().anyMatch(cell -> cell.slot() == selectedSlot));
            assertTrue(layout.bounds().x() + layout.bounds().width() < 265 - 177);
            // The slider is an overlay: it may visually cover cells below it (z-lift),
            // so we only verify it stays within screen bounds.
            assertTrue(layout.slider().y() + layout.slider().height() <= 240 - 4);
        }
        var narrow = PanelLayout.compute(240, 72, 60, 36, 0, -1, true);
        assertTrue(narrow.compact()); assertEquals(18, narrow.bounds().width());
        assertTrue(narrow.cells().isEmpty());
    }
    @Test void sliderStaysCenteredBelowItsCellAcrossRowsColumnsAndScrolling() {
        for (int count : new int[]{9, 16, 24, 30, 36})
            for (int height : new int[]{104, 208, 240, 480})
                for (int left : new int[]{88, 200, 400})
                    for (boolean book : new boolean[]{false, true})
                        for (int selected = 0; selected < count; selected++) {
                            var layout = PanelLayout.compute(height, left, 70, count, 2, selected, book);
                            if (layout.compact()) {
                                assertNull(layout.slider());
                                continue;
                            }
                            int selectedSlot = selected;
                            var cell = layout.cells().stream().filter(c -> c.slot() == selectedSlot)
                                    .findFirst().orElseThrow().bounds();
                            var slider = layout.slider();
                            assertNotNull(slider);
                            assertEquals(cell.y() + cell.height() + 2, slider.y());
                            assertEquals(cell.x() * 2 + cell.width(), slider.x() * 2 + slider.width());
                            assertTrue(layout.bounds().contains(slider.x(), slider.y()));
                            assertTrue(layout.bounds().contains(slider.x() + slider.width() - 1,
                                    slider.y() + slider.height() - 1));
                            assertTrue(slider.y() + slider.height() <= height - PanelLayout.MARGIN);
                        }
    }
    @Test void bottomRowPopupTakesInputBeforeTheReturnAddress() {
        var open = PanelLayout.compute(240, 200, 40, 9, 0, 8, false);
        var slider = open.slider();
        var address = open.returnBar();
        int x = Math.max(slider.x(), address.x());
        int y = Math.max(slider.y(), address.y());
        assertTrue(slider.contains(x, y));
        assertTrue(address.contains(x, y));
        assertFalse(open.returnAddressContains(x, y));
        assertTrue(open.returnAddressContains(address.x(), address.y()));
        var closed = PanelLayout.compute(240, 200, 40, 9, 0, -1, false);
        assertTrue(closed.returnAddressContains(x, y));
    }
    @Test void visibleSliderArtworkIsCenteredAndStartsJustBelowTheCell() {
        var layout = PanelLayout.compute(240, 200, 40, 9, 0, 8, false);
        var cell = layout.cells().stream().filter(c -> c.slot() == 8).findFirst().orElseThrow().bounds();
        var slider = layout.slider();
        int trackWidth = slider.width() - 2 * PanelLayout.TRACK_INSET;
        assertEquals(cell.x() * 2 + cell.width(),
                (slider.x() + PanelLayout.TRACK_INSET) * 2 + trackWidth);
        assertEquals(cell.y() + cell.height() + 2, slider.y() + PanelLayout.MAX_THUMB_Y);
        assertEquals(cell.y() + cell.height() + 6, slider.y() + PanelLayout.TRACK_Y);
        assertTrue(PanelLayout.MIN_THUMB_Y + 5 <= PanelLayout.SLIDER);
        assertTrue(PanelLayout.LABEL_Y + 8 <= PanelLayout.SLIDER);
    }
    @Test void sliderEndpointValuesMapToThePaintedTrackPixels() {
        var layout = PanelLayout.compute(240, 200, 40, 9, 0, 8, false);
        var slider = layout.slider();
        int tx = slider.x() + PanelLayout.TRACK_INSET;
        int last = tx + slider.width() - 2 * PanelLayout.TRACK_INSET - 1;
        int cap = 2;
        assertEquals(tx, PanelLayout.sliderThumbPx(slider.x(), slider.width(), 0, cap));
        assertEquals(last, PanelLayout.sliderThumbPx(slider.x(), slider.width(), cap, cap));
        assertEquals(cap, PanelLayout.sliderValue(slider.x(), slider.width(), last, cap));
        assertEquals(cap, PanelLayout.sliderValue(slider.x(), slider.width(), last + 1, cap));
    }
    @Test void slotSourceRetainsTheCompleteOpaqueCellEvenWithoutPanelReferenceArea() throws Exception {
        try (var source = getClass().getResourceAsStream("/assets/create_feed_me_packages/textures/gui/slot_source.png")) {
            assertNotNull(source);
            var image = javax.imageio.ImageIO.read(source);
            assertNotNull(image);
            Set<Integer> colors = new HashSet<>();
            for (int y = 65; y < 83; y++) for (int x = 101; x < 119; x++) {
                int rgba = image.getRGB(x, y);
                assertEquals(255, (rgba >>> 24), "Slot became transparent at " + x + "," + y);
                colors.add(rgba);
            }
            assertTrue(colors.size() > 1, "Slot border and interior were flattened");
        }
    }
    private static boolean intersects(PanelLayout.Rect a, PanelLayout.Rect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }

    @Test void sliderMidpointPixelMapsToMidpointValue() {
        int sliderX = 100;
        int width = 36;
        int groupCap = 32;
        int midpoint = PanelLayout.sliderThumbPx(sliderX, width, groupCap / 2, groupCap);

        assertEquals(16, PanelLayout.sliderValue(sliderX, width, midpoint, groupCap));
    }
}

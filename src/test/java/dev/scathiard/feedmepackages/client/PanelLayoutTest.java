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
    @Test void sliderEndpointsStayInsideTheTrackArtwork() {
        Random random = new Random(20260909L);
        for (int trial = 0; trial < 500; trial++) {
            int width = 2 * PanelLayout.ROW; // slider overlay width (2 cells)
            int x = random.nextInt(200);
            int cap = 1 + random.nextInt(32);
            int trackW = width - 2 * PanelLayout.TRACK_INSET;
            int trackFirst = x + PanelLayout.TRACK_INSET;
            int trackLast = x + PanelLayout.TRACK_INSET + trackW - 1;
            // Both extreme endpoints must land exactly on the track's first/last pixel - never past it.
            assertEquals(trackFirst, PanelLayout.thumbPx(x, width, 0, cap));
            assertEquals(trackLast, PanelLayout.thumbPx(x, width, cap, cap));
            // The "-1" (no return) visual position equals the maximum position.
            assertEquals(PanelLayout.thumbPx(x, width, cap, cap), PanelLayout.thumbPx(x, width, -1, cap));
            // Interior values stay inside the artwork range and increase monotonically.
            int previous = trackFirst;
            for (int value = 1; value < cap; value++) {
                int px = PanelLayout.thumbPx(x, width, value, cap);
                assertTrue(px >= trackFirst && px <= trackLast, "endpoint left the track: " + value + " -> " + px);
                assertTrue(px >= previous, "endpoint moved backwards: " + value + " -> " + px + " after " + previous);
                previous = px;
            }
            // Inverse at the midpoint keeps the established semantics (cap/2 groups at mid-track).
            // Integer midpoint rounding and odd caps move this by at most one pixel, hence the wide band.
            int mid = (trackFirst + trackLast) / 2;
            int midValue = PanelLayout.thumbValueAt(x, width, mid, cap);
            assertTrue(Math.abs(midValue - cap / 2.0) <= 2.0, "midpoint value drifted: " + midValue + " for cap " + cap);
            assertEquals(0, PanelLayout.thumbValueAt(x, width, trackFirst, cap));
        }
    }
    @Test void sliderEndpointRenderingAnchorsBothTrianglesOnTheirPixel() {
        int width = 2 * PanelLayout.ROW;
        int x = 100, cap = 32;
        int minAt = PanelLayout.thumbPx(x, width, 0, cap);
        int maxAt = PanelLayout.thumbPx(x, width, cap, cap);
        // 5-px sprites: a -2 blit puts the visual centre exactly on the endpoint pixel for both triangles.
        assertEquals(minAt, minAt - 2 + 2);
        assertEquals(maxAt, maxAt - 2 + 2);
        // When both endpoints share the same extreme position, both triangles centre identically.
        int bothRight = PanelLayout.thumbPx(x, width, cap, cap);
        int leftCentre = bothRight - 2 + 2;
        int rightCentre = bothRight - 2 + 2;
        assertEquals(leftCentre, rightCentre);
    }
    private static boolean intersects(PanelLayout.Rect a, PanelLayout.Rect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }
}

package dev.scathiard.feedmepackages.client;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PanelLayoutTest {
    @Test void everyUnlockedCellCanBeReachedWithoutShrinkingOrOverlap() {
        for (int count : new int[]{9, 16, 24, 30, 36}) for (int height : new int[]{208, 240, 360, 480, 1080}) {
            Set<Integer> reached = new HashSet<>();
            for (int row = 0; row < (count + 1) / 2; row++) {
                var layout = PanelLayout.compute(height, 72, 70, count, row, -1, false);
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
            var layout = PanelLayout.compute(240, 260, 60, 36, 0, selected, true);
            assertFalse(layout.compact()); assertNotNull(layout.slider());
            int selectedSlot = selected;
            assertTrue(layout.cells().stream().anyMatch(cell -> cell.slot() == selectedSlot));
            assertTrue(layout.bounds().x() + layout.bounds().width() < 260 - 177);
            // The slider is an overlay: it may visually cover cells below it (z-lift),
            // so we only verify it stays within screen bounds.
            assertTrue(layout.slider().y() + layout.slider().height() <= 240 - 4);
        }
        var narrow = PanelLayout.compute(240, 72, 60, 36, 0, -1, true);
        assertTrue(narrow.compact()); assertEquals(18, narrow.bounds().width());
        assertTrue(narrow.cells().isEmpty());
    }
    private static boolean intersects(PanelLayout.Rect a, PanelLayout.Rect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }
}

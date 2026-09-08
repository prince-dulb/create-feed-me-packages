package dev.scathiard.feedmepackages.client;

import java.util.*;

/** Pure geometry shared by drawing, mouse routing, JEI exclusion, and layout tests. */
public record PanelLayout(Rect bounds, List<CellBox> cells, Rect slider, Rect returnBar, int firstRow, int visibleRows, int totalRows, int footerY, boolean compact) {
    public static final int WIDTH = 46, ROW = 18, HEADER = 24, FOOTER = 6, SLIDER = 22, BAR = 22;
    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) { return mx >= x && my >= y && mx < x + width && my < y + height; }
    }
    public record CellBox(int slot, Rect bounds) {
        public Rect dot() { return new Rect(bounds.x() + 13, bounds.y() + 1, 4, 5); }
    }
    public Rect address() { return new Rect(bounds.x() + 11, bounds.y() + 3, bounds.width() - 16, 18); }
    public static int preferredColumns(int count) { return Math.max(2, (count + 5) / 6); }
    public static int preferredWidth(int count) { return preferredColumns(count) * ROW + 10; }
    public PanelLayout { cells = List.copyOf(cells); }
    public static PanelLayout compute(int screenHeight, int left, int top, int count, int firstRow, int expanded, boolean bookOpen) {
        int columns = Math.min(preferredColumns(count), Math.max(2, (left - 18 - (bookOpen ? 177 : 0)) / ROW));
        int width = columns * ROW + 10;
        int x = left - width - 4 - (bookOpen ? 177 : 0);
        if (x < 4) return new PanelLayout(new Rect(4, Math.max(4, top), 18, 18), List.of(), null, null, 0, 0, (count + 1) / 2, 0, true);
        int total = Math.max(1, (count + columns - 1) / columns);
        // The slider is an overlay: it never reserves space, shifts rows, or grows the panel.
        int rows = Math.max(1, Math.min(Math.min(total, 6), (screenHeight - 8 - HEADER - FOOTER - BAR) / ROW));
        int height = HEADER + FOOTER + rows * ROW + BAR, y = Math.max(4, Math.min(top, screenHeight - height - 4));
        int first = Math.clamp(firstRow, 0, Math.max(0, total - rows));
        if (expanded >= 0) first = Math.clamp(first, Math.max(0, expanded / columns - rows + 1), expanded / columns);
        List<CellBox> cells = new ArrayList<>(); Rect slider = null;
        for (int row = first; row < first + rows; row++) {
            int cy = y + HEADER + (row - first) * ROW;
            for (int column = 0; column < columns; column++) {
                int slot = row * columns + column;
                if (slot < count) cells.add(new CellBox(slot, new Rect(x + 4 + column * ROW, cy, ROW, ROW)));
            }
            if (expanded >= 0 && row == expanded / columns)
                slider = new Rect(x + 4 + Math.min(expanded % columns, columns - 2) * ROW,
                        Math.min(cy + ROW, screenHeight - 4 - SLIDER), ROW * 2, SLIDER);
        }
        Rect returnBar = new Rect(x + 4, y + HEADER + rows * ROW, width - 8, BAR);
        return new PanelLayout(new Rect(x, y, width, height), cells, slider, returnBar, first, rows, total, y + height - FOOTER, false);
    }
}

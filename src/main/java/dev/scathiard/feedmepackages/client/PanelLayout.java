package dev.scathiard.feedmepackages.client;

import java.util.ArrayList;
import java.util.List;

/** Geometry in GUI pixels; shared by rendering, mouse routing and JEI. */
public record PanelLayout(Rect bounds, List<CellBox> cells, Rect slider, Rect returnBar,
        int firstRow, int visibleRows, int totalRows, int footerY, boolean compact) {
    // panel.png: two 22-pixel end caps surround the 18-pixel inventory cells.
    public static final int ROW = 18, SIDE = 22, WIDTH = 2 * ROW + 2 * SIDE;
    public static final int HEADER = 18, BAR = 18, FOOTER = 24, SLIDER = 19;
    // Geometry of the visible artwork, not only the surrounding popup rectangle.
    public static final int TRACK_INSET = 5, TRACK_Y = 4, MIN_THUMB_Y = 8, MAX_THUMB_Y = 0, LABEL_Y = 11;
    public static final int MARGIN = 4, GAP = 4, BOOK_WIDTH = 177, MAX_ROWS = 6;

    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
    }
    public record CellBox(int slot, Rect bounds) {
        public Rect dot() { return new Rect(bounds.x() + 13, bounds.y() + 1, 4, 5); }
    }
    public PanelLayout { cells = List.copyOf(cells); }
    public Rect address() {
        return new Rect(bounds.x() + 11, bounds.y() + 1, bounds.width() - 22, 13);
    }
    public boolean returnAddressContains(double x, double y) {
        return returnBar != null && returnBar.contains(x, y)
                && (slider == null || !slider.contains(x, y));
    }
    public Rect scrollbar() {
        return new Rect(bounds.x() + bounds.width() - 13, bounds.y() + HEADER,
                2, visibleRows * ROW);
    }
    public static int preferredColumns(int count) {
        return Math.max(2, (Math.max(0, count) + MAX_ROWS - 1) / MAX_ROWS);
    }
    public static int preferredWidth(int count) { return preferredColumns(count) * ROW + 2 * SIDE; }
    /** Offset of the LAST painted track pixel from the track's first pixel. The track is
     *  {@code width - 2*TRACK_INSET} pixels wide, so this is that width minus one. */
    public static int sliderTrackSpan(int width) { return Math.max(1, width - 2 * TRACK_INSET - 1); }

    /** Endpoint pixel for a group value. Zero sits on the track's first pixel, the maximum is clamped
     *  to the LAST painted pixel (never one past it), and -1 (no return) renders at the maximum
     *  position. Rendering and hit-testing share this function. */
    public static int sliderThumbPx(int sliderX, int width, int value, int groupCap) {
        int cap = Math.max(1, groupCap);
        int trackW = Math.max(1, width - 2 * TRACK_INSET);
        int first = sliderX + TRACK_INSET;
        int last = first + trackW - 1;
        int clamped = Math.clamp((long)(value < 0 ? cap : value), 0, cap);
        int raw = first + clamped * trackW / cap;
        return Math.max(first, Math.min(last, raw));
    }

    /** Group value under the mouse: rounds to the NEAREST step so the endpoint follows the cursor.
     *  The last painted pixel always reads as the maximum, so dragging fully right cannot come back
     *  one group short. Flooring here made the endpoint lag behind the cursor and then jump a whole
     *  step (worst at low levels), which is the "not following the mouse" regression from test.57. */
    public static int sliderValue(int sliderX, int width, double x, int groupCap) {
        int cap = Math.max(1, groupCap);
        int trackW = Math.max(1, width - 2 * TRACK_INSET);
        double relative = x - (double)sliderX - TRACK_INSET;
        if (relative >= trackW - 1) return cap;
        return Math.clamp((long)Math.round(relative * (double)cap / trackW), 0, cap);
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }

    public static PanelLayout compute(int screenHeight, int left, int top, int count,
            int firstRow, int expanded, boolean bookOpen) {
        count = Math.max(0, count);
        int book = bookOpen ? BOOK_WIDTH : 0;
        int fittingColumns = (left - book - GAP - MARGIN - 2 * SIDE) / ROW;
        int fittingRows = (screenHeight - 2 * MARGIN - HEADER - BAR - FOOTER) / ROW;
        if (fittingColumns < 2 || fittingRows < 1) {
            int y = Math.max(MARGIN, Math.min(top, screenHeight - MARGIN - ROW));
            return new PanelLayout(new Rect(MARGIN, y, ROW, ROW), List.of(), null, null,
                    0, 0, Math.max(1, (count + 1) / 2), y, true);
        }
        int columns = Math.min(preferredColumns(count), fittingColumns);
        int width = columns * ROW + 2 * SIDE;
        int x = left - book - GAP - width;
        int total = Math.max(1, (count + columns - 1) / columns);
        int rows = Math.min(Math.min(total, MAX_ROWS), fittingRows);
        int height = HEADER + rows * ROW + BAR + FOOTER;
        int y = clamp(top, MARGIN, screenHeight - MARGIN - height);
        int first = clamp(firstRow, 0, total - rows);
        boolean hasSelection = expanded >= 0 && expanded < count;
        if (hasSelection) {
            int selectedRow = expanded / columns;
            first = clamp(first, Math.max(0, selectedRow - rows + 1), Math.min(selectedRow, total - rows));
        }
        List<CellBox> cells = new ArrayList<>();
        Rect slider = null;
        int gridBottom = y + HEADER + rows * ROW;
        for (int row = first; row < first + rows; row++) {
            int cy = y + HEADER + (row - first) * ROW;
            for (int column = 0; column < columns; column++) {
                int slot = row * columns + column;
                if (slot < count) cells.add(new CellBox(slot,
                        new Rect(x + SIDE + column * ROW, cy, ROW, ROW)));
            }
            if (hasSelection && row == expanded / columns) {
                // Keep the popup attached to its cell, including the last row/column.
                // The footer has room for its bottom-row overhang; input goes to the popup first.
                slider = new Rect(x + SIDE + (expanded % columns) * ROW - ROW / 2,
                        cy + ROW + 2, 2 * ROW, SLIDER);
            }
        }
        // The label is already part of the bottom end caps (source rows 99..116).
        Rect returnBar = new Rect(x + 15, gridBottom + 1, width - 32, BAR);
        // Retain the record ABI; footerY now marks the end of the scrollable grid.
        return new PanelLayout(new Rect(x, y, width, height), cells, slider, returnBar,
                first, rows, total, gridBottom, false);
    }
}

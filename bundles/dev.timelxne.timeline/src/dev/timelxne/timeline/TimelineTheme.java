package dev.timelxne.timeline;

import org.eclipse.swt.graphics.RGB;

/** Immutable visual settings. All dimensions are logical SWT pixels. */
public record TimelineTheme(
        RGB background,
        RGB alternateRow,
        RGB foreground,
        RGB grid,
        RGB playhead,
        RGB match,
        int labelWidth,
        int rulerHeight,
        int rowHeight,
        int laneHeight,
        int maxLanes) {

    public TimelineTheme {
        if (labelWidth < 80 || rulerHeight < 16 || rowHeight < 18 || laneHeight < 4 || maxLanes < 1) {
            throw new IllegalArgumentException("Invalid timeline dimensions");
        }
    }

    public static TimelineTheme defaults() {
        return new TimelineTheme(
                new RGB(250, 250, 250), new RGB(244, 246, 248), new RGB(35, 35, 35),
                new RGB(205, 210, 215), new RGB(220, 45, 45), new RGB(255, 193, 7),
                220, 34, 28, 18, 4);
    }
}

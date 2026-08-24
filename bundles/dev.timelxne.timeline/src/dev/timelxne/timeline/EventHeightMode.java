package dev.timelxne.timeline;

/** Controls how events use the vertical space of a track row. */
public enum EventHeightMode {
    /** Events use the compact lane height from the theme. */
    COMPACT_LANES,
    /** Events fill the row; simultaneous events divide it into lanes. */
    FILL_ROW
}

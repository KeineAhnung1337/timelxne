package dev.timelxne.timeline;

/** Geometry used when drawing duration-event bars. */
public record EventBarStyle(int cornerRadius, int borderWidth, int textPadding, int minimumWidth) {
    public EventBarStyle {
        if (cornerRadius < 0) throw new IllegalArgumentException("cornerRadius must not be negative");
        if (borderWidth < 0) throw new IllegalArgumentException("borderWidth must not be negative");
        if (textPadding < 0) throw new IllegalArgumentException("textPadding must not be negative");
        if (minimumWidth < 1) throw new IllegalArgumentException("minimumWidth must be at least 1");
    }

    public static EventBarStyle defaults() {
        return new EventBarStyle(5, 1, 4, 2);
    }
}

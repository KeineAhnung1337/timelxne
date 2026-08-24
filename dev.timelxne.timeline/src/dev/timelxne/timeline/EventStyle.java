package dev.timelxne.timeline;

import org.eclipse.swt.graphics.RGB;

/** Device-independent event colors. SWT Color resources remain owned by the widget. */
public record EventStyle(RGB fill, RGB border, RGB foreground) {
    public static EventStyle defaults() {
        return new EventStyle(new RGB(66, 133, 244), new RGB(35, 88, 180), new RGB(255, 255, 255));
    }
}

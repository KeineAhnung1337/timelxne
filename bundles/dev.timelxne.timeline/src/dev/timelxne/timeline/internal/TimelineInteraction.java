package dev.timelxne.timeline.internal;

import org.eclipse.swt.graphics.Rectangle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Device-independent interaction calculations shared by the SWT widget and tests. */
public final class TimelineInteraction {
    private TimelineInteraction() {
    }

    public static boolean exceedsHorizontalDragThreshold(int startX, int currentX, int threshold) {
        return Math.abs((long) currentX - startX) >= threshold;
    }

    public static int opacityToAlpha(double opacity) {
        if (!Double.isFinite(opacity)) opacity = 1.0;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, opacity)) * 255.0);
    }

    public static Rectangle clip(Rectangle bounds, Rectangle clippingArea) {
        return bounds.intersection(clippingArea);
    }

    /** Returns topmost-first values from paint order, keeping only the topmost value per stable ID. */
    public static <T> List<T> distinctFrontToBack(List<T> paintOrder, Function<T, String> idProvider) {
        List<T> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = paintOrder.size() - 1; i >= 0; i--) {
            T value = paintOrder.get(i);
            if (seen.add(idProvider.apply(value))) result.add(value);
        }
        return List.copyOf(result);
    }
}

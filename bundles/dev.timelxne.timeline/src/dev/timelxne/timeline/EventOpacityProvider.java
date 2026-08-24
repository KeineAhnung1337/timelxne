package dev.timelxne.timeline;

/** Supplies event-fill opacity without changing border, text, or selection opacity. */
@FunctionalInterface
public interface EventOpacityProvider {
    /** Returns an opacity from {@code 0.0} (transparent) to {@code 1.0} (opaque). */
    double opacityFor(TimelineEvent event);
}

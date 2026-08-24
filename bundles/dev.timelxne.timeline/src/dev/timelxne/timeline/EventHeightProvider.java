package dev.timelxne.timeline;

/** Supplies the fraction of an event's row or overlap lane that should be occupied vertically. */
@FunctionalInterface
public interface EventHeightProvider {
    /**
     * Returns a value from {@code 0.0} to {@code 1.0}. Values outside that range are clamped.
     * The event is vertically centered in its row or lane.
     */
    double heightFractionFor(TimelineEvent event);
}

package dev.timelxne.timeline;

/** Supplies duration-bar geometry for an event. */
@FunctionalInterface
public interface EventBarStyleProvider {
    EventBarStyle styleFor(TimelineEvent event);
}

package dev.timelxne.timeline;

/** Programmatic event filter that is combined with the timeline's text search. */
@FunctionalInterface
public interface EventFilter {
    boolean includes(TimelineEvent event);
}

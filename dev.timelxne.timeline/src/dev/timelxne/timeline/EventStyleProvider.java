package dev.timelxne.timeline;

@FunctionalInterface
public interface EventStyleProvider {
    EventStyle styleFor(TimelineEvent event);
}

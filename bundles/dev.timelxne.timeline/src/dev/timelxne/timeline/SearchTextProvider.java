package dev.timelxne.timeline;

@FunctionalInterface
public interface SearchTextProvider {
    String searchableText(TimelineEvent event);
}

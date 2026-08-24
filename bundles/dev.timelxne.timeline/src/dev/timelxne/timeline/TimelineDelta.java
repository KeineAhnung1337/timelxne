package dev.timelxne.timeline;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Batched additions/updates and removals. Upserts replace objects with the same stable ID. */
public record TimelineDelta(
        List<TimelineTrack> trackUpserts,
        Set<String> trackRemovals,
        List<TimelineEvent> eventUpserts,
        Set<String> eventRemovals) {

    public TimelineDelta {
        trackUpserts = List.copyOf(Objects.requireNonNull(trackUpserts, "trackUpserts"));
        trackRemovals = Set.copyOf(Objects.requireNonNull(trackRemovals, "trackRemovals"));
        eventUpserts = List.copyOf(Objects.requireNonNull(eventUpserts, "eventUpserts"));
        eventRemovals = Set.copyOf(Objects.requireNonNull(eventRemovals, "eventRemovals"));
    }

    public static TimelineDelta ofEvents(Collection<TimelineEvent> upserts, Collection<String> removals) {
        return new TimelineDelta(List.of(), Set.of(), List.copyOf(upserts), Set.copyOf(removals));
    }
}

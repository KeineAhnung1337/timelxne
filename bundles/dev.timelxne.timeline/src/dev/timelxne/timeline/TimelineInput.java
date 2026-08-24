package dev.timelxne.timeline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable snapshot supplied to a {@link Timeline}. */
public record TimelineInput(
        List<TimelineTrack> tracks,
        List<TimelineEvent> events,
        Instant rangeStart,
        Instant rangeEnd) {

    public TimelineInput {
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if ((rangeStart == null) != (rangeEnd == null)) {
            throw new IllegalArgumentException("rangeStart and rangeEnd must both be set or both be null");
        }
        if (rangeStart != null && !rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException("rangeEnd must be after rangeStart");
        }
    }

    public TimelineInput(List<TimelineTrack> tracks, List<TimelineEvent> events) {
        this(tracks, events, null, null);
    }

    public static TimelineInput empty() {
        return new TimelineInput(List.of(), List.of());
    }
}

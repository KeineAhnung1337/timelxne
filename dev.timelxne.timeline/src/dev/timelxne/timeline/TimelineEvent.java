package dev.timelxne.timeline;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** An instant event (no end) or an interval event. */
public record TimelineEvent(
        String id,
        String trackId,
        String label,
        Instant start,
        Instant end,
        Object payload) {

    public TimelineEvent {
        id = requireText(id, "id");
        trackId = requireText(trackId, "trackId");
        label = Objects.requireNonNull(label, "label");
        start = Objects.requireNonNull(start, "start");
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start for event " + id);
        }
    }

    public TimelineEvent(String id, String trackId, String label, Instant start) {
        this(id, trackId, label, start, null, null);
    }

    public TimelineEvent(String id, String trackId, String label, Instant start, Instant end) {
        this(id, trackId, label, start, end, null);
    }

    public boolean isInstant() {
        return end == null || end.equals(start);
    }

    public Optional<Instant> optionalEnd() {
        return Optional.ofNullable(end);
    }

    public Duration duration() {
        return isInstant() ? Duration.ZERO : Duration.between(start, end);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

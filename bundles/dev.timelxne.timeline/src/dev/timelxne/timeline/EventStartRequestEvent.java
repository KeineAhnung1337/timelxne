package dev.timelxne.timeline;

import java.time.Instant;
import java.util.Objects;

/** Request emitted by the selected-event action or an unambiguous event double-click. */
public record EventStartRequestEvent(Timeline source, TimelineEvent event, Instant time) {
    public EventStartRequestEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(time, "time");
    }
}

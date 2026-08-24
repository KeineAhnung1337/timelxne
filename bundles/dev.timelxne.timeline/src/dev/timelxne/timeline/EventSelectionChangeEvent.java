package dev.timelxne.timeline;

import java.util.Objects;
import java.util.Optional;

/** Describes a user- or application-driven change to the selected timeline event. */
public record EventSelectionChangeEvent(Timeline source, Optional<TimelineEvent> selectedEvent) {
    public EventSelectionChangeEvent {
        Objects.requireNonNull(source, "source");
        selectedEvent = Objects.requireNonNull(selectedEvent, "selectedEvent");
    }
}

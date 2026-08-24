package dev.timelxne.timeline;

import java.util.Objects;

/** A row in the timeline's hierarchical track tree. */
public record TimelineTrack(
        String id,
        String parentId,
        String label,
        boolean initiallyExpanded,
        boolean initiallyVisible) {

    public TimelineTrack {
        id = requireText(id, "id");
        label = Objects.requireNonNull(label, "label");
        if (parentId != null && parentId.equals(id)) {
            throw new IllegalArgumentException("A track cannot be its own parent: " + id);
        }
    }

    public TimelineTrack(String id, String label) {
        this(id, null, label, true, true);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

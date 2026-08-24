package dev.timelxne.timeline;

import java.time.Instant;

public record ReplayTimeChangeEvent(Timeline source, Instant time) {
}

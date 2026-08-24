package dev.timelxne.timeline.internal;

import dev.timelxne.timeline.TimelineEvent;
import dev.timelxne.timeline.TimelineInput;
import dev.timelxne.timeline.TimelineTrack;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertFalse;

/** Deterministic 100k-event indexing/query smoke benchmark. */
public class PreparedTimelinePerformanceTest {
    @Test(timeout = 5_000)
    public void preparesAndQueriesOneHundredThousandEvents() {
        Instant origin = Instant.parse("2026-01-01T00:00:00Z");
        List<TimelineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < 100; i++) tracks.add(new TimelineTrack("track-" + i, "Track " + i));
        List<TimelineEvent> events = new ArrayList<>(100_000);
        Random random = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            Instant start = origin.plusMillis(random.nextLong(86_400_000));
            events.add(new TimelineEvent("event-" + i, "track-" + random.nextInt(100), "Event " + i,
                    start, start.plusMillis(1 + random.nextLong(60_000))));
        }
        PreparedTimeline prepared = PreparedTimeline.prepare(new TimelineInput(tracks, events), TimelineEvent::label);
        assertFalse(prepared.query("track-0", origin.plusSeconds(3600), origin.plusSeconds(3660), "").isEmpty());
    }
}

package dev.timelxne.timeline.internal;

import dev.timelxne.timeline.EventBarStyle;
import dev.timelxne.timeline.TimelineEvent;
import dev.timelxne.timeline.TimelineInput;
import dev.timelxne.timeline.TimelineTrack;
import org.junit.Test;
import org.eclipse.swt.graphics.Rectangle;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PreparedTimelineTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    public void queriesInstantAndDurationEventsAtViewportBoundary() {
        TimelineTrack track = new TimelineTrack("track", "Track");
        TimelineEvent longEvent = new TimelineEvent("long", "track", "Long operation", T0, T0.plusSeconds(100));
        TimelineEvent instant = new TimelineEvent("instant", "track", "Marker", T0.plusSeconds(50));
        PreparedTimeline prepared = PreparedTimeline.prepare(
                new TimelineInput(List.of(track), List.of(longEvent, instant)), TimelineEvent::label);

        List<PreparedTimeline.EventEntry> result = prepared.query(
                "track", T0.plusSeconds(40), T0.plusSeconds(60), "");

        assertEquals(List.of("long", "instant"), result.stream().map(entry -> entry.event().id()).toList());
    }

    @Test
    public void searchIsCaseInsensitiveAndRetainsAncestorContext() {
        TimelineTrack parent = new TimelineTrack("parent", null, "Parent", true, true);
        TimelineTrack child = new TimelineTrack("child", "parent", "Child", true, true);
        TimelineEvent event = new TimelineEvent("event", "child", "Connection FAILED", T0);
        PreparedTimeline prepared = PreparedTimeline.prepare(
                new TimelineInput(List.of(parent, child), List.of(event)), TimelineEvent::label);

        assertTrue(prepared.trackOrDescendantMatches("parent", "failed"));
        assertEquals(1, prepared.query("child", T0.minusSeconds(1), T0.plusSeconds(1), "FaIlEd").size());
    }

    @Test
    public void stableLanesSeparateOverlappingEvents() {
        TimelineTrack track = new TimelineTrack("track", "Track");
        TimelineEvent a = new TimelineEvent("a", "track", "A", T0, T0.plusSeconds(10));
        TimelineEvent b = new TimelineEvent("b", "track", "B", T0.plusSeconds(5), T0.plusSeconds(15));
        PreparedTimeline prepared = PreparedTimeline.prepare(
                new TimelineInput(List.of(track), List.of(a, b)), TimelineEvent::label);

        List<PreparedTimeline.EventEntry> entries = prepared.query("track", T0, T0.plusSeconds(20), "");
        assertEquals(0, entries.get(0).lane());
        assertEquals(1, entries.get(1).lane());
    }

    @Test
    public void simultaneousInstantEventsReceiveSeparateRootDepths() {
        TimelineTrack root = new TimelineTrack("root", null, "Root", true, true);
        TimelineTrack first = new TimelineTrack("first", "root", "First", true, true);
        TimelineTrack second = new TimelineTrack("second", "root", "Second", true, true);
        PreparedTimeline prepared = PreparedTimeline.prepare(new TimelineInput(
                List.of(root, first, second),
                List.of(new TimelineEvent("a", "first", "A", T0),
                        new TimelineEvent("b", "second", "B", T0))), TimelineEvent::label);

        List<PreparedTimeline.EventEntry> entries = prepared.queryAggregate(
                "root", T0.minusNanos(1), T0.plusNanos(2), "", Set.of("root", "first", "second"));

        assertEquals(List.of(0, 1), entries.stream().map(PreparedTimeline.EventEntry::lane).toList());
    }

    @Test
    public void dragThresholdUsesHorizontalDistanceAndIncludesBoundary() {
        assertTrue(!TimelineInteraction.exceedsHorizontalDragThreshold(100, 104, 5));
        assertTrue(TimelineInteraction.exceedsHorizontalDragThreshold(100, 105, 5));
        assertTrue(TimelineInteraction.exceedsHorizontalDragThreshold(100, 94, 5));
    }

    @Test
    public void opacityAndPlotClippingClampToSupportedBounds() {
        assertEquals(0, TimelineInteraction.opacityToAlpha(-1.0));
        assertEquals(128, TimelineInteraction.opacityToAlpha(0.5));
        assertEquals(255, TimelineInteraction.opacityToAlpha(2.0));
        assertEquals(255, TimelineInteraction.opacityToAlpha(Double.NaN));
        assertEquals(new Rectangle(220, 10, 80, 20), TimelineInteraction.clip(
                new Rectangle(100, 10, 200, 20), new Rectangle(220, 0, 100, 100)));
    }

    @Test
    public void overlapCandidatesAreTopmostFirstAndDistinctByStableId() {
        record Painted(String id, int occurrence) {}
        List<Painted> result = TimelineInteraction.distinctFrontToBack(List.of(
                new Painted("outer", 1), new Painted("inner", 1),
                new Painted("outer", 2), new Painted("front", 1)), Painted::id);

        assertEquals(List.of("front", "outer", "inner"), result.stream().map(Painted::id).toList());
        assertEquals(2, result.get(1).occurrence());
    }

    @Test
    public void separateRootsOnlyAggregateTheirOwnDescendants() {
        TimelineTrack application = new TimelineTrack("application", null, "Application", true, true);
        TimelineTrack job = new TimelineTrack("job", "application", "Job", true, true);
        TimelineTrack infrastructure = new TimelineTrack("infrastructure", null, "Infrastructure", true, true);
        TimelineTrack database = new TimelineTrack("database", "infrastructure", "Database", true, true);
        TimelineEvent appEvent = new TimelineEvent("app-event", "job", "App", T0, T0.plusSeconds(10));
        TimelineEvent infraEvent = new TimelineEvent("infra-event", "database", "Infra", T0, T0.plusSeconds(10));
        PreparedTimeline prepared = PreparedTimeline.prepare(new TimelineInput(
                List.of(application, job, infrastructure, database), List.of(appEvent, infraEvent)),
                TimelineEvent::label);
        Set<String> visible = Set.of("application", "job", "infrastructure", "database");

        assertEquals(List.of("app-event"), prepared.queryAggregate(
                "application", T0, T0.plusSeconds(20), "", visible).stream()
                .map(entry -> entry.event().id()).toList());
        assertEquals(List.of("infra-event"), prepared.queryAggregate(
                "infrastructure", T0, T0.plusSeconds(20), "", visible).stream()
                .map(entry -> entry.event().id()).toList());
    }

    @Test
    public void rootAggregateIncludesVisibleDescendantsAndReassignsLanes() {
        TimelineTrack root = new TimelineTrack("root", null, "Application", true, true);
        TimelineTrack first = new TimelineTrack("first", "root", "First", true, true);
        TimelineTrack second = new TimelineTrack("second", "root", "Second", true, true);
        TimelineEvent a = new TimelineEvent("a", "first", "A", T0, T0.plusSeconds(10));
        TimelineEvent b = new TimelineEvent("b", "second", "B", T0.plusSeconds(5), T0.plusSeconds(15));
        PreparedTimeline prepared = PreparedTimeline.prepare(
                new TimelineInput(List.of(root, first, second), List.of(a, b)), TimelineEvent::label);

        List<PreparedTimeline.EventEntry> all = prepared.queryAggregate(
                "root", T0, T0.plusSeconds(20), "", Set.of("root", "first", "second"));
        assertEquals(List.of("a", "b"), all.stream().map(entry -> entry.event().id()).toList());
        assertEquals(List.of(0, 1), all.stream().map(PreparedTimeline.EventEntry::lane).toList());

        List<PreparedTimeline.EventEntry> hidden = prepared.queryAggregate(
                "root", T0, T0.plusSeconds(20), "", Set.of("root", "first"));
        assertEquals(List.of("a"), hidden.stream().map(entry -> entry.event().id()).toList());
    }

    @Test
    public void programmaticFilterRemovesEventsAndCompactsLanes() {
        TimelineTrack track = new TimelineTrack("track", "Track");
        TimelineEvent excluded = new TimelineEvent("excluded", "track", "Excluded", T0, T0.plusSeconds(20));
        TimelineEvent included = new TimelineEvent("included", "track", "Included", T0.plusSeconds(5), T0.plusSeconds(10));
        PreparedTimeline prepared = PreparedTimeline.prepare(
                new TimelineInput(List.of(track), List.of(excluded, included)), TimelineEvent::label);

        List<PreparedTimeline.EventEntry> entries = prepared.query(
                "track", T0, T0.plusSeconds(30), "", event -> event.id().equals("included"));

        assertEquals(List.of("included"), entries.stream().map(entry -> entry.event().id()).toList());
        assertEquals(0, entries.getFirst().lane());
    }

    @Test
    public void eventBarStyleValidatesGeometry() {
        assertEquals(new EventBarStyle(5, 1, 4, 2), EventBarStyle.defaults());
        assertThrows(IllegalArgumentException.class, () -> new EventBarStyle(-1, 1, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new EventBarStyle(1, -1, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new EventBarStyle(1, 1, 4, 0));
    }

    @Test
    public void rejectsDuplicateEventsMissingTracksAndCycles() {
        TimelineTrack track = new TimelineTrack("track", "Track");
        TimelineEvent event = new TimelineEvent("same", "track", "A", T0);
        assertThrows(IllegalArgumentException.class, () -> PreparedTimeline.prepare(
                new TimelineInput(List.of(track), List.of(event, event)), TimelineEvent::label));
        assertThrows(IllegalArgumentException.class, () -> PreparedTimeline.prepare(
                new TimelineInput(List.of(track), List.of(new TimelineEvent("x", "missing", "X", T0))),
                TimelineEvent::label));

        TimelineTrack a = new TimelineTrack("a", "b", "A", true, true);
        TimelineTrack b = new TimelineTrack("b", "a", "B", true, true);
        assertThrows(IllegalArgumentException.class, () -> PreparedTimeline.prepare(
                new TimelineInput(List.of(a, b), List.of()), TimelineEvent::label));
    }
}

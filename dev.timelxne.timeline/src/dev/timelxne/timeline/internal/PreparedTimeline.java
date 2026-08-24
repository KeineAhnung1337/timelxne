package dev.timelxne.timeline.internal;

import dev.timelxne.timeline.SearchTextProvider;
import dev.timelxne.timeline.EventFilter;
import dev.timelxne.timeline.TimelineEvent;
import dev.timelxne.timeline.TimelineInput;
import dev.timelxne.timeline.TimelineTrack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Background-prepared, device-independent timeline state. */
public final class PreparedTimeline {
    private static final Duration DEFAULT_RANGE = Duration.ofHours(1);

    public record EventEntry(TimelineEvent event, int lane, String searchText) {
        Instant effectiveEnd() {
            return event.isInstant() ? event.start() : event.end();
        }
    }

    private record ActiveLane(Instant end, int lane) {
    }

    private final TimelineInput source;
    private final Map<String, TimelineTrack> tracks;
    private final Map<String, List<String>> children;
    private final List<String> roots;
    private final Map<String, TrackIndex> indexes;
    private final Instant rangeStart;
    private final Instant rangeEnd;

    private PreparedTimeline(TimelineInput source, Map<String, TimelineTrack> tracks,
            Map<String, List<String>> children, List<String> roots, Map<String, TrackIndex> indexes,
            Instant rangeStart, Instant rangeEnd) {
        this.source = source;
        this.tracks = tracks;
        this.children = children;
        this.roots = roots;
        this.indexes = indexes;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
    }

    public static PreparedTimeline prepare(TimelineInput input, SearchTextProvider searchProvider) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(searchProvider, "searchProvider");

        Map<String, TimelineTrack> tracks = new LinkedHashMap<>();
        for (TimelineTrack track : input.tracks()) {
            if (tracks.putIfAbsent(track.id(), track) != null) {
                throw new IllegalArgumentException("Duplicate track id: " + track.id());
            }
        }
        Map<String, List<String>> children = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (TimelineTrack track : tracks.values()) {
            if (track.parentId() == null) {
                roots.add(track.id());
            } else {
                if (!tracks.containsKey(track.parentId())) {
                    throw new IllegalArgumentException("Missing parent track " + track.parentId() + " for " + track.id());
                }
                children.computeIfAbsent(track.parentId(), ignored -> new ArrayList<>()).add(track.id());
            }
        }
        rejectCycles(tracks, children, roots);

        Map<String, List<TimelineEvent>> grouped = new HashMap<>();
        Set<String> eventIds = new HashSet<>();
        Instant first = null;
        Instant last = null;
        for (TimelineEvent event : input.events()) {
            if (!eventIds.add(event.id())) throw new IllegalArgumentException("Duplicate event id: " + event.id());
            if (!tracks.containsKey(event.trackId())) {
                throw new IllegalArgumentException("Missing track " + event.trackId() + " for event " + event.id());
            }
            grouped.computeIfAbsent(event.trackId(), ignored -> new ArrayList<>()).add(event);
            first = first == null || event.start().isBefore(first) ? event.start() : first;
            Instant effectiveEnd = event.isInstant() ? event.start() : event.end();
            last = last == null || effectiveEnd.isAfter(last) ? effectiveEnd : last;
        }

        Instant rangeStart = input.rangeStart();
        Instant rangeEnd = input.rangeEnd();
        if (rangeStart == null) {
            rangeStart = first == null ? Instant.EPOCH : first;
            rangeEnd = last == null ? rangeStart.plus(DEFAULT_RANGE) : last;
            if (!rangeEnd.isAfter(rangeStart)) rangeEnd = rangeStart.plusSeconds(1);
        }

        Map<String, TrackIndex> indexes = new HashMap<>();
        for (TimelineTrack track : tracks.values()) {
            indexes.put(track.id(), TrackIndex.build(grouped.getOrDefault(track.id(), List.of()), searchProvider));
        }
        return new PreparedTimeline(input, Map.copyOf(tracks), copyLists(children), List.copyOf(roots),
                Map.copyOf(indexes), rangeStart, rangeEnd);
    }

    private static void rejectCycles(Map<String, TimelineTrack> tracks, Map<String, List<String>> children,
            List<String> roots) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (visited.add(id)) queue.addAll(children.getOrDefault(id, List.of()));
        }
        if (visited.size() != tracks.size()) {
            Set<String> cycle = new HashSet<>(tracks.keySet());
            cycle.removeAll(visited);
            throw new IllegalArgumentException("Track hierarchy contains a cycle: " + cycle);
        }
    }

    private static Map<String, List<String>> copyLists(Map<String, List<String>> source) {
        Map<String, List<String>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    public TimelineInput source() { return source; }
    public Collection<TimelineTrack> tracks() { return tracks.values(); }
    public TimelineTrack track(String id) { return tracks.get(id); }
    public List<String> roots() { return roots; }
    public List<String> children(String id) { return children.getOrDefault(id, List.of()); }
    public Instant rangeStart() { return rangeStart; }
    public Instant rangeEnd() { return rangeEnd; }

    public List<EventEntry> query(String trackId, Instant start, Instant end, String filter) {
        TrackIndex index = indexes.get(trackId);
        return index == null ? List.of() : index.query(start, end, normalize(filter));
    }

    public List<EventEntry> query(String trackId, Instant start, Instant end, String filter,
            EventFilter eventFilter) {
        return filterAndRelane(query(trackId, start, end, filter), eventFilter);
    }

    /** Queries a track and all its descendants, omitting hidden tracks and assigning lanes globally. */
    public List<EventEntry> queryAggregate(String trackId, Instant start, Instant end, String filter,
            Set<String> visibleTrackIds) {
        return queryAggregate(trackId, start, end, filter, visibleTrackIds, ignored -> true);
    }

    public List<EventEntry> queryAggregate(String trackId, Instant start, Instant end, String filter,
            Set<String> visibleTrackIds, EventFilter eventFilter) {
        String normalized = normalize(filter);
        Map<String, EventEntry> unique = new LinkedHashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(trackId);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (visibleTrackIds.contains(id)) {
                TrackIndex index = indexes.get(id);
                if (index != null) {
                    for (EventEntry entry : index.query(start, end, normalized)) {
                        unique.putIfAbsent(entry.event().id(), entry);
                    }
                }
            }
            pending.addAll(children(id));
        }
        return filterAndRelane(new ArrayList<>(unique.values()), eventFilter);
    }

    private static List<EventEntry> filterAndRelane(List<EventEntry> source, EventFilter eventFilter) {
        Objects.requireNonNull(eventFilter, "eventFilter");
        List<EventEntry> included = new ArrayList<>(source.size());
        for (EventEntry entry : source) {
            if (eventFilter.includes(entry.event())) included.add(entry);
        }
        return assignLanes(included);
    }

    private static List<EventEntry> assignLanes(List<EventEntry> source) {
        source.sort(Comparator.comparing((EventEntry entry) -> entry.event().start())
                .thenComparing(entry -> entry.event().id()));
        PriorityQueue<ActiveLane> active = new PriorityQueue<>(Comparator.comparing(ActiveLane::end));
        PriorityQueue<Integer> free = new PriorityQueue<>();
        List<EventEntry> result = new ArrayList<>(source.size());
        int nextLane = 0;
        for (EventEntry entry : source) {
            while (!active.isEmpty() && !active.peek().end().isAfter(entry.event().start())) {
                free.add(active.remove().lane());
            }
            int lane = free.isEmpty() ? nextLane++ : free.remove();
            Instant laneEnd = entry.event().isInstant()
                    ? entry.event().start().plusNanos(1) : entry.effectiveEnd();
            active.add(new ActiveLane(laneEnd, lane));
            result.add(new EventEntry(entry.event(), lane, entry.searchText()));
        }
        return List.copyOf(result);
    }

    public boolean trackOrDescendantMatches(String trackId, String filter) {
        String normalized = normalize(filter);
        if (normalized.isEmpty()) return true;
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(trackId);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            TimelineTrack track = tracks.get(id);
            if (track.label().toLowerCase(Locale.ROOT).contains(normalized)) return true;
            TrackIndex index = indexes.get(id);
            if (index != null && index.hasMatch(normalized)) return true;
            pending.addAll(children(id));
        }
        return false;
    }

    /** Returns direct matches and every ancestor needed to keep the hierarchy understandable. */
    public Set<String> matchingTrackIds(String filter) {
        String normalized = normalize(filter);
        if (normalized.isEmpty()) return tracks.keySet();
        Set<String> result = new HashSet<>();
        for (TimelineTrack track : tracks.values()) {
            TrackIndex index = indexes.get(track.id());
            if (track.label().toLowerCase(Locale.ROOT).contains(normalized)
                    || (index != null && index.hasMatch(normalized))) {
                TimelineTrack current = track;
                while (current != null && result.add(current.id())) {
                    current = current.parentId() == null ? null : tracks.get(current.parentId());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static final class TrackIndex {
        private final List<EventEntry> entries;
        private final Instant[] prefixMaxEnd;

        private TrackIndex(List<EventEntry> entries, Instant[] prefixMaxEnd) {
            this.entries = entries;
            this.prefixMaxEnd = prefixMaxEnd;
        }

        static TrackIndex build(List<TimelineEvent> source, SearchTextProvider searchProvider) {
            List<TimelineEvent> sorted = new ArrayList<>(source);
            sorted.sort(Comparator.comparing(TimelineEvent::start).thenComparing(TimelineEvent::id));
            PriorityQueue<ActiveLane> active = new PriorityQueue<>(Comparator.comparing(ActiveLane::end));
            PriorityQueue<Integer> free = new PriorityQueue<>();
            List<EventEntry> entries = new ArrayList<>(sorted.size());
            Instant[] prefix = new Instant[sorted.size()];
            Instant maxEnd = Instant.MIN;
            int nextLane = 0;
            for (int i = 0; i < sorted.size(); i++) {
                TimelineEvent event = sorted.get(i);
                while (!active.isEmpty() && !active.peek().end().isAfter(event.start())) {
                    free.add(active.remove().lane());
                }
                int lane = free.isEmpty() ? nextLane++ : free.remove();
                Instant effectiveEnd = event.isInstant() ? event.start() : event.end();
                Instant laneEnd = event.isInstant() ? event.start().plusNanos(1) : effectiveEnd;
                active.add(new ActiveLane(laneEnd, lane));
                String text = Objects.toString(searchProvider.searchableText(event), "")
                        .toLowerCase(Locale.ROOT);
                entries.add(new EventEntry(event, lane, text));
                if (effectiveEnd.isAfter(maxEnd)) maxEnd = effectiveEnd;
                prefix[i] = maxEnd;
            }
            return new TrackIndex(List.copyOf(entries), prefix);
        }

        List<EventEntry> query(Instant start, Instant end, String filter) {
            int upper = upperBound(end);
            int lower = upper;
            while (lower > 0 && !prefixMaxEnd[lower - 1].isBefore(start)) lower--;
            if (lower == upper) return List.of();
            List<EventEntry> result = new ArrayList<>();
            for (int i = lower; i < upper; i++) {
                EventEntry entry = entries.get(i);
                if (!entry.effectiveEnd().isBefore(start)
                        && (filter.isEmpty() || entry.searchText().contains(filter))) {
                    result.add(entry);
                }
            }
            return result;
        }

        boolean hasMatch(String filter) {
            for (EventEntry entry : entries) if (entry.searchText().contains(filter)) return true;
            return false;
        }

        private int upperBound(Instant time) {
            int low = 0, high = entries.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (entries.get(mid).event().start().isBefore(time)) low = mid + 1;
                else high = mid;
            }
            return low;
        }
    }
}

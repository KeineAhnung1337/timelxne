package dev.timelxne.timeline;

import dev.timelxne.timeline.internal.PreparedTimeline;
import dev.timelxne.timeline.internal.PreparedTimeline.EventEntry;
import dev.timelxne.timeline.internal.TimelineInteraction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseWheelListener;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hierarchical, zoomable SWT timeline. Widget mutators follow normal SWT display-thread rules,
 * except {@link #postReplayTime(Instant, RevealPolicy)}, which is explicitly thread-safe.
 */
public final class Timeline extends Composite {
    private static final int SCROLL_UNITS = 10_000;
    private static final double MIN_VISIBLE_SECONDS = 0.001;

    private final ExecutorService prepareExecutor;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<PendingReplay> pendingReplay = new AtomicReference<>();
    private final AtomicBoolean replayDispatchQueued = new AtomicBoolean();
    private final List<ReplayTimeListener> replayListeners = new ArrayList<>();
    private final List<EventStartRequestListener> eventStartRequestListeners = new ArrayList<>();
    private final List<EventSelectionListener> eventSelectionListeners = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> visible = new HashSet<>();
    private final Text search;
    private final TimelineCanvas canvas;
    private final Composite details;
    private final Label detailsTitle;
    private final Label detailsMetadata;
    private final Label detailsBody;
    private final Button detailsJump;
    private final ScrolledComposite detailsCandidateScroller;
    private final Composite detailsCandidates;

    private volatile boolean disposed;
    private TimelineInput currentInput = TimelineInput.empty();
    private PreparedTimeline prepared;
    private TimelineTheme theme = TimelineTheme.defaults();
    private EventStyleProvider styleProvider = ignored -> EventStyle.defaults();
    private EventBarStyleProvider eventBarStyleProvider = ignored -> EventBarStyle.defaults();
    private EventHeightProvider eventHeightProvider = ignored -> 1.0;
    private EventOpacityProvider eventOpacityProvider = ignored -> 1.0;
    private EventFilter eventFilter = ignored -> true;
    private TooltipProvider tooltipProvider = this::defaultTooltip;
    private SearchTextProvider searchTextProvider = event -> event.label() + " " + event.id();
    private ZoneId zoneId = ZoneId.systemDefault();
    private DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private EventHeightMode eventHeightMode = EventHeightMode.FILL_ROW;
    private RowSizingMode rowSizingMode = RowSizingMode.FILL_AVAILABLE;
    private int zoomSelectionModifier = SWT.CTRL;
    private int playheadDragThreshold = 5;
    private int hoverDelayMillis = 250;
    private boolean rootAggregationEnabled = true;
    private boolean eventTextVisible = true;
    private boolean hoverGuideVisible = true;
    private boolean detailsExpanded;
    private Instant visibleStart = Instant.EPOCH;
    private Instant visibleEnd = Instant.EPOCH.plusSeconds(3600);
    private Instant replayTime;
    private TimelineEvent selectedEvent;
    private List<TimelineEvent> detailCandidateEvents = List.of();

    public Timeline(Composite parent, int style) {
        super(parent, style);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "swt-timeline-prepare");
            thread.setDaemon(true);
            return thread;
        };
        prepareExecutor = Executors.newSingleThreadExecutor(factory);
        setLayout(new GridLayout(1, false));

        Composite controls = new Composite(this, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout controlLayout = new GridLayout(2, false);
        controlLayout.marginHeight = 0;
        controlLayout.marginWidth = 0;
        controls.setLayout(controlLayout);

        search = new Text(controls, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        search.setMessage("Search events");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ToolBar toolbar = new ToolBar(controls, SWT.FLAT);
        addAction(toolbar, "Expand all", "+", ignored -> expandAll());
        addAction(toolbar, "Collapse all", "−", ignored -> collapseAll());
        addAction(toolbar, "Show all", "Show", ignored -> showAll());
        addAction(toolbar, "Hide all", "Hide", ignored -> hideAll());
        addAction(toolbar, "Fit all", "Fit", ignored -> fitAll());
        addAction(toolbar, "Fit matches", "Fit matches", ignored -> fitMatches());

        canvas = new TimelineCanvas(this);
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        details = new Composite(this, SWT.BORDER);
        GridData detailsData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        detailsData.exclude = true;
        details.setLayoutData(detailsData);
        GridLayout detailsLayout = new GridLayout(2, false);
        detailsLayout.marginWidth = 10;
        detailsLayout.marginHeight = 8;
        details.setLayout(detailsLayout);
        detailsTitle = new Label(details, SWT.NONE);
        detailsTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button closeDetails = new Button(details, SWT.PUSH);
        closeDetails.setText("Close");
        closeDetails.addListener(SWT.Selection, ignored -> setDetailsExpanded(false));
        detailsCandidateScroller = new ScrolledComposite(details, SWT.V_SCROLL | SWT.BORDER);
        GridData candidateScrollerData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        candidateScrollerData.heightHint = 150;
        candidateScrollerData.exclude = true;
        detailsCandidateScroller.setLayoutData(candidateScrollerData);
        detailsCandidateScroller.setExpandHorizontal(true);
        detailsCandidateScroller.setExpandVertical(true);
        detailsCandidates = new Composite(detailsCandidateScroller, SWT.NONE);
        GridLayout candidatesLayout = new GridLayout(2, false);
        candidatesLayout.marginWidth = 6;
        candidatesLayout.marginHeight = 6;
        detailsCandidates.setLayout(candidatesLayout);
        detailsCandidateScroller.setContent(detailsCandidates);
        detailsMetadata = new Label(details, SWT.WRAP);
        detailsMetadata.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        detailsBody = new Label(details, SWT.WRAP);
        detailsBody.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        detailsJump = new Button(details, SWT.PUSH);
        detailsJump.setText("Set time to event start");
        detailsJump.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
        detailsJump.addListener(SWT.Selection, ignored -> {
            if (selectedEvent != null) fireEventStartRequest(selectedEvent);
        });
        search.addModifyListener(event -> canvas.invalidateStatic());
        addDisposeListener(event -> disposeTimeline());
    }

    private static void addAction(ToolBar bar, String tooltip, String text, Listener listener) {
        ToolItem item = new ToolItem(bar, SWT.PUSH);
        item.setText(text);
        item.setToolTipText(tooltip);
        item.addListener(SWT.Selection, listener);
    }

    /** Replaces the complete model and asynchronously prepares its indexes. */
    public CompletableFuture<Void> setInput(TimelineInput input) {
        checkWidget();
        currentInput = Objects.requireNonNull(input, "input");
        return schedulePrepare(input, true);
    }

    /** Applies a batch by stable ID and asynchronously rebuilds affected device-independent state. */
    public CompletableFuture<Void> applyDelta(TimelineDelta delta) {
        checkWidget();
        Objects.requireNonNull(delta, "delta");
        Map<String, TimelineTrack> tracks = new LinkedHashMap<>();
        for (TimelineTrack track : currentInput.tracks()) tracks.put(track.id(), track);
        delta.trackRemovals().forEach(tracks::remove);
        delta.trackUpserts().forEach(track -> tracks.put(track.id(), track));

        Map<String, TimelineEvent> events = new LinkedHashMap<>();
        for (TimelineEvent event : currentInput.events()) events.put(event.id(), event);
        delta.eventRemovals().forEach(events::remove);
        events.values().removeIf(event -> !tracks.containsKey(event.trackId()));
        delta.eventUpserts().forEach(event -> events.put(event.id(), event));
        currentInput = new TimelineInput(List.copyOf(tracks.values()), List.copyOf(events.values()),
                currentInput.rangeStart(), currentInput.rangeEnd());
        return schedulePrepare(currentInput, false);
    }

    private CompletableFuture<Void> schedulePrepare(TimelineInput input, boolean resetUiState) {
        long expectedGeneration = generation.incrementAndGet();
        SearchTextProvider provider = searchTextProvider;
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> PreparedTimeline.prepare(input, provider), prepareExecutor)
                .whenComplete((value, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    Display display = getDisplay();
                    display.asyncExec(() -> {
                        if (disposed || isDisposed() || expectedGeneration != generation.get()) {
                            result.cancel(false);
                            return;
                        }
                        install(value, resetUiState);
                        result.complete(null);
                    });
                });
        return result;
    }

    private void install(PreparedTimeline value, boolean resetUiState) {
        Set<String> previousTrackIds = prepared == null ? Set.of()
                : prepared.tracks().stream().map(TimelineTrack::id).collect(java.util.stream.Collectors.toSet());
        prepared = value;
        detailCandidateEvents = List.of();
        String previousSelectionId = selectedEvent == null ? null : selectedEvent.id();
        if (selectedEvent != null) {
            String selectedId = selectedEvent.id();
            selectedEvent = value.source().events().stream()
                    .filter(event -> event.id().equals(selectedId)).findFirst().orElse(null);
        }
        if (resetUiState) {
            expanded.clear();
            visible.clear();
            for (TimelineTrack track : value.tracks()) {
                if (track.initiallyExpanded()) expanded.add(track.id());
                if (track.initiallyVisible()) visible.add(track.id());
            }
        } else {
            for (TimelineTrack track : value.tracks()) {
                if (!previousTrackIds.contains(track.id())) {
                    if (track.initiallyVisible()) visible.add(track.id());
                    if (track.initiallyExpanded()) expanded.add(track.id());
                }
            }
            visible.retainAll(value.tracks().stream().map(TimelineTrack::id).toList());
            expanded.retainAll(value.tracks().stream().map(TimelineTrack::id).toList());
        }
        if (resetUiState) {
            visibleStart = value.rangeStart();
            visibleEnd = value.rangeEnd();
            canvas.verticalOffset = 0;
        } else {
            clampVisibleRange();
        }
        if (previousSelectionId != null && selectedEvent == null) {
            detailsExpanded = false;
            fireEventSelectionChange();
        }
        updateDetails();
        setDetailsExpanded(detailsExpanded);
        canvas.closeTransientUi();
        canvas.invalidateStatic();
    }

    public TimelineInput getInput() { checkWidget(); return currentInput; }

    public void setReplayTime(Instant time) { setReplayTime(time, RevealPolicy.KEEP_VIEWPORT); }

    public void setReplayTime(Instant time, RevealPolicy policy) {
        checkWidget();
        setReplayTimeInternal(Objects.requireNonNull(time, "time"), Objects.requireNonNull(policy, "policy"));
    }

    /** Coalesces calls from arbitrary threads to one display update per SWT event-loop cycle. */
    public void postReplayTime(Instant time, RevealPolicy policy) {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(policy, "policy");
        if (disposed) return;
        pendingReplay.set(new PendingReplay(time, policy));
        if (replayDispatchQueued.compareAndSet(false, true)) {
            getDisplay().asyncExec(() -> {
                replayDispatchQueued.set(false);
                PendingReplay pending = pendingReplay.getAndSet(null);
                if (!disposed && !isDisposed() && pending != null) {
                    setReplayTimeInternal(pending.time(), pending.policy());
                }
            });
        }
    }

    public Instant getReplayTime() { checkWidget(); return replayTime; }

    private void setReplayTimeInternal(Instant time, RevealPolicy policy) {
        int oldX = replayTime == null ? Integer.MIN_VALUE : canvas.timeToX(replayTime);
        replayTime = time;
        if (policy == RevealPolicy.CENTER || (policy == RevealPolicy.REVEAL_IF_OUTSIDE
                && (time.isBefore(visibleStart) || time.isAfter(visibleEnd)))) {
            double span = secondsBetween(visibleStart, visibleEnd);
            visibleStart = addSeconds(time, -span / 2);
            visibleEnd = addSeconds(time, span / 2);
            clampVisibleRange();
            canvas.invalidateStatic();
        } else {
            int newX = canvas.timeToX(time);
            if (oldX != Integer.MIN_VALUE) canvas.redraw(Math.min(oldX, newX) - 2, 0, Math.abs(newX - oldX) + 5, canvas.getSize().y, false);
            else canvas.redraw();
        }
    }

    public void setVisibleRange(Instant start, Instant end) {
        checkWidget();
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) throw new IllegalArgumentException("end must be after start");
        visibleStart = start;
        visibleEnd = end;
        clampVisibleRange();
        canvas.invalidateStatic();
    }

    public Instant getVisibleStart() { checkWidget(); return visibleStart; }
    public Instant getVisibleEnd() { checkWidget(); return visibleEnd; }
    public TimelineEvent getSelectedEvent() { checkWidget(); return selectedEvent; }
    public void clearEventSelection() { checkWidget(); selectEventInternal(null, false); }

    /** Selects an event from the current input by stable ID. */
    public boolean selectEvent(String eventId) {
        checkWidget();
        Objects.requireNonNull(eventId, "eventId");
        TimelineEvent event = currentInput.events().stream()
                .filter(candidate -> candidate.id().equals(eventId)).findFirst().orElse(null);
        if (event == null) return false;
        selectEventInternal(event, true);
        return true;
    }

    public void addEventSelectionListener(EventSelectionListener listener) {
        checkWidget();
        eventSelectionListeners.add(Objects.requireNonNull(listener));
    }
    public void removeEventSelectionListener(EventSelectionListener listener) {
        checkWidget();
        eventSelectionListeners.remove(listener);
    }

    public void fitAll() {
        checkWidget();
        if (prepared == null) return;
        visibleStart = prepared.rangeStart();
        visibleEnd = prepared.rangeEnd();
        canvas.invalidateStatic();
    }

    public void fitMatches() {
        checkWidget();
        if (prepared == null || search.getText().isBlank()) { fitAll(); return; }
        Instant first = null, last = null;
        for (TimelineTrack track : prepared.tracks()) {
            for (EventEntry entry : prepared.query(track.id(), prepared.rangeStart(), prepared.rangeEnd(),
                    search.getText(), eventFilter)) {
                TimelineEvent event = entry.event();
                first = first == null || event.start().isBefore(first) ? event.start() : first;
                Instant end = event.isInstant() ? event.start() : event.end();
                last = last == null || end.isAfter(last) ? end : last;
            }
        }
        if (first != null) {
            if (!last.isAfter(first)) last = first.plusSeconds(1);
            double padding = Math.max(0.5, secondsBetween(first, last) * 0.05);
            setVisibleRange(addSeconds(first, -padding), addSeconds(last, padding));
        }
    }

    public void expandAll() { checkWidget(); if (prepared != null) prepared.tracks().forEach(track -> expanded.add(track.id())); canvas.invalidateStatic(); }
    public void collapseAll() { checkWidget(); expanded.clear(); canvas.invalidateStatic(); }
    public void showAll() { checkWidget(); if (prepared != null) prepared.tracks().forEach(track -> visible.add(track.id())); canvas.invalidateStatic(); }
    public void hideAll() { checkWidget(); visible.clear(); canvas.invalidateStatic(); }

    public void setTrackVisible(String trackId, boolean show) {
        checkWidget();
        if (show) visible.add(trackId); else visible.remove(trackId);
        canvas.invalidateStatic();
    }

    public void setTrackExpanded(String trackId, boolean expand) {
        checkWidget();
        if (expand) expanded.add(trackId); else expanded.remove(trackId);
        canvas.invalidateStatic();
    }

    public void setFilterText(String value) { checkWidget(); search.setText(Objects.requireNonNull(value)); }
    public String getFilterText() { checkWidget(); return search.getText(); }

    public void addReplayTimeListener(ReplayTimeListener listener) { checkWidget(); replayListeners.add(Objects.requireNonNull(listener)); }
    public void removeReplayTimeListener(ReplayTimeListener listener) { checkWidget(); replayListeners.remove(listener); }

    public void addEventStartRequestListener(EventStartRequestListener listener) {
        checkWidget();
        eventStartRequestListeners.add(Objects.requireNonNull(listener));
        updateDetails();
    }
    public void removeEventStartRequestListener(EventStartRequestListener listener) {
        checkWidget();
        eventStartRequestListeners.remove(listener);
        updateDetails();
    }

    public void setDetailsExpanded(boolean expanded) {
        checkWidget();
        detailsExpanded = expanded && (selectedEvent != null || !detailCandidateEvents.isEmpty());
        GridData data = (GridData) details.getLayoutData();
        data.exclude = !detailsExpanded;
        details.setVisible(detailsExpanded);
        layout(true, true);
    }
    public boolean isDetailsExpanded() { checkWidget(); return detailsExpanded; }

    public void setTheme(TimelineTheme value) { checkWidget(); theme = Objects.requireNonNull(value); canvas.resetColors(); canvas.invalidateStatic(); }
    public TimelineTheme getTheme() { checkWidget(); return theme; }
    public void setEventStyleProvider(EventStyleProvider value) { checkWidget(); styleProvider = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public void setEventBarStyle(EventBarStyle value) {
        checkWidget();
        EventBarStyle fixed = Objects.requireNonNull(value);
        eventBarStyleProvider = ignored -> fixed;
        canvas.invalidateStatic();
    }
    public void setEventBarStyleProvider(EventBarStyleProvider value) { checkWidget(); eventBarStyleProvider = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public EventBarStyleProvider getEventBarStyleProvider() { checkWidget(); return eventBarStyleProvider; }
    public void setEventTextVisible(boolean value) { checkWidget(); eventTextVisible = value; canvas.invalidateStatic(); }
    public boolean isEventTextVisible() { checkWidget(); return eventTextVisible; }
    public void setEventHeightProvider(EventHeightProvider value) { checkWidget(); eventHeightProvider = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public EventHeightProvider getEventHeightProvider() { checkWidget(); return eventHeightProvider; }
    public void setEventOpacity(double opacity) {
        checkWidget();
        if (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("opacity must be between 0.0 and 1.0");
        }
        eventOpacityProvider = ignored -> opacity;
        canvas.invalidateStatic();
    }
    public void setEventOpacityProvider(EventOpacityProvider value) { checkWidget(); eventOpacityProvider = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public EventOpacityProvider getEventOpacityProvider() { checkWidget(); return eventOpacityProvider; }
    public void setEventFilter(EventFilter value) { checkWidget(); eventFilter = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public EventFilter getEventFilter() { checkWidget(); return eventFilter; }
    public void setTooltipProvider(TooltipProvider value) {
        checkWidget();
        tooltipProvider = Objects.requireNonNull(value);
        canvas.closeTransientUi();
        updateDetails();
    }
    public void setSearchTextProvider(SearchTextProvider value) {
        checkWidget();
        searchTextProvider = Objects.requireNonNull(value);
        schedulePrepare(currentInput, false);
    }
    public void setTimeZone(ZoneId value) { checkWidget(); zoneId = Objects.requireNonNull(value); canvas.closeTransientUi(); updateDetails(); canvas.invalidateStatic(); }
    public void setTimeFormatter(DateTimeFormatter value) { checkWidget(); timeFormatter = Objects.requireNonNull(value); canvas.closeTransientUi(); updateDetails(); canvas.invalidateStatic(); }
    public void setEventHeightMode(EventHeightMode value) { checkWidget(); eventHeightMode = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public EventHeightMode getEventHeightMode() { checkWidget(); return eventHeightMode; }
    public void setRowSizingMode(RowSizingMode value) { checkWidget(); rowSizingMode = Objects.requireNonNull(value); canvas.invalidateStatic(); }
    public RowSizingMode getRowSizingMode() { checkWidget(); return rowSizingMode; }
    public void setRootAggregationEnabled(boolean value) { checkWidget(); rootAggregationEnabled = value; canvas.invalidateStatic(); }
    public boolean isRootAggregationEnabled() { checkWidget(); return rootAggregationEnabled; }

    /** Sets the SWT state-mask required for selection zoom, for example {@link SWT#CTRL}. */
    public void setZoomSelectionModifier(int modifierMask) {
        checkWidget();
        int allowed = SWT.CTRL | SWT.SHIFT | SWT.ALT | SWT.COMMAND;
        if (modifierMask == 0 || (modifierMask & ~allowed) != 0) {
            throw new IllegalArgumentException("modifierMask must contain only SWT modifier constants");
        }
        zoomSelectionModifier = modifierMask;
    }
    public int getZoomSelectionModifier() { checkWidget(); return zoomSelectionModifier; }
    public void setPlayheadDragThreshold(int pixels) {
        checkWidget();
        if (pixels < 0) throw new IllegalArgumentException("pixels must not be negative");
        playheadDragThreshold = pixels;
    }
    public int getPlayheadDragThreshold() { checkWidget(); return playheadDragThreshold; }
    public void setHoverDelayMillis(int milliseconds) {
        checkWidget();
        if (milliseconds < 0) throw new IllegalArgumentException("milliseconds must not be negative");
        hoverDelayMillis = milliseconds;
    }
    public int getHoverDelayMillis() { checkWidget(); return hoverDelayMillis; }
    public void setHoverGuideVisible(boolean visible) {
        checkWidget();
        hoverGuideVisible = visible;
        canvas.redraw();
    }
    public boolean isHoverGuideVisible() { checkWidget(); return hoverGuideVisible; }

    private TooltipContent defaultTooltip(TimelineEvent event) {
        return new TooltipContent(event.label(), "");
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        return "Duration: " + seconds / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
    }

    private void fireUserReplayChange(Instant time) {
        ReplayTimeChangeEvent event = new ReplayTimeChangeEvent(this, time);
        for (ReplayTimeListener listener : List.copyOf(replayListeners)) listener.replayTimeChanged(event);
    }

    private void fireEventStartRequest(TimelineEvent timelineEvent) {
        EventStartRequestEvent event = new EventStartRequestEvent(this, timelineEvent, timelineEvent.start());
        for (EventStartRequestListener listener : List.copyOf(eventStartRequestListeners)) {
            listener.eventStartRequested(event);
        }
    }

    private void selectEventInternal(TimelineEvent event, boolean expandDetails) {
        String oldId = selectedEvent == null ? null : selectedEvent.id();
        String newId = event == null ? null : event.id();
        selectedEvent = event;
        detailCandidateEvents = List.of();
        if (event == null) detailsExpanded = false;
        else if (expandDetails) detailsExpanded = true;
        updateDetails();
        setDetailsExpanded(detailsExpanded);
        canvas.invalidateStatic();
        if (!Objects.equals(oldId, newId)) fireEventSelectionChange();
    }

    private void showEventCandidates(List<TimelineEvent> candidates) {
        String oldId = selectedEvent == null ? null : selectedEvent.id();
        selectedEvent = null;
        detailCandidateEvents = List.copyOf(candidates);
        detailsExpanded = !detailCandidateEvents.isEmpty();
        updateDetails();
        setDetailsExpanded(detailsExpanded);
        canvas.invalidateStatic();
        if (oldId != null) fireEventSelectionChange();
    }

    private void fireEventSelectionChange() {
        EventSelectionChangeEvent event = new EventSelectionChangeEvent(this, Optional.ofNullable(selectedEvent));
        for (EventSelectionListener listener : List.copyOf(eventSelectionListeners)) {
            listener.eventSelectionChanged(event);
        }
    }

    private void updateDetails() {
        if (details == null || details.isDisposed()) return;
        for (Control child : detailsCandidates.getChildren()) child.dispose();
        boolean choosing = !detailCandidateEvents.isEmpty();
        setDetailControlVisible(detailsCandidateScroller, choosing);
        setDetailControlVisible(detailsMetadata, !choosing);
        setDetailControlVisible(detailsBody, !choosing);
        setDetailControlVisible(detailsJump, !choosing);
        if (choosing) {
            detailsTitle.setText("Choose from " + detailCandidateEvents.size() + " overlapping events");
            GridData scrollerData = (GridData) detailsCandidateScroller.getLayoutData();
            scrollerData.heightHint = Math.min(180, Math.max(48, detailCandidateEvents.size() * 34 + 12));
            for (TimelineEvent candidate : detailCandidateEvents) {
                Label swatch = new Label(detailsCandidates, SWT.NONE);
                EventStyle candidateStyle = Objects.requireNonNullElse(
                        styleProvider.styleFor(candidate), EventStyle.defaults());
                swatch.setBackground(canvas.color(candidateStyle.fill()));
                swatch.setLayoutData(new GridData(14, 14));
                Button choice = new Button(detailsCandidates, SWT.PUSH | SWT.LEFT);
                choice.setText(candidate.label() + "  ·  " + trackPath(candidate.trackId())
                        + "  ·  " + formatEventTime(candidate));
                choice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                choice.addListener(SWT.MouseEnter, ignored -> canvas.setHoverEvent(candidate));
                swatch.addListener(SWT.MouseEnter, ignored -> canvas.setHoverEvent(candidate));
                choice.addListener(SWT.MouseExit, ignored -> canvas.setHoverEvent(null));
                swatch.addListener(SWT.MouseExit, ignored -> canvas.setHoverEvent(null));
                choice.addListener(SWT.Selection, ignored -> selectEventInternal(candidate, true));
            }
            Point minimum = detailsCandidates.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            detailsCandidateScroller.setMinSize(minimum);
            detailsCandidates.layout(true, true);
            details.layout(true, true);
            return;
        }
        if (selectedEvent == null) {
            detailsTitle.setText("");
            detailsMetadata.setText("");
            detailsBody.setText("");
            detailsJump.setEnabled(false);
            return;
        }
        TooltipContent content = tooltipProvider.tooltipFor(selectedEvent);
        detailsTitle.setText(content == null ? selectedEvent.label() : Objects.toString(content.title(), ""));
        detailsMetadata.setText(trackPath(selectedEvent.trackId()) + "  ·  " + formatEventTime(selectedEvent));
        detailsBody.setText(content == null ? "" : Objects.toString(content.body(), ""));
        detailsJump.setEnabled(!eventStartRequestListeners.isEmpty());
        details.layout(true, true);
    }

    private static void setDetailControlVisible(Control control, boolean visible) {
        GridData data = (GridData) control.getLayoutData();
        data.exclude = !visible;
        control.setVisible(visible);
    }

    private String formatEventTime(TimelineEvent event) {
        DateTimeFormatter formatter = timeFormatter.withZone(zoneId);
        if (event.isInstant()) return formatter.format(event.start());
        return formatter.format(event.start()) + " — " + formatter.format(event.end())
                + "  ·  " + formatDuration(event.duration());
    }

    private String trackPath(String trackId) {
        Map<String, TimelineTrack> sourceTracks = new HashMap<>();
        for (TimelineTrack sourceTrack : currentInput.tracks()) sourceTracks.put(sourceTrack.id(), sourceTrack);
        List<String> labels = new ArrayList<>();
        TimelineTrack track = prepared == null ? sourceTracks.get(trackId) : prepared.track(trackId);
        if (track == null) track = sourceTracks.get(trackId);
        while (track != null) {
            labels.add(0, track.label());
            track = track.parentId() == null ? null : prepared == null
                    ? sourceTracks.get(track.parentId())
                    : Objects.requireNonNullElse(prepared.track(track.parentId()), sourceTracks.get(track.parentId()));
        }
        return labels.isEmpty() ? trackId : String.join(" › ", labels);
    }

    private void clampVisibleRange() {
        if (prepared == null) return;
        double span = Math.max(MIN_VISIBLE_SECONDS, secondsBetween(visibleStart, visibleEnd));
        double total = secondsBetween(prepared.rangeStart(), prepared.rangeEnd());
        if (span >= total) {
            visibleStart = prepared.rangeStart();
            visibleEnd = prepared.rangeEnd();
        } else if (visibleStart.isBefore(prepared.rangeStart())) {
            visibleStart = prepared.rangeStart();
            visibleEnd = addSeconds(visibleStart, span);
        } else if (visibleEnd.isAfter(prepared.rangeEnd())) {
            visibleEnd = prepared.rangeEnd();
            visibleStart = addSeconds(visibleEnd, -span);
        }
    }

    private void disposeTimeline() {
        disposed = true;
        generation.incrementAndGet();
        prepareExecutor.shutdownNow();
    }

    @Override
    protected void checkSubclass() {
        // This is an intentional SWT custom widget.
    }

    private record PendingReplay(Instant time, RevealPolicy policy) {}
    private record VisibleTrack(TimelineTrack track, int depth) {}
    private record EventHit(Rectangle bounds, TimelineEvent event, String paintedTrackId, boolean rootAggregate) {}
    private record RootPlacement(int depth, int overlapCount) {}

    private final class TimelineCanvas extends Canvas implements MouseListener, MouseMoveListener, MouseWheelListener {
        private final Map<RGB, Color> colors = new HashMap<>();
        private final List<EventHit> hits = new ArrayList<>();
        private final Map<String, RootPlacement> rootPlacements = new HashMap<>();
        private Image staticBuffer;
        private boolean staticDirty = true;
        private int verticalOffset;
        private int dragStartX;
        private boolean draggingPlayhead;
        private boolean pendingScrub;
        private boolean pressInRuler;
        private EventHit pressedEvent;
        private boolean selectingZoom;
        private int selectionStartX;
        private int selectionCurrentX;
        private Shell transientTooltip;
        private TimelineEvent hoverEvent;
        private int hoverX = -1;
        private int hoverY = -1;
        private long hoverGeneration;

        TimelineCanvas(Composite parent) {
            super(parent, SWT.DOUBLE_BUFFERED | SWT.H_SCROLL | SWT.V_SCROLL | SWT.NO_BACKGROUND);
            addPaintListener(event -> paint(event.gc));
            addMouseListener(this);
            addMouseMoveListener(this);
            addMouseWheelListener(this);
            addListener(SWT.MouseExit, event -> clearHover());
            addListener(SWT.KeyDown, this::keyDown);
            addListener(SWT.Resize, event -> invalidateStatic());
            getVerticalBar().addListener(SWT.Selection, event -> {
                verticalOffset = getVerticalBar().getSelection();
                invalidateStatic();
            });
            getHorizontalBar().addListener(SWT.Selection, event -> scrollFromBar());
            addDisposeListener(event -> disposeResources());
        }

        void invalidateStatic() {
            if (isDisposed()) return;
            staticDirty = true;
            redraw();
        }

        void resetColors() {
            colors.values().forEach(Color::dispose);
            colors.clear();
        }

        private Color color(RGB rgb) {
            return colors.computeIfAbsent(rgb, key -> new Color(getDisplay(), key));
        }

        private void paint(GC target) {
            Rectangle area = getClientArea();
            if (area.width <= 0 || area.height <= 0) return;
            if (staticBuffer == null || staticBuffer.isDisposed()
                    || !staticBuffer.getBounds().equals(new Rectangle(0, 0, area.width, area.height))) {
                if (staticBuffer != null) staticBuffer.dispose();
                staticBuffer = new Image(getDisplay(), area.width, area.height);
                staticDirty = true;
            }
            if (staticDirty) {
                GC gc = new GC(staticBuffer);
                try { drawStatic(gc, area); } finally { gc.dispose(); }
                staticDirty = false;
            }
            target.drawImage(staticBuffer, 0, 0);
            drawHoverGuide(target, area);
            drawPlayhead(target, area);
            drawZoomSelection(target, area);
        }

        private void drawStatic(GC gc, Rectangle area) {
            hits.clear();
            gc.setAntialias(SWT.ON);
            gc.setBackground(color(theme.background()));
            gc.fillRectangle(area);
            if (prepared == null) {
                gc.setForeground(color(theme.foreground()));
                gc.drawText("No timeline data", 12, 12, true);
                return;
            }
            List<VisibleTrack> rows = visibleTracks();
            prepareRootPlacements();
            int rowHeight = effectiveRowHeight(rows.size());
            configureVerticalBar(rows.size() * rowHeight, Math.max(1, area.height - theme.rulerHeight()), rowHeight);
            configureHorizontalBar();
            drawRuler(gc, area);

            int top = theme.rulerHeight() - verticalOffset;
            for (int row = 0; row < rows.size(); row++) {
                int y = top + row * rowHeight;
                if (y + rowHeight < theme.rulerHeight() || y > area.height) continue;
                drawRow(gc, area, rows.get(row), row, y, rowHeight);
            }
            gc.setForeground(color(theme.grid()));
            gc.drawLine(theme.labelWidth(), 0, theme.labelWidth(), area.height);
        }

        private void drawRuler(GC gc, Rectangle area) {
            gc.setClipping(0, 0, area.width, theme.rulerHeight());
            gc.setBackground(color(theme.alternateRow()));
            gc.fillRectangle(0, 0, area.width, theme.rulerHeight());
            gc.setForeground(color(theme.foreground()));
            gc.drawText("Event type", 8, 8, true);
            DateTimeFormatter formatter = timeFormatter.withZone(zoneId);
            int left = theme.labelWidth();
            int right = Math.max(left, area.width - 1);
            int bottom = theme.rulerHeight() - 1;
            gc.setForeground(color(theme.grid()));
            gc.drawLine(left, bottom, right, bottom);
            gc.drawLine(left, bottom - 8, left, bottom);
            gc.drawLine(right, bottom - 8, right, bottom);
            gc.setForeground(color(theme.foreground()));
            String startLabel = formatter.format(visibleStart);
            String endLabel = formatter.format(visibleEnd);
            int plotWidth = Math.max(1, right - left);
            gc.setClipping(left + 4, 0, Math.max(1, plotWidth / 2 - 6), bottom);
            gc.drawText(startLabel, left + 4, 2, true);
            Point endExtent = gc.textExtent(endLabel);
            gc.setClipping(left + plotWidth / 2 + 2, 0, Math.max(1, plotWidth / 2 - 6), bottom);
            gc.drawText(endLabel, right - endExtent.x - 4, 2, true);
            gc.setClipping((Rectangle) null);
        }

        private void drawRow(GC gc, Rectangle area, VisibleTrack row, int rowIndex, int y, int rowHeight) {
            gc.setBackground(color((rowIndex & 1) == 0 ? theme.background() : theme.alternateRow()));
            gc.fillRectangle(0, y, area.width, rowHeight);
            gc.setForeground(color(theme.grid()));
            gc.drawLine(0, y + rowHeight - 1, area.width, y + rowHeight - 1);

            TimelineTrack track = row.track();
            int indent = 8 + row.depth() * 16;
            if (!prepared.children(track.id()).isEmpty()) {
                gc.setForeground(color(theme.foreground()));
                String disclosure = expanded.contains(track.id()) ? "▾" : "▸";
                gc.drawText(disclosure, indent, y + 5, true);
            }
            gc.setForeground(color(theme.foreground()));
            gc.drawText(visible.contains(track.id()) ? "●" : "○", indent + 16, y + 5, true);
            gc.drawText(track.label(), indent + 34, y + 5, true);

            Rectangle previousClip = gc.getClipping();
            Rectangle plotClip = TimelineInteraction.clip(new Rectangle(theme.labelWidth(), y,
                    Math.max(0, area.width - theme.labelWidth()), rowHeight), area);
            gc.setClipping(plotClip);

            String filter = search.getText();
            List<EventEntry> entries;
            if (!visible.contains(track.id())) {
                entries = List.of();
            } else if (rootAggregationEnabled && track.parentId() == null) {
                entries = prepared.queryAggregate(track.id(), visibleStart, visibleEnd, filter, visible, eventFilter);
            } else {
                entries = prepared.query(track.id(), visibleStart, visibleEnd, filter, eventFilter);
            }
            int laneCount = Math.max(1, Math.min(theme.maxLanes(),
                    entries.stream().mapToInt(entry -> entry.lane() + 1).max().orElse(1)));
            int fillLaneHeight = Math.max(3, (rowHeight - 6) / laneCount);
            int aggregate = 0;
            List<EventEntry> paintOrder = entries.stream()
                    .sorted(java.util.Comparator.comparingInt(entry -> {
                        RootPlacement placement = rootPlacements.get(entry.event().id());
                        return placement == null ? entry.lane() : placement.depth();
                    })).toList();
            for (EventEntry entry : paintOrder) {
                RootPlacement placement = rootPlacements.get(entry.event().id());
                if (placement == null && entry.lane() >= theme.maxLanes()) { aggregate++; continue; }
                int eventY;
                int eventHeight;
                if (placement != null) {
                    int availableHeight = eventHeightMode == EventHeightMode.FILL_ROW
                            ? Math.max(2, rowHeight - 8) : Math.max(6, theme.laneHeight() - 4);
                    double stackFraction = placement.overlapCount() <= 1 ? 1.0
                            : 1.0 - placement.depth() * 0.66 / (placement.overlapCount() - 1);
                    eventHeight = Math.max(2, (int) Math.round(availableHeight * stackFraction));
                    eventY = y + (rowHeight - eventHeight) / 2;
                } else {
                    int laneHeight = eventHeightMode == EventHeightMode.FILL_ROW ? fillLaneHeight : theme.laneHeight();
                    eventY = y + 3 + entry.lane() * laneHeight;
                    eventHeight = eventHeightMode == EventHeightMode.FILL_ROW
                            ? Math.max(2, laneHeight - 2) : Math.max(6, laneHeight - 4);
                }
                double fraction = placement != null && placement.overlapCount() > 1
                        ? 1.0 : eventHeightProvider.heightFractionFor(entry.event());
                if (!Double.isFinite(fraction)) fraction = 1.0;
                fraction = Math.max(0.0, Math.min(1.0, fraction));
                int fractionalHeight = Math.max(2, (int) Math.round(eventHeight * fraction));
                int centeredY = eventY + (eventHeight - fractionalHeight) / 2;
                drawEvent(gc, entry, centeredY, fractionalHeight, track);
            }
            if (aggregate > 0) {
                String text = "+" + aggregate;
                gc.setBackground(color(theme.match()));
                gc.setForeground(color(theme.foreground()));
                gc.fillRoundRectangle(area.width - 48, y + rowHeight - 20, 40, 16, 6, 6);
                gc.drawText(text, area.width - 43, y + rowHeight - 19, true);
            }
            gc.setClipping(previousClip);
        }

        private void prepareRootPlacements() {
            rootPlacements.clear();
            if (!rootAggregationEnabled) return;
            for (String rootId : prepared.roots()) {
                List<EventEntry> entries = prepared.queryAggregate(
                        rootId, visibleStart, visibleEnd, search.getText(), visible, eventFilter);
                List<EventEntry> group = new ArrayList<>();
                Instant groupEnd = null;
                for (EventEntry entry : entries) {
                    Instant start = entry.event().start();
                    if (groupEnd != null && !start.isBefore(groupEnd)) {
                        finishRootOverlapGroup(group);
                        group.clear();
                        groupEnd = null;
                    }
                    group.add(entry);
                    Instant end = entry.event().isInstant()
                            ? entry.event().start().plusNanos(1) : entry.event().end();
                    if (groupEnd == null || end.isAfter(groupEnd)) groupEnd = end;
                }
                finishRootOverlapGroup(group);
            }
        }

        private void finishRootOverlapGroup(List<EventEntry> group) {
            int overlapCount = group.stream().mapToInt(entry -> entry.lane() + 1).max().orElse(1);
            for (EventEntry entry : group) {
                rootPlacements.put(entry.event().id(), new RootPlacement(entry.lane(), overlapCount));
            }
        }

        private void drawEvent(GC gc, EventEntry entry, int y, int height, TimelineTrack paintedTrack) {
            TimelineEvent event = entry.event();
            EventStyle style = Objects.requireNonNullElse(styleProvider.styleFor(event), EventStyle.defaults());
            EventBarStyle barStyle = Objects.requireNonNullElse(
                    eventBarStyleProvider.styleFor(event), EventBarStyle.defaults());
            double opacity = eventOpacityProvider.opacityFor(event);
            int fillAlpha = TimelineInteraction.opacityToAlpha(opacity);
            int startX = timeToX(event.start());
            Rectangle bounds;
            int[] eventPolygon = null;
            int eventArc = 0;
            if (event.isInstant()) {
                int radius = Math.max(4, height / 2);
                int[] diamond = { startX, y, startX + radius, y + radius, startX, y + 2 * radius, startX - radius, y + radius };
                eventPolygon = diamond;
                gc.setBackground(color(style.fill()));
                gc.setAlpha(fillAlpha);
                gc.fillPolygon(diamond);
                gc.setAlpha(255);
                gc.setForeground(color(style.border()));
                if (barStyle.borderWidth() > 0) {
                    gc.setLineWidth(barStyle.borderWidth());
                    gc.drawPolygon(diamond);
                    gc.setLineWidth(1);
                }
                bounds = new Rectangle(startX - radius, y, radius * 2 + 1, radius * 2 + 1);
            } else {
                int endX = timeToX(event.end());
                int width = Math.max(barStyle.minimumWidth(), endX - startX);
                bounds = new Rectangle(startX, y, width, height);
                gc.setBackground(color(style.fill()));
                eventArc = Math.min(Math.min(bounds.width, bounds.height), barStyle.cornerRadius() * 2);
                gc.setAlpha(fillAlpha);
                if (eventArc == 0) gc.fillRectangle(bounds);
                else gc.fillRoundRectangle(bounds.x, bounds.y, bounds.width, bounds.height, eventArc, eventArc);
                gc.setAlpha(255);
                if (barStyle.borderWidth() > 0) {
                    gc.setForeground(color(style.border()));
                    gc.setLineWidth(barStyle.borderWidth());
                    if (eventArc == 0) gc.drawRectangle(bounds);
                    else gc.drawRoundRectangle(bounds.x, bounds.y, bounds.width, bounds.height, eventArc, eventArc);
                    gc.setLineWidth(1);
                }
                if (eventTextVisible && width > barStyle.textPadding() * 2 + 20) {
                    gc.setForeground(color(style.foreground()));
                    Rectangle rowClip = gc.getClipping();
                    gc.setClipping(TimelineInteraction.clip(bounds, rowClip));
                    gc.drawText(event.label(), startX + barStyle.textPadding(), y, true);
                    gc.setClipping(rowClip);
                }
            }
            if (!search.getText().isBlank()) {
                gc.setForeground(color(theme.match()));
                gc.setLineWidth(2);
                gc.drawRectangle(bounds);
                gc.setLineWidth(1);
            }
            if (selectedEvent != null && selectedEvent.id().equals(event.id())) {
                gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
                gc.setLineWidth(3);
                gc.setLineStyle(SWT.LINE_SOLID);
                if (eventPolygon != null) {
                    int centerX = bounds.x + bounds.width / 2;
                    int centerY = bounds.y + bounds.height / 2;
                    int radiusX = bounds.width / 2 + 2;
                    int radiusY = bounds.height / 2 + 2;
                    gc.drawPolygon(new int[] { centerX, centerY - radiusY, centerX + radiusX, centerY,
                            centerX, centerY + radiusY, centerX - radiusX, centerY });
                } else if (eventArc == 0) {
                    gc.drawRectangle(bounds.x - 2, bounds.y - 2, bounds.width + 4, bounds.height + 4);
                } else {
                    int outerWidth = bounds.width + 4;
                    int outerHeight = bounds.height + 4;
                    int outerArc = Math.min(Math.min(outerWidth, outerHeight), eventArc + 4);
                    gc.drawRoundRectangle(bounds.x - 2, bounds.y - 2, outerWidth, outerHeight,
                            outerArc, outerArc);
                }
                gc.setLineWidth(1);
            }
            if (hoverEvent != null && hoverEvent.id().equals(event.id())
                    && (selectedEvent == null || !selectedEvent.id().equals(event.id()))) {
                gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
                gc.setLineWidth(2);
                gc.setLineStyle(SWT.LINE_DASH);
                gc.drawRectangle(bounds.x - 1, bounds.y - 1, bounds.width + 2, bounds.height + 2);
                gc.setLineStyle(SWT.LINE_SOLID);
                gc.setLineWidth(1);
            }
            Rectangle hitBounds = TimelineInteraction.clip(bounds, gc.getClipping());
            if (hitBounds.width > 0 && hitBounds.height > 0) hits.add(new EventHit(hitBounds, event,
                    paintedTrack.id(), rootAggregationEnabled && paintedTrack.parentId() == null));
        }

        private void drawHoverGuide(GC gc, Rectangle area) {
            if (!hoverGuideVisible || hoverX < theme.labelWidth() || hoverX >= area.width
                    || hoverY < 0 || hoverY >= area.height || selectingZoom || draggingPlayhead) return;
            gc.setForeground(color(theme.grid()));
            gc.setLineStyle(SWT.LINE_DOT);
            gc.drawLine(hoverX, theme.rulerHeight(), hoverX, area.height);
            gc.setLineStyle(SWT.LINE_SOLID);
            String text = timeFormatter.withZone(zoneId).format(xToTime(hoverX));
            Point extent = gc.textExtent(text);
            int x = Math.max(theme.labelWidth() + 3, Math.min(hoverX + 6, area.width - extent.x - 4));
            gc.setBackground(color(theme.alternateRow()));
            gc.fillRectangle(x - 2, 1, extent.x + 4, extent.y + 2);
            gc.setForeground(color(theme.foreground()));
            gc.drawText(text, x, 2, true);
        }

        private void drawPlayhead(GC gc, Rectangle area) {
            if (replayTime == null) return;
            int x = timeToX(replayTime);
            if (x < theme.labelWidth() || x > area.width) return;
            gc.setForeground(color(theme.playhead()));
            gc.setLineWidth(2);
            gc.drawLine(x, 0, x, area.height);
            gc.setBackground(color(theme.playhead()));
            gc.fillPolygon(new int[] { x - 5, 0, x + 5, 0, x, 8 });
            gc.setLineWidth(1);
        }

        private void drawZoomSelection(GC gc, Rectangle area) {
            if (!selectingZoom) return;
            int x1 = Math.max(theme.labelWidth(), Math.min(selectionStartX, selectionCurrentX));
            int x2 = Math.min(area.width - 1, Math.max(selectionStartX, selectionCurrentX));
            if (x2 <= x1) return;
            Color selectionColor = selectionCurrentX >= selectionStartX
                    ? getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION) : color(theme.playhead());
            gc.setBackground(selectionColor);
            gc.setAlpha(55);
            gc.fillRectangle(x1, theme.rulerHeight(), x2 - x1, area.height - theme.rulerHeight());
            gc.setAlpha(255);
            gc.setForeground(selectionColor);
            gc.setLineStyle(SWT.LINE_DASH);
            gc.drawRectangle(x1, theme.rulerHeight(), x2 - x1, Math.max(0, area.height - theme.rulerHeight() - 1));
            gc.setLineStyle(SWT.LINE_SOLID);
        }

        private List<VisibleTrack> visibleTracks() {
            List<VisibleTrack> rows = new ArrayList<>();
            Set<String> matches = prepared.matchingTrackIds(search.getText());
            for (String root : prepared.roots()) appendVisible(root, 0, rows, matches);
            return rows;
        }

        private void appendVisible(String id, int depth, List<VisibleTrack> rows, Set<String> matches) {
            if (!matches.contains(id)) return;
            TimelineTrack track = prepared.track(id);
            rows.add(new VisibleTrack(track, depth));
            if (expanded.contains(id)) {
                for (String child : prepared.children(id)) appendVisible(child, depth + 1, rows, matches);
            }
        }

        private int effectiveRowHeight(int rowCount) {
            int minimum = eventHeightMode == EventHeightMode.COMPACT_LANES
                    ? Math.max(theme.rowHeight(), theme.maxLanes() * theme.laneHeight() + 6)
                    : theme.rowHeight();
            if (rowSizingMode != RowSizingMode.FILL_AVAILABLE || rowCount <= 0) return minimum;
            int available = Math.max(1, getClientArea().height - theme.rulerHeight());
            return Math.max(minimum, available / rowCount);
        }

        int timeToX(Instant time) {
            int width = Math.max(1, getClientArea().width - theme.labelWidth());
            double fraction = secondsBetween(visibleStart, time) / secondsBetween(visibleStart, visibleEnd);
            return theme.labelWidth() + (int) Math.round(fraction * width);
        }

        private Instant xToTime(int x) {
            int width = Math.max(1, getClientArea().width - theme.labelWidth());
            double fraction = Math.max(0, Math.min(1, (double) (x - theme.labelWidth()) / width));
            return addSeconds(visibleStart, secondsBetween(visibleStart, visibleEnd) * fraction);
        }

        private void configureVerticalBar(int content, int viewport, int rowHeight) {
            ScrollBar bar = getVerticalBar();
            bar.setMaximum(Math.max(content, viewport));
            bar.setThumb(Math.min(content, viewport));
            int increment = Math.max(1, rowHeight);
            bar.setIncrement(increment);
            bar.setPageIncrement(Math.max(increment, viewport - increment));
            verticalOffset = Math.min(verticalOffset, Math.max(0, content - viewport));
            bar.setSelection(verticalOffset);
            bar.setEnabled(content > viewport);
        }

        private void configureHorizontalBar() {
            ScrollBar bar = getHorizontalBar();
            double total = secondsBetween(prepared.rangeStart(), prepared.rangeEnd());
            double span = secondsBetween(visibleStart, visibleEnd);
            int thumb = (int) Math.max(1, Math.min(SCROLL_UNITS, Math.round(SCROLL_UNITS * span / total)));
            int selection = (int) Math.round(SCROLL_UNITS * secondsBetween(prepared.rangeStart(), visibleStart) / total);
            bar.setMaximum(SCROLL_UNITS);
            bar.setThumb(thumb);
            bar.setSelection(Math.max(0, Math.min(SCROLL_UNITS - thumb, selection)));
            bar.setPageIncrement(Math.max(1, thumb));
            bar.setIncrement(Math.max(1, thumb / 10));
            bar.setEnabled(span < total);
        }

        private void scrollFromBar() {
            if (prepared == null) return;
            double total = secondsBetween(prepared.rangeStart(), prepared.rangeEnd());
            double span = secondsBetween(visibleStart, visibleEnd);
            double offset = total * getHorizontalBar().getSelection() / SCROLL_UNITS;
            visibleStart = addSeconds(prepared.rangeStart(), offset);
            visibleEnd = addSeconds(visibleStart, span);
            clampVisibleRange();
            invalidateStatic();
        }

        @Override
        public void mouseDoubleClick(MouseEvent event) {
            if (event.button != 1 || prepared == null || event.y < theme.rulerHeight()) return;
            List<EventHit> candidates = eventHitsAt(event.x, event.y);
            if (candidates.size() == 1) {
                selectEventInternal(candidates.get(0).event(), true);
                if (!eventStartRequestListeners.isEmpty()) fireEventStartRequest(candidates.get(0).event());
            } else if (candidates.size() > 1) {
                showEventCandidates(candidates.stream().map(EventHit::event).toList());
            } else if (selectedEvent != null && !eventStartRequestListeners.isEmpty()) {
                // Opening the details strip after the first click may resize row geometry before
                // SWT delivers the double-click. The first click's selection remains authoritative.
                fireEventStartRequest(selectedEvent);
            }
        }

        @Override
        public void mouseDown(MouseEvent event) {
            closeTransientUi();
            setFocus();
            if (event.button != 1 || prepared == null) return;
            if (event.x >= theme.labelWidth() && event.y >= theme.rulerHeight()
                    && (event.stateMask & zoomSelectionModifier) == zoomSelectionModifier) {
                selectingZoom = true;
                selectionStartX = clampPlotX(event.x);
                selectionCurrentX = selectionStartX;
                setCursor(getDisplay().getSystemCursor(SWT.CURSOR_CROSS));
                redraw();
                return;
            }
            if (event.x < theme.labelWidth() && event.y >= theme.rulerHeight()) {
                toggleTrackAt(event.x, event.y);
                return;
            }
            if (event.x >= theme.labelWidth()) {
                dragStartX = event.x;
                pressedEvent = event.y >= theme.rulerHeight() ? eventHitAt(event.x, event.y) : null;
                pressInRuler = event.y < theme.rulerHeight();
                pendingScrub = true;
            }
        }

        @Override
        public void mouseUp(MouseEvent event) {
            if (event.button == 1) {
                if (selectingZoom) {
                    int startX = selectionStartX;
                    int endX = clampPlotX(event.x);
                    selectingZoom = false;
                    if (Math.abs(endX - startX) >= 5) {
                        if (endX > startX) setVisibleRange(xToTime(startX), xToTime(endX));
                        else fitAll();
                    } else {
                        redraw();
                    }
                } else if (draggingPlayhead) {
                    updatePlayheadFromMouse(event.x);
                } else if (pendingScrub) {
                    if (pressInRuler) {
                        updatePlayheadFromMouse(event.x);
                    } else if (pressedEvent != null) {
                        List<EventHit> candidates = eventHitsAt(event.x, event.y);
                        if (pressedEvent.rootAggregate() && candidates.size() > 1) {
                            showEventCandidates(candidates.stream().map(EventHit::event).toList());
                        } else {
                            selectEventInternal(pressedEvent.event(), true);
                        }
                    } else {
                        selectEventInternal(null, false);
                    }
                }
                draggingPlayhead = false;
                pendingScrub = false;
                pressedEvent = null;
                setCursor(null);
            }
        }

        @Override
        public void mouseMove(MouseEvent event) {
            hoverX = event.x;
            hoverY = event.y;
            if (selectingZoom) {
                selectionCurrentX = clampPlotX(event.x);
                redraw();
            } else if (draggingPlayhead) updatePlayheadFromMouse(event.x);
            else if (pendingScrub && TimelineInteraction.exceedsHorizontalDragThreshold(
                    dragStartX, event.x, playheadDragThreshold)) {
                pendingScrub = false;
                draggingPlayhead = true;
                pressedEvent = null;
                setCursor(getDisplay().getSystemCursor(SWT.CURSOR_SIZEWE));
                updatePlayheadFromMouse(event.x);
            }
            if (selectingZoom || draggingPlayhead || pendingScrub) {
                cancelHoverPreview();
            } else {
                scheduleHoverPreview(event.x, event.y);
            }
            redraw();
        }

        private void updatePlayheadFromMouse(int x) {
            Instant time = xToTime(x);
            replayTime = time;
            redraw();
            fireUserReplayChange(time);
        }

        private void toggleTrackAt(int x, int y) {
            List<VisibleTrack> rows = visibleTracks();
            int index = (y - theme.rulerHeight() + verticalOffset) / effectiveRowHeight(rows.size());
            if (index < 0 || index >= rows.size()) return;
            VisibleTrack row = rows.get(index);
            int indent = 8 + row.depth() * 16;
            if (x >= indent && x < indent + 16 && !prepared.children(row.track().id()).isEmpty()) {
                if (!expanded.remove(row.track().id())) expanded.add(row.track().id());
            } else if (x >= indent + 16 && x < indent + 34) {
                if (!visible.remove(row.track().id())) visible.add(row.track().id());
            }
            invalidateStatic();
        }

        @Override
        public void mouseScrolled(MouseEvent event) {
            if (prepared == null) return;
            closeTransientUi();
            if ((event.stateMask & SWT.SHIFT) != 0) {
                double delta = -event.count * secondsBetween(visibleStart, visibleEnd) / 20.0;
                visibleStart = addSeconds(visibleStart, delta);
                visibleEnd = addSeconds(visibleEnd, delta);
                clampVisibleRange();
                invalidateStatic();
                return;
            }
            ScrollBar bar = getVerticalBar();
            int maximum = Math.max(0, bar.getMaximum() - bar.getThumb());
            int step = Math.max(1, bar.getIncrement());
            verticalOffset = Math.max(0, Math.min(maximum, verticalOffset - event.count * step));
            bar.setSelection(verticalOffset);
            invalidateStatic();
        }

        private int clampPlotX(int x) {
            return Math.max(theme.labelWidth(), Math.min(getClientArea().width - 1, x));
        }

        private EventHit eventHitAt(int x, int y) {
            List<EventHit> candidates = eventHitsAt(x, y);
            return candidates.isEmpty() ? null : candidates.get(0);
        }

        private List<EventHit> eventHitsAt(int x, int y) {
            List<EventHit> matching = hits.stream().filter(hit -> hit.bounds().contains(x, y)).toList();
            List<EventHit> unique = TimelineInteraction.distinctFrontToBack(matching, hit -> hit.event().id());
            if (unique.isEmpty()) return List.of();
            EventHit first = unique.get(0);
            if (!first.rootAggregate()) return List.of(first);
            return unique.stream()
                    .filter(hit -> hit.rootAggregate() && hit.paintedTrackId().equals(first.paintedTrackId()))
                    .toList();
        }

        private void scheduleHoverPreview(int x, int y) {
            List<EventHit> candidates = eventHitsAt(x, y);
            TimelineEvent next = candidates.size() == 1 ? candidates.get(0).event() : null;
            if (Objects.equals(hoverEvent == null ? null : hoverEvent.id(), next == null ? null : next.id())
                    && transientTooltip != null && !transientTooltip.isDisposed()) return;
            long expected = ++hoverGeneration;
            hideTransientTooltip();
            setHoverEvent(null);
            if (candidates.isEmpty()) return;
            getDisplay().timerExec(hoverDelayMillis, () -> {
                if (isDisposed() || expected != hoverGeneration || selectingZoom || draggingPlayhead || pendingScrub) return;
                Point pointer = toControl(getDisplay().getCursorLocation());
                List<EventHit> current = eventHitsAt(pointer.x, pointer.y);
                if (current.isEmpty()) return;
                showPassivePreview(current, pointer.x, pointer.y);
            });
        }

        private void showPassivePreview(List<EventHit> candidates, int x, int y) {
            hideTransientTooltip();
            if (candidates.size() == 1) setHoverEvent(candidates.get(0).event());
            Shell shell = new Shell(getShell(), SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS);
            GridLayout layout = new GridLayout(1, false);
            layout.marginWidth = 8;
            layout.marginHeight = 6;
            shell.setLayout(layout);
            Label title = new Label(shell, SWT.NONE);
            TooltipContent singleContent = candidates.size() == 1
                    ? tooltipProvider.tooltipFor(candidates.get(0).event()) : null;
            title.setText(candidates.size() == 1
                    ? singleContent == null ? candidates.get(0).event().label()
                            : Objects.toString(singleContent.title(), "")
                    : candidates.size() + " overlapping events");
            Label body = new Label(shell, SWT.WRAP);
            body.setLayoutData(new GridData(360, SWT.DEFAULT));
            if (candidates.size() == 1) {
                TimelineEvent event = candidates.get(0).event();
                String custom = singleContent == null ? "" : Objects.toString(singleContent.body(), "");
                body.setText(trackPath(event.trackId()) + System.lineSeparator() + formatEventTime(event)
                        + (custom.isBlank() ? "" : System.lineSeparator() + custom));
            } else {
                body.setText("Click the overlap to inspect these events in the details section.");
            }
            shell.pack();
            placeShell(shell, x, y);
            transientTooltip = shell;
            shell.setVisible(true);
        }

        private void placeShell(Shell shell, int x, int y) {
            Point displayPoint = toDisplay(x + 12, y + 18);
            Rectangle monitor = getMonitor().getClientArea();
            Point size = shell.getSize();
            int shellX = Math.max(monitor.x, Math.min(displayPoint.x, monitor.x + monitor.width - size.x));
            int shellY = Math.max(monitor.y, Math.min(displayPoint.y, monitor.y + monitor.height - size.y));
            shell.setLocation(shellX, shellY);
        }

        private void setHoverEvent(TimelineEvent event) {
            String oldId = hoverEvent == null ? null : hoverEvent.id();
            String newId = event == null ? null : event.id();
            hoverEvent = event;
            if (!Objects.equals(oldId, newId)) invalidateStatic();
        }

        private void cancelHoverPreview() {
            hoverGeneration++;
            hideTransientTooltip();
            setHoverEvent(null);
        }

        private void clearHover() {
            hoverX = -1;
            hoverY = -1;
            cancelHoverPreview();
            redraw();
        }

        private void hideTransientTooltip() {
            if (transientTooltip != null && !transientTooltip.isDisposed()) transientTooltip.dispose();
            transientTooltip = null;
        }

        private void closeTransientUi() {
            hoverGeneration++;
            hideTransientTooltip();
            setHoverEvent(null);
        }

        private void keyDown(Event event) {
            if (prepared == null) return;
            if (event.keyCode == SWT.ESC && (selectingZoom || pendingScrub || draggingPlayhead)) {
                selectingZoom = false;
                pendingScrub = false;
                draggingPlayhead = false;
                pressedEvent = null;
                setCursor(null);
                redraw();
                return;
            }
            if (event.keyCode == SWT.F2) {
                if (hoverEvent != null) selectEventInternal(hoverEvent, true);
                else if (selectedEvent != null) setDetailsExpanded(true);
                return;
            }
            double span = secondsBetween(visibleStart, visibleEnd);
            if (event.keyCode == SWT.ARROW_LEFT || event.keyCode == SWT.ARROW_RIGHT) {
                double direction = event.keyCode == SWT.ARROW_LEFT ? -1 : 1;
                if ((event.stateMask & SWT.CTRL) != 0 && replayTime != null) {
                    Instant next = addSeconds(replayTime, direction * span / 100);
                    replayTime = next;
                    fireUserReplayChange(next);
                    redraw();
                } else {
                    visibleStart = addSeconds(visibleStart, direction * span / 10);
                    visibleEnd = addSeconds(visibleEnd, direction * span / 10);
                    clampVisibleRange();
                    invalidateStatic();
                }
            } else if (event.character == '+' || event.character == '=') {
                zoomAt(0.8, 0.5);
            } else if (event.character == '-') {
                zoomAt(1.25, 0.5);
            } else if (event.keyCode == SWT.HOME) {
                fitAll();
            }
        }

        private void zoomAt(double factor, double anchorFraction) {
            double oldSpan = secondsBetween(visibleStart, visibleEnd);
            double total = secondsBetween(prepared.rangeStart(), prepared.rangeEnd());
            double newSpan = Math.max(MIN_VISIBLE_SECONDS, Math.min(total, oldSpan * factor));
            Instant anchor = addSeconds(visibleStart, oldSpan * anchorFraction);
            visibleStart = addSeconds(anchor, -newSpan * anchorFraction);
            visibleEnd = addSeconds(visibleStart, newSpan);
            clampVisibleRange();
            invalidateStatic();
        }

        private void disposeResources() {
            closeTransientUi();
            if (staticBuffer != null && !staticBuffer.isDisposed()) staticBuffer.dispose();
            resetColors();
        }
    }

    private static double secondsBetween(Instant start, Instant end) {
        long seconds = end.getEpochSecond() - start.getEpochSecond();
        long nanos = end.getNano() - start.getNano();
        return seconds + nanos / 1_000_000_000d;
    }

    private static Instant addSeconds(Instant instant, double seconds) {
        long whole = (long) Math.floor(seconds);
        long nanos = Math.round((seconds - whole) * 1_000_000_000d);
        return instant.plusSeconds(whole).plusNanos(nanos);
    }

}

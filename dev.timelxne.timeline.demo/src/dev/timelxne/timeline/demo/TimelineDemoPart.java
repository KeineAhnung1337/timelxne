package dev.timelxne.timeline.demo;

import dev.timelxne.timeline.EventHeightMode;
import dev.timelxne.timeline.EventBarStyle;
import dev.timelxne.timeline.EventStyle;
import dev.timelxne.timeline.RevealPolicy;
import dev.timelxne.timeline.RowSizingMode;
import dev.timelxne.timeline.Timeline;
import dev.timelxne.timeline.TimelineEvent;
import dev.timelxne.timeline.TimelineInput;
import dev.timelxne.timeline.TimelineTrack;
import jakarta.annotation.PostConstruct;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class TimelineDemoPart {
    private static final String[] FILTERS = { "All events", "Startup", "Data flow", "External", "Maintenance" };
    private static final String[] FILTER_KEYS = { "all", "startup", "data", "external", "maintenance" };

    @PostConstruct
    public void create(Composite parent) {
        parent.setLayout(new GridLayout(4, false));
        Timeline timeline = new Timeline(parent, SWT.NONE);
        timeline.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1));

        Label replayValue = new Label(parent, SWT.NONE);
        replayValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss")
                .withZone(ZoneId.systemDefault());
        timeline.addReplayTimeListener(event ->
                replayValue.setText("Dragged replay time: " + formatter.format(event.time())));
        timeline.addEventSelectionListener(event -> replayValue.setText(event.selectedEvent()
                .map(selected -> "Selected: " + selected.label())
                .orElse("No event selected")));

        Label filterLabel = new Label(parent, SWT.NONE);
        filterLabel.setText("API event filter:");
        Combo eventFilter = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        eventFilter.setItems(FILTERS);
        eventFilter.select(0);
        eventFilter.addListener(SWT.Selection, ignored -> {
            String selected = FILTER_KEYS[eventFilter.getSelectionIndex()];
            timeline.setEventFilter(event -> selected.equals("all")
                    || ((DemoEventData) event.payload()).category().equals(selected));
        });

        Label barLabel = new Label(parent, SWT.NONE);
        barLabel.setText("Event bars:");
        Combo barStyle = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        barStyle.setItems("Rounded", "Square", "Thick border", "Compact minimum");
        barStyle.select(0);
        barStyle.addListener(SWT.Selection, ignored -> timeline.setEventBarStyle(switch (barStyle.getSelectionIndex()) {
            case 1 -> new EventBarStyle(0, 1, 4, 2);
            case 2 -> new EventBarStyle(8, 3, 7, 4);
            case 3 -> new EventBarStyle(3, 1, 2, 1);
            default -> EventBarStyle.defaults();
        }));
        Button eventText = new Button(parent, SWT.CHECK);
        eventText.setText("Show event text");
        eventText.setSelection(true);
        eventText.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        eventText.addListener(SWT.Selection, ignored -> timeline.setEventTextVisible(eventText.getSelection()));

        Label opacityLabel = new Label(parent, SWT.NONE);
        opacityLabel.setText("Event fill opacity:");
        Combo opacity = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        opacity.setItems("Per category", "100%", "75%", "50%", "25%");
        opacity.select(0);
        opacity.addListener(SWT.Selection, ignored -> {
            if (opacity.getSelectionIndex() == 0) configureCategoryOpacity(timeline);
            else timeline.setEventOpacity(switch (opacity.getSelectionIndex()) {
                case 2 -> 0.75;
                case 3 -> 0.50;
                case 4 -> 0.25;
                default -> 1.0;
            });
        });
        Label opacityHelp = new Label(parent, SWT.NONE);
        opacityHelp.setText("Fill only; borders and labels stay opaque");
        opacityHelp.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        Label help = new Label(parent, SWT.WRAP);
        help.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
        help.setText("Search above filters event text · Drag past 5 px: move playhead · Wheel: rows · "
                + "Shift+wheel: time · Ctrl+drag →: zoom · Ctrl+drag ←: fit · "
                + "Click: inspect/choose overlap · Double-click: set event start · F2: inspect hover");
        Instant origin = Instant.now().minus(Duration.ofHours(2));

        timeline.setEventStyleProvider(event -> switch (data(event).category()) {
            case "startup" -> new EventStyle(new RGB(66, 133, 244), new RGB(35, 88, 170), new RGB(255, 255, 255));
            case "data" -> new EventStyle(new RGB(52, 168, 83), new RGB(24, 110, 50), new RGB(255, 255, 255));
            case "external" -> new EventStyle(new RGB(251, 188, 4), new RGB(176, 118, 0), new RGB(45, 45, 45));
            case "maintenance" -> new EventStyle(new RGB(234, 67, 53), new RGB(170, 30, 25), new RGB(255, 255, 255));
            default -> EventStyle.defaults();
        });
        timeline.setEventHeightProvider(event -> data(event).heightFraction());
        configureCategoryOpacity(timeline);
        timeline.setPlayheadDragThreshold(5);
        timeline.setTimeFormatter(DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm"));
        timeline.setEventHeightMode(EventHeightMode.FILL_ROW);
        timeline.setRowSizingMode(RowSizingMode.FILL_AVAILABLE);
        timeline.addEventStartRequestListener(request -> {
            timeline.setReplayTime(request.time(), RevealPolicy.REVEAL_IF_OUTSIDE);
            replayValue.setText("Hint requested event start: " + formatter.format(request.time()));
        });
        timeline.setInput(sample(origin));
        timeline.setReplayTime(origin.plus(Duration.ofHours(2)));
    }

    private static TimelineInput sample(Instant origin) {
        List<TimelineTrack> tracks = List.of(
                new TimelineTrack("application", null, "Application (all events)", true, true),
                new TimelineTrack("bootstrap", "application", "Bootstrap", true, true),
                new TimelineTrack("configuration", "application", "Configuration", true, true),
                new TimelineTrack("authentication", "application", "Authentication", true, true),
                new TimelineTrack("ingestion", "application", "Ingestion", true, true),
                new TimelineTrack("validation", "application", "Validation", true, true),
                new TimelineTrack("calculation", "application", "Calculation", true, true),
                new TimelineTrack("storage", "application", "Storage", true, true),
                new TimelineTrack("notifications", "application", "Notifications", true, true),
                new TimelineTrack("recovery", "application", "Recovery", true, true),
                new TimelineTrack("shutdown", "application", "Shutdown", true, true),
                new TimelineTrack("infrastructure", null, "Infrastructure (all events)", true, true),
                new TimelineTrack("database", "infrastructure", "Database", true, true),
                new TimelineTrack("message-broker", "infrastructure", "Message broker", true, true),
                new TimelineTrack("monitoring", "infrastructure", "Monitoring", true, true));
        List<TimelineEvent> events = List.of(
                event("event-01", "bootstrap", "Start runtime", origin, 0, 15, 1.00, "startup"),
                event("event-02", "configuration", "Load configuration", origin, 15, 15, 0.75, "startup"),
                event("event-03", "authentication", "Authenticate services", origin, 30, 15, 0.50, "external"),
                event("event-04", "ingestion", "Receive batch A", origin, 45, 15, 1.00, "data"),
                event("event-05", "validation", "Validate batch A", origin, 60, 15, 0.35, "data"),
                event("event-06", "calculation", "Calculate batch A", origin, 75, 15, 0.80, "data"),
                event("event-07", "storage", "Store batch A", origin, 90, 15, 0.55, "data"),
                event("event-08", "notifications", "Publish batch A", origin, 105, 15, 0.25, "external"),
                event("event-09", "ingestion", "Receive batch B", origin, 120, 15, 1.00, "data"),
                event("event-10", "validation", "Reject invalid records", origin, 135, 15, 0.40, "data"),
                event("event-11", "recovery", "Recover source connection", origin, 150, 15, 0.65, "maintenance"),
                event("event-12", "calculation", "Calculate batch B", origin, 165, 15, 0.85, "data"),
                event("event-13", "storage", "Store batch B", origin, 180, 15, 0.60, "data"),
                event("event-14", "notifications", "Publish batch B", origin, 195, 15, 0.30, "external"),
                event("event-15", "configuration", "Reload configuration", origin, 210, 15, 0.70, "maintenance"),
                event("event-16", "shutdown", "Stop runtime", origin, 225, 15, 1.00, "maintenance"),
                event("event-17", "authentication", "Refresh access token", origin, 45, 30, 1.00, "external"),
                event("event-18", "storage", "Open transaction", origin, 45, 15, 1.00, "data"),
                event("event-19", "calculation", "Warm calculation cache", origin, 120, 30, 0.90, "data"),
                event("event-20", "notifications", "Send progress update", origin, 120, 15, 0.80, "external"),
                event("event-21", "ingestion", "Read side channel", origin, 125, 10, 0.90, "data"),
                event("event-22", "database", "Create database pool", origin, 10, 35, 1.00, "startup"),
                event("event-23", "message-broker", "Connect message broker", origin, 20, 30, 0.85, "external"),
                event("event-24", "monitoring", "Start metric collection", origin, 25, 20, 0.65, "startup"),
                event("event-25", "database", "Compact event store", origin, 115, 40, 0.80, "maintenance"),
                event("event-26", "message-broker", "Drain retry queue", origin, 120, 25, 0.70, "data"),
                event("event-27", "monitoring", "Export health snapshot", origin, 125, 15, 0.55, "external"),
                event("event-28", "validation", "Validate side channel", origin, 120, 32, 0.70, "data"),
                event("event-29", "storage", "Write audit checkpoint", origin, 122, 28, 0.60, "data"),
                event("event-30", "notifications", "Notify batch observers", origin, 124, 24, 0.50, "external"),
                event("event-31", "recovery", "Probe fallback endpoint", origin, 126, 20, 0.40, "maintenance"));
        return new TimelineInput(tracks, events, origin, origin.plus(Duration.ofHours(4)));
    }

    private static TimelineEvent event(String id, String track, String label, Instant origin,
            long offsetMinutes, long durationMinutes, double heightFraction, String category) {
        Instant start = origin.plus(Duration.ofMinutes(offsetMinutes));
        return new TimelineEvent(id, track, label, start, start.plus(Duration.ofMinutes(durationMinutes)),
                new DemoEventData(heightFraction, category));
    }

    private static DemoEventData data(TimelineEvent event) {
        return (DemoEventData) event.payload();
    }

    private static void configureCategoryOpacity(Timeline timeline) {
        timeline.setEventOpacityProvider(event -> switch (data(event).category()) {
            case "startup" -> 0.90;
            case "data" -> 0.72;
            case "external" -> 0.58;
            case "maintenance" -> 0.45;
            default -> 1.0;
        });
    }

    private record DemoEventData(double heightFraction, String category) {
    }
}

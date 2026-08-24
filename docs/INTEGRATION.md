# Integrating timelxne into an Eclipse RCP application

This guide targets an Eclipse RCP 4.40 application built with OSGi, Tycho/Maven, Java 21, and SWT.
The widget itself depends only on SWT.

## 1. Add the library to the target platform

Build this repository once:

```shell
mvn verify
```

The resulting p2 update site is
`releng/dev.timelxne.timeline.repository/target/repository`. During local development, add it to your
application's target definition and select the library feature:

```xml
<location includeMode="planner" includeSource="false" type="InstallableUnit">
  <repository location="file:/absolute/path/to/timelxne/releng/dev.timelxne.timeline.repository/target/repository"/>
  <unit id="dev.timelxne.timeline.feature.feature.group" version="0.0.0"/>
</location>
```

For a shared build, publish that directory as a p2 site and replace the `file:` URL with its HTTPS
URL. The demo feature is intentionally separate; consuming applications only need the library
feature.

Add the bundle to the consuming plugin's `META-INF/MANIFEST.MF`:

```text
Require-Bundle: org.eclipse.swt,
 dev.timelxne.timeline;bundle-version="[1.0.0,2.0.0)"
```

If your product is feature-based, include `dev.timelxne.timeline.feature` in its product feature
list or import it from your own application feature.

## 2. Create the widget in an E4 part

`Timeline` is an SWT `Composite`. Construct it on the display thread and let the parent dispose it.
It does not require an E4 binding service or a dependency-injected timeline service.

```java
package com.example.application.parts;

import dev.timelxne.timeline.Timeline;
import dev.timelxne.timeline.TimelineEvent;
import dev.timelxne.timeline.TimelineInput;
import dev.timelxne.timeline.TimelineTrack;
import jakarta.annotation.PostConstruct;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;

import java.time.Instant;
import java.util.List;

public final class TimelinePart {
    @PostConstruct
    public void createControls(Composite parent) {
        parent.setLayout(new FillLayout());
        Timeline timeline = new Timeline(parent, SWT.NONE);

        TimelineTrack application =
                new TimelineTrack("application", null, "Application", true, true);
        TimelineTrack imports =
                new TimelineTrack("imports", "application", "Imports", true, true);

        Instant start = Instant.parse("2026-08-24T10:00:00Z");
        TimelineEvent importEvent = new TimelineEvent(
                "import-42", "imports", "Import customers",
                start, start.plusSeconds(45), new ImportPayload(42));

        timeline.setInput(new TimelineInput(
                List.of(application, imports), List.of(importEvent)));
    }

    private record ImportPayload(long jobId) {}
}
```

Every track and event needs a non-blank stable ID. A child track references its parent by ID, and an
event references its owning track by ID. A `null` event end creates an instant marker. Explicit
input range bounds are optional; without them, the library derives the range from the events.

## 3. Connect replay time

User scrubbing reports a time through `ReplayTimeListener`. Treat this as a request to update your
application's replay model:

```java
timeline.addReplayTimeListener(change -> replayController.seek(change.time()));
```

The selected-event action and an unambiguous double-click use `EventStartRequestListener`:

```java
timeline.addEventStartRequestListener(request -> replayController.seek(request.time()));
```

When the application clock changes, update the timeline without firing the user listener again:

```java
timeline.setReplayTime(currentTime, RevealPolicy.REVEAL_IF_OUTSIDE);
```

`setReplayTime` follows normal SWT rules and must run on the display thread. Replay engines often
run on a worker thread; use the thread-safe, coalescing method in that case:

```java
timeline.postReplayTime(currentTime, RevealPolicy.KEEP_VIEWPORT);
```

Available reveal policies are `KEEP_VIEWPORT`, `REVEAL_IF_OUTSIDE`, and `CENTER`.

## 4. Observe and control selection

```java
timeline.addEventSelectionListener(change -> change.selectedEvent().ifPresentOrElse(
        this::showRelatedData,
        this::clearRelatedData));

timeline.selectEvent("import-42");
timeline.clearEventSelection();
```

Selection is retained across input replacements by stable event ID. If the selected event is
removed, the timeline clears selection and notifies listeners.

## 5. Update live data

`setInput` and `applyDelta` prepare indexes away from the UI thread and return a future that
completes after the prepared state is installed on the display thread:

```java
timeline.applyDelta(TimelineDelta.ofEvents(
        List.of(updatedEvent, newEvent),
        List.of("removed-event-id")));
```

Use `setInput` for a complete snapshot. Use one `TimelineDelta` for a batch of changes instead of
many small updates. Widget mutators other than `postReplayTime` must be invoked on the SWT display
thread.

## 6. Configure presentation

Providers receive the original `TimelineEvent`, including its application-defined `payload`:

```java
timeline.setTimeZone(ZoneId.of("Europe/Berlin"));
timeline.setTimeFormatter(DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss"));

timeline.setEventStyleProvider(event -> styleFor(event.payload()));
timeline.setEventOpacityProvider(event -> event.isInstant() ? 1.0 : 0.75);
timeline.setEventHeightProvider(event -> 0.8);
timeline.setEventFilter(event -> !isSuppressed(event));
timeline.setTooltipProvider(event -> new TooltipContent(event.label(), describe(event.payload())));
```

Use `setEventBarStyle` for one duration-bar shape or `setEventBarStyleProvider` for per-event
geometry. `setTheme` controls global colors and dimensions. SWT `Color` instances are created and
owned by the widget; providers return device-independent `RGB` values.

## 7. Troubleshooting

### `InjectionException` mentioning `BindingTableManager`

Do not inject or instantiate Eclipse's internal `BindingServiceImpl` for timeline search. Construct
`Timeline` in the E4 part as shown above; its search box is an ordinary SWT control and needs no
binding-service argument.

### The application cannot resolve `dev.timelxne.timeline`

Confirm that the library feature is present in the target platform, the consuming manifest has the
`Require-Bundle` entry, and the product includes the feature. Reload the target platform after
changing a local p2 repository.

### `Invalid thread access`

Call configuration, input, selection, and viewport methods on the display thread. Only
`postReplayTime` accepts calls from arbitrary threads.

### Events are missing

Check that IDs are unique, every event track exists, parent relationships are acyclic, the track is
visible, and both the programmatic filter and search text accept the event.

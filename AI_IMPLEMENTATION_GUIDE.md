# Implementation instructions for coding agents

Use this file as the authoritative handoff when integrating `timelxne` into an existing Eclipse RCP
application. It is intentionally explicit so a small coding model can complete the work without
inventing APIs or making product decisions.

## Task statement to give the agent

> Integrate the `timelxne` SWT timeline into this Eclipse RCP application. Read and follow
> `AI_IMPLEMENTATION_GUIDE.md` from the timelxne repository. Inspect this application's existing
> target definition, feature, product, plugin manifest, E4 part, event model, and replay-time API
> before editing. Replace the guide's example data with adapters for the application's real event
> data. Build and test the application after the changes.

Also give the agent the local path or GitHub URL of this repository:

```text
https://github.com/KeineAhnung1337/timelxne
```

## Required outcome

The consuming application must:

1. Resolve the library feature `dev.timelxne.timeline.feature.feature.group` from a p2 repository.
2. Include the bundle `dev.timelxne.timeline` at runtime.
3. Create one `Timeline` in an SWT/E4 view or part.
4. Convert the application's event hierarchy into `TimelineTrack` and `TimelineEvent` records.
5. Send user playhead changes and event-start requests to the application's replay controller.
6. Send application replay-clock changes back through `postReplayTime` or `setReplayTime`.
7. Build without adding dependencies on Eclipse internal binding-service implementations.

Do not rewrite the timeline widget inside the consuming application. Use the public API exported by
the `dev.timelxne.timeline` bundle.

## Facts that must not be guessed

| Item | Required value |
| --- | --- |
| Minimum Java version | 21 |
| Targeted Eclipse release | 4.40 |
| Library bundle ID | `dev.timelxne.timeline` |
| Library feature ID | `dev.timelxne.timeline.feature` |
| Installable-unit ID | `dev.timelxne.timeline.feature.feature.group` |
| Main widget class | `dev.timelxne.timeline.Timeline` |
| UI toolkit | SWT; the widget is a `Composite` |
| Thread-safe update | `postReplayTime(Instant, RevealPolicy)` only |
| Full model update | `setInput(TimelineInput)` |
| Incremental model update | `applyDelta(TimelineDelta)` |

All widget methods other than `postReplayTime` follow normal SWT display-thread rules.

## Phase 1: inspect the consuming application

Before editing, locate these files or their equivalents:

- Parent Maven/Tycho `pom.xml`
- PDE target definition (`*.target`)
- Application feature (`feature.xml`)
- Product definition (`*.product`)
- Consuming plugin manifest (`META-INF/MANIFEST.MF`)
- E4 application model (`Application.e4xmi`) or extension declaring the target part
- Existing SWT part/view that should contain the timeline
- Domain event type and hierarchy/category type
- Replay controller or function that accepts an `Instant`
- Existing callback that reports replay time changes

Preserve the application's existing dependency style. Do not replace a feature-based product with a
plugin-based product, change its application ID, or modify unrelated start levels.

If the event model or replay function cannot be found, stop and report the exact missing type rather
than inventing a second application clock.

## Phase 2: add the OSGi dependency

Build this repository with:

```shell
mvn verify
```

Its p2 repository is:

```text
dev.timelxne.timeline.repository/target/repository
```

Add this installable unit to the consuming target definition. Replace the repository path with the
real absolute local URI or the organization's published p2 URL:

```xml
<location includeMode="planner" includeSource="false" type="InstallableUnit">
  <repository location="file:/absolute/path/to/timelxne/dev.timelxne.timeline.repository/target/repository"/>
  <unit id="dev.timelxne.timeline.feature.feature.group" version="0.0.0"/>
</location>
```

Add the library feature to the consuming feature:

```xml
<requires>
  <!-- Keep existing imports. -->
  <import feature="dev.timelxne.timeline.feature"/>
</requires>
```

Add the bundle dependency to the plugin containing the E4 part:

```text
Require-Bundle: org.eclipse.swt,
 dev.timelxne.timeline;bundle-version="[1.0.0,2.0.0)"
```

Keep all existing manifest dependencies. If `Require-Bundle` already exists, append the entry with
valid manifest continuation lines instead of creating a second header.

For a feature-based product, ensure the product directly or transitively includes
`dev.timelxne.timeline.feature`. Do not add `dev.timelxne.timeline.demo.feature` to a real
application.

## Phase 3: construct the widget

Use this pattern in the existing E4 part. Adapt the package name and surrounding layout, but keep
construction inside the `@PostConstruct` method:

```java
import dev.timelxne.timeline.RevealPolicy;
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
    private Timeline timeline;

    @PostConstruct
    public void createControls(Composite parent) {
        parent.setLayout(new FillLayout());
        timeline = new Timeline(parent, SWT.NONE);

        timeline.addReplayTimeListener(change -> setApplicationTime(change.time()));
        timeline.addEventStartRequestListener(request -> setApplicationTime(request.time()));
        timeline.addEventSelectionListener(change ->
                change.selectedEvent().ifPresentOrElse(
                        this::onEventSelected,
                        this::onSelectionCleared));

        timeline.setInput(createTimelineInput());
    }

    private TimelineInput createTimelineInput() {
        TimelineTrack root =
                new TimelineTrack("application", null, "Application", true, true);
        TimelineTrack operations =
                new TimelineTrack("operations", "application", "Operations", true, true);

        Instant start = Instant.parse("2026-08-24T10:00:00Z");
        TimelineEvent example = new TimelineEvent(
                "operation-1",
                "operations",
                "Example operation",
                start,
                start.plusSeconds(30),
                null);

        return new TimelineInput(List.of(root, operations), List.of(example));
    }

    private void setApplicationTime(Instant time) {
        // Replace this body with the application's existing replay/seek function.
    }

    private void onEventSelected(TimelineEvent event) {
        // Optional: synchronize other application views using event.payload().
    }

    private void onSelectionCleared() {
        // Optional: clear synchronized application views.
    }

    public void onApplicationReplayTimeChanged(Instant time) {
        if (timeline != null && !timeline.isDisposed()) {
            timeline.postReplayTime(time, RevealPolicy.KEEP_VIEWPORT);
        }
    }
}
```

The example methods with comments are adapter points, not a second replay implementation. Replace
their bodies with calls to the real application services.

## Phase 4: map real data

Apply these rules exactly:

- Track IDs are non-blank and unique.
- A root track has `parentId == null`.
- A child track references an existing parent ID.
- Track parent relationships are acyclic.
- Event IDs are non-blank and unique across the complete input.
- `TimelineEvent.trackId` references an existing track.
- `end == null` or `end.equals(start)` creates an instant diamond.
- A duration event has `end.isAfter(start)`.
- Store the original domain object in `TimelineEvent.payload` when callbacks or styling need it.
- Use the same stable IDs in later snapshots and deltas so selection can survive updates.

The root row automatically aggregates events from its visible descendants. Do not duplicate child
events onto the root track merely to make them visible there.

Use an explicit input range only when the application needs empty time before or after all events:

```java
new TimelineInput(tracks, events, rangeStart, rangeEnd)
```

Otherwise use `new TimelineInput(tracks, events)` and let the library derive it.

## Phase 5: connect updates correctly

For a complete domain snapshot on the SWT thread:

```java
timeline.setInput(new TimelineInput(tracks, events));
```

For a batch of live changes on the SWT thread:

```java
timeline.applyDelta(TimelineDelta.ofEvents(eventUpserts, removedEventIds));
```

Do not call `.join()` or `.get()` on the future returned by `setInput` or `applyDelta` from the SWT
thread. Completion is installed through that same display thread, so blocking it can deadlock.

For replay-clock updates from any worker thread:

```java
timeline.postReplayTime(time, RevealPolicy.KEEP_VIEWPORT);
```

Do not wrap `postReplayTime` in another `asyncExec`; it already coalesces updates onto the display
thread.

## Phase 6: optional configuration

Only add options required by the application. These are real public methods:

```java
timeline.setTimeZone(zoneId);
timeline.setTimeFormatter(dateTimeFormatter);
timeline.setEventFilter(event -> true);
timeline.setSearchTextProvider(event -> event.label() + " " + event.id());
timeline.setTooltipProvider(event -> new TooltipContent(event.label(), "Details"));
timeline.setEventStyleProvider(event -> EventStyle.defaults());
timeline.setEventBarStyle(EventBarStyle.defaults());
timeline.setEventHeightProvider(event -> 1.0);
timeline.setEventOpacityProvider(event -> 1.0);
timeline.setEventTextVisible(true);
timeline.setRootAggregationEnabled(true);
timeline.setHoverGuideVisible(true);
timeline.setHoverDelayMillis(250);
timeline.setPlayheadDragThreshold(5);
timeline.setZoomSelectionModifier(SWT.CTRL);
```

Opacity and height fractions must be between `0.0` and `1.0`. Providers run during rendering, so
they must be fast and must not perform I/O or mutate SWT controls.

## Forbidden implementations

Do not:

- Inject or instantiate `BindingServiceImpl`, `BindingTableManager`, or another Eclipse internal
  binding type for timeline search.
- Copy `Timeline.java` into the consuming application.
- Modify the application's replay time directly from a background thread through `setReplayTime`.
- Duplicate aggregated events on root tracks.
- Create or dispose SWT `Color` objects inside style providers; return `RGB` through `EventStyle`.
- Block the SWT thread waiting for asynchronous timeline preparation.
- Commit generated `target/`, `.metadata/`, or workspace content.
- Add the demo bundle or demo feature to a production product.
- Guess a domain mapping when the real event type or replay API has not been located.

## Acceptance checklist

The task is complete only when all applicable checks pass:

- [ ] The target platform resolves `dev.timelxne.timeline.feature.feature.group`.
- [ ] The application feature/product includes the library feature.
- [ ] The consuming manifest resolves `dev.timelxne.timeline`.
- [ ] The timeline fills and resizes with its parent part.
- [ ] Instant and duration domain events appear at the correct times.
- [ ] Root rows aggregate only their own descendants.
- [ ] Search and application filtering work together.
- [ ] Dragging reports application replay time.
- [ ] Application replay updates move the playhead.
- [ ] The details action seeks to the selected event start.
- [ ] Overlapping root events can be chosen in the bottom details section.
- [ ] No `BindingTableManager` injection exception occurs.
- [ ] The consuming application's normal Maven/Tycho verification command passes.
- [ ] No generated build or workspace directories are committed.

## Required final report from the agent

The implementing agent must report:

1. Files changed.
2. Domain track/event mapping used.
3. Replay input and output callbacks connected.
4. Build or test command executed and its result.
5. Any unresolved assumption, missing application type, or manual installation step.

For additional context—not replacement instructions—see `docs/INTEGRATION.md`, `docs/API.md`, and
the working demo in `dev.timelxne.timeline.demo`.

## If the task is to modify this library itself

Keep public types in `dev.timelxne.timeline` and device-independent indexing/calculations in
`dev.timelxne.timeline.internal`. Preserve SWT resource ownership, stable-ID selection, plot-area
clipping, asynchronous preparation, and thread-safe replay coalescing. Add or update tests and the
demo, run `mvn verify`, update user-facing documentation, and never commit generated `target/` or
workspace content.

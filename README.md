# timelxne

`timelxne` is a dependency-light timeline widget for Java SWT and Eclipse RCP. It renders large,
hierarchical event sets with instant markers, duration bars, aggregated root rows, filtering,
selection details, replay-time control, and configurable event styling.

The project currently targets Eclipse Platform 4.40 and Java 21.

## Highlights

- Hierarchical tracks with expand, collapse, show, hide, and text search
- Instant and duration events with stable overlap lanes
- Aggregated root rows with an embedded chooser for ambiguous overlaps
- Passive hover previews, exact-time guide, persistent selection details, and host callbacks
- Drag-to-scrub replay time and modifier-drag interval zoom
- Per-event colors, height, opacity, bar geometry, tooltip content, and filtering
- Asynchronous input indexing and coalesced background replay updates
- p2 feature, update site, and packaged cross-platform demo products

## Quick start

Build the library, tests, p2 repository, and demo products:

```shell
mvn verify
```

The local p2 repository is created at:

```text
dev.timelxne.timeline.repository/target/repository
```

Add the `SWT Timeline Library` feature to your RCP target/product, add
`dev.timelxne.timeline` to the consuming bundle's `Require-Bundle`, and create the widget in an E4
part:

```java
@PostConstruct
public void createControls(Composite parent) {
    parent.setLayout(new FillLayout());

    Timeline timeline = new Timeline(parent, SWT.NONE);
    TimelineTrack root = new TimelineTrack("application", null, "Application", true, true);
    TimelineTrack jobs = new TimelineTrack("jobs", "application", "Jobs", true, true);

    Instant start = Instant.now();
    TimelineEvent job = new TimelineEvent(
            "job-1", "jobs", "Import customers", start, start.plusSeconds(30));

    timeline.setInput(new TimelineInput(List.of(root, jobs), List.of(job)));
}
```

See [Integration guide](docs/INTEGRATION.md) for target-platform setup, a complete E4 example,
threading rules, replay integration, incremental updates, and troubleshooting. See
[API guide](docs/API.md) for the configuration surface and interaction defaults.

For implementation by a coding agent, provide it with the decision-complete
[AI implementation guide](AI_IMPLEMENTATION_GUIDE.md). The guide includes a ready-to-copy task
statement, exact dependency identifiers, adapter points, forbidden approaches, and acceptance
checks.

## Try the demo

After `mvn verify`, launch the product for your platform from:

```text
dev.timelxne.timeline.repository/target/products/
```

On Linux:

```shell
dev.timelxne.timeline.repository/target/products/\
dev.timelxne.timeline.demo.product/linux/gtk/x86_64/timelxne-demo
```

The demo contains two root trees, dense overlapping events, per-category styling, event filters,
and controls for opacity, text, and bar geometry.

## Interaction defaults

- Hover an event for a passive preview; the ruler displays the exact cursor time.
- Click an event to select it and open the bottom details section.
- Click an ambiguous root overlap to choose the event in the bottom details section.
- Double-click an unambiguous event to request its start time from the host application.
- Drag horizontally past 5 pixels to move the replay playhead.
- Ctrl+drag left-to-right to zoom to a range; Ctrl+drag right-to-left to fit all events.
- Use the mouse wheel for rows and Shift+wheel or the horizontal scrollbar for time.
- Press F2 to move a hovered event into the details section.

## Project layout

| Module | Purpose |
| --- | --- |
| `dev.timelxne.timeline` | Public SWT timeline bundle |
| `dev.timelxne.timeline.feature` | Library-only installable p2 feature |
| `dev.timelxne.timeline.tests` | Unit and 100,000-event performance tests |
| `dev.timelxne.timeline.demo` | Eclipse 4 demo application |
| `dev.timelxne.timeline.demo.feature` | Demo feature |
| `dev.timelxne.timeline.repository` | p2 repository and native demo products |
| `dev.timelxne.timeline.target` | Reproducible Eclipse 4.40 target definition |

## Development

Requirements:

- Maven 3.9 or newer
- JDK 21 or newer
- Linux, Windows, or macOS supported by SWT

Run all verification checks with `mvn verify`. Build artifacts and Eclipse workspaces are ignored.
For contribution and module details, see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Licensed under the [MIT License](LICENSE).

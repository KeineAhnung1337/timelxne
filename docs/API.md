# API and interaction guide

## Model

| Type | Purpose |
| --- | --- |
| `TimelineTrack` | Hierarchical row identified by a stable ID and optional parent ID |
| `TimelineEvent` | Instant or duration event with an optional application payload |
| `TimelineInput` | Immutable complete snapshot, with optional explicit time bounds |
| `TimelineDelta` | Batched track/event upserts and removals by stable ID |

Root aggregation is enabled by default. A root row displays events from its visible descendants;
separate root trees remain isolated. Overlapping root events are nested visually, and an ambiguous
click places all candidates in the bottom details section.

## Data and viewport

| API | Behavior |
| --- | --- |
| `setInput(input)` | Asynchronously prepares and replaces the complete model |
| `applyDelta(delta)` | Asynchronously applies a stable-ID batch |
| `setVisibleRange(start, end)` | Sets an explicit visible interval |
| `fitAll()` / `fitMatches()` | Fits all data or current search matches |
| `setTrackExpanded(id, value)` | Controls hierarchy expansion |
| `setTrackVisible(id, value)` | Controls event visibility |
| `setFilterText(text)` | Updates built-in case-insensitive text search |
| `setEventFilter(filter)` | Adds an application filter combined with search |

## Replay and selection

| API | Behavior |
| --- | --- |
| `setReplayTime(time, policy)` | UI-thread playhead update |
| `postReplayTime(time, policy)` | Thread-safe, coalesced playhead update |
| `addReplayTimeListener(listener)` | Reports user scrubbing |
| `addEventStartRequestListener(listener)` | Reports the details action or double-click |
| `addEventSelectionListener(listener)` | Reports selected stable event changes |
| `selectEvent(id)` | Selects from the current input; returns false when missing |
| `setDetailsExpanded(value)` | Expands or collapses selected-event details |

## Appearance

| API | Default or constraint |
| --- | --- |
| `setTheme(theme)` | Global RGB colors and logical SWT dimensions |
| `setEventStyleProvider(provider)` | Fill, border, and text RGB per event |
| `setEventBarStyle(...)` | Duration corner radius, border width, padding, and minimum width |
| `setEventTextVisible(value)` | Event labels are visible by default |
| `setEventHeightProvider(provider)` | Fraction from 0.0 to 1.0, vertically centered |
| `setEventOpacity(value/provider)` | Fill opacity only; border, text, and selection stay opaque |
| `setEventHeightMode(mode)` | `FILL_ROW` by default; `COMPACT_LANES` is available |
| `setRowSizingMode(mode)` | `FILL_AVAILABLE` by default |
| `setTooltipProvider(provider)` | Supplies custom preview/details title and body |
| `setTimeZone(zone)` / `setTimeFormatter(formatter)` | Controls every displayed timestamp |

Invalid opacity, geometry, modifier, range, and model values fail early with
`IllegalArgumentException`.

## Interaction configuration

| API | Default |
| --- | --- |
| `setPlayheadDragThreshold(pixels)` | 5 logical pixels |
| `setZoomSelectionModifier(mask)` | `SWT.CTRL` |
| `setHoverDelayMillis(milliseconds)` | 250 ms |
| `setHoverGuideVisible(value)` | true |
| `setRootAggregationEnabled(value)` | true |

Event painting and hit testing are clipped to the time plot. Selection outlines preserve the event
shape: diamonds for instant events, rounded outlines for rounded bars, and rectangles for square
bars.

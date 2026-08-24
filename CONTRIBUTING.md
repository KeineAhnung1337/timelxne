# Contributing

## Build and test

Use Maven 3.9+ and JDK 21+:

```shell
mvn verify
```

This compiles the SWT bundle and demo, runs functional and 100,000-event performance tests,
assembles the p2 repository, and packages Linux, Windows, and macOS demo products.

## Repository conventions

- Keep the public API in `dev.timelxne.timeline` and implementation details in its `internal`
  package.
- Keep model/index preparation device-independent so it remains unit-testable without SWT UI code.
- Follow SWT ownership rules: dispose resources created by the widget and never dispose system
  colors or cursors.
- Preserve stable-ID behavior when changing input, selection, lanes, or deltas.
- Update the demo and documentation when adding a public option or interaction.
- Add focused tests for device-independent calculations and run the full reactor before committing.

Generated `target/` directories and Eclipse runtime workspaces are intentionally ignored.

# Contributing to ContinentRegions

Thanks for your interest in improving ContinentRegions! 🌍

## Getting started

1. **Fork** the repository and create a feature branch off `main`.
2. You need **JDK 21** (Temurin recommended). The Gradle wrapper handles the rest.
3. Build and run the tests:
   ```bash
   ./gradlew build
   ```
   The shaded plugin jar lands in `build/libs/`.
4. Spin up a local Paper server with WorldEdit, WorldGuard and BlueMap
   pre-downloaded:
   ```bash
   ./gradlew runServer
   ```

## Project layout

See the [Architecture](README.md#architecture) section of the README. In short:
pure, unit-testable logic lives under `validation/` and `service/`; Bukkit-facing
code is isolated in `worldguard/`, `bluemap/`, `command/` and `editor/`.

## Coding guidelines

- Match the surrounding style (4-space indent, `final` where it adds clarity, no
  magic strings — use config/constants).
- **Never call the Bukkit API from an async thread.** HTTP handlers dispatch to the
  main thread via the scheduler; keep it that way.
- Keep new third-party dependencies minimal and shade/relocate them in
  `build.gradle.kts` (see the Gson/SQLite setup for the pattern).
- Add unit tests for pure logic (geometry, parsing, storage). Bukkit-free classes
  should stay Bukkit-free so they remain testable without a server.

## Pull requests

- Keep PRs focused; describe the change and how you verified it.
- Make sure `./gradlew build` passes (CI runs the same).
- Update `README.md` / `CHANGELOG.md` when behaviour or config changes.

## Reporting bugs / requesting features

Use the [issue templates](https://github.com/denfry/ContinentRegions/issues/new/choose).
Include your server version, plugin version and relevant logs.

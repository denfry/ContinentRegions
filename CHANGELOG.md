# Changelog

All notable changes to **ContinentRegions** are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- **Editor link lost its token, leaving the editor panel empty.** `/continent
  editor` appended `?token=…` to `editor-url`, but the default `editor-url` ended
  in `#continent-editor`, so the token landed in the URL **hash fragment**. BlueMap
  is a single-page app that rewrites `location.hash` on load, dropping the token
  before the editor addon reads it — the panel never built (no buttons). The token
  is now inserted as a query parameter **before** any `#fragment`, and the default
  `editor.editor-url` is simplified to `http://localhost:8100/`.

## [2.0.0] - 2026-06-14

### Added
- **SQLite storage backend** (`storage.type: sqlite`) with an in-memory read cache;
  the xerial driver is bundled and isolated per plugin.
- **Automatic YAML → SQLite migration** on first start (`storage.migrate-on-start`),
  leaving `continents.yml` untouched as a backup.
- **Ramer–Douglas–Peucker outline simplification** (`PolygonSimplifier`):
  `editor.simplify-on-save`, the `/continent simplify` command and a client-side
  preview button in the editor.
- **Continent overlap detection** (`PolygonGeometry`) with `editor.overlap-policy`
  (`error` | `warn` | `off`); edge-adjacent tiling is allowed, true area overlaps
  are reported.
- **Flag presets** (`flag-presets:` config) applied via `/continent preset`,
  `GET /presets` and an editor dropdown.
- **Per-continent history and rollback** (`HistoryService`): snapshots before each
  change, `/continent rollback`, `/continent history` and a Rollback button.
- **Per-continent visibility toggle** (`hidden`): `/continent toggle` keeps the
  WorldGuard region but hides the BlueMap marker.
- **Bulk export/import**: `/continent export all`, array-aware `/continent import`
  (BOM-tolerant) and an "Export all" editor button.
- **Richer BlueMap editor**: full undo/redo (Ctrl+Z / Ctrl+Y), an editable vertex
  list (edit X/Z, reorder, insert, delete, pick-on-map) and a flag editor.
- New REST endpoints: `/continents/{id}/preset|simplify|toggle|rollback|history`,
  `GET /presets`. Create/update now return `{ continent, warnings }`.

### Changed
- `compileOnly` WorldEdit/WorldGuard pinned to **7.3.11 / 7.0.13** to match the
  dev runtime.

### Fixed
- `simplify` no longer produces a duplicate, no-op history snapshot.

## 1.0.0 - 2026-05

### Added
- Initial release: draw continents on BlueMap, validate them
  (polygon/ID/area/self-intersection), persist to `continents.yml`, project to a
  WorldGuard `ProtectedPolygonalRegion` and a BlueMap shape marker.
- `/continent` commands (`editor`, `list`, `create`, `delete`, `apply`, `reload`,
  `export`, `import`, `flag`, `tp`), a token-secured REST API and the BlueMap
  editor addon.

[2.0.0]: https://github.com/denfry/ContinentRegions/releases/tag/v2.0.0

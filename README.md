# ContinentRegions — BlueMap continent editor for WorldGuard

> Draw continents on your **BlueMap** web map and turn them into **WorldGuard**
> polygon regions — a visual region/territory editor for **Paper** Minecraft servers.

[![Build](https://github.com/denfry/ContinentRegions/actions/workflows/ci.yml/badge.svg)](https://github.com/denfry/ContinentRegions/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/denfry/ContinentRegions?sort=semver&label=release)](https://github.com/denfry/ContinentRegions/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/denfry/ContinentRegions/total?label=downloads)](https://github.com/denfry/ContinentRegions/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21.x-blue.svg)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

**ContinentRegions** is a Paper 1.21.x plugin that turns BlueMap into a visual editor
for continents: draw a polygon on the web map, and the plugin validates it, stores it,
creates a WorldGuard `ProtectedPolygonalRegion`, and shows it as a BlueMap shape marker.

The plugin's storage is the source of truth. WorldGuard is the applied protection
layer; BlueMap is the visualization and the editor surface.

> **Keywords:** minecraft, paper, spigot, bukkit, worldguard, bluemap, region editor,
> continent, territory, polygon regions, map editor, java plugin.

## Requirements

- **Paper 1.21.x** (server)
- **WorldEdit** + **WorldGuard** (required dependencies)
- **BlueMap** (optional — without it the plugin runs in WorldGuard/storage-only mode)
- **Java 21**

## Build

The project uses Gradle (wrapper pinned to 8.10). Java 21 is required.

```bash
./gradlew build
```

The shaded plugin jar is written to `build/libs/ContinentRegions-<version>.jar`
(Gson is bundled and relocated; the server-provided APIs are not).

## Local dev server

A ready-to-run Paper server is configured via the `run-paper` Gradle plugin. It
downloads Paper, WorldEdit, WorldGuard and BlueMap automatically:

```bash
./gradlew runServer
```

The dev server lives in `run/`. EULA is pre-accepted there. The plugin's REST API
listens on `:8124`; BlueMap's web app on `:8100`.

## Install (production)

1. Download the latest `ContinentRegions-<version>.jar` from the
   [**Releases**](https://github.com/denfry/ContinentRegions/releases/latest) page
   (or build it yourself, see [Build](#build)) and drop it into `plugins/`.
2. Ensure WorldEdit, WorldGuard and (optionally) BlueMap are installed.
3. Start the server once to generate `plugins/ContinentRegions/config.yml`.
4. Set `editor.editor-url` and `editor.cors-allowed-origins` to your BlueMap web
   origin (see Configuration), then `/continent reload`.

## Usage

1. In-game: `/continent editor` → you receive a tokenized BlueMap editor link.
2. Open the link. A **Continent Editor** panel appears on the map.
3. Pick a world, choose **New continent**, fill in ID/name/color/Y-range/priority.
4. Click **Draw**, then click the map to drop outline points (Ctrl+Z undoes the
   last point, **Clear** resets, **Preview** redraws the outline).
5. Click **Save**. The plugin validates the polygon, stores it, creates the
   WorldGuard region (`continent_<id>` by default) and the BlueMap marker.
6. Verify in-game with `/rg info continent_<id>`.

Continents are restored from storage on server restart and re-drawn on BlueMap.

## Commands

All under `/continent`:

| Subcommand            | Permission         | Description                              |
|-----------------------|--------------------|------------------------------------------|
| `editor`              | `continent.editor` | Create a token and print the editor link |
| `list`                | `continent.view`   | List continents                          |
| `create <id> [world]` | `continent.editor` | Create an empty continent (no points)    |
| `delete <id>`         | `continent.delete` | Delete from storage, WorldGuard, BlueMap |
| `apply <id\|all>`     | `continent.admin`  | Re-apply stored continent(s)             |
| `reload`              | `continent.admin`  | Reload config and rebuild markers        |
| `export <id\|all>`    | `continent.view`   | Write `exports/<id>.json`, or all to `continents-all.json` |
| `import <name>`       | `continent.editor` | Import `imports/<name>.json` (single object or array) |
| `flag <id> <f> <v>`   | `continent.flag`   | Set a WorldGuard flag                    |
| `tp <id>`             | `continent.view`   | Teleport to a continent's center         |
| `preset <id> <name>`  | `continent.flag`   | Apply a flag preset from config (v2)     |
| `simplify <id> [tol]` | `continent.editor` | Ramer–Douglas–Peucker outline thinning (v2) |
| `rollback <id>`       | `continent.admin`  | Restore the previous saved version (v2)  |
| `history <id>`        | `continent.view`   | List saved versions (v2)                 |
| `toggle <id> [show\|hide]` | `continent.editor` | Show/hide a continent on BlueMap (v2) |

Permissions: `continent.admin` (op) implies all; `continent.view` defaults to true.

## REST API

Base: `http://<host>:8124/api/v1`. Write methods require `Authorization: Bearer <token>`
from `/continent editor`. CORS is restricted to `editor.cors-allowed-origins`.

| Method | Endpoint                      | Notes                         |
|--------|-------------------------------|-------------------------------|
| GET    | `/continents`                 | List                          |
| GET    | `/continents/{id}`            | One                           |
| POST   | `/continents`                 | Create (token)                |
| PUT    | `/continents/{id}`            | Update (token)                |
| DELETE | `/continents/{id}`            | Delete (token)                |
| POST   | `/continents/{id}/apply`      | Re-apply (token)              |
| POST   | `/continents/{id}/flags`      | Set flags `{ "pvp":"deny" }`  |
| POST   | `/continents/{id}/preset`     | Apply preset `{ "preset":"safe-zone" }` (token, v2) |
| POST   | `/continents/{id}/simplify`   | RDP simplify `{ "tolerance":5 }` (token, v2) |
| POST   | `/continents/{id}/toggle`     | Show/hide `{ "hidden":true }` (token, v2) |
| POST   | `/continents/{id}/rollback`   | Restore previous version (token, v2) |
| GET    | `/continents/{id}/history`    | Saved version timestamps (v2) |
| GET    | `/presets`                    | Configured flag presets (v2)  |
| GET    | `/worlds`                     | Loaded worlds                 |

Create/update responses are an envelope: `{ "continent": {...}, "warnings": [...] }`,
where `warnings` carries non-blocking notices such as continent overlaps.

## Configuration

See `src/main/resources/config.yml` for the full, commented layout. Notable keys:

- `worldguard.region-prefix` — region id prefix (default `continent_`).
- `bluemap.*` — marker set id/label, display Y, colors/opacity.
- `editor.bind` / `editor.port` — REST API bind address/port.
- `editor.token-expire-minutes` — editor token lifetime.
- `editor.editor-url` — BlueMap web app URL the editor link points at.
- `editor.cors-allowed-origins` — browser origins allowed to call the API.
- `editor.validate-self-intersection`, `editor.min-area`, `editor.max-points-per-continent`.
- `editor.simplify-on-save` / `editor.simplify-tolerance` — RDP outline thinning on save (v2).
- `editor.overlap-policy` — `error` | `warn` | `off` for continent overlaps (v2).
- `storage.type` — `yaml` or `sqlite` (v2); `storage.sqlite-file`, `storage.migrate-on-start`.
- `history.enabled` / `history.max-versions` — per-continent rollback history (v2).
- `flag-presets` — named flag bundles for `/continent preset` and the editor (v2).

### Storage backends (v2)

`storage.type: yaml` (default) stores continents in `continents.yml`.
`storage.type: sqlite` stores them in `continents.db` (the bundled, isolated
xerial driver — no external setup). With `storage.migrate-on-start: true`, the
first SQLite start imports an existing `continents.yml` automatically and leaves
the YAML file untouched as a backup.

> If BlueMap is served over HTTPS, the REST API must also be reachable over HTTPS
> from the browser (no mixed content). For local HTTP setups this is automatic.

## Architecture

```
ContinentRegionsPlugin
├─ ConfigManager            config.yml (incl. presets, overlap, history)
├─ ContinentRepository      YAML (continents.yml) or SQLite (continents.db)
├─ ContinentService         validate + simplify + overlap + persist + apply
├─ HistoryService           per-continent version snapshots (rollback)
├─ PolygonValidator         polygon/ID/area/self-intersection checks
├─ PolygonSimplifier        Ramer–Douglas–Peucker outline thinning
├─ PolygonGeometry          continent-vs-continent overlap detection
├─ WorldGuardHook           ProtectedPolygonalRegion + flags
├─ BlueMapHook              shape markers + editor asset deployment
├─ EditorHttpServer         REST API (JDK HttpServer, :8124)
├─ EditorSessionService     tokens (30 min, bound to player UUID)
└─ CommandManager           /continent subcommands
web/                        BlueMap editor addon (JS/CSS), injected at runtime
```

## Editor (v2)

The BlueMap editor panel supports:

- **Draw** mode (click the map to add outline points) and a **vertex list** with
  per-point X/Z editing, reorder (↑/↓), insert-after (+), delete (✕) and
  **pick-on-map** (⌖) to reposition a vertex by clicking the map.
- **Undo/redo** over all edits (Ctrl+Z / Ctrl+Y or Ctrl+Shift+Z).
- A **flag editor** with add/remove rows and **config presets** (apply locally,
  then Save).
- **Simplify** (client-side RDP preview), a **Hidden on map** toggle, **Rollback**
  to the previous saved version, plus Export/Import JSON.

## Limitations

- WorldGuard polygon regions are 2D (X/Z) with a `minY..maxY` range, not arbitrary 3D.
- No polygon holes; model lakes as separate higher-priority regions.
- Vertices are edited via the panel + pick-on-map; BlueMap's public JS API does
  not expose direct in-canvas marker dragging.
- Overlap detection compares polygon interiors; identical/edge-adjacent tiling is
  allowed, true area overlaps are flagged per `editor.overlap-policy`.
- Very detailed coastlines should respect `max-points-per-continent`.

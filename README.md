# AdvancedTrainControlSystem

AdvancedTrainControlSystem (ATCS) is a Paper plugin that is being built as the authoritative operating layer for a TrainCarts railway. TrainCarts remains responsible for train physics and rail execution; ATCS owns network identity, operational events, service planning, dispatch policy, passenger information, and recovery.

The current `0.1.0-SNAPSHOT` is the Phase 0/1 foundation, the first Phase 2 passenger-information adapter, and the first embedded network administration workbench. It targets Paper/Purpur 1.21.11 and compiles with Java 21.

## Implemented now

- Gradle Kotlin DSL build with pinned Paper, TrainCarts, and BKCommonLib APIs
- One deployable shaded plugin JAR
- SQLite database initialization in WAL mode
- Ordered schema migrations and pre-migration database backups
- Initial physical, service, fleet, receiver, audit, and event schema
- Core railway domain types and route invariants
- Append-only, idempotent operational event storage
- A single-threaded asynchronous event publishing pipeline
- Central English announcement templates and placeholder rendering
- The standardized TrainCarts `railrcv` receiver sign adapter
- Receiver enter/clear de-duplication for live server sessions
- A bounded `/atcs scan loaded` inventory that never loads or generates additional chunks
- Structured parsing of legacy station, spawn, announce, property, and other TrainCarts signs
- Correct separation of `spawn 9999` launch force from `12m` or `9m` regular-minecart patterns
- TrainCarts-owned railway world scope with no duplicate ATCS world list
- A persisted legacy review queue for action aliases, complex spawn patterns, and configurable launch-force boundaries
- A continuously refreshed passenger action bar showing line, service, destination, next station, and status
- Configurable saved- or loaded-chunk rail inventory, with a shape-aware physical network graph and persisted scan coverage
- Immutable per-run source-sign snapshots, deterministic evidence fingerprints, and typed infrastructure candidate identities
- An evidence-backed Candidate Review gate for departure and track-control candidates, with exact-run decisions and no automatic authority promotion
- Atomic candidate adoption with explicit target identity, server-derived authority role, optimistic concurrency, audited clear/include/ignore transitions, and automatic stale reconciliation
- An optional embedded administration site with local-token login, HttpOnly sessions, CSRF protection, rate limiting, security headers, and audited mutations
- A manual symbol workbench in which every current sign remains `PENDING` until an operator explicitly includes or ignores it
- Explicit symbol roles for passenger platforms, departure points, reversing controls, motion/re-acceleration controls, track markers, and other infrastructure
- Authoritative station and platform creation using preserved symbol coordinates as optional operator input
- Line identity management with public name, diagram colour, enabled state, and optimistic version checks
- Directional route-plan drafts with termini, circular/terminal policy, ordered station/platform calls, stop policy, dwell time, and explicit track-assignment state
- Fare gate, fare zone, and fare rule registers, including configurable sensor/output JSON and flat, station-pair, zone-pair, or distance rule types
- A web audit register for editor changes
- Read-only operational mode and dependency health reporting
- `/atcs status`, `/atcs scan`, `/atcs discover`, `/atcs announce test`, and `/atcs help`

The receiver adapter currently records `RECEIVER_ENTERING` and `RECEIVER_CLEARED`. The HUD labels these transitions as approaching and departed. Authoritative arrived, berthed, pass-through, and misalignment classification still needs route/trip assignment and platform telemetry.

## Build

```powershell
.\gradlew.bat clean build
```

The deployable JAR is generated at `build/libs/AdvancedTrainControlSystem-0.1.0-SNAPSHOT.jar`. The JAR whose name ends in `-plain.jar` is not the deployable artifact because it does not contain SQLite.

## Server requirements

- Paper or Purpur 1.21.11
- Java 21 or newer at runtime (Java 21 is the compatibility baseline)
- TrainCarts `2.0.0`
- BKCommonLib `2.0.1`
- SignLink `1.21.5-v1` is detected but remains optional in this phase
- Essencard `6.0` may remain installed during the read-only inventory phase

The compile-time adapters are pinned to the exact versions listed above. TrainCarts `2.0.0` declares BKCommonLib `2.0.0`; the server-provided `2.0.1` maintenance version is the compile-time target used by ATCS.

The current JAR has been staged successfully on Paper 1.21.11 build 132. That run used the server backup's Java 25 runtime because its installed WorldEdit build is compiled for Java 25; ATCS itself remains Java 21 compatible. See [STAGING_INVENTORY.md](STAGING_INVENTORY.md) for the captured network evidence and test boundaries.

## Install

1. Back up the server, worlds, and existing plugin data.
2. Install compatible BKCommonLib and TrainCarts builds.
3. Copy the shaded ATCS JAR into `plugins/`.
4. Start the server once so `plugins/AdvancedTrainControlSystem/` is created.
5. Keep `operating-mode: READ_ONLY` until the network inventory has been reviewed.
6. Run `/atcs status` and confirm the database and dependency health.

The web editor is disabled by default. Enable it only on a trusted administration interface. The portable default binds to loopback and generates a random token file:

```yaml
web:
  enabled: false
  bind-address: 127.0.0.1
  port: 8765
  auth:
    token-file: web-admin.token
    session-minutes: 30
    login-attempts-per-minute: 5
    secure-cookie: false
```

For remote access, keep ATCS behind an authenticated TLS reverse proxy and set `secure-cookie: true`. Do not expose the embedded HTTP listener directly to the public Internet.

Use a full server restart when replacing the JAR. Do not hot-load or hot-reload ATCS with PlugMan because the plugin owns TrainCarts sign registrations, scheduled HUD tasks, and database worker threads.

## Receiver sign

```text
[+train]
railrcv
CEN:P1
platform
```

`CEN:P1` is parsed as station `CEN`, platform `P1`. Sign placement requires `atcs.receiver.build`.

## Commands

- `/atcs status` — show runtime mode, database health, dependency versions, receiver registration, and stored event count
- `/atcs scan loaded` — inventory TrainCarts signs in chunks that are already loaded
- `/atcs scan status` — show counts from the latest stored inventory
- `/atcs discover rail` — build the physical graph using the configured `SAVED_CHUNKS` or `LOADED_CHUNKS` scope; saved mode indexes existing Anvil chunks, never generates terrain, and signs remain overlays rather than scan seeds
- `/atcs discover candidates` — create non-authoritative infrastructure suggestions for review; this never adopts a station automatically
- `/atcs discover status` — show the latest candidate and rail snapshot counts
- `/atcs review status` — show review states and configured issue counts
- `/atcs review list [page]` — list signs with review issues in TrainCarts-active loaded worlds
- `/atcs review show <sign-id>` — show the original four lines, location, status, and issues
- `/atcs review set <sign-id> <status> [note]` — record an audited decision using `approved`, `rejected`, `needs-correction`, or `unreviewed`
- `/atcs announce test <event>` — render an English announcement template with sample data
- `/atcs help` — show command help

## Legacy sign inventory

The scanner reads both sides of sign block entities in chunks that were loaded when the scan started. It processes a configurable number of chunks per tick and skips any target chunk that unloads before inspection. It never calls a chunk-loading or chunk-generation API.

The original four sign lines are preserved. Parsed attributes are stored separately, so migration tools can be improved without losing evidence. Examples recognized by the current parser include:

```text
[+train]
station
5
continue
```

```text
[+train]
spawn 9999
12m

```

For the second example, the stored values are `launch_force=9999`, `spawn_pattern=12m`, and `regular_minecart_count=12`.

## TrainCarts world scope and review policy

ATCS does not maintain an active, disabled, or archive world list. It uses TrainCarts' public world predicate and considers only currently loaded worlds enabled by TrainCarts. Configure the authoritative scope in `plugins/Train_Carts/config.yml`:

```yaml
enabledWorlds: []
disabledWorlds: []
```

Inventory review settings remain in ATCS's own `plugins/AdvancedTrainControlSystem/config.yml`; they do not define world scope:

```yaml
inventory-review:
  # Aliases create warnings only; ATCS never rewrites legacy signs automatically.
  action-aliases:
    annnounce: announce
  flag-complex-spawn-patterns: true
  launch-force:
    # Zero disables the corresponding boundary.
    minimum: 0
    maximum: 0
  page-size: 8
```

When `enabledWorlds` is nonempty, TrainCarts treats it as the allow-list. Otherwise all loaded worlds except `disabledWorlds` are enabled. ATCS snapshots that effective set on the Paper main thread and filters discovery, review, administration, and future control through it. Unloaded or TrainCarts-disabled worlds are ignored without ATCS modifying their files or records. ATCS never applies a special rule to a world name; if a world is later loaded or its TrainCarts setting changes, ATCS follows TrainCarts' resulting decision.

Review decisions are stored in `audit_record`. `APPROVED` and `REJECTED` signs leave the open issue queue while preserving their detected evidence; `NEEDS_CORRECTION` remains open. If any preserved sign line or parsed action changes in a later scan, the previous decision is invalidated and the sign returns to `UNREVIEWED`.

The web editor adds a separate evidence-backed infrastructure candidate layer. An absent decision is `PENDING`. `INCLUDE` requires an explicit target ID and display name while the server derives the only valid authority role from the candidate evidence; `IGNORE` records an audited rejection, and `CLEAR` returns a decided candidate to `PENDING` with a monotonically increasing decision version. Every decision is bound to the latest discovery run, source sign, evidence fingerprint, and revision. Changed evidence, a newer run, or an inactive TrainCarts world makes the decision stale and disables only the exactly linked derived authority. Legacy action type, nearby announcements, and discovery confidence are evidence only. In particular, a station sign is not accepted or rejected merely because an announcement sign is present or absent. Reverse and re-acceleration station signs are classified as track controls before passenger inference.

## Network administration workbench

When enabled, open the configured loopback address and log on with the generated token stored in the plugin data directory. The current workbench provides:

- a WebGL2 overhead 3D physical rail map with perspective and top views, map-style grid, pan/zoom/orbit controls, material-aware rail colors, and all active legacy symbols overlaid independently;
- preserved four-line sign evidence and manual `PENDING` / `INCLUDED` / `IGNORED` classification;
- a separate Candidate Review ledger for departure and track-control suggestions, including preserved raw lines, parsed attributes, inference evidence, evidence fingerprints, stale state, and one-record-at-a-time authority adoption;
- station and platform creation;
- line identity creation/editing and directional route-plan drafts with ordered station calls;
- fare gate creation/editing, fare zones, and fare rules;
- an audit change register.

All web editor text and operational output are English. The interface uses a clean, high-density administration layout with a light theme by default and an optional dark theme. It is implemented as framework-free embedded assets so installations do not need a separate Node.js service. The physical map deliberately requires WebGL2 and does not include a legacy rendering compatibility layer.

Rail discovery can inspect many millions of block positions. `SAVED_CHUNKS` first performs a background, read-only palette index over existing region files. Complete rail-bearing chunks are loaded without generation for Bukkit snapshots and released immediately when ATCS loaded them. Rail data in incomplete protochunks is decoded directly from Anvil NBT, so ATCS does not complete, generate, or rewrite those chunks. `LOADED_CHUNKS` remains available for a faster memory-only pass. Block inspection is divided across server ticks, empty snapshot sections are skipped, and the final run stores coverage and block-check counts.

## Passenger HUD

Passengers in any loaded TrainCarts train receive a continuously refreshed action bar:

```text
Central Line Express  ◆  To: Airport  ◆  Next: Harbour  ◆  In service
```

Until ATCS trip assignments become authoritative, the HUD uses the live TrainCarts destination and destination route. Optional TrainCarts tags can override passenger-facing values:

```text
atcs:line=Central_Line
atcs:service=Limited_Express
atcs:destination=Airport_Terminal
atcs:next=Harbour
```

Underscores are displayed as spaces. These are transitional import tags; final line, destination, and next-station values will come from the ATCS trip database.

## Safety boundary

`READ_ONLY` is the default. In this mode ATCS can initialize its digital twin, observe receiver activity, and persist operational evidence, but train spawn/control writes must remain disabled. Unknown trains are never deleted automatically.

## Next implementation slice

The next slice hardens rail evidence before any route becomes operational authority: place Anvil/NBT ingestion behind explicit size, sector, decompression, allocation, and unknown-shape bounds; record per-chunk `COMPLETE` / `PARTIAL` / `FAILED` outcomes; and introduce stable physical rail-component and corridor identities that survive discovery-run IDs. The following corridor-editor slice can then select, split, join, and validate reusable directed track segments against exact rail evidence instead of transient scan rows. Route plans remain disabled while track assignment is `UNASSIGNED`, `PARTIAL`, stale, or discontinuous. After that, ATCS can compile approved plans into operational route variants, attach UUIDs to adopted trains, map receiver observations to scheduled trip visits, and implement the full platform state machine:

```text
APPROACHING -> ENTERING -> BERTHED -> DWELLING -> DEPARTING -> PLATFORM_CLEARED
```

The staging inventory and schema V7 evidence gate provide the exact server/plugin versions and real station, spawn, announcement, reversing-control, motion-control, and physical rail evidence needed for this work. Complex spawn patterns, malformed legacy signs, and semantic station assignments remain review-only until an operator explicitly approves their interpretation.

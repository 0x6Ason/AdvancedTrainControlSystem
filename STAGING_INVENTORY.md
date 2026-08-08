# Staging Inventory Snapshot

This report records the read-only evidence captured from the server backup supplied on 2026-08-05. The original `D:\StagingSv\full.zip` was not modified. All server boots used a disposable selective copy bound to `127.0.0.1:25575` with the whitelist enforced and external backup, updater, proxy, map-rendering, and web-service plugins excluded.

## Compatibility verification

- Archived `server.jar`: Paper 1.21.5 build 114
- Archived version history: Paper 1.21.11 build 126 followed by Paper 1.21.5 build 114
- Final verification runtime: Paper 1.21.11 build 132, the latest stable build available during the test
- Final Java runtime: Temurin 25.0.1 from the archived AutoPlug runtime
- Final staged JAR SHA-256: `A583B92642FC2D69898DE6E6AFDF2B93395D9C7727809F5C7EA9E800A8122C0F`
- TrainCarts: 2.0.0
- BKCommonLib: 2.0.1
- SignLink: 1.21.5-v1 build 99
- Multiverse-Core: 5.7.0
- WorldEdit: 7.4.4
- WirelessRedstone: 4.2.1

An earlier foundation artifact enabled successfully on Paper 1.21.5. The current plugin targets Paper 1.21.11 directly and does not carry a separate legacy compatibility path. On the final 1.21.11 boot, the database was healthy, `railrcv` registered, the passenger HUD task started, every required integration was available, and the embedded administration site listened on loopback only. Full stops used the local staging RCON interface instead of a plugin hot reload.

## Inventory scope

The legacy sign inventory is deliberately not a full disk scan. `/atcs scan loaded` only inspects chunks already loaded at the moment the command starts and never loads or generates another chunk. Physical rail inventory uses the separate saved-chunk process documented below.

Two completed scans were stored:

| Server | Chunks scanned | Sign sides observed in that run |
| --- | ---: | ---: |
| Paper 1.21.5 | 2,108 | 221 |
| Paper 1.21.11 | 3,493 | 203 |

The database currently records 221 signs as present. That value is the safe accumulated inventory: a later loaded-chunk scan only marks a sign missing when its own chunk was included in that scan. Every observed sign used the front side.

## Present signs

| World | Station | Spawn | Announce | Switcher | Property | Other | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `world` | 61 | 15 | 97 | 0 | 4 | 26 | 203 |
| `old_world` | 6 | 4 | 0 | 2 | 0 | 6 | 18 |
| **Total** | **67** | **19** | **97** | **2** | **4** | **32** | **221** |

`old_world` is archive-only. It must not be auto-loaded, adopted into active routes, or controlled by ATCS. Its 18 inventory records remain read-only historical evidence.

This decision is represented by the staging-only YAML override rather than a Java constant. The open-source plugin defaults remain generic.

The V003 review migration classified all 18 `old_world` records as `ARCHIVED`. The active review queue contains three signs: the `annnounce` action at `world -822 58 779` and the two `2m24s2m` spawners at `world -471 68 -740` and `world -471 68 -738`. The misspelled action is recorded as `NEEDS_CORRECTION` with an audited console note; its Minecraft sign was not changed.

Of these signs, 208 use the always-powered `[+train]` header and 13 use a normal `[train]` header. Station instructions comprise 62 `continue` signs and 5 `reverse` signs.

## Spawn evidence

| Spawn pattern | Signs | Safe regular-minecart interpretation |
| --- | ---: | --- |
| `4m` | 6 | 4 regular minecarts |
| `9m` | 6 | 9 regular minecarts |
| `12m` | 3 | 12 regular minecarts |
| `1m` | 1 | 1 regular minecart |
| `20m` | 1 | 20 regular minecarts |
| `2m24s2m` | 2 | Review required; preserve the TrainCarts pattern verbatim |

Launch forces are `9999` on 14 signs, `3000` on 4 signs, and `4` on 1 sign. Spawn intervals include `00:20`, `00:30`, `00:35`, `00:40`, `00:45`, `00:50`, and `01:00`.

The importer must not treat the `m` suffix as minutes. It must also avoid collapsing `2m24s2m` into a guessed cart count because that is a mixed TrainCarts spawn pattern.

## Passenger-information evidence

The scan found 97 valid `announce` signs. Their messages already follow recognizable roles such as line/service, destination, next station, arrival, end-of-line, and dwell notices. Spelling and phrasing vary, including both `Arrive:` and `Arrived:`. Central English templates can normalize passenger output while keeping every original sign line as evidence.

One additional sign uses the misspelled action `annnounce`. It is intentionally classified as `OTHER` and should be flagged for operator review instead of being silently corrected.

## Physical network and web-editor verification

`/atcs discover rail` completed a V7 saved-world inventory of every rail-bearing active-world chunk. The background region index inspected 37,087 saved chunk entries and found 3,222 chunks whose block-state palettes contain rail. The final snapshot contains 48,936 rail nodes and 39,718 shape-aware rail edges. It scanned all 3,222 considered chunks, performed 116,584,448 block checks, recorded 160 connections reaching the edge of the saved rail set, and did not reach the configured 500,000-node safety limit. Every one of the 3,222 target chunk coordinates has at least one stored rail node.

The first saved-world pass reached only 3,095 of those chunks because Bukkit correctly refused to load 127 incomplete protochunks whose status was `minecraft:initialize_light`. Their existing block-state palettes still contained real rail data. ATCS now reads the rail states in those chunks directly from Anvil NBT without completing, loading, generating, or rewriting them. This added 1,073 nodes and 683 edges, reduced the previously artificial boundary count from 257 to 160, and raised coverage from 3,095/3,222 to 3,222/3,222.

The older 18,689-node / 18,012-edge loaded-chunk result and 13,046-node / 13,035-edge sign-seeded experiment are retained only as historical comparisons. Station, spawn, announcement, and control signs are independent evidence overlays and never seed or limit rail discovery.

The live administration API served all 48,936 nodes and 39,718 edges without truncation and reported 3,222/3,222 coverage. All embedded HTML, CSS, application JavaScript, and WebGL map assets returned successfully, with the light theme marked as the default. The graph is accompanied by all 203 active-world symbols. The 18 `old_world` archive records are excluded. The malformed `annnounce` record remains searchable by its preserved text at `world -822 58 779` with all four original sign lines.

No infrastructure decision was applied during visual staging verification. The web register therefore showed 203 `PENDING`, zero `INCLUDED`, and zero `IGNORED` active symbols. This is separate from the V003 parser-quality review status described above: parser review does not authorize a station, platform, or control-point assignment.

Schema V7 and the following web modules were exercised read-only against the staging database:

- WebGL2 physical network map in 3D perspective and top-down modes, including the independent symbol overlay;
- restrained light-default operations workplane with an optional dark theme;
- symbol search and property inspector;
- station/platform forms;
- line identity editor and directional route-plan editor;
- gate, fare-zone, and fare-rule forms;
- audit register.

Mutation routes were exercised by automated tests against a temporary database, including login, CSRF rejection, manual symbol decisions, stations, lines, directional route plans, fare gates, zones, fare rules, and audit reads. No synthetic station, route, line, gate, or fare record was inserted into the staging database.

## Review and safety notes

- No legacy sign, rail, or world block was modified. The parser-quality review state and the infrastructure adoption decision are independent, versioned layers.
- SQLite `PRAGMA quick_check` returned `ok` after the web and rail-discovery verification.
- All 41 automated tests passed, including direct extraction of shaped rail data from an incomplete Anvil chunk.
- The final full scan held 20.0 TPS over the one- and five-minute windows and 19.9 TPS over the fifteen-minute window.
- The supplied outer ZIP contains CRC errors in an AutoPlug historical backup, AutoPlug's compile-time `ct.sym`, one BlueMap tile, a Paper cache JAR, one Paper library, and an archived log. No selectively extracted world region, ATCS dependency JAR, or TrainCarts data file reported a CRC error.
- The disposable staging boots allowed TrainCarts and Paper to save their copied runtime state. That copy is not an authoritative world backup; `full.zip` remains the immutable source for future extraction.
- Adoption into authoritative station, platform, route, control-point, gate, or train records requires an explicit operator decision. Discovery output remains evidence only.

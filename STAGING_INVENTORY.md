# Staging Inventory Snapshot

This report records the current clean-reset verification of ATCS on the disposable staging copy. The original `D:\StagingSv\full.zip` remains untouched. ATCS was restarted with a fresh plugin data directory and a new schema V7 database; no earlier ATCS configuration, review state, or inventory rows were carried forward.

## Compatibility verification

- Paper: 1.21.11
- TrainCarts: 2.0.0
- BKCommonLib: 2.0.1
- SignLink: 1.21.5-v1
- ATCS operating mode: `READ_ONLY`
- Database: fresh schema V7
- Administration site: loopback only on `127.0.0.1:8765`
- Deployed JAR SHA-256: `151A1A8CCB3D268D799B00F93C29EC1842E1003E3BAC2660D3481FC0DE6A0EEC`

The staged plugin enabled cleanly, registered the `railrcv` sign action, started the passenger HUD task, and reported healthy required integrations. Full stops use the local staging RCON interface instead of plugin hot reload.

## Railway world scope

TrainCarts is the only authority for railway world scope. ATCS asks TrainCarts which currently loaded worlds are enabled and keeps no separate active, disabled, or archive world list.

On the verified staging boot, TrainCarts reported these enabled loaded worlds:

- `world`
- `world_nether`
- `world_the_end`

Both `enabledWorlds` and `disabledWorlds` are empty in the current TrainCarts configuration. Under TrainCarts semantics, every loaded world is therefore enabled. `old_world` is not loaded, so it is outside the current runtime set. ATCS does not special-case, archive, disable, load, or mutate it. If another plugin later loads that world, TrainCarts configuration—not ATCS—determines whether it belongs to the railway scope.

The administration API returned the same three-world set. Rail-map requests require an explicit world parameter rather than falling back to a hard-coded world name.

## Current sign inventory

`/atcs scan loaded` completed against the TrainCarts-enabled loaded worlds. It inspected 1,922 chunks already loaded when the scan began, observed 203 present sign sides, and skipped no target chunks. The scanner did not load or generate any additional chunk.

| World | Station | Spawn | Announce | Property | Other | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `world` | 61 | 15 | 97 | 4 | 26 | 203 |
| `world_nether` | 0 | 0 | 0 | 0 | 0 | 0 |
| `world_the_end` | 0 | 0 | 0 | 0 | 0 | 0 |
| **Total** | **61** | **15** | **97** | **4** | **26** | **203** |

The clean reset retained no prior ATCS infrastructure decisions. Discovered signs are evidence for operator review; they do not create stations, platforms, routes, or control points automatically. Original sign text is preserved. The known misspelled `annnounce` action at `world -822 58 779` remains evidence for review and was not rewritten in Minecraft.

Candidate inference produced zero passenger stations, zero platforms, 15 departure points, and 61 track motion controls. In particular, `reverse` and `continue`/re-acceleration instructions are classified as control evidence before passenger inference, even when nearby arrival text exists. These remain reviewable candidates and were not promoted into authoritative topology.

## Physical rail inventory

`/atcs discover rail` completed a saved-world inventory across the current TrainCarts-enabled loaded-world set. The persisted snapshot contains:

- 3,222 rail-bearing saved chunks considered and scanned;
- 48,936 rail nodes;
- 39,718 shape-aware rail edges.

Saved-world discovery indexes existing Anvil data and does not generate terrain. Station, spawn, announcement, and control signs are independent evidence overlays and never seed or limit physical rail discovery.

## Review and safety notes

- No legacy sign, rail, or world block was modified.
- The Java 21 build gate passed all 41 tests and the English-text policy with no failure, error, or skip.
- `old_world` remained unloaded and untouched; its status is a consequence of current server loading and TrainCarts scope, not an ATCS archive policy.
- The original `D:\StagingSv\full.zip` remains the immutable source for future clean extraction.
- The staging server stayed healthy and responsive during discovery.
- Adoption into authoritative station, platform, route, control-point, gate, or train records still requires an explicit operator decision.

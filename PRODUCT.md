# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The primary user is a Minecraft railway server owner or trusted operator configuring and supervising a TrainCarts network. Future open-source users must be able to adapt the system to their own worlds, lines, stations, rolling stock, fares, and operating rules through configuration and explicit administration decisions.

## Product Purpose

AdvancedTrainControlSystem is the authoritative operating and administration layer around TrainCarts. It turns physical rail evidence and legacy signs into a reviewable network model, then supports service planning, passenger information, fares, gates, dispatch policy, and recovery without silently changing world blocks or inventing station semantics.

## Positioning

ATCS separates observed Minecraft infrastructure from operator-approved railway meaning. Rails and signs remain evidence until an operator explicitly includes, ignores, classifies, or assigns them to authoritative station, platform, line, route, gate, and fare records.

## Operating Context

Operators work from a dense desktop administration interface while a Paper 1.21.11 server and TrainCarts remain responsible for world state and train physics. TrainCarts is the sole source of truth for which loaded worlds belong to the railway. The physical map must expose every saved rail in those worlds, including track with no station or announcement sign. Long rail scans run incrementally and persist their result for later web sessions.

## Capabilities and Constraints

- Target Paper/Purpur 1.21.11 with Java 21 source compatibility.
- TrainCarts executes trains; ATCS owns network identity, operational policy, passenger information, and administration records.
- All code comments, web copy, commands, announcements, and operational output are English.
- World names, station rules, train lengths, forces, fares, and similar installation details must be configuration-driven rather than hard-coded.
- Saved-world rail discovery may load complete existing chunks for snapshots and may read incomplete protochunks directly, but must never complete or generate terrain or modify rail blocks.
- Signs never seed or limit rail discovery and never become authoritative records automatically.
- The embedded editor remains framework-free and served directly by the plugin; the map requires WebGL2.

## Brand Commitments

The product name is AdvancedTrainControlSystem, abbreviated ATCS. The user requires a modern interface with simple, direct color, a light theme by default, and an optional dark theme. TFlow is a supplied craft reference for decisive typography, clarity, and restraint, not a template to copy. The interface must avoid generic AI-dashboard styling and remain credible as railway operations software.

## Evidence on Hand

- The server staging copy and persisted SQLite inventory under `D:\StagingSv\atcs-test`.
- The immutable original server archive at `D:\StagingSv\full.zip`.
- The project implementation, README, and staging inventory in this repository.
- Existing TrainCarts signs and saved world rails; no synthetic station, line, route, gate, or fare records should be represented as real staging data.

## Product Principles

1. Evidence before authority.
2. Explicit operator control over inference.
3. Complete physical visibility without world mutation.
4. Configuration over server-specific constants.
5. Operational clarity before decorative expression.

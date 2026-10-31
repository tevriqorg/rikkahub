# P14 / RikkaHub 3.1.1 — Unified integration gate (2026-10-31)

Status: **EXPERIMENTAL — NOT OWNER-ACCEPTED, NO FORMAL RELEASE.**

## Frozen sources of authority

- P14-Entry Issue #51, original P14R3, P14R3 Architecture Addendum, P14R3 Remote Channel (§§6, 8, 11, 14, 19), R3 Recovery Matrix, P14R2 Profile/Capture/Observation requirements, and Server/Android Architecture.
- Android PR #14 is now the single active 3.1.1 client-upgrade line. No second P14 runtime or 2.5.6 notification maintenance.

## Exact source lineage and notifications governance

- Existing stable test baseline: deaec054 (unchanged), package me.rerere.rikkahub.debug.
- RikkaHub upstream 3.1.1: ad2c4430.
- Previous 3.1.1 integration: 85dbb456 (upstream ChatService/runAgent, native P14 durable message adapter, Coil initialization).
- Notification RC-G5 Android source: PR #15 / 982fe21e, merged as the **second parent** of 96c518bd into the upgrade branch. All six files and commit history retained. Do not cherry-pick duplications.
- PR #15 closed as superseded, NOT accepted. Permanent rollback tag archive/p14-notification-native-discussion-982fe21e-20261031 points to the exact original SHA.
- The old notification branch remains frozen while its separate Tokyo VPS worktree is checked out. New changes go to PR #14.

## Server fixes (read-only compatibility, never port Python into Android)

- Server PR #57: duplicate Outbox/Completions, 500 and Working progress fixes.
- Server PR #58: authoritative NotificationRow/producer/context re-resolution for every remote discussion turn.
- Server PR #59: ddace69 final-only append-only OpenAI SSE after mutable Hermes drafts, heartbeat while pending.
- Latest Tokyo TEST container verified running p14-server:ddace69b2c34da0860b62e32d1053e04d0bd0e74; historical read-only replay exactly 785/785 stored final characters and one terminal stop/DONE, no stream consistency error. No Hermes invocation or production service mutation in this Android upgrade.
- Notification context is untrusted information, never approval for an Agent action, deploy, or repair.

## Requirement gates (evidence vs unknown)

| Gate | Actual state |
|---|---|
| S0-LOCAL / SYNC-REPLAY | Existing P14 Room/Outbox and replay retained. Notification source is accepted locally/defer-AI and flushed before native chat. Synthetic and two-device reacceptance pending. |
| RC-G1 Native chat | 3.1.1 native ChatService/runAgent, old GenerationLoop removed, P14 remote-only tools/memory isolation and stable identities kept. Android main source + JVM baseline green, post-notification live UI regression pending. |
| RC-G2 media / voice | Existing encrypted attachments and true-voice source kept; new physical-device tests pending. |
| RC-G3 message ops | Existing quote revision, remote edit/delete and Activity kept. Must retest real behavior after 3.1.1 merge. |
| RCG4-NOTIFICATION | Canonical details, provenance, notification -> native conversation, click navigation inherited from 982fe21e; native CI pending. OEM system push still unverified. |
| RCG5-INTERACTION | One deterministic native conversation ID per notification; background source Outbox barrier; no execution/approval from discussion. Synthetic tests pending. No Hermes approval/execution allowed. |
| DUR-REMOTE-DB | New P14Upgrade30To32PreservationTest creates real Room v30 fixture, upgrades with production factory to v32, validates conversation and usage; independent synthetic P14 v6 DB reopened with pending Outbox intact. Actual user-device migration still RED. |
| VIEWS-001 / CAPTURE-001 | Real emulator screenshot shows quick-add soft keyboard hidden after landscape rotation, fixed by post-modal focus/IME retry 839a53c0; strict visual test still pending. |
| Setup/auth/push | APIs and controls retained; physical and multi-device acceptance pending. |

## GitHub Actions evidence

- Pre-notification 3.1.1 Android compile PASS: https://github.com/tevriqorg/public-ci/actions/runs/41202660927
- PR #15 merged into 3.1.1 compile PASS: https://github.com/tevriqorg/public-ci/actions/runs/41204141055
- Public CI emulator JDK rate-limit repair PR #11 and Room migration gate PR #12 merged; legacy 2.5.6 still supports its own task names.
- Latest full ARM64/JVM/Android-test compile: https://github.com/tevriqorg/public-ci/actions/runs/41205120797
- Latest fully synthetic, no-Hermes emulator: https://github.com/tevriqorg/public-ci/actions/runs/41205117582

**No new installable 3.1.1 candidate until post-merge CI and Room migration are GREEN.** Synthetic acceptance does not prove owner in-place data upgrade. CI owns Android build. Existing pinned TEST certificate on VPS may sign a verified immutable CI artifact after all gates, upload only a labeled GitHub test prerelease. Never use Hermes or real Agent actions under current authorization.

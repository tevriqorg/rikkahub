# P14 / RikkaHub 3.1.1 — Integration gate (2026-10-31)

**Status: EXPERIMENTAL — NOT FOR OWNER INSTALLATION.** No Hermes use, server deployment, or APK distribution.

## Source and recovery authority
- P14 Android: deaec054f4411e3d97e64329359a55cc8d3369f2.
- Upstream release 3.1.1: ad2c4430d50ba52392b2941367f98ef59614cb73.
- P14 authority Issue #51, Requirement P14R3 and P14R3_REMOTE_CHANNEL; recovery rows S0-LOCAL, SYNC-REPLAY, RC-G1, RC-G4, CAPTURE-001.

## Architecture blocking gate
- 3.1.1 changes approximately 646 files; 10 conflicting files and 24 conflict blocks.
- Native ChatService now uses the upstream runAgent and ChatRequestFactory, while P14 owns durable remote message identity, Outbox, retry, edits and quote revision.
- First-pass integration deliberately retains P14 ChatService and GenerationLoop. This is not proof of compatibility with the new 3.1.1 Agent architecture. Full migration remains a required gate.
- App version code 194, 3.1.1 with P14 CI test suffix; preserve exact application ID and long-lived fixed-signature install track.
- Upstream 3.1.1 already contains equivalent context-limit and presigned S3 download patches: do not duplicate cherry-picks. Image/video creation and new SSE code need semantic comparison against archived branches.
- Room main DB v30 to v31 to v32 migration requires representative dataset testing. P14 product Room v6 must remain independent.
- Preserve Entry body vs Conversation, real native chat and same message IDs, local Outbox, replay, stop/retry/edit, and UnifiedPush/Interaction.

## Required gates before install
1. GitHub Actions compile, unit tests, Android test compilation — never build Android code on VPS.
2. Adapt P14 remote execution into the native 3.1.1 Agent architecture, without a second chat engine.
3. Database upgrade and rollback rehearsal.
4. Real multi-turn conversation and device push checks.
5. Fixed signature human-test build only after all gates pass.

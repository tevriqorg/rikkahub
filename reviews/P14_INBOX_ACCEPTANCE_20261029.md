# P14 Inbox candidate acceptance — 2026-10-29

Authority: P14-Entry #51. Existing rows VIEWS-001 / S0-LOCAL / SCALE-001; frozen requirements/schema unchanged. Original design readback: P14R3 §§4.1–4.4/5/9/13–14, Architecture Addendum §§3/5/8/10, Remote Channel §§4.4/5/11, and P14R2/Profile carry-forward. Current human instruction makes AI discussion explicitly optional.

Implementation: `c6bbde942a89b2d0c6c2443472c6d387afb98604`. Companion Server existing PR #56: `f805b994ef995ca8592b2176269d9f26a845870e`. This later commit only preserves evidence; application/build/test sources remain byte-identical to the tested implementation.

## Matrix

| Item | Result / evidence |
|---|---|
| Inbox → independent detail → optional AI → detail / back, archive filter, View classification, dark theme | 6 Compose UI tests pass, real API35 emulator screenshots |
| Actual Inbox Save without AI provider/model/credentials | PASS, source accepted into Room/Outbox with existing nullable binding metadata |
| App network unavailable | PASS: same UID online probe succeeds; IPv4/IPv6 UID block then causes ConnectException while loopback host probe remains alive |
| Room/draft/media/Outbox durability and idempotency | localContracts + seedOffline → external force-stop → verifyOffline PASS |
| Actual saved Inbox record after process restart | verifyQuickCaptureAfterRestart PASS, one message/Outbox operation |
| ARM64 / JVM / test compilation | public-ci run 40859674875 SUCCESS |
| Final emulator combined acceptance | public-ci run 40864835331 SUCCESS: 6 UI + 7 instrumentation checks |
| Quiet Server Capture, first discussion source/voice context, same Conversation, old request hashes, >500 quiet Captures, regression | 95 passed / 0 skipped in isolated SQLite/PostgreSQL, Server PR #56 evidence |
| Private Server GitHub CI | NOT STARTED: account payment/spending limit; not represented as green |
| Search | Current loaded records only |
| Custom tags / calendar / reminders | Pending and explicitly marked in UI |
| Fixed P14 signing, distribution, live deployment, physical-device/live-Hermes/multi-device acceptance | Not performed; requires later authority / acceptance |

Raw screenshots, XML, transcripts, network rules/probe evidence and SHA-256 manifest: `evidence/p14-inbox-c6bbde94/acceptance.json` and sibling files. All data is synthetic. No real Hermes process/request was invoked.

The earlier Wi-Fi/mobile disable snapshot still showed an active default network, so it was not treated as sufficient offline proof. Final acceptance uses verified App-UID traffic rejection with online/offline control rather than relying on radio-toggle commands.

CI APK uses a temporary Android Debug certificate despite legacy UNSIGNED artifact naming. It has NOT received the fixed P14 certificate and cannot overwrite the installed fixed-signature app. Package identity/versionCode are unchanged. The current live Server still runs `88293f63-viewfix-f4b0d507`; its missing new endpoint causes safe 404/pending behavior, never fallback to automatic AI. Do not claim complete live sync or close Recovery #51.

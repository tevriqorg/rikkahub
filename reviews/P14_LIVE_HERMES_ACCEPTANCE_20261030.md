# P14 Entry discussion: real backend and native acceptance

Owner authorized real Hermes acceptance on 2026-10-30 after rejecting the pending-only chat. Synthetic records and a separate SQLite Server are used; production Server, records, pairing, credentials and Hermes Core are unchanged.

## Original design readback and reuse

Authority #51, central matrix S0-LOCAL/CAPTURE-001; Remote Channel §§2–4.4 (Server-facing client, authoritative remote context, Entry-first chat), Architecture Addendum §§2–3 (product identities separate from Hermes), Server/Android design §6 (Room/Outbox) remain authoritative.

Existing remote chat in `ChatService` already accepts into `P14ProductRepository.acceptRikkaMessage` and uses the Server OpenAI-compatible streaming facade. Its facade `_append_user_turn` feeds the same durable product Conversation and Hermes inbound path. Inbox discussion uses `acceptEntryContinuation` on the existing Conversation, existing Outbox and existing replay/WebSocket receiver. No second Agent, new Conversation or replacement chat protocol is needed. The earlier application repair supplied the omitted `quote.source_revision`, repaired provable legacy pending payloads in place, and fixed delivery/retry/display ordering.

## Results by layer

| Layer | Evidence | Result |
|---|---|---|
| Real Server HTTP → stock Hermes CLI → Server | `evidence/p14-live-hermes-20261030/result.json` | PASS: three turns; revised v2 context, prior-turn recall, revised v3 context; same Conversation and Hermes Session |
| Actual P14 Platform Adapter and BasePlatform dispatch → stock Hermes CLI | `evidence/p14-live-hermes-20261030/platform-result.json` | PASS: two turns, 14 real streamed updates, same Conversation/Session, retry dedupe |
| Actual Android Activity → isolated Server → actual adapter/real Hermes → native reply | Actions run below | IN PROGRESS; not yet accepted |
| Owner physical device, existing production gateway/deployment | No substitution by these isolated tests | PENDING |

Hermes checkout `dce1e9b37581dd62e480a9064dc04a709c2940d3`; existing configured remote provider, no tools enabled for these acceptance queries. Backend test handler is the stock CLI wrapper attached to the actual adapter, not the existing production GatewayRunner. This distinction is intentional and must not be hidden by an end-to-end claim.

Server image/source `f805b994ef995ca8592b2176269d9f26a845870e`. No source compilation on the VPS. Private CI node is single-use/ephemeral; test Server bearer is generated only for the isolated DB. Neither Hermes nor production credentials are sent to CI. Listener is localhost/Tailscale only. Temporary task secrets and nodes must be removed after acceptance.

## Native execution

Test source `64aa50b58ef27c16cbf98c0b744296aafee6b484`, existing PR13 branch. Native test explicitly requires live opt-in, synthetic opt-in, ranchu/goldfish hardware, no previous pairing, and exact emulator-host test endpoint. It opens the production Activity, sends two actual UI turns, requires durable real assistant results and displayed content, checks causal order and unchanged original Capture, and returns through Detail to Inbox.

- Run41041382689 failed before Android: official Tailscale action adds `--accept-routes` internally; an explicit duplicate option was rejected. Read the failure log, removed the duplicate, and dispatched replacement.
- Current run: https://github.com/tevriqorg/public-ci/actions/runs/41041603757

The previously passed six-screen/IME/rotation/attachments/force-stop/strict-UID regression is run again before the live test. Native live screenshots and the correlated adapter journal are required before updating this row to PASS.

## Drift check

No Requirement/schema/architecture or P2 expansion; original Entry remains immutable and revised Message carries current context. No production deploy, Core modification, merge, formal Release or P20/QQ distribution. Physical acceptance remains the owner’s decision.

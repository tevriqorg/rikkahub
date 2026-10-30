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
| Actual Android Activity → isolated Server → actual adapter/real Hermes → native reply | Actions41043503058; `native-7d36b6c5/native-live-result.json` and `adapter-journal.json` | PASS: two displayed real replies, revised context, same Conversation/Session, 14 streamed updates, source preserved and return navigation |
| Existing production adapter compatibility | `compat-old-result.json` vs `compat-patched-result.json` | **RED: deployed old adapter drops quote fields; minimal patch tested, deployment approval required** |
| Owner physical device | No substitution by isolated tests | PENDING after adapter repair |

Hermes checkout `dce1e9b37581dd62e480a9064dc04a709c2940d3`; existing configured remote provider, no tools enabled for these acceptance queries. Backend test handler is the stock CLI wrapper attached to the actual adapter, not the existing production GatewayRunner. This distinction is intentional and must not be hidden by an end-to-end claim.

Server image/source `f805b994ef995ca8592b2176269d9f26a845870e`. No source compilation on the VPS. Private CI node is single-use/ephemeral; test Server bearer is generated only for the isolated DB. Neither Hermes nor production credentials are sent to CI. Listener is localhost/Tailscale only. Temporary task secrets and nodes must be removed after acceptance.

## Native execution

Test source `7d36b6c5671efa4102a6ae83635ccc17c89e442e`, existing PR13 branch. Native test explicitly requires live opt-in, synthetic opt-in, ranchu/goldfish hardware, no previous pairing, and exact emulator-host test endpoint. It opens the production Activity, sends two actual UI turns, requires durable real assistant results and displayed content, checks causal order and unchanged original Capture, and returns through Detail to Inbox.

- Run41041382689 failed before Android: official Tailscale action adds `--accept-routes` internally; an explicit duplicate option was rejected. Read the failure log, removed the duplicate, and dispatched replacement.
- Run41041603757 failed in visual test geometry/owner access before live AI. Lazy geometry reads were moved to the UI thread and rotated/re-themed owners were isolated from the subsequent chat process. Superseded runs were cancelled.
- PASS: https://github.com/tevriqorg/public-ci/actions/runs/41043503058

The six-screen/IME/rotation/attachments/force-stop/strict-UID regression passed again before the live test. Native live screenshots were inspected: both actual replies are displayed, with the second question and reply in correct order and the real keyboard present. Native user message IDs and reply bodies match the adapter journal exactly; both calls use Hermes Session `20261030_190042_aa3a26`. Evidence is preserved under `evidence/p14-live-hermes-20261030/native-7d36b6c5/`. The native harness uses the stock `GatewayInboundMixin._prepend_inbound_reply_context` for quote injection, rather than adding a test-only instruction to prefer the new revision. The existing gateway implementation already preserves the full quoted content; no Core change is required.

## Drift check

No Requirement/schema/architecture or P2 expansion; original Entry remains immutable and revised Message carries current context. No production deploy, Core modification, merge, formal Release or P20/QQ distribution. Physical acceptance remains the owner’s decision.

## Candidate and cleanup

Application production code is unchanged from tested/signed `837d06fe59ea8e5bdde6b84b64a0d5742581c03e` (`git diff 837d06fe..7d36b6c5 -- app/src/main` is empty). Existing fixed-sign ARM64 candidate SHA256 remains `5f2d25cc032a34e2191caf0c49649aee5af918128bb34d329359a354845d544c`; no reinstall/reset is needed for the test-only commits. Cover-install preserves owner pairing/data. Owner physical-device and existing production GatewayRunner acceptance remain pending; the isolated adapter/stock CLI path is explicitly distinguished above.

Temporary CI secrets and key were removed/revoked; disposable node logged out and isolated Server was stopped. Production Server and Hermes Core remain unchanged. See `cleanup-result.json`. No merge, formal Release or P20/QQ distribution.

## Production compatibility blocker — do not hand off as fully accepted

Read-only comparison after native PASS found the installed P14 Platform adapter differs from the tested PR adapter. Installed SHA256 `ea342b0ba4cfc9e435278b4cc979138142e2f244684ff814a49e3e0c3846aeeb` omits `reply_to_message_id`, `reply_to_text`, and `reply_to_is_own_message` when creating Hermes MessageEvent. The stock gateway already injects quoted text correctly, but cannot inject fields the old adapter discards. This is not an Android model/setup problem.

A localhost-only negative control loaded the **unchanged installed adapter** against an isolated fresh Server. After revising source from OLD-OBSOLETE to a random PLUM value, real Hermes answered **OLD-OBSOLETE**; both reply pointer fields were null. A copy with **only the three missing fields added** correctly answered its revised PLUM value and delivered the full current quote. Evidence: `compat-old-result.json`, `compat-patched-result.json`, `production-adapter-quote-only.patch`, `adapter-patch-manifest.json` under the live evidence directory. No installed plugin, Core or production Server was changed. The compatibility test container is stopped.

The minimal patch is concrete and reviewable. Applying it to the installed P14 adapter and reloading/restarting the production Hermes gateway requires fresh owner authorization because the task permits isolated testing, but does not authorize production changes. Preserve a checksum-verified adapter backup and existing sessions; do not deploy P14 Server, alter Hermes Core or change credentials. The running production gateway remains PID2647676, started2026-10-27.

**Acceptance remains RED for edited-source context on the current production adapter.** Android/isolated native AI acceptance is PASS; that does not authorize claiming the owner's current live path is accepted. Ordinary original-source conversations can still work while edited context is lost. Resolve this blocker before asking the owner to accept AI correctness.

# P14 high-fidelity Inbox development readback

Authority: P14-Entry#51 and current human instruction dated 2026-10-30. Existing PR13/branch HEAD10cfbc04 fresh-read and clean, remote equal; Server PR56 HEADd34282f read only. Reference priority: attached real TickTick quick-add/editor captures, then attached six-screen P14 collage. Earlier emulator evidence is NOT a visual baseline.

Requirement readback: P14R3 §§4.3–4.4,5,7.2 (stable Entry/source, independent Conversation, local-first Capture); Architecture Addendum §§3,5; Remote Channel §§3,4.4,5,11,12 (existing versioned own-message edit and stable operation ID; explicit revision/follow-up context); Server/Android design §§6,client local-first. Rows VIEWS-001,S0-LOCAL,CAPTURE-001,SCALE-001.

Compatibility: original Entry.body_text and initial CREATE payload are immutable. Editable content is an existing source-Message revision, sent to the already-implemented /messages/{id}/edit route using the same Room Outbox and a stable operation ID. No database migration/server model/schema or frozen requirement change. Source message identity is accepted from original create events/local CREATE Outbox or complete server history; never guessed from a recent chat turn. Preserve queued later edits when earlier acknowledgements arrive. Surface revision conflicts, retain draft; never overwrite silently. Revised AI context uses existing reply/quote metadata, explicitly labeled revision, not a new Conversation or auto AI turn.

Pin/archive use existing state protocol with durable local Outbox. Existing View classifications are reused, not arbitrary custom tags. Existing attachments get actual cached file thumbnails and open actions. Calendar/dates/reminders/repeat/priority/Todo completion and custom-tag backend changes remain P2 compatibility proposals, not fake interactions.

Insets feasibility: pinned Compose BOM2026.09.00, Material3 1.5.0-alpha29, Coil3.6.3. Follow Android official consumption-aware imePadding/window insets and real focus APIs; use Android IME, not drawn keyboard. References: https://developer.android.com/develop/ui/compose/system/insets-ui and https://developer.android.com/develop/ui/compose/system/keyboard-animations . Actual CI keyboard screenshots must include full display and IME evidence, not Compose-root-only capture.

No Hermes invocation, live Server deployment, P20/QQ distribution, release, merge or real data mutation authorized this round. Visual/interaction acceptance remains pending actual side-by-side iteration.

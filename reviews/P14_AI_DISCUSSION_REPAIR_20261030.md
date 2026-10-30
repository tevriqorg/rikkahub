# Owner-reported AI discussion repair — 2026-10-30

The previous owner candidate was rejected. A persisted message and seeded assistant screenshots did not prove chat delivery. This report supersedes the earlier AI acceptance claim.

## Root cause and repair

Edited-capture continuations quoted the revised body but omitted the existing Server `QuoteSelection.source_revision` field. Its default1 made the Server reject revision2 text with409. New requests include the real revision; unaccepted legacy pending requests recover an exact matching saved revision without replacing Entry, Conversation, message or operation identity. Unknown revisions are blocked rather than guessed. Raw Capture is unchanged.

The worker now retries while durable chat work remains; the active discussion drives serialized foreground delivery/replay with existing WebSocket retained. Chat shows sending/connection failure/retry/wait-for-reply feedback; internal Capture/revision narration and stale detail-save status are removed.

Native rotation regression exposed a real focus/IME restoration defect. Quick capture and editor restore focus after orientation/window changes. Screenshot tests require stable real IME visibility and <16dp keyboard gap.

Visual inspection then rejected a green-run screenshot because equal-millisecond message timestamps were ordered by random UUID. Paged history now uses stable Room insertion order for timestamp ties; @Upsert preserves it. No schema change. Tests require question-before-answer, including equal timestamps and updates.

## Verification

- Application source: 837d06fe59ea8e5bdde6b84b64a0d5742581c03e
- ARM64/JVM/test compilation: https://github.com/tevriqorg/public-ci/actions/runs/41009046717
- Full isolated API35 Activity/Room/Compose/UID regression: https://github.com/tevriqorg/public-ci/actions/runs/41009050284
- Native HTTP chat: first request503, same-message retry, exact revised quote validation, visible draft, delayed final reply, two turns and question-before-answer, same Conversation. Synthetic loopback service only; no Hermes invocation or real AI response claimed.
- Existing full six screens, real quick/editor keyboard, portrait/landscape, attachments, external force-stop and strict IPv4/IPv6 app-UID offline regressions retained.

## Owner candidate

- Package `me.rerere.rikkahub.debug`; ARM64; versionCode191; versionName `2.5.6-p14-837d06fe`.
- Existing pinned certificate `dfa33df904e4eb3ca30fdc61a1817dbec36d131a94a594663f7d79522430cc94`; v2/v3 verification required.
- Signed APK SHA256: 5f2d25cc032a34e2191caf0c49649aee5af918128bb34d329359a354845d544c
- Direct private Tailscale download; no P20/QQ job or notification, stable Release, merge or Server deployment.

Both linked runs passed; native screenshots were reviewed. Isolated chat regression is green. Owner's actual AI conversation and physical-device acceptance remain pending. Existing P2 protocol limitations remain unchanged. Cover-install over the fixed-sign baseline, preserving pairing and data; do not uninstall or clear storage. Existing failed pending messages are retried with their original identity.


[Actual native two-turn chat](evidence/p14-chat-837d06fe/visual-output/09-native-chat-exchange.png) · [Rejected / fixed order comparison](evidence/p14-chat-837d06fe/chat-order-before-after.jpg)

All screenshots and HTTP replies are synthetic. The discarded green-run screenshot is preserved to show why automated assertions alone were insufficient. The complete visual journey includes actual keyboard/rotation, and cleanup is synchronously flushed before subsequent isolated test processes.

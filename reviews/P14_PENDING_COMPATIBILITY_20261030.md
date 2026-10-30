# Pending compatibility scope — not an approved Requirement change

The current UI uses existing P14 Entry/Conversation/Message revision, View membership, Attachment and durable Outbox. No frozen schema/model is changed.

| Capability | Existing contract | Scope requiring approval |
|---|---|---|
| True Todo completion | `todo` is a View; archive is organization | A revisioned task facet/action with explicit completion, undo, actor/time, sync events and idempotency. Never map Archive to Completed. |
| Dates/reminders/repeat | Creation timestamp only | Due timezone, all-day/date-only semantics, reminder delivery/permission, recurrence model, offline outbox, race/expiry rules. Never use created_at as a deadline. |
| Priority/calendar | No accepted persisted facet/calendar protocol | Stable priority enum and revisioned update; calendar linkage/permissions/privacy and reference identity. No false calendar or flag action. |
| Custom tags/lists | Four compiled View classifications | Approve custom View/tag identity, names/colors, rename/delete/sync/version behavior. Existing classifications remain real and usable; arbitrary labels are not invented. |
| Add attachments to an existing saved record | Capture attaches materials; ordinary continuation starts AI | A quiet versioned material-append operation, stable attachment references, upload retry/dedupe, with no AI dispatch. Existing material viewing/opening and multi-material new Capture are implemented; editor toolbar explains append is pending. |
| Full WYSIWYG editing | Existing plain text/Markdown content | Current toolbar inserts real Markdown, detail renders Markdown and undo/redo are real. A full attributed/rich-text storage format would need explicit compatibility design; it is not silently introduced. |

P0/P1 implementation and visual iteration continue without these changes. Calendar/dates/reminder/priority controls are visibly marked pending; custom tags are distinguished from actual View membership. No production deployment/distribution or Hermes call is authorized this round.

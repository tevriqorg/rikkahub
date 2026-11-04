# Physical-device feedback repair

Authority: P14-Entry#51; rows VIEWS-001, CAPTURE-001. Source readback: P14R3 §§4.3–4.4,5.2,5.3; Architecture Addendum §3 stable IDs/local durable capture; Remote Channel §§4.4–5; existing server/Android design. No schema, protocol, requirement, identity, credentials or real data changes.

Observed: supplied device screenshots show an assistant answer before its question and ambiguous task-checkbox/archive affordance. Root cause found in Android: synced message upsert/history hydration retained phone-local creation time, mixing phone and server clocks. Use authoritative server message timestamp once synced, including refreshing already-synced historical records. Offline pending timestamps remain local until acknowledgement.

Launcher opens the existing record Inbox before provider setup. Existing shell stays below it for provider settings, reachable by drawer “连接与设置”. Existing chat menu names now “收件箱” and “通知”. Archive is an explicit text action instead of a task checkbox. Existing historical entries remain intact and visible per the shared Entry model; do not hide/delete legitimate chat-origin records.

Regression added: real Room + repository event ingestion with phone clock 5 minutes ahead, for both pending and previously-synced user rows; verify question before reply, same IDs and cursor. Compose asserts explicit archive label. Existing offline/restart and detail/optional-AI tests remain required.

Validation pending public ARM64/JVM and emulator runs. This change does not claim physical-device acceptance. No Hermes calls, live Server change, merge or release.

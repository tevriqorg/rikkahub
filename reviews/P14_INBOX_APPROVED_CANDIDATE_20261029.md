## Public Actions workaround and approved candidate preparation — 2026-10-29

Reused the earlier public Actions build approach in active `tevriqorg/public-ci`. The former rikkahub Actions dispatch is disabled. No private source was published: a short-lived archive capability and task-only encryption key were used, and both temporary Actions secrets were deleted after completion.

- Server public CI: https://github.com/tevriqorg/public-ci/actions/runs/40869331729 — 95 pytest tests passed, 0 skipped; exact Docker image HTTP smoke passed (capture-only no AI inbound, retry idempotency, original context on explicit continuation). No Hermes invocation.
- Server source: `f805b994ef995ca8592b2176269d9f26a845870e`; public workflow: `71291fe00846bb2303d2c4f5ff9f61bdc0cc70ae`. Encrypted image authenticated and OCI config/layers/revision verified before loading.
- With user approval, Tokyo Server image switched after validated PostgreSQL dump and media backup. New capture route available. All four table counts AND row-content digests unchanged: entries31, conversations31, messages895, attachments28. PostgreSQL container and environment unchanged. Existing image/config retained for rollback. No real test Entry created.
- Fixed-sign ARM64 test candidate prepared from Android source `c6bbde942a89b2d0c6c2443472c6d387afb98604`; package `me.rerere.rikkahub.debug`, versionCode191, versionName `2.5.6-p14-c6bbde94`. v2/v3 signature verification passed.
- APK SHA256: `6384e9c6c419b73a34667f50109e9266a38a60c7e54120332e45a4be6ca040a7`; fixed certificate SHA256: `dfa33df904e4eb3ca30fdc61a1817dbec36d131a94a594663f7d79522430cc94`.
- Android acceptance CI: https://github.com/tevriqorg/public-ci/actions/runs/40864835331 ; ARM64/JVM: https://github.com/tevriqorg/public-ci/actions/runs/40859674875 . Existing emulator screenshots/XML and strict per-UID offline/restart evidence remain in PR13.

Readiness: candidate is ready for controlled physical-device installation testing; physical-device acceptance remains pending. No formal release, mainline merge, Hermes task, or automatic P20/QQ distribution performed. Calendar/reminders/custom tags remain explicitly pending; search remains limited to loaded entries.

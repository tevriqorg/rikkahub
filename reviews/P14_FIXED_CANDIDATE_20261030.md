# Fixed-sign owner test candidate — 2026-10-30

Owner explicitly authorized the existing fixed-sign test package in this conversation. No key regeneration, source build on VPS, server change, merge, Release or P20/QQ distribution.

- Tested application source: `d42f225d9c5073914055ebe1002aa8fb7f4f3f33`.
- Public ARM64 build: https://github.com/tevriqorg/public-ci/actions/runs/40992947570
- Full emulator acceptance: https://github.com/tevriqorg/public-ci/actions/runs/40992944285
- Candidate: `P14-Inbox-d42f225d-fixed-arm64.apk` (private Tokyo candidate storage).
- Package: `me.rerere.rikkahub.debug`; ARM64; versionCode191; versionName `2.5.6-p14-d42f225d`.
- Fixed certificate SHA256: `dfa33df904e4eb3ca30fdc61a1817dbec36d131a94a594663f7d79522430cc94`.
- Signed APK SHA256: `ecf3b9bca32cf251f529894a631abf77ebc6ffdc844a18e9ae03e21874393cd7`.
- apksigner verification: exactly one pinned signer, v2 and v3 PASS.

Certificate/package match the previous fixed-sign test candidate. Same versionCode allows replacement installation; actual physical-device upgrade remains untested. Preserve pairing/data: do not uninstall or clear storage. Check versionName after install because versionCode remains191.

Delivery remains pending: the latest explicit task prohibits P20/QQ and formal Release. No delivery job or notification was created. Full prior regression and remaining P2/live AI/physical-device limitations remain in P14_HIGH_FIDELITY_ACCEPTANCE_20261030.md.

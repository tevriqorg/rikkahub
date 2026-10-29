# Device feedback repair verification — 2026-10-29

- Android source: `70cc7540fd7c3a56d13a97839c4a3c7ef3751bee` on existing PR13 branch. No schema/protocol/scope/data/credential changes.
- Synced message time now comes from Server, including old synced history refresh. Actual Room regression passes with phone clock 5 minutes ahead for both pending and already-synced rows. Pending offline records retain local time until acknowledgement.
- App icon starts the existing Inbox, with immediate + capture; existing provider settings page opens directly from drawer “连接与设置”; warm launcher returns to Inbox, and root Back exits rather than revealing old chat. Actual launcher/Compose regression passes.
- Archive is explicit text, not a task checkbox. Chat menu says “收件箱” and “通知”. Historical Entry data retained; no new Completed state.
- Public ARM64/JVM CI: https://github.com/tevriqorg/public-ci/actions/runs/40882638663
- Public emulator/Room/Compose + strict per-UID offline/restart CI: https://github.com/tevriqorg/public-ci/actions/runs/40884338875
- Raw synthetic emulator screenshots and XML/log evidence: `reviews/evidence/p14-feedback-70cc7540`.
- Fixed-signed candidate APK SHA256: `69e8dea215a26d249700ae9c5b47616733ab4cbb19a13b067089827299a9dd61`; certificate: `dfa33df904e4eb3ca30fdc61a1817dbec36d131a94a594663f7d79522430cc94`.
- User-authorized P20 candidate delivery, job `fbbe50f71cc547d6844be1e2fd5cec07`; formal release/merge not performed. Physical-device reacceptance remains pending.

Initial failed runs are retained: missing UI Automator dependency replaced with existing test APIs; accessibility lookup did not observe Compose tree despite resumed Entry Activity, so launcher assertions use Compose semantics. Real launcher tests now use an empty Compose rule in a separate class, avoiding a competing component-host Activity; both classes and all original acceptance checks run in public workflow `08058f3f1daf5643eb37a8f97108c7c5b666b244`. Full acceptance was rerun rather than skipping that test. No Hermes call or live Server change in this repair.

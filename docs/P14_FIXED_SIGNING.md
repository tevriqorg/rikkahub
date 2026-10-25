# P14R3 fixed Android test signing identity

Authority: `tevriqorg/P14-Entry#51`. Recovery matrix row: `DELIVERY-001`.
This document freezes the **existing** long-lived **test-only** identity; it does not change product scope or authorize formal APK release.

- Android application ID: `me.rerere.rikkahub.debug`
- Keystore type / key alias: `PKCS12` / `p14-fixed-recovery`
- **Only accepted certificate SHA-256:** `dfa33df904e4eb3ca30fdc61a1817dbec36d131a94a594663f7d79522430cc94`
- Already verified on Tokyo signing host: private key exists, unlock works, DER certificate fingerprint matches, private file permissions 0600 / directory 0700 (2026-10-25).
- This identity is intentionally distinct from the **older** `519fa88e36dfcc565c8c1693f3554e91b368f4597a1556962b5510fcef068db7` test APK certificate, and from throwaway Android debug keys.
- Human accepted **fresh install with no old application data kept** on 2026-10-25. Uninstall/clear data only on the explicitly selected test device; this approval is not permission to erase another device, server data or shared media.

## Mandatory process for each P14R3 real-device candidate

1. Build Android source on **public GitHub Actions** from an exact SHA. CI must output a real `arm64-v8a` debug APK under `me.rerere.rikkahub.debug`. No Android compilation on the VPS.
2. Transfer the unsigned / CI-debug-signed artifact to the authorized Tokyo signing host. The key, PKCS12 and password remain private there; **do not create replacement keys or place them in Git, workflow artifacts, chat, or a browser**.
3. Use `python3 scripts/p14_sign_fixed_candidate.py --input INPUT.apk --output PRIVATE/OUTPUT.apk --source-sha COMMIT_SHA` on the authorized signing host. It fails closed for incorrect certificate, missing key, wrong application ID or non-ARM64 binary.
4. Verify `apksigner verify --verbose --print-certs` and the generated sidecar manifest; require the pinned certificate fingerprint and signed APK SHA-256. Signatures v2/v3 must verify. Keep outputs private, immutable, and bound to their source SHA.
5. For test-device installation, use the **fixed-signed output**, not the CI `assembleDebug` download. Test-only candidates are not formal releases; publishing / PR merge / Recovery closure still require the #51 gates.
6. Never silently rotate signing identity on rebuilds. Lost / inaccessible key or mismatch => **STOP**, record a blocking issue in #51, and seek explicit approval for any identity migration.

No Hermes invocation, backend mutation, APK publication or device wipe is authorized by this signing policy.

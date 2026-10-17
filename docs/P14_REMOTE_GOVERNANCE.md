# P14 Remote client governance

## Product boundary

- Android client repository: `tevriqorg/rikkahub`.
- Canonical client branch: `p14/remote`.
- Upstream base: `master`.
- `tevriqorg/P14-Entry` is requirements / protocol / P14 Server authority; it is not an Android product repository.
- No standalone `org.tevriq.p14.entry` launcher/application is permitted.


## Recovery anti-drift authority

- Single human-approved recovery authority: `tevriqorg/P14-Entry#51`.
- Single machine-readable recovery matrix: `tevriqorg/P14-Entry/requirements/P14R3_RECOVERY_MATRIX.json`.
- This repository must **reference** that matrix; do not maintain a second copied requirement matrix here.
- Before code changes, select existing Row ID(s). New recovery commits after activation require a `P14-Row:` commit trailer.
- A recovery PR must declare the Row IDs it touches and must state `P14-Scope-Change: no`.
- A row is not complete because code exists. It becomes GREEN only when the central matrix has acceptance evidence and no remaining work.
- Scope cannot be expanded, removed, or deferred from this repository. Human-approved authority must change first.

## Build and release

- GitHub Actions performs all Android compilation and tests.
- CI artifacts are source build artifacts only and are not installation authority.
- Release packaging/signing may run on the private Tokyo release node after CI succeeds; the node must not compile source.
- Routine CI does not create GitHub prereleases.
- P20 watches only explicit stable GitHub Releases from this repository.

## Signing baseline

- Test-track package: `me.rerere.rikkahub.debug`.
- Fixed P14 signing certificate SHA-256:
  `51:9F:A8:8E:36:DF:CC:56:5C:8C:16:93:F3:55:4E:91:B3:68:F4:59:7A:15:56:96:2B:55:10:FC:EF:06:8D:B7`
- Private signing material must never be committed to Git, attached to a GitHub Release, or printed in CI logs.
- The private key is held outside the repository on the release node with mode `0600`.
- Previous P14 debug builds used non-stable debug keys and are not upgrade-compatible. One uninstall/reinstall is required when moving to this baseline; future P14 builds must retain this certificate.

## Release naming

- Stable human-acceptance / formal tags: `p14-remote-vX.Y.Z`.
- Only the ARM64 APK intended for the user is attached to the stable Release unless another ABI is explicitly requested.

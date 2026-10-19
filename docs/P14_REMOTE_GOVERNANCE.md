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
  `DF:A3:3D:F9:04:E4:EB:3C:A3:0F:DC:61:A1:81:7D:BE:C3:6D:13:1A:94:A5:94:66:3F:7D:79:52:24:30:CC:94`
- Private signing material must never be committed to Git, attached to a GitHub Release, or printed in CI logs.
- The private key is held outside the repository on the release node with mode `0600`.
- Previous P14 debug builds used non-stable debug keys and are not upgrade-compatible. One uninstall/reinstall is required when moving to this baseline; future P14 builds must retain this certificate.

## Release naming

- Stable human-acceptance / formal tags: `p14-remote-vX.Y.Z`.
- Only the ARM64 APK intended for the user is attached to the stable Release unless another ABI is explicitly requested.

## 2026-10-20 人类授权：更换为新的长期固定测试签名

因旧固定私钥的解锁路径未能恢复，人类在 tevriqorg/P14-Entry#51 Recovery 会话明确要求“新建一个固定签名来做”。从此使用上述新指纹；不得在每次构建时重新生成。

- 旧指纹 `519fa88e36dfcc565c8c1693f3554e91b368f4597a1556962b5510fcef068db7` 退为历史，旧密钥保留。
- 新密钥与密码仅位于东京 release node 的受控存储，均0600；logical reference：`rikkahub-p14-signing/recovery-20261020/signing.json`。该私有manifest记录相对keystore/password文件及alias，不得提交Git或输出密码。
- 已用新固定签名包装产品源码5b3d3a3e的既有公开CI ARM64 APK，v2/v3验签通过。未编译、安装或发布。
- package仍为`me.rerere.rikkahub.debug`；没有创建另一条客户端产品线。
- **新签名不能直接覆盖旧签名安装。** 不得自动卸载或清除旧数据；第四任务必须明确数据迁移/新基线验收路径。不能把新安装测试声称为旧签名覆盖升级通过。
- 上文历史“一次卸载重装”说明不构成当前自动卸载授权。

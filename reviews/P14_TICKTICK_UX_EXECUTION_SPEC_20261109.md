# P14 Entry｜TickTick 风格交互基准与逐 Entry AI 上下文验收（2026-11-09）

> **性质：执行级 UI/UX 设计与验收清单，不是新的 Requirement、schema 或发布授权。**
> Authority：`tevriqorg/P14-Entry#51`、P14R3 当前 canonical 文件、`requirements/P14R3_RECOVERY_MATRIX.json` 的 `2026-10-17-freeze-1`。Android implementation：`tevriqorg/rikkahub` 既有 Draft PR #13；Server：既有 Draft PR #56。任何新功能超出现有协议/矩阵时，先交给 Owner 确认并更新规范，不能先用 UI 伪装。

## 0. 决策和证据层级

- **产品定位**：像 TickTick 一样顺畅地收集、查找和编辑；每个保存的 Entry 可在需要时进入自己的 ChatGPT 式持续 AI 对话。**记录优先，AI 显式可选**。不强迫在保存前分类、配置 Provider 或打开 AI。
- **沿用现有客户端**：RikkaHub/Kotlin/Compose + P14-owned Room/Outbox、Server sync/replay；不重做独立 APK、不迁移 Flutter、不改 Hermes Core、不得破坏原始 Capture。
- **参考优先级**：Owner 提供的真实 TickTick Android 快速添加/编辑截图与真机交互 > 既有六屏 P14 参考 > 官方文档与社区反馈 > 旧版模拟器截图。不得将网页宣传图当作最新版本的像素尺寸或完整行为证明。
- **本次批准的内容**：明确五步体验基准与测试，不隐含允许部署生产 Server/Hermes、应用生产 adapter patch、变更协议/schema、合并、重新签名或分发 APK。
- **公认限制**：闭源 TickTick 的内部实现、布局精确像素、动画曲线不可验证；只复现被观察到的外部行为，不照搬商标、图标或代码。
- **已知生产阻断**：`reviews/P14_LIVE_HERMES_ACCEPTANCE_20261030.md` 显示隔离 Native → Server → Hermes 多轮通过，但已部署 adapter 会丢失编辑后 source 的 quote 字段；真实生产链路仍为 RED、Owner 真机验收 PENDING。任何 UI 绿灯不能冲抵此项。

现有可重用入口：
- `app/src/main/java/me/rerere/rikkahub/ui/activity/P14TickTickInbox.kt`（列表、快速添加、Entry Detail、显式归档/置顶）；
- `app/src/main/java/me/rerere/rikkahub/ui/activity/P14EntryEditor.kt`（全屏原位编辑、草稿、IME、Undo）；
- `app/src/main/java/me/rerere/rikkahub/ui/activity/P14EntryActivity.kt`（页面、导航、同步及 AI 入口）；
- `app/src/main/java/me/rerere/rikkahub/data/remote/p14/P14ProductRepository.kt`（Entry、修订、Conversation、Outbox、replay）；
- `app/src/androidTest/java/me/rerere/rikkahub/ui/activity/P14HighFidelityVisualInstrumentedTest.kt` 和 `P14InboxReplicaUiInstrumentedTest.kt`（真实 IME / 界面 / 断网基线）。

## 1. 唯一主线和状态机

```text
Launcher / Widget / Share
  -> Inbox（Room 首帧，后台 replay）
      -> "+" -> Quick Capture（系统 IME，未分类草稿）
          -> Save（单事务 durable Entry/Outbox） -> Inbox（马上显示）
      -> Tap Entry -> Detail（正文、原始素材、修订与整理）
          -> Edit inline -> commit revision -> Detail（不创建新 Entry）
          -> "AI 讨论"（显式操作） -> Entry 的原 Conversation
              -> 多轮 AI / 重试 / replay（原 Conversation 不变）
              -> Back -> Detail -> Back -> Inbox（滚动与筛选状态可恢复）
```

**身份不变量**：对同一 Entry 进行保存、进入详情、修订、打开 AI、发送、重试、退出、重启，都不能产生第二个 Entry ID、意外的新 Conversation ID 或 Hermes Session 产品身份。Entry/source、Conversation、Message/operation ID 分开。自动 AI 不得在首次纯 Capture 保存时被暗中触发。

## 2. 五步交互规范

### S1｜Inbox：一打开就能看见并处理记录
- 冷/热启动直接走现有 Inbox launcher；本地 Room 内容优先显示，网络与 Provider 状态不能阻塞展示。
- 一条记录显示**摘要优先**：标题或正文首行、简要预览、素材/讨论标记、必要的同步状态；长标题截断且不引起全列表行高抖动。
- 笔记/资料没有无意义的完成复选框，`Archive` 不假装成 `Complete`；已支持的 Pin、Archive、既有 View、搜索均呈现真实服务端/本地投影。
- 高级分类和筛选是次要操作；不把未来日期/提醒/重复/优先级、完整 Calendar/custom tags 展示成已生效的控件。
- 列表用稳定 `entryId` keys 和适当的 `contentType`，保存/删除/回放不造成全列表跳动；进入详情返回时尽量保留列表位置与筛选。
- 离线新保存的记录立即出现；不要等待 AI、网络、媒体上传才刷新。

### S2｜Quick Capture：一到两次明确动作完成保存
- 任意 Inbox 状态下明显且单手可达的 `+`；点击后使用**真实系统输入法**，输入框自动焦点，底部工具栏紧贴 IME 顶端且不被挡住。
- 默认先文本；按需加入附件、语音或现有 View，绝不强制 Note/Todo 类型选择；图片/PDF/语音应保持真实附件身份，不被强制转文本。
- 中英文拼音/联想词输入不丢字符、不挪光标、不闪烁全屏，连续 100 字输入与删除不引起重新建页。
- 保存的可见反馈 = **本地持久化成功**；同时建立 Outbox，断网依然成功。远程发送失败只能显示“待同步/重试”，不能改写为“保存失败”。
- Back 优先处理 IME，然后关闭面板；有草稿时必须保存/恢复草稿，不能悄悄丢弃。旋转/系统杀进程后按当前产品协议恢复草稿。
- “连续添加”只作为**候选增强**，当前不得在未批准的情况下改变默认 Save/Draft 语义。

### S3｜Detail + 全屏 Editor：一条记录可持续改
- 列表点入即见独立 Detail：当前正文、原始 Capture 追溯、附件、修订/整理、显式 AI 入口；不把原始正文当成 AI 聊天的第一气泡。
- 点击正文或编辑后进入全屏就地编辑：标题、正文、可用格式工具和现有附件，系统键盘工具栏与 IME Insets 正确；无需无意义的多重确认界面。
- 编辑只产生**同一 source Message 的修订**，保留原始 Capture、原 Entry/Conversation 关联与既有 Message ID；不能覆盖、重建或静默回滚。
- Back/保存/旋转/异常退出：编辑草稿、光标选择、滚动偏移尽量按现有 draft store 恢复；写盘失败时明确告警且阻止丢失。
- 与远端修订冲突时按已有冲突处理 UI 明确选择，不得最后写入悄悄胜出；附加材料功能如协议尚不支持，应标注未来能力而非伪装成功。

### S4｜AI 讨论：ChatGPT 式继续，但每条 Entry 独立
- 入口只在 Detail 中显式启动；从未发起过对话的 Entry，仍可独立阅读、编辑、归档。
- 打开 Entry A → A 的 Conversation；切到 Entry B → B 的 Conversation；回 A → 准确恢复 A 全部已确认及本地 pending 消息，禁止串线。
- 编辑 Source 到 v2 后继续讨论，必须携带真实 `source_revision` 与必要 quote/source context；AI 继续使用**原 Conversation**，原始 source 不可变。
- 待发送/发送中/等 AI 回复/可重试四类状态语义清晰；503/网络恢复时使用同一个 message/operation ID 幂等重试；409 不可当成成功。
- AI 是可选后续操作，不能擅自覆盖 Entry 正文；任何修改/Action 需遵循已有授权与明确提交。
- 多轮语义链必须分别在（a）离线/合成服务器，（b）隔离真实 adapter/Hermes，（c）已部署实际链路与 Owner 真机校验。生产 adapter quote 阻断未解除前，(c) **不能标绿**。
- 不向 GitHub、截图证据、CI 日志上传真实用户内容、凭据、Hermes memory 或生产附件。

### S5｜返回 Inbox：无摩擦、恢复现场
- AI 页 Back（键盘先收起）→ Detail；Detail Back → Inbox；不返回到旧 RikkaHub 的无关聊天首页。
- 重新打开同一 Entry，AI 聊天消息顺序、定位、输入草稿、处理状态可恢复；同毫秒消息依 Room 稳定插入/服务器顺序而非随机 UUID 排序。
- 每次导航不能创建重复 listener、重复发送者或新 Conversation；跨设备 A→B→A 继续靠既有 Server replay/cursor 收敛。
- Android launcher、桌面快捷方式/组件和外部 Share 入口分别验收；本轮没有覆盖的入口不得假称 PASS。

## 3. 真实 TickTick 对照采集标准（参考而非复制代码）

统一同一台 Android 设备/系统字体级别/显示缩放/输入法/明暗主题；真实 TickTick 和 P14 分别执行：
1. 冷启动到 Inbox：首帧/可交互时刻、列表密度、上下留白、首屏项目数；
2. 点加号：从点击至底部 sheet 可用/IME 出现的时序，工具栏 top/bottom 与 IME top 的关系；
3. 录入中文拼音、英语、换行、粘贴、删除、上传照片/PDF；记录焦点、光标与抖动；
4. 保存后进入记录、编辑光标定位、收起键盘、返回；
5. 显式进入 AI、两轮问答、返回、再进入同一 Entry；
6. 竖屏/横屏、系统深浅色、断网、强杀、重启后重复。
采集完整屏幕（包括真实 IME）、设备尺寸/密度、布局层级与响应耗时；对照版本号、日期、配置都写入证据。具体 dp/时间只从实测得出，不事先猜成 TickTick 官方内部数据。只用合成素材。

## 4. 初始性能预算（待真机标定，不代表已 PASS 或 TickTick 实测）

- `tap + → text accepts input`：温启动 **p95 ≤ 100 ms** 的初始目标；记录 sheet 出现与 IME 完整可见为**两个不同指标**。
- `save pressed → durable local commit + visible Inbox row`：**p95 ≤ 150 ms** 的初始目标（独立记录 DB commit/首屏显示耗时）；保存反馈不得提前于 durable commit。
- Android 60 Hz 平均帧预算约 16.7 ms，120 Hz 约 8.3 ms；统计掉帧、slow/frozen frames、滚动/输入 jank，而不是只看录屏感觉。
- 使用 release-like / profileable build、Macrobenchmark/UI Automator 或 trace，分别测 **50/250/1000 条合成 Entry**、多附件与长对话；真实设备分位数才是性能指标，模拟器只用于功能与回归。
- 测量前不得改动当前原有签名基线或清空 Owner 的现有数据。CI 新的 synthetic fixture 必须独立隔离。

## 5. 验收矩阵（新项一律先标 NOT RUN）

| ID | 可执行验收 | 自动化/人工证据 | 当前状态 |
|---|---|---|---|
| UX-01 | Launcher→本地 Inbox 首帧；加载 50/250/1000 条 | `P14InboxLauncherInstrumentedTest` 扩性能计时 | 既有功能基线；性能 NOT RUN |
| UX-02 | 加号→系统 IME→中文输入→本地保存→列表新增 | `P14HighFidelityVisualInstrumentedTest` + 真机录屏 | 既有模拟器 PASS；真机 NOT RUN |
| UX-03 | 旋转、Back、草稿/光标恢复、IME gap <16dp | `P14HighFidelityVisualInstrumentedTest` | 既有隔离模拟器 PASS；真机 NOT RUN |
| UX-04 | Detail→Editor→v2→原始 Capture 不变→返回 | `P14EntryRevisionInstrumentedTest` | 既有隔离数据测试 PASS；Owner NOT RUN |
| UX-05 | A/B 独立 Conversation；编辑后 quote revision；两轮 AI | `P14LiveHermesInstrumentedTest` | 隔离真实 AI PASS；生产 adapter RED |
| UX-06 | 离线创建、强杀、恢复后原操作幂等送达 | `P14RecoveryInstrumentedTest` | 既有隔离 PASS；真机 NOT RUN |
| UX-07 | AI Back→Detail→Inbox，列表位置/输入草稿可恢复 | 导航自动化 + 真实 IME | 部分基线 PASS；专项 NOT RUN |
| UX-08 | 多设备 A create→B continue→A replay，不串 Entry | Recovery matrix `SYNC-MULTI` + 真实 Client | 既有自动化 GREEN；Owner NOT RUN |
| UX-09 | 对照 TickTick 真机逐帧录屏与输入 jank | 真实 TickTick/P14 同设备 | NOT RUN |
| UX-10 | 外部 Share / 桌面组件 / App 图标冷暖启动 | 实体设备逐入口测试 | 部分 launcher 已测；其余 NOT RUN |

**说明**：历史“PASS”仅表示指定隔离测试当时的证据，不是本次新版本已运行，也不能视为 TickTick 像素级复刻或真实设备通过。

## 6. 实现/交付顺序，避免扩 scope

1. **已有行为审计**：只核对 `P14TickTickInbox.kt` / `P14EntryEditor.kt` / `P14EntryActivity.kt` 和现有测试；逐项判定 UX-01~10 的可观察差异，优先修复已批准行为中的 IME、焦点、Back、State、滑动反馈/性能，而非增加新商业模型。
2. **交互细节闭环**：一项问题 → 小范围修复 → 公共 GitHub Actions JVM/ARM64/真系统 IME 模拟器 → 完整屏幕截图/trace → 主流程回归。不得用测试造图或只绿单元测试冒充实机体验。
3. **真实 AI 完整闭环**：保持 Entry/Conversation 和 Outbox 协议不变；生产 adapter 的三个 quote 字段差异先单独由 Owner 授权，绝不因本规范自动变更生产 Hermes。
4. **Owner 物理 Android 验收**：真实对照 TickTick，重点验证中文输入、屏幕键盘、返回、长列表、AI 复访及固定签名覆盖安装保留旧数据。
5. **macOS/iOS 移植策略**：共用 Entry/Conversation/revision/outbox/replay 语义，不承诺共用 Android Compose UI；桌面三栏、移动端原生分别做后续设计，暂不建立新客户端或 PR。

## 7. 冻结范围/映射及漂移检查

- 当前矩阵行：`VIEWS-001`（Entry Inbox/可见页面）、`S0-LOCAL` 与 `S0-RECOVERY`（离线与草稿持久性）、`CAPTURE-001`（混合附件）、`SCALE-001`（长列表/历史）、`SYNC-REPLAY` 与 `SYNC-MULTI`（跨设备）、Remote Channel 既有对话契约。无新增 Row。
- 不变：P14 Server schema/protocol、Hermes Core、Entry/Conversation identity、RikkaHub 主客户端、Profile、Observation、原始 Capture、长期固定测试签名。
- **本次仅写设计与验收文件**，不会改动任何运行时代码、工作流、生产服务、签名、凭据、安装包或发布状态；“继续添加”“Calendar”“Todo 完成日期/提醒”等超出既有能力只列候选，不暗中实施。
- 下一重大实施任务必须重新 readback #51、原 Requirement、matrix、当前 PR HEAD，并按现有仓库治理规则提交与验证；每个重大任务结束向 Owner 报告 drift check 后停止，不继续偷跑下一任务。

## 8. 外部依据

- TickTick 官方快速添加（加号、日期/优先级/标签/列表、工具栏自定义）：https://help.ticktick.com/articles/7055782422935240704
- TickTick 官方详情/Note 转换/动作菜单：https://help.ticktick.com/articles/7055782408586526720
- 2026-09 TickTick Inbox 组织经验与社区讨论：https://www.reddit.com/r/ticktick/comments/1wd1zb2/ticktick_tip_organize_your_inbox_faster/
- 2026-04 用户反馈 Inbox 批量整理效率问题：https://www.reddit.com/r/ticktick/comments/1sm43s8/how_to_process_inbox_fast_keyboard_shortcuts_for/
- Android Compose Lazy keys/contentType：https://developer.android.com/develop/ui/compose/performance/bestpractices
- Android Macrobenchmark：https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview

:By ChatGPT

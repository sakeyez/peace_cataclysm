# Challenge Runtime 迁移记录

## 2026-10-16：M0 基线与 M1 第一批拆分

- 当前目标：Minecraft 1.21.1、NeoForge 21.1.244、Java 21、单模组 `culinary_cataclysm`。
- M0 编译基线：使用本机 Gradle 8.10.2、`--offline -I .tools/local-maven.init.gradle` 执行 `build`，通过；`test` 为 `NO-SOURCE`。仓库没有 Gradle wrapper。
- M1 已移动：祭坛与生成关联到 `IgnisTrigger`，命令到 `IgnisCommands`/`IgnisDiagnostics`，文案到 `IgnisPresentation`，施工和清理到 `IgnisBuildController`，回合推进到 `IgnisRounds`，最终结果演出与旧奖励调用到 `IgnisSettlement`，摆盘事务与提交评分到 `IgnisDishSubmission`，保护规则到 `IgnisProtection`。`IgnisChapter` 持有剩余阶段调度与交互，旧 `CookingCompetitionManager` 只作为 NeoForge 事件桥。以上仍使用原 `CompetitionRecord`、`CompetitionSavedData` 与 NBT 键。
- 森罗边界已收紧：`CookeryIntegration` 不再导入比赛对象或摆盘类；站点归属、章节菜谱筛选和依赖检查由 Ignis 章节负责。
- 拆分后再次执行相同 `build`，通过；没有实际 GameTest。开发客户端在施工期间已经运行，本次没有触碰其世界存档，也没有将已有进程算作新构建的运行验收。

## 2026-10-17：M2 外层存档

- 用户反馈已在游戏内验收 M1 流程。M2 新增 `Challenge` 外壳、正交的 `ChallengeLifecycle`/`ChallengeResult`、同一 `ChallengeStore` 中的 Active 与精简 History。`ChallengeStore` 是新格式唯一可写的 SavedData；旧 `culinary_cataclysm_competitions` 仅供读取导入，原文件保留。
- `IgnisChallengeCodec` 将现有 `CompetitionRecord` 放进版本化 `ChapterData`，外层身份、维度和参与者不重复放入章节数据。旧控制器经 `CompetitionSavedData` 兼容视图读写新 Store；旧记录按比赛 UUID 幂等导入，已完成记录直接成为精简 History。场地施工游标、恢复阶段、厨师库存与料理证据仍由 Ignis 章节数据保存。
- M2 暂不拆旧 `CompetitionRecord` 字段和控制器签名；也不实现 M3 的玩家/Boss 索引。区域查询现在只遍历活动 Ignis 记录，不再遍历长期历史。兼容视图的 `setDirty()` 会提交所有活动 Ignis 记录，适合当前小规模过渡，后续可改为逐记录提交。
- 新增 `verifyChallengeMigration`，并接入 `build/check`：验证章节数据往返、旧数据重复导入、施工和厨师状态、归档精简、归档后不复活。`gradle.bat build --offline -I .tools/local-maven.init.gradle --no-daemon --console=plain` 通过。
- 对 `run/saves` 中 12 个已有旧存档文件执行只读迁移往返检查：包含活动记录及已完成记录；记录数量、活动状态 NBT、重复导入均通过。旧集合字段 `PreservedBattlefieldBlocks` 的数组顺序可能改变，检查按集合内容比较，未发现数据差异。执行时不写这些世界文件。

## 2026-10-17：M2 独立世界副本运行验收

- 经用户授权，使用隔离的 NeoForge `runServer` 目录（`.tools/m2-runtime-20261017` 和 `.tools/m2-cleanup-runtime-20261017`），从 `run/saves` 复制旧世界。原世界不作为服务端运行目录；两份副本的旧比赛文件与原文件 SHA-256 相同。
- `新的世界 (10)` 含一条已完成、一条中断中的比赛。服务端成功加载依赖与旧世界；执行 `save-all flush` 后生成 `culinary_cataclysm_challenges.dat`，得到 Active 1、History 1。正常停服、重启、再保存后仍为 Active 1、History 1；Ignis 的身份、恢复阶段、施工游标、厨师库存和证据数量匹配旧记录，重复导入不会增加记录。
- `新的世界 (8)` 的旧比赛处于 `CLEANUP`。隔离服务端加载、保存、正常停服成功；新 Store 为 Active 1、History 0，清理阶段和游标匹配旧记录。此副本没有模拟玩家进入或清理直至结束。
- 再次执行 `build` 与两份已写盘 Store 的针对性读取检查，均通过。上述证据验证 M2 的服务器存档迁移、保存和一次重启；不替代客户端画面、玩家交互或奖励结算的游戏内验证。

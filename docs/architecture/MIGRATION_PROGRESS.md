# Challenge Runtime 进度

## 已完成

- **M1（2026-10-16）**：将 Ignis 触发、命令、演出、施工、回合、结算、摆盘与保护从 `CookingCompetitionManager` 分到章节类。用户已在游戏内验收当时流程。
- **M2（2026-10-17）**：建立 `Challenge`、Lifecycle/Result、同一 `ChallengeStore` 中的 Active/History，以及 Ignis 章节数据序列化。曾在隔离世界副本中验证旧数据导入、保存与一次重启；这是当时版本的历史证据，不代表当前版本支持旧世界。
- **M3（2026-10-17）**：建立可重建的玩家/Boss 活动索引，区域查询只遍历活动记录。曾在隔离副本中验证索引重建与 Store 重启；未完成玩家交互或保护事件的游戏内验收。
- **旧格式清理（2026-10-18）**：按用户决定移除旧比赛文件导入器、旧双厨房与旧场地模板、旧施工模式、旧交菜与餐桌迁移路径。Ignis 章节数据版本升至 2。当前格式的施工进度、祭坛方块避让集合、料理证据及清理状态继续持久化。
- **收尾重构（2026-10-18）**：移除 `CompetitionSavedData` 过渡视图，改为 Ignis 章节内的 `IgnisChallengeRegistry`，按单场比赛提交变更；NeoForge 入口改名为 `IgnisEventBridge`。清理完成归档后，同一 tick 不再重复提交已移除的记录。用户已确认当前凳子版本；模板与凳子逻辑未改动。

## 当前验证与限制

`gradle.bat clean build --offline -I .tools/local-maven.init.gradle --no-daemon --console=plain` 包含 `verifyChallengeStore`：检查当前格式的章节数据往返、活动索引、唯一性冲突、单记录提交和精简归档；干净构建通过且 `build/libs` 仅留当前 r17 JAR。收尾重构后，独立测试世界 `.tools/fresh-runtime-20261018` 的 NeoForge 服务端加载了本模组及全部依赖，完成 `save-all flush` 并正常停服。该世界没有挑战记录，因此服务端启动不证明实体演出、实物摆盘、保护或清理恢复；这些需要实际客户端或 GameTest 验证。

后续工作按 [Challenge Runtime v0.1](CHALLENGE_RUNTIME_V0.1_MIGRATION.md) 的 M4–M6 继续。旧游戏世界兼容不再是验收项。

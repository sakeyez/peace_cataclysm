# Challenge Runtime 迁移记录

## 2026-10-16：M0 基线与 M1 第一批拆分

- 当前目标：Minecraft 1.21.1、NeoForge 21.1.244、Java 21、单模组 `culinary_cataclysm`。
- M0 编译基线：使用本机 Gradle 8.10.2、`--offline -I .tools/local-maven.init.gradle` 执行 `build`，通过；`test` 为 `NO-SOURCE`。仓库没有 Gradle wrapper。
- M1 已移动：祭坛与生成关联到 `IgnisTrigger`，命令到 `IgnisCommands`/`IgnisDiagnostics`，文案到 `IgnisPresentation`，施工和清理到 `IgnisBuildController`，回合推进到 `IgnisRounds`，最终结果演出与旧奖励调用到 `IgnisSettlement`，摆盘事务与提交评分到 `IgnisDishSubmission`，保护规则到 `IgnisProtection`。`IgnisChapter` 持有剩余阶段调度与交互，旧 `CookingCompetitionManager` 只作为 NeoForge 事件桥。以上仍使用原 `CompetitionRecord`、`CompetitionSavedData` 与 NBT 键。
- 森罗边界已收紧：`CookeryIntegration` 不再导入比赛对象或摆盘类；站点归属、章节菜谱筛选和依赖检查由 Ignis 章节负责。
- 拆分后再次执行相同 `build`，通过；没有实际 GameTest。开发客户端在施工期间已经运行，本次没有触碰其世界存档，也没有将已有进程算作新构建的运行验收。

## 下一门槛

用世界副本验证祭坛、施工中断续建、摆盘、结算和清理。上述游戏流程未通过前，不切换权威存档到新 `ChallengeStore`。下一步才提取公共 Challenge 数据和派生索引；不要将当前 `CompetitionRecord` 改名后当作公共身份。

# Peace Cataclysm — Challenge Runtime v0.1 迁移施工图

状态：架构基准草案，供逐阶段实施与评审；本文件不改变游戏行为。

依据：本地仓库 `e252791` 的 Java 源码、构建配置和资源；迁移开始前远端 `main` 指向同一提交。`03_完整设计基线` 未包含在此仓库，本文不替它定义文明进度的归属或外部事件协议。

## 1. 已定边界与尚待定案的细节

公共核心仅锁定 `Challenge`、`ChallengeStore`、`ChallengeLifecycle`、`ChallengeRouter`、派生的 `ActiveChallengeIndex`。`ChallengeRuntime` 是这套子系统的统称，不是一个类。结果 `UNRESOLVED / SUCCESS / FAILURE / ABORTED` 与生命周期 `RUNNING / PAUSED / CLEANUP / ENDED` 正交。`Challenge` 的公共身份不含 Boss UUID、厨房、轮次、菜谱或评分；这些属于章节数据。

依赖方向：入口事件 → Router / Store → 章节；章节 → 外部模组适配器。核心、Store、Router、适配器均不得导入 Ignis 状态或现有 `CompetitionRecord`。`ChapterHandler`、Boss Adapter、Arena Service、评分框架的 Java 形式尚未冻结。

需要在编码前定清的语义：

1. `participants` 是参与者列表，不等于文明进度归属。个人、队伍或世界认可仍待产品方案决定。v0.1 为保持现有行为，一位玩家同时只能有一场活动挑战；若以后允许并行，玩家索引须从单值改为集合。
2. `finishedAt` 指结果判定时间还是清理归档时间必须明确。建议分别记录 `resolvedAt` 与 `endedAt`；计时器继续使用游戏刻，历史显示时间另行定义，不能混用时钟。
3. `chapter_data_version` 由章节解释；Store 只验证外层 `format_version`。Boss UUID 可存于 Ignis 章节数据并用于重载关联，不成为公共身份。
4. `Challenge` 中的可变 `CompoundTag` 不能作为未经管理的公开字段直接修改，否则 Store 可能漏掉 `setDirty()`。章节读写状态须经受控更新或显式提交。
5. `settlement` 尚不是锁定的六态公共枚举。结果已记录、奖励待发、奖励已发、清理已完成等里程碑可能独立发生；实现前先定幂等记录和提交顺序，避免制造第三套僵硬状态机。

## 2. 存档与索引契约

`ChallengeStore` 可以直接继承 1.21.1 的 `SavedData`。一个权威 Store 保存 `active: Map<UUID, Challenge>` 和精简的 `history: Map<UUID, ChallengeHistory>`。归档必须在结果处理、需发奖励和场地清理达到规定完成条件后进行；`CLEANUP` 期间仍属于 Active，保护仍生效。历史只存身份、时间、结果和必要的章节摘要，不存厨师账本、菜品证据、场地快照和施工游标。文明认可是外部进度系统的权威状态，历史只陈述这场挑战的事实。

`ActiveChallengeIndex` 由 Active 重建，不写入 NBT。初期索引玩家 UUID 和 Boss UUID 到 Challenge ID；区域查询暂遍历 Active。若性能证据要求区块索引，再用 `(dimension, chunk) → Set<ChallengeId>` 找候选，最终仍调用章节区域的 `contains(pos)`。Boss 关联由章节数据导出；创建、换 Boss、暂停、清理和归档时更新或重建索引。Index 绝不持有第二份 `Challenge` 对象。

旧存档名称为 `culinary_cataclysm_competitions`，旧 `CompetitionRecord` 的 NBT 键是兼容输入。迁移读取旧数据后按 `competitionId` 幂等导入新 Store；旧格式先保留，不在同一阶段删文件。新 Store 的活动记录使用外层 `format_version` 和内层 `chapter_data_version`，Ignis 章节读取并迁移其内层数据。完成记录只导入精简历史。未完成施工的游标必须和旧模板、旧施工顺序对应，不能重新排序后沿用游标。

同一个 SavedData 可让 Active → History 的内存变更一次标脏，但这不等于玩家背包、进度与 SavedData 是原子事务。重要奖励要幂等；发实体物品需要额外凭证或领取账本。`setDirty()` 只标记需要写盘，不表示立刻持久化。

## 3. 当前字段与目标所有者

以下源自 `CookingCompetitionManager.java:63-70` 与 `CompetitionRecord.java:19-88`。

| 当前字段 | 当前使用者 | 标记 | 目标所有者 |
| --- | --- | --- | --- |
| `OPENING_TICKS`, `RESULT_TICKS` | Ignis 阶段 Tick | MOVE | Ignis 阶段配置 |
| `ALTAR`, `ASHES`, `pendingSummons` | 祭坛点击、实体生成、超时 | MOVE | IgnisTrigger；这是创建前的短期关联，不写 Store |
| `pendingPlates` | 摆盘前后两个事件、超时、退出 | MOVE | IgnisDishSubmission；只保留短期内存事务 |
| `builder` | 场地预检、施工、取消、清理 | MOVE | IgnisBuildController 持有现有建造器 |
| `cookingTracker` | 锅具交互与每刻确认 | MOVE | IgnisCookingController 持有现有追踪器 |
| `competitionId`, `playerId`, `dimension` | 存档和查找 | SPLIT | 公共 `Challenge.id / participants / dimension`；兼容读取旧键 |
| `ignisId`, `blazeChefId` | Boss 与 NPC 恢复 | MOVE | IgnisState / BlazeChefState；由章节向 Index 导出 Boss 关联 |
| `state`, `interruptedState`, `stateTicks` | 旧比赛状态机 | SPLIT | 外层 Lifecycle 与 IgnisPhase；旧阶段值需逐项迁移 |
| `arenaCenter`, 双厨房和 `battlefieldOrigin` | 场地、广播、保护 | MOVE | IgnisBuildState / IgnisRegion |
| `preservedBattlefieldBlocks`, `originalBattlefieldFloor` | 安全施工、回滚 | COMPATIBILITY | IgnisBuildState；完整保留旧快照语义 |
| `twoSidedBuild`, `spiralBuild`, `mirroredBuild`, `aboveGroundOnly`, `buildCursor`, `cleanupTargetCount` | 新旧施工顺序 | COMPATIBILITY | IgnisBuildState；版本化读取，不合并旧模式 |
| `platedSubmission`, `servingTableBuilt`, 双方摆盘 ID、`platedJudgingAtGameTime` | 旧版和新版提交 | MOVE | IgnisDishLedger / ServingTable |
| `currentRound`、`recipes`、双方分数和总分 | 比赛轮次与胜负 | MOVE | IgnisState / IgnisRoundController |
| `rewardIssued`, `finalResultResolved` | 结算防重 | SPLIT | Ignis 结算里程碑；外层 Result 不代替发奖状态 |
| 生产、提交和锅具操作计数，当前菜品 ID、原料 | 料理证明 | MOVE | IgnisDishLedger |
| `blazeWorkState`, 航点、停滞、工序、失败原因、`blazeInventory` | NPC 恢复和账本 | MOVE | BlazeChefState；不再引用 Controller 内嵌的 `WorkState` |
| `dishEvidence` | 服务端菜品证明 | MOVE | IgnisDishLedger / EvidenceLedger |

`CompetitionRecord.currentRecipe()`、坐标计算与 `containsArena()` 等方法跟随各自字段：菜谱和坐标归 Ignis，区域命中由 IgnisRegion 实现。`save/load` 留作旧格式读取器，直到新格式和旧存档恢复均完成验证。`CookedDishEvidence` 使用的 `CompetitionRecord.putPos/getPos` 坐标 NBT 辅助方法另移到无业务依赖的序列化工具。

## 4. `CookingCompetitionManager` 字段、方法与调用者迁移

“目标”表示职责归属，尚不强迫每个名字都成为单独 Java 类。`KEEP` 表示保留行为；`MOVE` 表示移动归属；`SPLIT` 表示拆开不同职责；`COMPATIBILITY` 表示须保留旧存档或交互语义。迁移完成前不直接删除业务方法。

| 当前成员及行号 | 当前直接调用者 | 标记 | 目标与保留约束 |
| --- | --- | --- | --- |
| `onRegisterCommands` 73 | NeoForge 命令注册事件 | MOVE | 薄 CommandBridge；保留现有命令名 |
| `registerCommands` 77 | `onRegisterCommands` | MOVE | CommandBridge；`resume/cancel/end/status/diagnose` 路径不变 |
| `startFromAltar` 86 | `onEntityJoin`, `onEntityInteract` | SPLIT | IgnisTrigger 验证 + Store.create 唯一性 + Ignis 初始化；预检失败不创建记录 |
| `resumeCommand` 136 | 命令分发 | SPLIT, COMPATIBILITY | 命令入口 + Lifecycle 恢复 + Ignis 原阶段恢复；不能重放投料 |
| `cancelCommand` 160 | `cancel`, `end` 命令 | SPLIT, COMPATIBILITY | 命令入口 + Result.ABORTED/CLEANUP + Ignis 取消视觉和部分施工清理 |
| `statusCommand` 184 | 命令分发 | SPLIT | 公共挑战状态 + Ignis 专属比分和轮次 |
| `diagnoseCommand` 197 | 命令分发 | MOVE | Ignis 诊断；含菜谱、锅具、厨师和依赖版本 |
| `onServerTick` 240 | NeoForge ServerTick.Post | SPLIT | Tick 入口只遍历 Active；Ignis 处理短期请求超时和 `cookingTracker.tick` |
| `tickCompetition` 259 | `onServerTick` | SPLIT | 派发归 Router/TickDispatcher；桌位迁移、Boss 丢失、观众、各比赛阶段归 Ignis |
| `tickOpening` 321 | `tickCompetition` | MOVE | Ignis 开场演出 |
| `tickBuilding` 340 | `tickCompetition` | MOVE, COMPATIBILITY | IgnisBuildController；保留旧模板、游标和失败恢复 |
| `startRound` 381 | `tickCompetition`, `advanceRound` | MOVE | IgnisRoundController；重标锅具、重置轮次事务、启动厨师 |
| `advanceRound` 398 | `tickCompetition` | MOVE | IgnisRoundController |
| `tickFinalResult` 404 | `tickCompetition` | SPLIT | Ignis 比分与文案；外层 Result、奖励待办与清理门槛；玩家离线行为不擅改 |
| `tickCleanup` 427 | `tickCompetition` | SPLIT, COMPATIBILITY | Ignis 清桌、NPC、模板；Store 在条件满足后归档并移除 Index |
| `onRightClickBlock` 448 | NeoForge 右键方块事件 | SPLIT | 祭坛创建触发、餐桌摆盘、锅具过程追踪、Ignis 区域规则四条路径 |
| `onEntityInteract` 522 | NeoForge 实体交互事件 | SPLIT | 观众交互、未管理 Ignis 补触发、旧式交菜分别归 Ignis |
| `submitRound` 569 | `onEntityInteract` | MOVE, COMPATIBILITY | Ignis 旧式提交与评分；消费后标记、重复提交规则不变 |
| `tryJudgePlated` 620 | `tickCompetition` | MOVE | Ignis 实物摆盘展示和评分 |
| `resetPlatedJudgingTimer` 683 | `tryJudgePlated` | MOVE | Ignis 摆盘事务 |
| `matchesPlatedEvidence` 690 | `tryJudgePlated` | MOVE | Ignis 菜品证明匹配 |
| `onIncomingDamage` 700 | NeoForge 伤害事件 | SPLIT | EventBridge 同步取消；Ignis 判定 Boss、厨师及其弹射物 |
| `onMobGriefing` 712 | NeoForge 实体破坏事件 | SPLIT | EventBridge + Ignis 实体规则 |
| `onEntityJoin` 718 | NeoForge 实体进入事件 | SPLIT | 飞行展示重载清理、Ignis 生成触发、Boss 维持、厨房掉落物规则分开 |
| `onBreak` 745 | NeoForge 破坏方块事件 | SPLIT | EventBridge → 活动区域候选 → Ignis 当前保护规则；同步取消 |
| `onPlace` 751 | NeoForge 放置方块事件 | SPLIT | Ignis 摆盘确认 + 活动区域保护；保留先后顺序 |
| `onExplosion` 776 | NeoForge 爆炸事件 | SPLIT | 按每个受影响方块同步过滤活动区域；章节决定保护策略 |
| `onPiston` 783 | NeoForge 活塞事件 | SPLIT | 入口、出口、推送和破坏位置逐项命中；同步取消 |
| `onFluidPlace` 801 | NeoForge 流体放置事件 | SPLIT | EventBridge + 活动区域保护 |
| `onLogout` 808 | NeoForge 玩家退出事件 | SPLIT, COMPATIBILITY | Ignis 清短期摆盘请求；仅旧 `ROUND_ACTIVE` 自动暂停，其余阶段可继续 |
| `PendingSummon` 823 | `onRightClickBlock`, `onEntityJoin`, `onServerTick` | MOVE | IgnisTrigger 内存关联；不写公共 Challenge |
| `PendingPlate` 826 | `onRightClickBlock`, `onPlace`, `onServerTick`, `onLogout` | MOVE | IgnisDishSubmission 内存事务 |
| `altarSlotEmpty` 830 | `onRightClickBlock` | MOVE | IgnisTrigger 的 Cataclysm 祭坛访问 |
| `face` 841 | `tickOpening` | MOVE | Ignis 演出辅助 |
| `transition` 851 | 多个 Ignis 阶段方法 | SPLIT | 外层 Lifecycle 转换与 IgnisPhase 转换分别标脏 |
| `showRoundProgress` 857 | `startRound` | MOVE | IgnisPresentation |
| `showRoundResult` 863 | `tickCompetition` | MOVE | IgnisPresentation |
| `announce` 870 | Ignis 开始、失败、阶段和结果 | MOVE | IgnisPresentation；保留广播范围和 Ignis 前缀 |
| `statusText` 878 | `resumeCommand`, `statusCommand` | MOVE | Ignis 诊断与展示 |
| `itemId` 884 | `diagnoseCommand` | MOVE | Ignis 诊断辅助 |
| `versions` 888 | `diagnoseCommand` | MOVE | Ignis 诊断辅助 |
| `version` 894 | `versions` | MOVE | Ignis 诊断辅助 |

### 4.1 两条路由路径

创建路径：祭坛/实体事件 → IgnisTrigger 检查玩家、Boss、触发位置和资源 → Store 做最终唯一性检查并创建 → 更新派生 Index。旧代码的 `pendingSummons` 负责关联祭坛点击与随后生成的实体；它不属于挑战身份。

已有挑战路径：事件 → Index/Active 候选 → Store 取记录 → 根据章节 ID 分派。方块事件的区域判断仍由章节区域和保护规则完成。NeoForge 事件取消必须在原事件回调内同步完成；不先异步排队。

## 5. 其他类的迁移标记

| 当前类 | 标记 | 目标 |
| --- | --- | --- |
| `CulinaryCataclysm` | KEEP | 入口保持薄，装配 EventBridge、Store/Router 与章节 |
| `CompetitionSavedData` | COMPATIBILITY | 保留旧数据读取器；新 `ChallengeStore` 是权威 Active/History |
| `CompetitionRecord`, `CompetitionState` | SPLIT, COMPATIBILITY | 外层 Challenge/Lifecycle/Result + 内层 IgnisState/IgnisPhase；保留旧值读取 |
| `IgnisPeaceController` | KEEP, MOVE | Ignis 章节；暂不抽通用 Boss 接口，也不改变赛后状态 |
| `BlazeChefController`, `ArenaAudienceController` | KEEP, MOVE | Ignis 章节；厨师 WorkState 数据从 Controller 独立，观众布局仍属 Ignis |
| `CookingProcessTracker`, `ServingTable`, `DishProvenance`, `CookedDishEvidence`, `DishQualityEvaluator` | KEEP, MOVE | Ignis 料理/提交子域；不抽公共评分；解除证据类对 CompetitionRecord 的静态辅助依赖 |
| `CompetitionRewardHandler` | SPLIT | Ignis 奖励内容 + 可恢复的发放里程碑；文明认可的权威归属另议 |
| `CookeryIntegration` | SPLIT | 森罗适配器只提供菜谱、锅具、品质事实；Ignis 锅位与站点标记移回章节，`IGNIS` 实体 ID 不留在 Cookery 适配器 |
| `KitchenTemplateManager`, `ArenaPlacementValidator`, `MagicSchematicBuilder` | KEEP, SPLIT LATER | 先保持施工行为；渐进分出 BuildPlan 与执行器；祭坛、双厨房、烈焰人座位属 Ignis |

`RENAME` 和 `DELETE` 只在职责已移动、旧格式验收后处理。第一阶段不靠改文件名宣布拆分成功。

## 6. 分阶段施工与验收门槛

这里对来稿的 M1–M4 顺序作一项施工调整：先让 Ignis 职责内聚，再切换权威存档，缩短新旧 Store 同时参与运行的阶段。`Challenge` 的外层格式可在 M1 先定义，但 M2 验收前不接管实际保存。每个阶段单独提交并验证，旧格式读取器保留到旧世界续玩通过。

| 阶段 | 改动范围 | 完成证据 |
| --- | --- | --- |
| M0 基线 | 备份旧世界，记录旧 Ignis 各阶段 NBT 与实际游戏表现；补自动化测试入口 | 正常、施工中断、出锅、摆盘、胜负、奖励、清理中断的可复现样本 |
| M1 Ignis 责任内聚 | 移动 Ignis 逻辑，仍使用旧 CompetitionSavedData/NBT | 旧世界行为、命令、事件取消与视觉节奏不变；build 和相关游戏验证通过 |
| M2 外层存档 | 新 Challenge/Store、版本化章节数据、旧格式幂等导入 | 旧活动记录能恢复；重复启动不重复导入；结果和清理游标不丢 |
| M3 派生索引 | Active only、玩家与 Boss 索引、重建 | 重启前后查询一致；历史不 Tick；归档后区域保护解除 |
| M4 结算与归档 | 精简 History、Result 与奖励/清理里程碑 | 离线与重启可续结算；重复 Tick 不重复奖励；旧完整状态不留 History |
| M5 外部边界 | Cookery 适配器不导入 Ignis/CompetitionRecord；建造职责逐步分开 | 依赖方向静态检查、build、Ignis 实际流程 |
| M6 第二章节 | 远古遗魂接入同一薄核心 | 无锅具/厨师/轮次假设进入公共层；独立章节可恢复与清理 |

GameTest 可用于确定性的世界交互测试，仍需实际客户端验证 Ignis 演出、Boss 行为和旧存档续玩。`gradlew build` 只证明编译与打包，不证明这些游戏行为。当前仓库尚无 `src/test` 或 GameTest 源码。

## 7. 评审红线

1. `Challenge`、Store、Router、Index 不出现 `Ignis`、`Blaze`、`Cookery`、锅、餐桌、轮次或评分字段/分支。
2. 适配器只读取或操作外部模组事实，不导入 `CompetitionRecord`、`IgnisState` 或章节控制器。
3. Index 可从 Active 和章节关联重建；不保存第二份挑战对象，也不成为权威状态。
4. Active → History 前，结果、需要执行的奖励和清理都有明确的完成或待处理记录。
5. 旧 `CompetitionRecord` 中的模板、游标、NPC 账本和证据在迁移期间不丢失；旧世界可继续玩。
6. EventBridge 在同一事件回调内返回取消/允许结果；TickDispatcher 只遍历 Active，章节决定工作频率。

参考：[NeoForge 1.21.1 SavedData](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)、[NeoForge 1.21.1 GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)。

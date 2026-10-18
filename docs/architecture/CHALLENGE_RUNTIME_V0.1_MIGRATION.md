# Peace Cataclysm — Challenge Runtime v0.1

状态：M1–M3 已落地；此文记录当前边界及后续迁移顺序。目标为 Minecraft 1.21.1、NeoForge 21.1.244、Java 21、单模组 `culinary_cataclysm`。

## 当前边界

- `Challenge` 保存公共身份、参与者、维度、Lifecycle、Result、时间与版本化章节数据；不包含 Boss、锅具、轮次、评分等 Ignis 字段。
- `ChallengeStore` 是唯一可写的挑战 `SavedData`，同一文件中保存完整 Active 与精简 History。`CLEANUP` 仍属于 Active；归档后不再 Tick。
- `ActiveChallengeIndex` 从 Active 重建玩家关联；Boss 关联由章节从自身数据重放。索引不写盘，也不保存第二份 Challenge 对象。区域查询暂遍历活动 Ignis 记录，待性能证据出现后再考虑 Chunk 索引。
- `ChallengeLifecycle` 的 `RUNNING / PAUSED / CLEANUP / ENDED` 与 `ChallengeResult` 的 `UNRESOLVED / SUCCESS / FAILURE / ABORTED` 是两个维度。
- `ChallengeRuntime` 只是子系统名称，不是一个 Manager 类。`ChallengeRouter` 尚未实现；目前 `IgnisEventBridge` 是 NeoForge 事件入口，`IgnisChapter` 处理章节行为。
- 外部模组适配器提供事实与操作；公共 Store 和 Index 不解释 Ignis 的章节数据。`CookeryIntegration` 已不导入比赛对象。

## 存档约定

当前世界使用 `culinary_cataclysm_challenges.dat`，Store 外层格式为 1，Ignis `chapter_data_version` 为 2。`CompetitionRecord.save/load` 是**当前 Ignis 章节数据**的序列化入口；`IgnisChallengeRegistry` 是章节专属的活动记录视图，每次只把发生变化的记录提交给 `ChallengeStore`，归档时从活动视图移除。它不是第二份存档。`CompetitionRecord` 的状态职责仍待 M4 拆分。

用户已明确放弃旧游戏世界兼容。程序不再读取 `culinary_cataclysm_competitions.dat`，也不迁移旧 Store 的 Ignis v1 章节数据。旧世界若含不支持的章节版本，加载会报错；需要用新世界验证当前流程。旧世界文件未被本次代码清理删除。

当前场地只使用 `battlefield.nbt` 的地面以上方块；按行镜像施工和实物食物方块摆盘。凳子与当前模板保持用户已确认的版本。大厅原地板不改动；与场地重叠的祭坛方块由 `preservedBattlefieldBlocks` 跳过。旧双厨房模板、螺旋施工顺序、旧式手持菜品右击 Ignis 交菜及旧餐桌位置迁移已删除。`buildCursor`、`cleanupTargetCount`、厨师库存和料理证据仍需保存，以支持**当前版本**比赛在施工、评分或清理中重启后继续。

`setDirty()` 标记 Store 需要写盘，不等于马上完成磁盘事务。奖励发放与 SavedData 不是原子事务；后续结算工作须继续处理幂等性。History 仅留身份、时间、结果与必要摘要，不留施工、NPC 或料理运行状态。

## 后续迁移顺序

| 阶段 | 主要工作 | 验收重点 |
| --- | --- | --- |
| M4 | 继续拆 `CompetitionRecord` 的 Ignis 状态；明确奖励、清理、归档里程碑 | 新世界中断、重启、继续、奖励不重发、场地恢复 |
| M5 | 抽薄事件路由，逐步分离施工执行与章节布局；继续收紧外部适配器 | 事件取消仍同步，公共层无 Ignis 分支 |
| M6 | 接入远古遗魂章节 | 无锅具、厨师、轮次假设进入公共层；独立恢复与清理 |

第二章节出现前，不冻结通用 Boss Adapter、Arena Service、评分引擎、大量泛型接口或动态 Codec 工厂。

## 评审规则

1. Store、Index 和未来 Router 不导入 `CompetitionRecord` 或 Ignis 控制器。
2. Index 只加速查找，权威状态由 Store 与章节数据保存。
3. 方块保护、爆炸、活塞等同步取消必须在 NeoForge 事件回调中完成。
4. 重构以当前格式的新世界行为为基线；构建成功不能替代实际游戏交互和重启验证。

参考：[NeoForge 1.21.1 SavedData](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)、[NeoForge 1.21.1 GameTest](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)。

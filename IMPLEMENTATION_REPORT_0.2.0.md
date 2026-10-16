# 食之旅人：灾变与羁绊 0.2.0 实现与验证报告

## 实际实现

### 玩家真实料理闭环

- 监听参赛者对本轮指定森罗锅具的服务端交互。
- 对比交互前后 `PotBlockEntity` / `StockpotBlockEntity` 的公开状态、输入、炒锅翻炒次数、热源和真实成品。
- 只有锅具已完成、实际配方匹配、存在森罗品质组件且真实取出产生新成品时，才生成 `CookedDishEvidence`。
- 每份菜品得到唯一 UUID；物品数据组件仅保存索引，SavedData 保存权威记录。
- 提交时同时验证比赛 UUID、轮次、作者、菜谱、成品 ID、未提交状态；全部通过后消费一份并原子标记已提交。
- 赛前同 ID 菜品、其他轮次菜品、其他比赛菜品和同 UUID 复制品不能重复计分。

### NPC 真实料理闭环

- 状态：`WAITING / FETCHING / PREPARING / MOVING_TO_STATION / COOKING / COLLECTING / SUBMITTING / FINISHED / FAILED`。
- 每轮只按真实配方向封闭账本配给一次食材、容器和必要耗材。
- 直接调用森罗 1.6.0 公开 `PotBlockEntity` / `StockpotBlockEntity` API 投料、检查热源、翻炒/盖盖、等待真实完成并取出真实成品。
- 原料从 NPC 账本扣除后才进入锅具；中断恢复不会再次配给同一轮原料。
- NPC 成品进入与玩家完全相同的 `CookedDishEvidence` 和 `DishQualityEvaluator`。

### 公平评分

总分只使用可验证事实：

- 真实森罗锅具和有效热源：40 分。
- 有可追溯投入食材：20 分。
- 有效工序完成比例：20 分。
- 森罗真实品质组件：20 分。

`SUPERB / EXCELLENT / STANDARD / POOR` 分别提供 20 / 16 / 12 / 6 分。纯等待、自然倒计时和无意义重复右键不计有效工序。

### 诊断

`/culinarycompetition diagnose`：

- 普通玩家看到是否可继续、当前菜谱、双方锅具/热源、NPC 状态、生产和提交数量。
- 管理员额外看到实际加载的依赖版本、注册表状态、锅具状态、配方匹配和权威证据数量。

### 演出

- Ignis 使用 Cataclysm 3.33 独立同步的 `setIsSword(true/false)` 持剑状态；不调用 `MAGIC_ATTACK`、`PHASE_3` 或挥剑攻击动画。
- 该安全状态能平滑显剑/持剑，但没有把它虚报为完整“剑尖指天”的专属动画。
- 厨房施工使用原版 `BlockDisplay` 展示真实目标方块状态，最多同时 6 个，持续 12 tick 沿弧线飞行；落位时服务器才放置实际方块和 BlockEntity。

### 存档与安全

- 新证据、NPC 状态、NPC 原料账本、当前成品 UUID、生产/提交计数均写入原有 SavedData。
- 旧 0.1.0 严格菜谱 ID 自动迁移到 0.2.0 灵活菜谱；不存在的番茄牛腩汤迁移为真实存在的罗宋汤。
- 比赛区域阻止方块破坏、放置、流体放置、爆炸破坏和活塞搬运。
- NPC 厨房禁止玩家交互；临时设备清理仍只删除与模板方块类型一致的目标方块。

## 依赖与菜谱核验

- 依赖版本和文件名见 `DEPENDENCY_LOCK.md`。
- 八个 `rounds.json` ID 已直接在森罗 1.6.0 发布 JAR 中逐项核验，均存在且产物正确。
- 使用 `flex_pot` / `flex_stockpot`，由森罗本身计算并写入品质，而不是附属伪造品质。

## 实际测试结果

### 通过

- `mc_check`：通过，16 个 Java 文件，无类型、资源链或已知运行前错误。
- `mc_build`：通过，Java 21 / NeoForge 21.1.244，生成可安装 JAR。
- JAR 自检：包含新增证据类与八轮配置；不内嵌森罗第三方类。
- 菜谱资源审计：8/8 ID 与产物匹配。

### 尝试但未完成

- 尝试安装 NeoForge 21.1.244 专用服务端进行真实依赖共同启动。
- 安装器下载连接 `maven.neoforged.net:443` 连续 133 秒超时，服务端无法安装；因此没有真实加载日志。
- 厨房 NBT 的临时外部解析脚本因解析器依赖下载超时未完成；模板仍由工程内真实 `NbtIo` 加载器处理，但灶台热源结论必须实机确认。

### 尚未实机验收

- 玩家真实锅具交互与成品签发。
- NPC 八轮连续烹饪。
- 赛前菜、创造复制、箱子/丢弃/拾取/漏斗转移。
- 世界重启后的比赛恢复。
- 客户端 Ignis 持剑和飞行方块画面。
- MSPT、FPS 和完整八轮性能数据。

不能把上述未验收项写成通过；可复现步骤见 `TESTING_0.2.0.md`。

## 正常召唤状态

Cataclysm 3.33 正式召唤链为 `burning_ashes → altar_of_fire → AltarOfFire_Block_Entity 延时创建 Ignis`。新 Ignis 不带召唤者 UUID或不可伪造的来源标记，单凭 `EntityJoinLevelEvent` 不能安全区分祭坛、显式 NBT 命令和其他模组直接创建。本轮保留 `/culinarycompetition start`，未无差别自动接管所有 Ignis。后续应在祭坛投入/生成路径建立服务端可信关联。

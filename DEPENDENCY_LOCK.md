# 0.2.0 兼容依赖锁定

本记录用于复现实机整合包；附属本身不内嵌这些依赖。

本地 `run/mods` 当前使用 `[机械动力] create-1.21.1-6.0.10.jar`；模组元数据允许 Create `[6.0.6,6.1)`。下表的 6.0.6 是原始锁定清单，复现实机时应另记录实际加载的文件版本。

| 组件 | 锁定版本 | 实际文件名 | 来源 |
|---|---|---|---|
| Minecraft | 1.21.1 | 官方客户端/服务端 | Mojang Launcher |
| NeoForge | 21.1.244 | NeoForge 21.1.244 | NeoForged Maven |
| L_Ender's Cataclysm | 3.33 | `L_Ender's Cataclysm 1.21.1-3.33.jar` | Modrinth 版本 `PsPYpoCC` |
| Lionfish API | 3.1 系列（Cataclysm 必需） | 以 Cataclysm 3.33 发布页依赖为准 | Modrinth 项目 `lionfish-api` |
| Curios API | 9.2.2+1.21.1 或更高兼容版（Cataclysm 必需） | 以 Cataclysm 3.33 发布页依赖为准 | Modrinth 项目 `curios` |
| Create | 6.0.6 | `create-1.21.1-6.0.6.jar` | Modrinth 版本 `tS7ygzAE` |
| Kaleidoscope Cookery | 1.6.0 NeoForge | `kaleidoscopecookery-1.6.0-neoforge+mc1.21.1.jar` | Modrinth 版本 `g8w0b2ly` |
| Culinary Cataclysm | 0.2.0 | `culinary_cataclysm-0.2.0.jar` | 本工程构建产物 |

## 已核实接口

- 森罗注册 ID：`pot`、`stockpot`、`stove`、`chopping_board`。
- 森罗公开锅具 API：`IPot`、`IStockpot`，公开投料、热源检查、状态、锅盖和取出操作。
- 森罗灵活配方序列化器：`flex_pot`、`flex_stockpot`。
- 森罗 1.6.0 的灵活配方会在成品上写入 `kaleidoscope_cookery:quality` 数据组件。
- Cataclysm 3.33 的 Ignis 实体为 `cataclysm:ignis`；安全演出仅使用独立同步的持剑状态，不调用攻击动画。

## 兼容边界

- `neoforge.mods.toml` 将核心依赖范围锁到上述已调查版本线；更换整合包版本前应重新核对公开 API 与菜谱 ID。
- Create 当前仅作为整合包必需依赖和未来蓝图兼容点；0.2.0 的飞行施工使用原版 `BlockDisplay`，没有调用 Create 内部类。
- 本记录是源码/API 审计结果，不等同于完整客户端实机通过记录。

## 本地开发实例辅助模组

以下文件安装在 `run/mods`，供本地 `runClient` 使用；不打入本附属模组的构建产物。版本均为 Minecraft 1.21.1 / NeoForge 对应文件。

| 模组 | 版本 | 文件 | 来源 |
|---|---|---|---|
| Just Enough Items (JEI) | 19.51.0.418 | `jei-1.21.1-neoforge-19.51.0.418.jar` | [Modrinth](https://modrinth.com/mod/jei/version/ufHUqt9b) |
| Jade（玉） | 15.10.6 | `Jade-1.21.1-NeoForge-15.10.6.jar` | [Modrinth](https://modrinth.com/mod/jade/version/eYz2YBGT) |
| Just Enough Characters（拼音搜索，客户端） | 4.5.30 | `jecharacters-1.21.1-neoforge-4.5.30.jar` | [Modrinth](https://modrinth.com/mod/justenoughcharacters/version/iNnJuUnx) |
| Building Wands（建筑魔杖） | 3.0.5 beta | `BuildingWands-neoforge-MC1.21.1-3.0.5.jar` | [Modrinth](https://modrinth.com/mod/building-wands/version/nvMyIx0w) |
| Cloth Config API（魔杖必需） | 15.0.140 | `cloth-config-15.0.140-neoforge.jar` | [Modrinth](https://modrinth.com/mod/cloth-config/version/izKINKFg) |
| Architectury API（魔杖必需） | 13.0.11 | `architectury-13.0.11-neoforge.jar` | [Modrinth](https://modrinth.com/mod/architectury-api/version/1IiqEQGl) |

下载后按 Modrinth 版本元数据逐个校验 SHA-512。`runClient` 加载清单包含上述六个模组；世界内的拼音查询和魔杖操作仍需手动体验确认。

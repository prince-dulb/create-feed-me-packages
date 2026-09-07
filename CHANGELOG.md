# Changelog ｜ 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。版本代号 `test.N` 为内部测试构建，正式版本才进入「Unreleased / 已发布」段。项目内部规划文档不随源代码仓库公开，见维护者工作副本。

## [Unreleased] ｜ 未发布

### 计划中 / Planned
- `v0.2.0` 真实退货与 `v0.3.0` 插件构筑。
- 见 README Roadmap。

---

## [0.1.0] - 2026-09-07

首个可玩版本。First playable version.

### Added ｜ 新增
- 两类终端：**供应链坠**（本件缓存，随物品转交）与 **供应链坠·私人定坠**（物主锁定）。
- 真实补货闭环：Create 网络真实请求 → 真实打包 → 本地收件口自动入库；每格一个残包位，后台自动续收。
- 精确物品身份：物品注册 ID + 完整持久化数据组件；同 ID 不同组件可分别占格、组件往返不降级。
- 1—5 级缓存：9/16/24/30/36 格，128/256/512/1024/2048 单格容量；Create 序列组装 + 锻造台升级。
- 私人化：普通坠保留整份缓存私人化；物主=锻造者，仅物主访问/升级，非物主佩戴退化为全新普通坠。
- 取料：原版 2×2/3×3、配方书、JEI 配方填充与标准投射物；固定背包优先。
- 地址横幅点击复制、逐格残包绿点、成长链与面板提示精简；依赖声明仅 Minecraft/NeoForge/Create/Curios 四项 required。

### Changed ｜ 变更
- 缓存计数采用统一「组容量」模型（`groupCapacity = capacity / 64`），显示回归精确物品数。

### Removed ｜ 移除
- 移除旧「待签收容量授权 / 佩戴签收」升级机制，改为物主直接升级。

### Internal test builds ｜ 内部测试构建
- `test.24`：物主锁定模型（本版本母版）。
- `test.23`：残包按物品格分格。
- `test.17—22`：组容量、滑条层级/覆盖、逐格残包提示等收敛。

---

[0.1.0]: https://github.com/prince-dulb/create-feed-me-packages/releases/tag/v0.1.0

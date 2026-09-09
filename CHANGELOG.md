# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[semantic-ish versioning](https://semver.org/) with `MAJOR.MINOR.PATCH` where `MINOR` marks a
gameplay milestone.

本文件记录本项目的所有重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号沿用 `MAJOR.MINOR.PATCH`，其中 `MINOR` 对应一次玩法里程碑。

## [0.2.0] — 2026-09-09

### Added / 新增

- **Automatic returns / 自动退货**: each cache can store a default return address. When a cell
  exceeds its configured maximum, the surplus is packed and dispatched by a paper plane or a
  robo-bee. The cache and the carrier are debited only after the carrier really accepts the
  package; a failed dispatch changes nothing.
  每个缓存可设默认退货地址；某格超过最高值时，超出部分自动打包并交给纸飞机或运输蜂；只有载体真实受理后才扣缓存与载体，失败不扣。
- **Take-reserve / 取物预留**: taking from a cache cell first shows a preview on the cursor.
  The cache is debited only when the items are really placed; putting them back is net zero, and
  closing the screen or removing the pendant cancels an unplaced preview and leaves the items in
  the cache.
  从缓存格取出时先在光标上预览，真正放下才扣缓存；放回净零；关闭界面或卸下饰品会取消未落位预览并留在缓存。
- **Uniform stack-group capacity / 统一组容量**: level 1–5 cells hold 2 / 4 / 8 / 16 / 32 stacks,
  converted with each item's own stack size. The slider works in stacks, with a lower handle
  (restock) and an upper handle (return; fully right = no return).
  1—5 级单格容量为 2／4／8／16／32 组，按物品自身堆叠上限换算；滑条以“组”为刻度，下端点设最低值（补货）、上端点设最高值（退货，最右为不退货）。
- **Full-inventory receiving / 满背包收件**: dedicated parcels still enter the cache when the
  player inventory and hotbar are full. Anything that does not fit stays in a per-cell residual
  package with a small green dot, and is absorbed automatically when space frees up.
  背包与快捷栏全满时专用包裹仍能进入缓存；装不下的部分按物品格保留为残包（左下角绿点提示），有空间时自动续收。

### Changed / 变更

- Return-arrow colour now reflects the real dispatch result: red only after a carrier accepted the
  surplus while the cell is still over its maximum, grey otherwise, and it disappears once the
  cell is back at its maximum.
  退货箭头颜色改为反映真实发运结果：仅在载体受理且仍超额时为红色，其余为灰色，退到最高值后消失。
- Cache schema is **8** (per-cache return address); panel network protocol is **4**. Use the same
  version on client and server.
  缓存 schema 为 **8**（每缓存默认退货地址）；面板网络协议为 **4**，客户端与服务端请使用同一版本。

### Fixed / 修复

- Creative-mode store/take no longer duplicates items.
- Held-item icon no longer renders offset from the cursor.
- Panel, slider and tooltip layering corrected.
- Slider endpoints no longer overshoot the track by one pixel; dragging follows the cursor, and
  pressing an endpoint without moving no longer rewrites the value.
  修复创造模式存取复制、手持图标错位、面板／滑条／提示层级、滑条端点超出轨道 1px，以及按住端点不动却改数值。

## [0.1.1] — 2026-09-07

### Added / 新增

- **Ownerless personal pendant / 无主私人定坠**: a personal pendant without an owner is claimed by
  its first wearer and from then on belongs to them. Pendants that already have an owner still
  degrade to a fresh ordinary pendant for other players.
  无主私人定坠由第一个佩戴者认领并归其所有；已有物主的坠子对其他玩家仍退化为全新普通坠。

## [0.1.0] — 2026-09-07

### Added / 新增

- First local playable release: supply-chain cache with pendant binding, real Create restock
  requests, package receiving with residuals, crafting / JEI / standard-projectile material
  drawing, five-level growth and owner-locked personal caches.
  首个本地可玩版本：坠子绑定缓存、真实 Create 补货请求、含残包的收件、合成／JEI／标准投射物取料、五级成长与物主锁定的个人缓存。

[0.2.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.2.0
[0.1.1]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.1.1
[0.1.0]: https://github.com/Scathiard/create-feed-me-packages/releases/tag/v0.1.0

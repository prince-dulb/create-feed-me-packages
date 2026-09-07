# Create: Feed Me Packages! ｜ 机械动力：喂我发包！

A Create addon for Minecraft 1.21.1 / NeoForge that lets you wear a supply-chain pendant, configure a real logistics cache, and have your Create factory deliver materials straight to you — plus pull cached materials into crafting, JEI and standard projectile firing.

一个「机械动力」附属模组：佩戴供应链坠，配置一份真实物流缓存，让 Create 工厂把材料直接送来；缓存的物资还能喂给原版合成、JEI 与弓弩弹药。

- **Platform / 平台**: Minecraft **1.21.1** / **NeoForge 21.1.219**
- **License / 许可证**: MIT
- **Status / 状态**: v0.1.0 — first playable version

---

## ▶️ Features / 特性

- **Two terminals / 两种终端**
  - **Supply Chain Pendant / 供应链坠**: a cache bound to the physical pendant (travels with the item).
  - **Supply Chain Pendant: Made to Order / 供应链坠·私人定坠**: a cache locked to its crafter (owner), shared by that owner and upgraded in the smithing table.
- **Real logistics / 真实物流**: creates real Create packages from your network; FMP never spawns items.
- **Exact item identity / 精确物品身份**: filters and stores items by full registered ID + persistent data components (so enchanted books, different ammo/oil states each occupy their own cell).
- **1–5 tiers / 1—5 级**: 9/128 → 36/2048 cells / per-cell capacity, with sequenced-assembly upgrades and a shared plugin-slot entitlement (plugin content arrives later).
- **Crafting & ammo / 合成 & 弹药**: pull cached materials into vanilla 2×2/3×3, the recipe book and JEI, and feed standard projectile weapons.
- **Fits your inventory / 满背包也收**: a dedicated receive port holds real leftover parcels per cell; no hidden duplicate backpacks.
- **Fixed backpack-first / 固定背包优先**: cached materials top up only when your backpack is short — no toggles.

## 📦 Installation / 安装

1. A Minecraft **1.21.1** NeoForge profile (**NeoForge 21.1.219+**).
2. Install **Create** (6.0.10) and **Curios** (9.5.1+) — both are **required**.
3. Drop `create-feed-me-packages-0.1.0.jar` into your `mods` folder.

JEI, Package Couriers and Mobile Packages are **optional** compat targets; the mod loads fine without them (they are *not* declared as hard dependencies).

## 🎮 Gameplay / 玩法

- Craft a **Supply Chain Pendant**, sneak-use it on a Create logistics-network device to bind it, then wear it in the **Curios necklace slot** (only one pendant total).
- The cache panel appears on the left of your inventory. Deposit an item into an empty cell to set its filter, then set a low threshold from the small dot.
- When the cached stock drops below the minimum, FMP sends a **real Create request** to the bound network; the delivered package is unpacked automatically (with a per-cell residual when your inventory is full).
- **Made to Order**: smith an ordinary pendant with a Personalization Link to lock it to you. Only you can use that personal cache and upgrade it (smithing each link advances one tier, up to tier 5). Anyone else who wears it gets a fresh ordinary pendant instead.

> This repository contains the **source and build config** only. The project's internal planning docs (`docs/`, full collaboration record) and per-test-build release archives are intentionally **not** committed here — they live in the maintainer's working copy. Download built releases from the [Releases](../../releases) page.

## 🗺️ Roadmap / 路线图

| Version | 内容 |
| --- | --- |
| `v0.1.0` ✅ | Two caches, real restock & receive, ownership-locked personal pendant, crafting/JEI/ammo pulls |
| `v0.2.0` | Real returns: max threshold, transport slots, return address, paper-plane / bee dispatch |
| `v0.3.0` | Plugin crafting: real plugins, repeatable install, atomic swap & capacity safety |
| `v0.4.x` | TaCZ, third-party backpack access, no-Curios armour entry |

## 🔨 Build / 构建

Requires a Java 21 toolchain (`java-runtime-delta`) and, if behind a proxy, process-level `GRADLE_OPTS` for `127.0.0.1:7890`.

```powershell
.\gradlew.bat test build runGameTestServer -PfmpGameTests --no-daemon --no-configuration-cache --console=plain
```

## 📄 License / 许可证

Released under the **MIT License** (© 2026 Scathiard). See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency/licensing details. No third-party mod assets are redistributed.

## 🙏 Credits / 致谢

- **Create** (MIT) and **Curios** (LGPL-3.0-or-later) are required dependencies resolved separately.
- Mod ID / Maven group: `dev.scathiard` · Java root package: `dev.scathiard.feedmepackages`.
- Project lead: **Scathiard**.

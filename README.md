# Create: Feed Me Packages!

[简体中文](readme.zh.md) | [English](README.md)

A **Create** addon for Minecraft 1.21.1 / NeoForge that turns your Create factory into a carry-it-with-you supply chain: wear a pendant, configure a real logistics cache, and have Create deliver materials straight to you — plus pull cached materials into crafting, JEI and standard projectile firing.

- **Platform**: Minecraft **1.21.1** / **NeoForge 21.1.219**
- **License**: MIT
- **Status**: v0.1.0 — first playable version

---

## ▶️ Features

- **Two terminals**
  - **Supply Chain Pendant** — a cache bound to the physical pendant (travels with the item).
  - **Supply Chain Pendant: Made to Order** — a cache locked to its crafter (owner), shared by that owner and upgraded in the smithing table.
- **Real logistics** — creates real Create packages from your network; never spawns items.
- **Exact item identity** — filters and stores items by full registered ID + persistent data components (enchanted books, different ammo/oil states each occupy their own cell).
- **1–5 tiers** — 9/128 → 36/2048 cells / per-cell capacity, with sequenced-assembly upgrades and a shared plugin-slot entitlement (plugin content arrives later).
- **Crafting & ammo** — pull cached materials into vanilla 2×2/3×3, the recipe book and JEI, and feed standard projectile weapons.
- **Fits your inventory** — a dedicated receive port holds real leftover parcels per cell; no hidden duplicate backpacks.
- **Fixed backpack-first** — cached materials top up only when your backpack is short; no toggles.

## 📦 Installation

1. A Minecraft **1.21.1** NeoForge profile (**NeoForge 21.1.219+**).
2. Install **Create** (6.0.10) and **Curios** (9.5.1+) — both are **required**.
3. Drop `create-feed-me-packages-0.1.0.jar` into your `mods` folder.

> JEI, Package Couriers and Mobile Packages are **optional** compat targets; the mod loads fine without them. They are **not** declared as hard dependencies.

## 🎮 Gameplay

- Craft a **Supply Chain Pendant**, sneak-use it on a Create logistics-network device to bind it, then wear it in the **Curios necklace slot** (only one pendant total).
- The cache panel appears on the left of your inventory. Deposit an item into an empty cell to set its filter, then set a low threshold from the small dot.
- When cached stock drops below the minimum, FMP sends a **real Create request** to the bound network; the delivered package is unpacked automatically (with a per-cell residual when your inventory is full).
- **Made to Order**: smith an ordinary pendant with a Personalization Link to lock it to you. Only you can use that personal cache and upgrade it (smithing each link advances one tier, up to tier 5). Anyone else who wears it gets a fresh ordinary pendant instead.

## 🗺️ Roadmap

| Version | Highlights |
| --- | --- |
| `v0.1.0` ✅ | Two caches, real restock & receive, ownership-locked personal pendant, crafting/JEI/ammo pulls |
| `v0.2.0` | Real returns: max threshold, transport slots, return address, paper-plane / bee dispatch |
| `v0.3.0` | Plugin crafting: real plugins, repeatable install, atomic swap & capacity safety |
| `v0.4.x` | TaCZ, third-party backpack access, no-Curios armour entry |

## 🔨 Build

Requires a Java 21 toolchain (`java-runtime-delta`). If behind a proxy, set process-level `GRADLE_OPTS` for `127.0.0.1:7890`.

```powershell
.\gradlew.bat test build runGameTestServer -PfmpGameTests --no-daemon --no-configuration-cache --console=plain
```

## 📄 License

Released under the **MIT License** (© 2026 Scathiard). See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency/licensing details. No third-party mod assets are redistributed.

## 🙏 Credits

- **Create** (MIT) and **Curios** (LGPL-3.0-or-later) are required dependencies resolved separately.
- Mod ID / Maven group: `dev.scathiard` · Java root package: `dev.scathiard.feedmepackages`.
- Project lead: **Scathiard**.

[简体中文](readme.zh.md) | [English](README.md)

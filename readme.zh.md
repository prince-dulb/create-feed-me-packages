# 机械动力：喂我发包！

[简体中文](readme.zh.md) | [English](README.md)

一个「机械动力」附属模组，把 Create 工厂变成随身的供应链：佩戴供应链坠，配置一份真实物流缓存，让 Create 工厂把材料直接送来——缓存里的物资还能喂给原版合成、JEI 与标准投射物武器。

- **平台 / Platform**：Minecraft **1.21.1** / **NeoForge 21.1.219**
- **许可证 / License**：MIT
- **状态 / Status**：v0.1.0 首个可玩版本

---

## ▶️ 特性 / Features

- **两种终端 / Two terminals**
  - **供应链坠 / Supply Chain Pendant**——缓存绑定在坠子本体上（随物品转交）。
  - **供应链坠·私人定坠 / Supply Chain Pendant: Made to Order**——缓存锁定给锻造它的物主，由该物主共享并在锻造台升级。
- **真实物流 / Real logistics**——从你的 Create 网络生成真实包裹，绝不凭空出物。
- **精确物品身份 / Exact item identity**——按「注册 ID + 完整持久化数据组件」过滤与存储（附魔书、不同弹药/气量各自独立占格）。
- **1—5 级 / 1–5 tiers**——9/128 → 36/2048 格 / 单格容量；序列组装升级，并预留插件槽权益（插件内容后续版本）。
- **合成 & 弹药 / Crafting & ammo**——把缓存材料填入原版 2×2/3×3、配方书与 JEI，并供给标准投射物武器。
- **满背包也收 / Fits your inventory**——专用收件口按格持有真实残包，无隐藏复制背包。
- **固定背包优先 / Fixed backpack-first**——只在你背包不足时用缓存补足，无切换开关。

## 📦 安装 / Installation

1. 一个 Minecraft **1.21.1** 的 NeoForge 环境（**NeoForge 21.1.219+**）。
2. 安装 **Create**（6.0.10）与 **Curios**（9.5.1+）——两者为**必需**依赖。
3. 把 `create-feed-me-packages-0.1.0.jar` 放入 `mods` 文件夹。

> JEI、Package Couriers 与 Mobile Packages 为**可选**兼容目标；缺少也能正常加载，它们**未**写入硬依赖声明。

## 🎮 玩法 / Gameplay

- 制造**供应链坠**，潜行右键 Create 物流网络设备绑定，然后戴到 **Curios 项链槽**（全身上下限一件坠子）。
- 背包左侧出现缓存面板。把实物放进空格设置过滤，再点格子小点设置最低数量。
- 缓存低于最低值时，FMP 向绑定的网络提交**真实 Create 请求**；送达的包裹自动入库（背包满时按格保留残包）。
- **私人定坠**：用私人化链节在锻造台把普通坠锁给**你**。只有你能使用并升级该私人缓存（每枚链节升一级，最高 5 级）。别人戴上会得到一枚全新普通坠。

## 🗺️ 路线图 / Roadmap

| 版本 | 内容 |
| --- | --- |
| `v0.1.0` ✅ | 两类缓存、真实补货与收件、物主锁定私人定坠、合成/JEI/弹药取料 |
| `v0.2.0` | 真实退货：最高阈值、运输物格、返回地址、纸飞机/运输蜂发运 |
| `v0.3.0` | 插件构筑：真实插件、重复安装、原子拆装与容量安全 |
| `v0.4.x` | TaCZ、第三方背包访问、无 Curios 护甲入口 |

## 🔨 构建 / Build

需要 Java 21 工具链（`java-runtime-delta`）。若走代理，用进程级 `GRADLE_OPTS` 指向 `127.0.0.1:7890`。

```powershell
.\gradlew.bat test build runGameTestServer -PfmpGameTests --no-daemon --no-configuration-cache --console=plain
```

## 📄 许可证 / License

采用 **MIT 许可证**（© 2026 Scathiard）。依赖与许可详情见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。不重新分发任何第三方模组素材。

## 🙏 致谢 / Credits

- **Create**（MIT）与 **Curios**（LGPL-3.0-or-later）为分别解析的必需依赖。
- Mod ID / Maven group：`dev.scathiard` · Java 根包：`dev.scathiard.feedmepackages`。
- 项目负责人：**Scathiard**。

[简体中文](readme.zh.md) | [English](README.md)

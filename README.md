# Create: Feed Me Packages!

English | [简体中文](readme.zh.md)

## About

Your factory can produce building supplies automatically, but you still have to run home to collect them. **Create: Feed Me Packages!** is about saving that trip.

Wear a Supply Chain Pendant, connect it to your factory's logistics network, and choose what to keep stocked. As you use supplies, the pendant requests replacements. Your packagers box them up, paper planes or robo-bees bring them over, and the contents go into a cache beside your inventory. You can also draw from it to craft what you need on the spot.

The idea comes from personal logistics in *Dyson Sphere Program*. Here, we want keeping yourself supplied to be another job for the Create factory you've built. The mod runs on **Minecraft 1.21.1 / NeoForge** and requires **Create** and **Curios**.

## Where it helps

- Keep common blocks and machines on your restocking list when building away from base, with fewer trips back to storage.
- Use cached ingredients while expanding your factory, without sending a manual request through a portable terminal.
- Keep arrows and other consumables in the cache and let your factory replenish them, leaving inventory space for things you find along the way.
- Hand a pendant and its supplies to a friend, or make a personal pendant to keep your own stock.

## Features

### Two Supply Chain Pendants

Who keeps the supplies depends on which pendant you wear.

| Item | Cache ownership |
| --- | --- |
| **Supply Chain Pendant** | Supplies and settings travel with the pendant. Give it to a friend and they can pick up where you left off. Destroying it removes access to that cache. |
| **Supply Chain Pendant: Made to Order** | Personalize an ordinary pendant to move its contents into the crafter's personal cache. All personal pendants belonging to that player share stock, settings, and level. |

A Made to Order pendant belongs to a specific player. If someone else wears yours, it becomes a fresh ordinary pendant; your personal stock stays yours. An ownerless personal pendant belongs to the first player who equips it.

You can wear one pendant at a time. Taking it off stops cache access, restocking, receiving, and automatic material use. Your stock and settings stay there for when you put it back on.

### A separate cache with automatic restocking

Equip a pendant and cache cells appear to the left of your inventory, in both Survival and Creative. Put an item into an empty cell to choose what goes there, then click the small dot in the corner to set how much to keep stocked.

For example, set a minimum of two stacks of logs. Whenever the cache falls below that amount, the pendant requests the difference from your network. Only cached stock counts toward this minimum, not items in your normal inventory.

Delivered packages are unpacked straight into the cache. You don't have to empty them into your inventory first, and a dedicated receiving entry still works when that inventory is full. Anything that won't fit in the cache stays in a residual package until there's room. There's a limit to those leftovers, too, so they aren't an infinite warehouse.

Stored items keep data such as enchantments, with different variants kept in separate cells. Items that can't be saved safely are rejected, not silently turned into plain versions.

### Automatic returns

When you've finished building, put the spare materials back in the cache. Set how much you want to keep and a return address, and the surplus gets packed for a paper plane or robo-bee to carry back.

Each return consumes paper-plane parts or a robo-bee. Without a suitable carrier, or if dispatch cannot start, the materials stay in the cache without being deducted.

### Five levels of capacity

Your factory has a part in upgrades, too. Make Expansion Links through Create sequenced assembly, then upgrade the pendant at a smithing table to carry more kinds of supplies and more of each.

| Level | Cache cells | Capacity per cell |
| --- | --- | --- |
| 1 | 9 | 2 stacks |
| 2 | 16 | 4 stacks |
| 3 | 24 | 8 stacks |
| 4 | 30 | 16 stacks |
| 5 | 36 | 32 stacks |

Stacks follow the item's own stack limit. A level 1 cell holds 128 logs, 32 ender pearls, or 2 milk buckets.

### Crafting and ammunition

You don't need to rummage through the cache whenever a recipe is short on ingredients. Vanilla 2×2 inventory crafting, 3×3 workbench crafting, the recipe book, and JEI recipe transfer can all use cached materials when your inventory doesn't have enough. Vanilla bows and crossbows can keep using cached arrows after your inventory runs out.

Your inventory is used first, and the cache supplies the shortfall. Support includes the standard projectile consumption path shared by vanilla bows and crossbows, but it doesn't extend to every modded gun, machine, or backpack. To place blocks or use an item directly by hand, you'll still need to take it out first.

## Mod integrations

| Mod | Requirement | Integration |
| --- | --- | --- |
| **Create** | Required | Supplies the goods. The pendant binds to a logistics network, checks stock, and requests materials for your packagers to box up. Sequenced assembly also produces Expansion Links. |
| **Curios** | Required | Provides the equipment slot for the pendant. |
| **JEI** | Optional | View recipes and transfer ingredients, drawing missing materials from the cache. Dragging JEI item templates into cache filters isn't supported. |
| **Package Couriers** | Optional | Uses its existing paper-plane delivery to bring restocking packages to you, ready for this mod to receive into the cache. |
| **Create: Mobile Packages** | Optional | Uses its existing robo-bee delivery to bring packages over, with their contents received into the cache on arrival. |
| **TaCZ** | Optional | Once inventory ammunition runs out, reloading draws the matching ammo type from the cache, supplied by your factory. |

Your factory needs stock, and the packages need a way to reach you. Paper planes and robo-bees still need their own infrastructure, addresses, and delivery conditions. The pendant won't build those for you or teleport materials out of storage. Without these optional mods, you can still use the cache and core features, and receive dedicated restocking packages obtained through other means.

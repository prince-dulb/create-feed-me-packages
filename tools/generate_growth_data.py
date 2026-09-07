"""Reproduce this reference's original manufacturing recipes, localized names and 16px sprites."""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources"
MOD = "create_feed_me_packages"
RECIPES = ROOT / "data" / MOD / "recipe"
ASSETS = ROOT / "assets" / MOD


def own(name):
    return f"{MOD}:{name}"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def shaped(name, pattern, ingredients, mechanical=False):
    assert len({len(row) for row in pattern}) == 1
    assert set("".join(pattern)) - {" "} == set(ingredients)
    recipe = {"type": "create:mechanical_crafting" if mechanical else "minecraft:crafting_shaped", "category": "misc",
              "pattern": pattern, "key": {key: {"item": item} for key, item in ingredients.items()}, "result": {"id": own(name), "count": 1}}
    if mechanical:
        recipe.update(accept_mirrored=False, show_notification=False)
    write_json(RECIPES / ("mechanical_crafting" if mechanical else "crafting") / f"{name}.json", recipe)


def sequence(name, base, incomplete, steps, scrap):
    sequence = []
    for operation, addition in steps:
        step = {"type": f"create:{operation}", "ingredients": [{"item": own(incomplete)}], "results": [{"id": own(incomplete)}]}
        if operation == "deploying":
            step["ingredients"].append({"item": addition})
        if operation == "filling":
            step["ingredients"].append({"type": "neoforge:single", "amount": 250, "fluid": addition})
        if operation == "cutting":
            step["processing_time"] = 80
        sequence.append(step)
    write_json(RECIPES / "sequenced_assembly" / f"{name}.json", {
        "type": "create:sequenced_assembly", "ingredient": {"item": base}, "transitional_item": {"id": own(incomplete)},
        "loops": 1, "sequence": sequence, "results": [{"id": own(name), "chance": 4.0}, {"id": scrap, "chance": 1.0}]})


def smithing(name, base, addition, result, level):
    write_json(RECIPES / "smithing" / f"{name}.json", {
        "type": own("pendant_smithing"), "template": {"item": own("assembly_template")}, "base": {"item": own(base)},
        "addition": {"item": own(addition)}, "result": {"id": own(result), "count": 1}, "target_level": level})


def recipes():
    shaped("supply_chain_pendant", ["S S", " I ", " A "], {"S": "minecraft:string", "I": "create:iron_sheet", "A": "create:andesite_alloy"})
    shaped("assembly_template", [" P ", "IRI", " P "], {"P": "minecraft:paper", "I": "minecraft:iron_nugget", "R": "minecraft:redstone"})
    shaped("link_frame_2", [" I ", "IAI", " I "], {"I": "create:iron_sheet", "A": "create:andesite_alloy"})
    shaped("link_frame_3", [" B ", "BTB", " B "], {"B": "create:brass_sheet", "T": "create:electron_tube"})
    shaped("link_frame_4", [" B B", " PT ", " T P", "B B "], {"B": "create:brass_casing", "P": "create:precision_mechanism", "T": own("link_frame_3")}, True)
    pattern5 = ["  S  ", " SNP ", "SF FS", " PNS ", "  S  "]
    assert "".join(pattern5).count("S") == 6
    shaped("link_frame_5", pattern5, {"S": "create:sturdy_sheet", "N": "minecraft:netherite_scrap", "P": "create:precision_mechanism", "F": own("link_frame_4")}, True)
    sequence("upgrade_link_2", own("link_frame_2"), "incomplete_link_2", [("pressing", None), ("cutting", None), ("pressing", None)], "minecraft:iron_nugget")
    sequence("upgrade_link_3", own("link_frame_3"), "incomplete_link_3", [("deploying", "create:cogwheel"), ("deploying", "create:electron_tube"), ("pressing", None)], "create:brass_nugget")
    sequence("upgrade_link_4", own("link_frame_4"), "incomplete_link_4", [("deploying", "create:precision_mechanism"), ("cutting", None), ("deploying", "create:electron_tube"), ("pressing", None)], "create:brass_nugget")
    sequence("upgrade_link_5", own("link_frame_5"), "incomplete_link_5", [("filling", "minecraft:lava"), ("pressing", None), ("deploying", "create:precision_mechanism"), ("filling", "minecraft:water"), ("pressing", None)], "create:powdered_obsidian")
    sequence("private_link", "minecraft:ender_pearl", "incomplete_private_link", [("deploying", "create:brass_sheet"), ("deploying", "create:electron_tube"), ("pressing", None)], "minecraft:ender_pearl")
    for level in range(2, 6):
        for base in ["supply_chain_pendant", "personal_supply_chain_pendant"]:
            smithing(f"{base}_to_{level}", base, f"upgrade_link_{level}", base, level)
    smithing("personalize", "supply_chain_pendant", "private_link", "personal_supply_chain_pendant", 0)


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def sprite(name, color, kind, level=0):
    pixels = [[(0, 0, 0, 0) for _ in range(16)] for _ in range(16)]
    outline = (52, 49, 44, 255)
    def rect(x1, y1, x2, y2, rgba):
        for y in range(y1, y2 + 1):
            for x in range(x1, x2 + 1):
                pixels[y][x] = rgba
    if kind == "template":
        rect(3, 1, 12, 14, outline); rect(4, 2, 11, 13, (61, 94, 112, 255))
        for y in (4, 7, 10): rect(5, y, 10, y, (185, 211, 204, 255))
        rect(7, 3, 7, 11, (185, 211, 204, 255))
    else:
        rect(2, 3, 12, 12, outline); rect(3, 4, 11, 11, (*color, 255)); rect(5, 6, 9, 9, outline)
        rect(4, 4, 10, 4, tuple(min(255, c + 45) for c in color) + (255,))
        if kind == "frame": rect(6, 5, 8, 10, (135, 125, 101, 255))
        elif kind == "incomplete": rect(8, 3, 12, 5, (0, 0, 0, 0)); rect(2, 11, 4, 13, (90, 92, 85, 255))
        else: rect(7, 2, 9, 5, (223, 194, 116, 255)); rect(7, 9, 9, 13, (223, 194, 116, 255))
        digits = {1: [" 1 ", "11 ", " 1 ", " 1 ", "111"], 2: ["111", "  1", "111", "1  ", "111"], 3: ["111", "  1", "111", "  1", "111"],
                  4: ["1 1", "1 1", "111", "  1", "  1"], 5: ["111", "1  ", "111", "  1", "111"]}
        if level:
            rect(10, 9, 14, 15, outline)
            for y, row in enumerate(digits[level]):
                for x, cell in enumerate(row):
                    if cell == "1": pixels[10 + y][11 + x] = (240, 226, 176, 255)
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    encoded = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    texture = ASSETS / "textures/item" / f"{name}.png"; texture.parent.mkdir(parents=True, exist_ok=True); texture.write_bytes(encoded)
    write_json(ASSETS / "models/item" / f"{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}})


def assets_and_language():
    zh = {"assembly_template": "供应链装配图", "private_link": "私人化链节", "incomplete_private_link": "未完成的私人化链节"}
    en = {"assembly_template": "Supply Chain Assembly Plan", "private_link": "Personalization Link", "incomplete_private_link": "Incomplete Personalization Link"}
    sprite("assembly_template", (0, 0, 0), "template")
    sprite("private_link", (59, 157, 151), "link"); sprite("incomplete_private_link", (77, 125, 123), "incomplete")
    colors = {2: (171, 179, 161), 3: (198, 152, 64), 4: (218, 183, 93), 5: (111, 104, 130)}
    for level, color in colors.items():
        for prefix, kind, label_zh, label_en in [("upgrade_link", "link", "号扩容链节", "Upgrade Link"), ("link_frame", "frame", "号链节框架", "Link Frame"), ("incomplete_link", "incomplete", "号未完成链节", "Incomplete Link")]:
            number = level - 1
            name = f"{prefix}_{level}"; sprite(name, color, kind, number); zh[name] = f"{number}{label_zh}"; en[name] = f"{label_en} {number}"
    texts = {
        "tooltip.create_feed_me_packages.upgrade_link": ("将缓存从前一级扩容至%s级；不会跳级。", "Advances capacity to level %s from the preceding level; no skipped levels."),
        "tooltip.create_feed_me_packages.private_link": ("将普通链坠整份私人化；无法全量转入时不消耗输入。", "Personalizes the complete ordinary cache; rejected transfers consume no inputs."),
        "tooltip.create_feed_me_packages.smithing": ("锻造台：装配图＋链坠＋链节；装配图保留。", "Smithing table: assembly plan + pendant + link. The plan is reusable."),
        "tooltip.create_feed_me_packages.capacity_grant": ("一次性容量授权：%s→%s级；合格玩家有效佩戴后签收。", "One-use capacity grant: %s to %s. Claimed by an eligible active wearer."),
        "tooltip.create_feed_me_packages.preview": ("制造预览：不能佩戴使用，取出成品后才生效。", "Manufacturing preview: unusable until the result is taken."),
        "message.create_feed_me_packages.growth.success": ("供应链装配完成；装配图已保留。", "Supply chain assembly completed; the plan was retained."),
        "message.create_feed_me_packages.growth.claimed": ("个人缓存已扩容；一次性授权已签收。", "Personal capacity upgraded; the one-use grant was claimed."),
        "message.create_feed_me_packages.growth.stale": ("制造状态已变化，请检查刷新后的成品再取出。", "Manufacturing state changed. Check the refreshed result before taking it."),
        "message.create_feed_me_packages.growth.invalid_input": ("装配输入无效；只接受真实链坠、装配图和对应链节。", "Invalid assembly input. Use a real pendant, assembly plan and matching link."),
        "message.create_feed_me_packages.growth.wrong_level": ("链节等级不符：每次只能从当前等级提升一级。", "Wrong link level: capacity can only advance one level at a time."),
        "message.create_feed_me_packages.growth.pending_grant": ("这件私人定坠仍有未签收授权；请先由合格玩家佩戴签收。", "This pendant has an unclaimed grant. An eligible player must claim it first."),
        "message.create_feed_me_packages.growth.merge_full": ("个人缓存空间不足，供应链坠库存无法转移；请检查过滤、容量和残包。", "The complete cache cannot fit. Check filters, capacity and residual parcels."),
        "message.create_feed_me_packages.growth.configuration_conflict": ("同一精确物品的补货／退货阈值不同，请先统一配置再私人化。", "The same exact item has conflicting thresholds. Align them before personalization."),
        "message.create_feed_me_packages.growth.invalid_residual": ("残包的目标或封签已失效，请先取回处理；本次未消耗输入。", "A residual has an invalid route or seal. Retrieve it first; inputs were retained."),
        "message.create_feed_me_packages.growth.no_space": ("成品没有安全位置；请清空光标或背包中的一个格子。", "No safe result space. Clear the cursor or one inventory slot."),
        "message.create_feed_me_packages.growth.unsupported_click": ("请左／右键取成品、Shift取入背包，或移入空快捷栏格；不支持丢出或克隆预览。", "Take the result normally, shift-click it, or use an empty hotbar slot; previews cannot be dropped or cloned."),
        "message.create_feed_me_packages.growth.invalid_state": ("缓存入口或制造状态无效；输入物品已保留，请重新检查。", "Invalid cache identity or manufacturing state. Inputs were retained."),
        "message.create_feed_me_packages.growth.storage_locked": ("缓存账本已锁定以保留异常数据，不能制造。", "The cache ledger is locked to preserve invalid data; manufacturing is unavailable."),
        "message.create_feed_me_packages.growth.committed_sync_error": ("制造已完成，但界面同步失败；请重新打开界面，不要重复提交。", "Manufacturing completed, but UI synchronization failed. Reopen the screen; do not resubmit.")
    }
    for language, names, index in [("zh_cn", zh, 0), ("en_us", en, 1)]:
        path = ASSETS / "lang" / f"{language}.json"; values = json.loads(path.read_text(encoding="utf-8"))
        values.update({f"item.{MOD}.{name}": title for name, title in names.items()}); values.update({key: pair[index] for key, pair in texts.items()})
        write_json(path, values)


if __name__ == "__main__":
    recipes(); assets_and_language()
    print("Generated 20 manufacturing recipes and 15 original growth sprites/models; no pendant sprite or third-party asset was edited.")

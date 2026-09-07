"""Read-only release-artifact checks. No downloads, extraction or source rewriting."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile


def check(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def audit(artifact: Path, project: Path) -> dict:
    namespace = "create_feed_me_packages"
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        entries = set(names)
        check(len(names) == len(entries), "Duplicate archive entry")
        check(archive.testzip() is None, "Corrupt archive entry")
        check(archive.read("META-INF/LICENSE") == (project / "LICENSE").read_bytes(), "License differs from source")
        meta = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        mods = meta["mods"]
        check(len(mods) == 1 and mods[0]["modId"] == namespace, "Unexpected mod registration")
        version = mods[0]["version"]
        expected_version = next(line.split("=", 1)[1].strip() for line in (project / "gradle.properties").read_text(encoding="utf-8").splitlines() if line.startswith("mod_version="))
        check(version == expected_version and artifact.name == f"create-feed-me-packages-{version}.jar", "Wrong artifact version/name")
        dependencies = meta["dependencies"][namespace]
        check(len(dependencies) == 4 and {d["modId"] for d in dependencies} == {"minecraft", "neoforge", "create", "curios"}, "Unexpected dependency declaration")
        check(all(d["type"] == "required" and d["side"] == "BOTH" for d in dependencies), "Wrong required dependency policy")

        classes = [name for name in names if name.endswith(".class")]
        check(classes and all(name.startswith("dev/scathiard/feedmepackages/") for name in classes), "Bundled foreign classes")
        forbidden = (b"FMP_CLIENT_REVIEW_", b"FMP_NETWORK_FLIGHT_PASSED", b"GameTestHelper", b"fmp_reference_manufacturing_probe")
        for name in classes:
            check("/gametest/" not in name and not any(marker in archive.read(name) for marker in forbidden), f"Test code leaked into {name}")
        check(not any("/structure/" in name or name.startswith("data/create_feed_me_packages/structures/") for name in names), "Test structures leaked into main JAR")
        for name in names:
            if name.startswith("assets/") and not name.endswith("/"):
                check(name.startswith(f"assets/{namespace}/"), f"Bundled foreign asset: {name}")
            if name.endswith(".json"):
                json.loads(archive.read(name))

        mixins = json.loads(archive.read(f"{namespace}.mixins.json"))
        for type_name in [mixins["plugin"]] + [mixins["package"] + "." + name for name in mixins["mixins"] + mixins["client"]]:
            check(type_name.replace(".", "/") + ".class" in entries, f"Missing mixin/plugin class: {type_name}")

        language = {code: json.loads(archive.read(f"assets/{namespace}/lang/{code}.json")) for code in ("en_us", "zh_cn")}
        check(language["en_us"].keys() == language["zh_cn"].keys(), "Translation key mismatch")
        for name in names:
            if name.startswith(f"assets/{namespace}/models/item/") and name.endswith(".json"):
                item = Path(name).stem
                check(f"item.{namespace}.{item}" in language["en_us"], f"Missing item translation: {item}")
                for texture in json.loads(archive.read(name)).get("textures", {}).values():
                    if texture.startswith(namespace + ":"):
                        check(f"assets/{namespace}/textures/{texture.split(':', 1)[1]}.png" in entries, f"Missing texture: {texture}")
        recipes = [name for name in names if name.startswith(f"data/{namespace}/recipe/") and name.endswith(".json")]
        check(len(recipes) == 20, "Expected exactly 20 manufacturing recipes")
        check(not any("test_remainder" in name for name in names), "Test recipe leaked into main JAR")

    inputs = sorted([*project.joinpath("src/main").rglob("*")], key=lambda path: path.relative_to(project).as_posix())
    inputs += [project / name for name in ("build.gradle", "settings.gradle", "gradle.properties", "LICENSE")]
    digest = hashlib.sha256()
    for path in inputs:
        if path.is_file():
            digest.update(path.relative_to(project).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return {"artifact": str(artifact.resolve()), "version": version, "size_bytes": artifact.stat().st_size,
            "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "main_inputs_sha256": digest.hexdigest(),
            "classes": len(classes), "recipes": len(recipes), "translation_keys": len(language["en_us"]),
            "dependencies": [entry["modId"] for entry in dependencies], "static_checks": "passed",
            "boundary": "Static checks only; runtime results are recorded separately."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = json.dumps(audit(args.artifact, Path(__file__).resolve().parent.parent), indent=2, ensure_ascii=False) + "\n"
    if args.report:
        args.report.write_text(result, encoding="utf-8")
    print(result, end="")

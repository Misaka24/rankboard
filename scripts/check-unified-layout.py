"""Validate scanner safety and loader isolation without starting Minecraft."""
import argparse
import json
import struct
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar")
    args = parser.parse_args()
    with zipfile.ZipFile(args.jar) as jar:
        names = set(jar.namelist())
        if any(name.endswith(".jar") for name in names):
            raise SystemExit("Nested implementation JARs are forbidden")
        config = json.loads(jar.read("rankboard.mixins.json"))
        mixin_prefix = config["package"].replace(".", "/") + "/"
        classes = [name for name in names if name.endswith(".class")]
        annotated_mods = []
        for name in classes:
            data = jar.read(name)
            magic, minor, major = struct.unpack(">IHH", data[:8])
            if magic != 0xCAFEBABE or major > 65 or minor == 65535:
                raise SystemExit(f"Not safe for Java 21 scanners: {name}")
            if b"Lnet/neoforged/fml/common/Mod;" in data:
                annotated_mods.append(name)
            if name.startswith(mixin_prefix) and not name.endswith("Mixin.class"):
                raise SystemExit(f"Ordinary class is inside a reserved Mixin package: {name}")
        expected_mod = "cn/bamgdam/rankboard/unified/NeoForgeEntrypoint.class"
        if annotated_mods != [expected_mod]:
            raise SystemExit(f"Expected exactly one native NeoForge entrypoint: {annotated_mods}")
        for family in ("fabric121", "neo121", "mojang26"):
            core = f"cn/bamgdam/rankboard/impl/{family}/RankBoardMod.class"
            if core not in names:
                raise SystemExit(f"Missing compiled API adapter: {family}")
            if core.startswith(mixin_prefix):
                raise SystemExit(f"Business classes are reserved as Mixins: {core}")
            for mixin in ("PlayerEntityNameMixin", "ServerPlayerListNameMixin"):
                if f"{mixin_prefix}{family}/{mixin}.class" not in names:
                    raise SystemExit(f"Missing isolated Mixin: {family}/{mixin}")
        for simple in ("UnifiedRuntime", "FabricEntrypoint", "NeoForgeEntrypoint", "UnifiedMixinPlugin"):
            name = f"cn/bamgdam/rankboard/unified/{simple}.class"
            if b"net/minecraft/" in jar.read(name):
                raise SystemExit(f"Bootstrap eagerly links game-version-specific types: {name}")
        fabric = jar.read("cn/bamgdam/rankboard/impl/fabric121/RankBoardMod.class")
        if b"net/minecraft/class_" not in fabric:
            raise SystemExit("Fabric 1.21 implementation was not remapped to intermediary")
        metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata["entrypoints"]["main"] != ["cn.bamgdam.rankboard.unified.FabricEntrypoint"]:
            raise SystemExit("Fabric metadata does not select the isolated bootstrap")
        if "META-INF/neoforge.mods.toml" not in names:
            raise SystemExit("NeoForge metadata is missing")
        print(f"UNIFIED_LAYOUT_OK: {len(classes)} scanner-safe classes, 3 API families, 1 native Mod annotation, no nested JARs")


if __name__ == "__main__":
    main()

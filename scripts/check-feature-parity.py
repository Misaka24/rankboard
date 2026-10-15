"""Fail CI when the Yarn and Mojmap implementations expose different features."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [ROOT / f"src/{kind}/java/cn/bamgdam/rankboard" for kind in ("main", "mojmap")]


def inventory(root):
    config = (root / "RankBoardConfig.java").read_text(encoding="utf-8")
    mod = (root / "RankBoardMod.java").read_text(encoding="utf-8")
    language = (root / "RankBoardLanguage.java").read_text(encoding="utf-8")
    metrics = mod.split("public enum Metric {", 1)[1].split(";", 1)[0]
    return {
        "config defaults": dict(re.findall(r'option\("([^"]+)",\s*"([^"]*)"', config)),
        "command names": set(re.findall(r'(?:CommandManager|Commands)\.literal\("([^"]+)"', mod)),
        "metric order": re.findall(r'^\s*([A-Z_]+)\("', metrics, re.M),
        "language entries": dict(re.findall(r'Map\.entry\("([^"\n]+)",\s*"([^"\n]*)"\)', language)),
    }


def main():
    left, right = map(inventory, SOURCES)
    errors = []
    for category, expected in left.items():
        actual = right[category]
        if expected == actual:
            print(f"PASS: {category} ({len(expected)} entries)")
            continue
        if isinstance(expected, dict):
            mismatch = sorted(key for key in expected.keys() | actual.keys()
                              if expected.get(key) != actual.get(key))
        elif isinstance(expected, set):
            mismatch = sorted(expected ^ actual)
        else:
            mismatch = {"Yarn": expected, "Mojmap": actual}
        errors.append(f"{category}: {mismatch}")
    for root in SOURCES:
        mod = (root / "RankBoardMod.java").read_text(encoding="utf-8")
        tracker = root / "RuntimeMetricTracker.java"
        for hook in ("start", "stop", "join", "disconnect", "tick"):
            if f"RuntimeMetricTracker.{hook}(" not in mod:
                errors.append(f"{root.parent.parent.parent.name}: runtime tracker hook {hook} missing")
        if not tracker.is_file():
            errors.append(f"{root}: runtime metric tracker missing")
    neoforge_build = (ROOT / "neoforge/build.gradle").read_text(encoding="utf-8")
    for hook in ("start", "stop", "join", "disconnect", "tick"):
        if f"RuntimeMetricTracker.{hook}(" not in neoforge_build:
            errors.append(f"NeoForge generated lifecycle: runtime tracker hook {hook} missing")
    if "RankBoardLanguage.load(server);" not in neoforge_build:
        errors.append("NeoForge generated lifecycle: language initialization missing")
    if errors:
        raise SystemExit("Feature parity failed:\n" + "\n".join(errors))
    print("FEATURE_PARITY_OK")


if __name__ == "__main__":
    main()

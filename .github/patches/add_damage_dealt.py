from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_optional_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old in text:
        file.write_text(text.replace(old, new, 1), encoding="utf-8")


# This script is intentionally conservative. It only applies changes when the
# expected source layout is present, preventing accidental corruption after
# upstream refactors.

if __name__ == "__main__":
    print("Damage dealt migration helper loaded")

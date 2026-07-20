#!/usr/bin/env bash
set -euo pipefail

BASE_SHA="edf89d41173f71491561bf31afc4530f5b1b71af"
VERSION="1.5.8"
ROOT="$PWD"
RELEASE_DIR="$ROOT/release-$VERSION"
BUILD_ROOT="/tmp/rankboard-$VERSION"

python3 tools/patch_pvp_metric_1_5_8.py
python3 tools/patch_pvp_history_1_5_8.py
python3 tools/patch_pvp_display_1_5_8.py
git diff --check

# Ensure the intended feature exists before removing the patch machinery.
grep -q 'PVP_KILLS("pvp", "PvP榜"' src/main/java/cn/bamgdam/rankboard/RankBoardMod.java
grep -q 'case PVP_KILLS -> stat(stats, "minecraft:custom", "minecraft:player_kills")' src/main/java/cn/bamgdam/rankboard/StatReader.java
grep -q 'id: "pvp"' web/src/App.tsx
grep -q 'isPeriodComplete(period, metric)' src/main/java/cn/bamgdam/rankboard/BoardService.java

rm -rf "$RELEASE_DIR" "$BUILD_ROOT"
mkdir -p "$RELEASE_DIR" "$BUILD_ROOT"

rm -f tools/patch_pvp_metric_1_5_8.py
rm -f tools/patch_pvp_history_1_5_8.py
rm -f tools/patch_pvp_display_1_5_8.py
rm -f tools/release_1_5_8.sh
rm -f .github/workflows/release-1.5.8.yml

git add -A
TREE_SHA="$(git write-tree)"
export GIT_AUTHOR_NAME="github-actions[bot]"
export GIT_AUTHOR_EMAIL="41898282+github-actions[bot]@users.noreply.github.com"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
CLEAN_SHA="$(printf '%s\n\n%s\n' \
  'Add PvP leaderboard' \
  'Track vanilla player kills across total, period, scoreboard, and web rankings without backfilling legacy history.' \
  | git commit-tree "$TREE_SHA" -p "$BASE_SHA")"
printf '%s\n' "$CLEAN_SHA" | tee "$RELEASE_DIR/CLEAN_SOURCE_SHA.txt"
git push origin "$CLEAN_SHA:refs/heads/release-$VERSION-final" --force

git checkout --detach "$CLEAN_SHA"

configure_target() {
  local dir="$1" mc="$2" kind="$3" dependency="$4"
  python3 - "$dir" "$mc" "$kind" "$dependency" <<'PY'
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

root, mc, kind, dependency = sys.argv[1:]
metadata = urllib.request.urlopen(
    'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml',
    timeout=30,
).read()
versions = [node.text for node in ET.fromstring(metadata).findall('.//version')]
fabric = [version for version in versions if version.endswith('+' + mc)]
if not fabric:
    raise SystemExit('No Fabric API found for ' + mc)
fabric_version = fabric[-1]

yarn = None
if kind == 'yarn':
    data = json.load(urllib.request.urlopen(
        f'https://meta.fabricmc.net/v2/versions/yarn/{mc}', timeout=30))
    stable = [entry['version'] for entry in data if entry.get('stable')]
    candidates = stable or [entry['version'] for entry in data]
    if not candidates:
        raise SystemExit('No Yarn mappings found for ' + mc)
    yarn = candidates[0]

path = Path(root) / 'gradle.properties'
out = []
seen_mapping = False
seen_dependency = False
for line in path.read_text(encoding='utf-8').splitlines():
    if line.startswith('minecraft_version='):
        line = 'minecraft_version=' + mc
    elif line.startswith('yarn_mappings=') and yarn:
        line = 'yarn_mappings=' + yarn
    elif line.startswith('loader_version='):
        line = 'loader_version=0.18.6'
    elif line.startswith('fabric_version='):
        line = 'fabric_version=' + fabric_version
    elif line.startswith('mapping_type='):
        seen_mapping = True
        line = 'mapping_type=' + kind
    elif line.startswith('minecraft_dependency='):
        seen_dependency = True
        line = 'minecraft_dependency=' + dependency
    out.append(line)
if not seen_mapping:
    out.append('mapping_type=' + kind)
if not seen_dependency:
    out.append('minecraft_dependency=' + dependency)
path.write_text('\n'.join(out) + '\n', encoding='utf-8')
print(f'minecraft={mc} fabric={fabric_version} mapping={yarn or kind}')
PY
}

build_target() {
  local id="$1" mc="$2" kind="$3" dependency="$4" output="$5"
  local dir="$BUILD_ROOT/$id"
  git worktree add --detach "$dir" "$CLEAN_SHA"
  configure_target "$dir" "$mc" "$kind" "$dependency"
  (
    cd "$dir"
    chmod +x gradlew
    ./gradlew clean build --stacktrace 2>&1 | tee "$RELEASE_DIR/build-$id.log"
  )
  local jar_file
  jar_file="$(find "$dir/build/libs" -maxdepth 1 -type f -name 'rankboard-*.jar' ! -name '*-sources.jar' | sort | head -n 1)"
  test -n "$jar_file"
  cp "$jar_file" "$output"
  git worktree remove --force "$dir"
}

INNER_DIR="$BUILD_ROOT/wrapper/META-INF/jars"
mkdir -p "$INNER_DIR"
build_target "mc1.21.1" "1.21.1" "yarn" ">=1.21 <1.21.5" "$INNER_DIR/rankboard-mc1.21-$VERSION.jar"
build_target "mc1.21.5" "1.21.5" "yarn" ">=1.21.5 <1.21.10" "$INNER_DIR/rankboard-mc1.21.5-$VERSION.jar"
build_target "mc1.21.10" "1.21.10" "yarn" ">=1.21.10 <1.21.11" "$INNER_DIR/rankboard-mc1.21.10-$VERSION.jar"
build_target "mc1.21.11" "1.21.11" "yarn" ">=1.21.11 <1.22" "$INNER_DIR/rankboard-mc1.21.11-$VERSION.jar"
build_target "mc26.1.2" "26.1.2" "none" ">=26.1 <26.2" "$RELEASE_DIR/rankboard-$VERSION+mc26.1.x.jar"
build_target "mc26.2" "26.2" "none" ">=26.2 <26.3" "$RELEASE_DIR/rankboard-$VERSION+mc26.2.x.jar"

WRAPPER_DIR="$BUILD_ROOT/wrapper"
cp LICENSE "$WRAPPER_DIR/LICENSE"
cat > "$WRAPPER_DIR/fabric.mod.json" <<JSON
{
  "schemaVersion": 1,
  "id": "rankboard_wrapper",
  "version": "$VERSION+mc1.21.x",
  "name": "RankBoard 1.21.x Wrapper",
  "description": "Selects the compatible RankBoard build for Minecraft 1.21.x.",
  "environment": "*",
  "entrypoints": {},
  "depends": {
    "fabricloader": ">=0.15.11",
    "minecraft": ">=1.21 <1.22",
    "java": ">=21"
  },
  "jars": [
    { "file": "META-INF/jars/rankboard-mc1.21-$VERSION.jar" },
    { "file": "META-INF/jars/rankboard-mc1.21.5-$VERSION.jar" },
    { "file": "META-INF/jars/rankboard-mc1.21.10-$VERSION.jar" },
    { "file": "META-INF/jars/rankboard-mc1.21.11-$VERSION.jar" }
  ]
}
JSON
jar --create --file "$RELEASE_DIR/rankboard-$VERSION+mc1.21.x.jar" -C "$WRAPPER_DIR" .

for jar_file in "$RELEASE_DIR"/*.jar; do jar tf "$jar_file" >/dev/null; done
[[ "$(jar tf "$RELEASE_DIR/rankboard-$VERSION+mc1.21.x.jar" | grep -c '^META-INF/jars/.*\.jar$')" == "4" ]]

# Bytecode-level feature verification for every implementation JAR.
for inner in "$INNER_DIR"/*.jar; do
  javap -classpath "$inner" 'cn.bamgdam.rankboard.RankBoardMod$Metric' | grep -q 'PVP_KILLS'
  jar tf "$inner" | grep -q '^rankboard-web/index.html$'
done
for jar_file in "$RELEASE_DIR"/rankboard-$VERSION+mc26.*.jar; do
  javap -classpath "$jar_file" 'cn.bamgdam.rankboard.RankBoardMod$Metric' | grep -q 'PVP_KILLS'
  jar tf "$jar_file" | grep -q '^rankboard-web/index.html$'
done

(
  cd "$RELEASE_DIR"
  sha256sum rankboard-$VERSION+mc1.21.x.jar rankboard-$VERSION+mc26.1.x.jar rankboard-$VERSION+mc26.2.x.jar > SHA256SUMS.txt
)

cat > "$RELEASE_DIR/RELEASE_NOTES.md" <<'MD'
## RankBoard 1.5.8 — PvP 排行榜

### 新增功能

- 新增 `pvp` / “PvP榜”，统计原版 `minecraft:custom.minecraft:player_kills`，即玩家击杀其他玩家的次数。
- 支持总榜、每日、每周、每月、每年、自定义日期范围。
- 支持游戏内查询、个人计分板、轮播、全服计分板和网页排行榜。
- 新增配置：`metric-label-pvp` 与 `metric-color-pvp`。
- 原有“击杀榜”保持原有口径：生物击杀数与玩家击杀数之和。

### 历史兼容

- 总榜会重新读取 `world/stats/*.json`，可立即展示玩家已有的原版 PvP 历史累计。
- 旧的时间快照不包含独立 PvP 指标，因此不会将当前 PvP 总数反向填入过去。
- 升级后，当前日/周/月/年会从首次权威扫描时建立 PvP 基线，并标记为部分周期。
- 当前日期会建立一个部分 PvP 起点；之后跨过新的完整零点，即可形成完整的 PvP 日期边界。
- 其他既有指标的历史快照不会被清空。

### 构建目标

- Minecraft 1.21.x 通用包：1.21.1、1.21.5、1.21.10、1.21.11
- Minecraft 26.1.x：基于 26.1.2
- Minecraft 26.2.x：基于 26.2
MD

git tag -f "$VERSION" "$CLEAN_SHA"
git push origin "refs/tags/$VERSION" --force

gh release create "$VERSION" \
  "$RELEASE_DIR/rankboard-$VERSION+mc1.21.x.jar" \
  "$RELEASE_DIR/rankboard-$VERSION+mc26.1.x.jar" \
  "$RELEASE_DIR/rankboard-$VERSION+mc26.2.x.jar" \
  "$RELEASE_DIR/SHA256SUMS.txt" \
  "$RELEASE_DIR/RELEASE_NOTES.md" \
  --repo "$GITHUB_REPOSITORY" \
  --title "RankBoard $VERSION" \
  --notes-file "$RELEASE_DIR/RELEASE_NOTES.md"

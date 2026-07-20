#!/usr/bin/env bash
set -euo pipefail

BASE_SHA="48c64e4a6bd2f17d08e46d3d3f203dcc6b78ca0a"
VERSION="1.5.7"
ROOT="$PWD"
RELEASE_DIR="$ROOT/release-$VERSION"
BUILD_ROOT="/tmp/rankboard-$VERSION"

python3 tools/patch_partial_ranges_1_5_7.py
python3 tools/patch_period_display_1_5_7.py
python3 tools/patch_version_docs_1_5_7.py
git diff --check

rm -rf "$RELEASE_DIR" "$BUILD_ROOT"
mkdir -p "$RELEASE_DIR" "$BUILD_ROOT"

rm -f tools/patch_partial_ranges_1_5_7.py
rm -f tools/patch_period_display_1_5_7.py
rm -f tools/patch_version_docs_1_5_7.py
rm -f tools/release_1_5_7.sh
rm -f .github/workflows/release-1.5.7.yml

git add -A
TREE_SHA="$(git write-tree)"
export GIT_AUTHOR_NAME="github-actions[bot]"
export GIT_AUTHOR_EMAIL="41898282+github-actions[bot]@users.noreply.github.com"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
CLEAN_SHA="$(printf '%s\n\n%s\n' \
  'Allow partial leaderboard periods' \
  'Use the earliest trustworthy snapshot for active periods, report actual boundaries, and label incomplete scoreboards without hiding their data.' \
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
for inner in "$INNER_DIR"/*.jar; do
  unzip -p "$inner" cn/bamgdam/rankboard/LeaderboardState.class | strings | grep -q '请求周期缺少完整零点起点'
  unzip -p "$inner" cn/bamgdam/rankboard/RankBoardMod.class | strings | grep -q '统计为部分周期'
  unzip -p "$inner" cn/bamgdam/rankboard/BoardService.class | strings | grep -q '（部分）'
done
for jar_file in "$RELEASE_DIR"/rankboard-$VERSION+mc26.*.jar; do
  unzip -p "$jar_file" cn/bamgdam/rankboard/LeaderboardState.class | strings | grep -q '请求周期缺少完整零点起点'
  unzip -p "$jar_file" cn/bamgdam/rankboard/RankBoardMod.class | strings | grep -q '统计为部分周期'
  unzip -p "$jar_file" cn/bamgdam/rankboard/BoardService.class | strings | grep -q '（部分）'
done

(
  cd "$RELEASE_DIR"
  sha256sum rankboard-$VERSION+mc1.21.x.jar rankboard-$VERSION+mc26.1.x.jar rankboard-$VERSION+mc26.2.x.jar > SHA256SUMS.txt
)

cat > "$RELEASE_DIR/RELEASE_NOTES.md" <<'MD'
## RankBoard 1.5.7 — 周期快照可用性修复

### 修复内容

- 最近一日、最近一周、最近一月不再因为升级后尚未产生完整零点快照而全部报错。
- 日期范围会优先使用准确零点快照；缺少完整起点时，改用所选范围内最早的可信快照，并返回实际开始日期。
- 部分周期会通过 API 的 `complete=false` 与 `warnings` 明确标记，不会伪装成完整周期。
- 游戏内每日、每周、每月、每年榜允许展示本周期内已有数据，标题标记“（部分）”。
- 全服、个人和总览计分板均可恢复部分周期，不再因周期不完整而被跳过或抛出异常。
- 自定义历史范围的结束日期仍要求真实的次日边界，避免重新引入跨日期的大幅统计偏差。
- “最早可查”现在显示最早实际快照；若该快照为中途建立，会标记“（部分）”。

### 数据含义

当服务器在周期中途启动或从 1.5.6 升级时，周榜/月榜可能只覆盖“首次可信快照至当前”的数据。网页会显示实际开始日期和警告，游戏计分板标题会标记“（部分）”。服务器跨过真正的周期起点后，会自动恢复为完整周期。

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
  --repo "$GITHUB_REPOSITORY" \
  --title "RankBoard $VERSION" \
  --notes-file "$RELEASE_DIR/RELEASE_NOTES.md"

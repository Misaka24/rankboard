#!/usr/bin/env bash
set -euo pipefail

BASE_SHA="dcf0a77d743f94f22f8fcecff26d7e6c23054655"
VERSION="1.5.6"
ROOT="$PWD"
RELEASE_DIR="$ROOT/release-1.5.6"
BUILD_ROOT="/tmp/rankboard-1.5.6-builds"

rm -rf "$RELEASE_DIR" "$BUILD_ROOT"
mkdir -p "$RELEASE_DIR" "$BUILD_ROOT"

# Correct the final documentation wording, then construct one clean source commit
# whose parent is the previous clean 1.5.5 source commit. Temporary release files
# and workflows are deliberately excluded from that tree.
python3 <<'PY'
from pathlib import Path
p = Path('README.md')
text = p.read_text(encoding='utf-8')
text = text.replace('日期快照升级为 schema 2', '日期与周期快照升级为 schema 3')
needle = '- 日期范围必须存在准确的开始日期和结束次日零点快照；错过零点或启动后补建的快照会拒绝查询。\n'
extra = '- 游戏内日榜、周榜、月榜和年榜同样要求完整周期起点；中途启动建立的基线不会冒充完整周期。\n'
if extra not in text:
    if needle not in text:
        raise SystemExit('README period note anchor not found')
    text = text.replace(needle, needle + extra, 1)
p.write_text(text, encoding='utf-8')
PY

rm -f tools/release_1_5_6.sh
rm -f .github/workflows/release-data-fix-1.5.6.yml
git add -A
TREE_SHA="$(git write-tree)"
export GIT_AUTHOR_NAME="github-actions[bot]"
export GIT_AUTHOR_EMAIL="41898282+github-actions[bot]@users.noreply.github.com"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
CLEAN_SHA="$(printf '%s\n\n%s\n' \
  'Fix leaderboard data correctness' \
  'Use authoritative vanilla-stat scans, strict immutable boundaries, complete period baselines, and safe history migration.' \
  | git commit-tree "$TREE_SHA" -p "$BASE_SHA")"
printf '%s\n' "$CLEAN_SHA" | tee "$RELEASE_DIR/CLEAN_SOURCE_SHA.txt"
git push origin "$CLEAN_SHA:refs/heads/release-1.5.6-clean" --force

git checkout --detach "$CLEAN_SHA"

configure_target() {
  local dir="$1" mc="$2" kind="$3" dependency="$4"
  python3 - "$dir" "$mc" "$kind" "$dependency" <<'PY'
import json
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

dir_path, mc, kind, dependency = sys.argv[1:]
metadata = urllib.request.urlopen(
    'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml',
    timeout=30,
).read()
versions = [node.text for node in ET.fromstring(metadata).findall('.//version')]
fabric_matches = [version for version in versions if version.endswith('+' + mc)]
if not fabric_matches:
    raise SystemExit('No Fabric API version found for ' + mc)
fabric_version = fabric_matches[-1]

yarn_version = None
if kind == 'yarn':
    data = json.load(urllib.request.urlopen(
        f'https://meta.fabricmc.net/v2/versions/yarn/{mc}', timeout=30))
    stable = [entry['version'] for entry in data if entry.get('stable')]
    candidates = stable or [entry['version'] for entry in data]
    if not candidates:
        raise SystemExit('No Yarn mapping found for ' + mc)
    yarn_version = candidates[0]

path = Path(dir_path) / 'gradle.properties'
out = []
seen_mapping = False
seen_dependency = False
for line in path.read_text(encoding='utf-8').splitlines():
    if line.startswith('minecraft_version='):
        line = 'minecraft_version=' + mc
    elif line.startswith('yarn_mappings=') and yarn_version:
        line = 'yarn_mappings=' + yarn_version
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
print(f'minecraft={mc} fabric={fabric_version} mappings={yarn_version or kind} dependency={dependency}')
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

for jar_file in "$RELEASE_DIR"/*.jar; do
  jar tf "$jar_file" >/dev/null
  if [[ "$jar_file" != *mc1.21.x.jar ]]; then
    unzip -p "$jar_file" cn/bamgdam/rankboard/LeaderboardState.class | strings | grep -q 'partialSnapshotDates'
  fi
done
[[ "$(jar tf "$RELEASE_DIR/rankboard-$VERSION+mc1.21.x.jar" | grep -c '^META-INF/jars/.*\.jar$')" == "4" ]]
for inner in "$INNER_DIR"/*.jar; do
  unzip -p "$inner" cn/bamgdam/rankboard/LeaderboardState.class | strings | grep -q 'partialSnapshotDates'
  unzip -p "$inner" fabric.mod.json | grep -q '"version": "1.5.6'
done

(
  cd "$RELEASE_DIR"
  sha256sum rankboard-$VERSION+mc1.21.x.jar rankboard-$VERSION+mc26.1.x.jar rankboard-$VERSION+mc26.2.x.jar > SHA256SUMS.txt
)

cat > "$RELEASE_DIR/RELEASE_NOTES.md" <<'MD'
## RankBoard 1.5.6 — 数据正确性修复

### 修复

- 总榜只有在全部原版 `world/stats/*.json` 完成权威扫描后才可查询，避免旧缓存、在线数据和部分离线数据混合。
- 挖掘榜在线与离线读取统一使用当前方块注册表，排除已卸载模组遗留的无效统计 ID。
- 玩家退出时先捕获内存中的最新原版统计，再异步核对磁盘文件，降低退出瞬间的落后值。
- 删除会把“当前累计值”反向写入过去快照的历史补全逻辑。
- 日期查询改为严格边界：必须存在开始日期零点快照和结束日期次日零点快照，不再用之后的日期静默替代。
- 新玩家缺少开始基线、或累计计数因回档/重置发生下降时，会从该范围结果排除并给出警告，不再制造巨额增量或静默归零。
- 日榜、周榜、月榜、年榜增加完整周期标记；服务器在周期中途启动建立的基线不会冒充完整周期。
- 修复跨年 ISO 周所属年份计算。
- 统计扫描完成后再恢复全服与在线玩家保存的计分板。

### 历史数据迁移

旧版本的日期与周期快照可能已被反向补值污染，因此 1.5.6 会升级到 history schema 3 并自动废弃旧基线。升级当天通常不是完整零点边界；服务器持续运行并跨过下一个零点后，才会开始产生可精确查询的新日期历史。

### 统计口径

挖掘榜和放置榜继续采用 Minecraft 原版累计统计口径：挖掘为原版 `minecraft:mined`，放置为当前注册 `BlockItem` 的 `minecraft:used`。1.5.6 修复的是读取范围、缓存一致性和历史差值错误；原版统计没有记录的创造模式、命令、爆炸或模组直接改方块行为无法追溯补算。

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
  --target "$CLEAN_SHA" \
  --title "RankBoard $VERSION" \
  --notes-file "$RELEASE_DIR/RELEASE_NOTES.md"

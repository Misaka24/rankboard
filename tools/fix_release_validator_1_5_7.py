from pathlib import Path

path = Path("tools/release_1_5_7.sh")
text = path.read_text(encoding="utf-8")
old = '''for jar_file in "$RELEASE_DIR"/*.jar; do jar tf "$jar_file" >/dev/null; done
[[ "$(jar tf "$RELEASE_DIR/rankboard-$VERSION+mc1.21.x.jar" | grep -c '^META-INF/jars/.*\\.jar$')" == "4" ]]
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
'''
new = '''for jar_file in "$RELEASE_DIR"/*.jar; do jar tf "$jar_file" >/dev/null; done
[[ "$(jar tf "$RELEASE_DIR/rankboard-$VERSION+mc1.21.x.jar" | grep -c '^META-INF/jars/.*\\.jar$')" == "4" ]]
validate_implementation() {
  local jar_file="$1"
  jar tf "$jar_file" | grep -q '^cn/bamgdam/rankboard/LeaderboardState.class$'
  jar tf "$jar_file" | grep -q '^cn/bamgdam/rankboard/RankBoardMod.class$'
  jar tf "$jar_file" | grep -q '^cn/bamgdam/rankboard/BoardService.class$'
  javap -classpath "$jar_file" -p cn.bamgdam.rankboard.LeaderboardState \\
    | grep -Fq 'cn.bamgdam.rankboard.RankBoardMod$Metric, boolean);'
  unzip -p "$jar_file" fabric.mod.json | grep -Fq '"version": "1.5.7'
}
for inner in "$INNER_DIR"/*.jar; do validate_implementation "$inner"; done
for jar_file in "$RELEASE_DIR"/rankboard-$VERSION+mc26.*.jar; do validate_implementation "$jar_file"; done
'''
if text.count(old) != 1:
    raise SystemExit(f"Unexpected validator block count: {text.count(old)}")
path.write_text(text.replace(old, new), encoding="utf-8")
Path(__file__).unlink()

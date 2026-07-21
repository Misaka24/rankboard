from pathlib import Path

FILES = [
    Path("src/main/java/cn/bamgdam/rankboard/RankBoardMod.java"),
    Path("src/mojmap/java/cn/bamgdam/rankboard/RankBoardMod.java"),
]

OLD_MENU_BLOCK = """        int visible = 0;
        visible += sendMetricMenuRow(source, Metric.ELYTRA_DISTANCE, Metric.JUMPS, Metric.MINED, Metric.PLACED);
        visible += sendMetricMenuRow(source, Metric.FISHING, Metric.CRAFTED, Metric.TRADES, Metric.PLAY_TIME);
        visible += sendMetricMenuRow(source, Metric.KILLS, Metric.DEATHS, Metric.DAMAGE_TAKEN, Metric.DAMAGE_DEALT);
        visible += sendMetricMenuRow(source, Metric.PICKED_UP, Metric.FOOD, Metric.DROPPED, Metric.REDSTONE_PLACED);
"""

NEW_MENU_BLOCK = """        List<Metric> menuMetrics = orderedMenuMetrics();
        int visible = 0;
        for (int start = 0; start < menuMetrics.size(); start += 4) {
            visible += sendMetricMenuRow(source, menuMetrics.subList(
                    start, Math.min(start + 4, menuMetrics.size())).toArray(Metric[]::new));
        }
"""

HELPER = """    private static List<Metric> orderedMenuMetrics() {
        List<Metric> ordered = new java.util.ArrayList<>(List.of(
                Metric.ELYTRA_DISTANCE, Metric.JUMPS, Metric.MINED, Metric.PLACED,
                Metric.FISHING, Metric.CRAFTED, Metric.TRADES, Metric.PLAY_TIME,
                Metric.KILLS, Metric.PVP_KILLS, Metric.DEATHS, Metric.DAMAGE_TAKEN,
                Metric.DAMAGE_DEALT, Metric.PICKED_UP, Metric.FOOD, Metric.DROPPED,
                Metric.REDSTONE_PLACED));
        for (Metric metric : Metric.values()) {
            if (!ordered.contains(metric)) ordered.add(metric);
        }
        return List.copyOf(ordered);
    }

"""

MARKERS = {
    FILES[0]: "    private int sendMetricMenuRow(ServerCommandSource source, Metric... metrics) {",
    FILES[1]: "    private int sendMetricMenuRow(CommandSourceStack source, Metric... metrics) {",
}

for path in FILES:
    text = path.read_text(encoding="utf-8")
    if text.count(OLD_MENU_BLOCK) != 1:
        raise SystemExit(f"Expected exactly one old menu block in {path}")
    if "orderedMenuMetrics()" in text:
        raise SystemExit(f"Menu helper already exists in {path}")
    marker = MARKERS[path]
    if text.count(marker) != 1:
        raise SystemExit(f"Expected exactly one insertion marker in {path}")
    text = text.replace(OLD_MENU_BLOCK, NEW_MENU_BLOCK, 1)
    text = text.replace(marker, HELPER + marker, 1)
    path.write_text(text, encoding="utf-8")

# Correct stale help text while touching the menu implementation.
yarn = FILES[0]
yarn_text = yarn.read_text(encoding="utf-8").replace(
    "列出 11 个榜单的当前颜色", "列出全部榜单的当前颜色")
yarn.write_text(yarn_text, encoding="utf-8")

mojmap = FILES[1]
mojmap_text = mojmap.read_text(encoding="utf-8")
old_help = "placed 放置，kills 击杀，deaths 死亡，"
new_help = "placed 放置，kills 击杀，pvp 玩家击杀，deaths 死亡，"
if old_help not in mojmap_text:
    raise SystemExit("Expected Mojmap help metric list was not found")
mojmap.write_text(mojmap_text.replace(old_help, new_help, 1), encoding="utf-8")

properties = Path("gradle.properties")
properties_text = properties.read_text(encoding="utf-8")
if properties_text.count("mod_version=1.5.8") != 1:
    raise SystemExit("Expected mod_version=1.5.8 exactly once")
properties.write_text(properties_text.replace("mod_version=1.5.8", "mod_version=1.5.9", 1), encoding="utf-8")

for path in FILES:
    result = path.read_text(encoding="utf-8")
    if "Metric.KILLS, Metric.PVP_KILLS, Metric.DEATHS" not in result:
        raise SystemExit(f"PvP metric missing from menu order in {path}")
    if result.count("private static List<Metric> orderedMenuMetrics()") != 1:
        raise SystemExit(f"Unexpected menu helper count in {path}")

print("Patched PvP menu visibility for Yarn and Mojmap sources")

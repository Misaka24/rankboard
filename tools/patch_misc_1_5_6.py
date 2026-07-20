from pathlib import Path


def load(path): return Path(path).read_text(encoding='utf-8')
def save(path, text): Path(path).write_text(text, encoding='utf-8')

def one(text, old, new, label):
    count = text.count(old); print(label, count)
    if count != 1: raise RuntimeError(f'{label}: {count}')
    return text.replace(old, new, 1)


def patch_mod(path, mojmap):
    text = load(path)
    text = one(text, '            BoardService.restoreGlobal(server);\n', '', path+' restore')
    if mojmap:
        old = '        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {\n            StatReader.reloadPlayer(server, handler.getPlayer().getUUID());\n'
        new = '        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {\n            StatReader.capturePlayer(server, handler.getPlayer());\n            StatReader.reloadPlayer(server, handler.getPlayer().getUUID());\n'
    else:
        old = '        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {\n            StatReader.reloadPlayer(server, handler.getPlayer().getUuid());\n'
        new = '        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {\n            StatReader.capturePlayer(server, handler.getPlayer());\n            StatReader.reloadPlayer(server, handler.getPlayer().getUuid());\n'
    text = one(text, old, new, path+' disconnect')
    text = one(text,
        '    static List<Entry> entries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {\n        LeaderboardState state = LeaderboardState.get(server);\n',
        '    static List<Entry> entries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {\n'
        '        if (!StatReader.isReady()) throw new IllegalStateException("统计文件尚未完成权威扫描（" + StatReader.progress() + "）");\n'
        '        LeaderboardState state = LeaderboardState.get(server);\n', path+' readiness')
    text = one(text, 'date.getYear() + "-W" + date.get(WeekFields.ISO.weekOfWeekBasedYear())',
               'date.get(WeekFields.ISO.weekBasedYear()) + "-W" + date.get(WeekFields.ISO.weekOfWeekBasedYear())', path+' week')
    save(path, text)


def patch_web(path):
    text = load(path)
    text = one(text,
        '        if (!period.equals("all") && !StatReader.isReady()) {\n            throw new IllegalStateException("历史统计缓存仍在加载（" + StatReader.progress() + "），日期范围榜将在加载完成后可用。");\n        }',
        '        if (!StatReader.isReady()) {\n            throw new IllegalStateException("统计文件仍在进行权威扫描（" + StatReader.progress() + "），总榜和日期范围榜暂不可用。");\n        }', path+' readiness')
    text = one(text, '        String actualStart;\n        String actualEnd;\n',
        '        String actualStart;\n        String actualEnd;\n        boolean complete = true;\n        List<String> warnings = List.of();\n', path+' vars')
    text = one(text, '            LeaderboardState.RangeData range = state.range(server, from, to, metric);\n',
        '            LeaderboardState.RangeData range = state.range(server, from, to, metric);\n            complete = range.complete();\n            warnings = range.warnings();\n', path+' range')
    text = one(text, '        root.addProperty("earliest", state.earliestSnapshotDate());\n',
        '        root.addProperty("earliest", state.earliestSnapshotDate());\n        root.addProperty("complete", complete);\n        JsonArray warningArray = new JsonArray();\n        warnings.forEach(warningArray::add);\n        root.add("warnings", warningArray);\n', path+' json')
    save(path, text)

patch_mod('src/main/java/cn/bamgdam/rankboard/RankBoardMod.java', False)
patch_mod('src/mojmap/java/cn/bamgdam/rankboard/RankBoardMod.java', True)
patch_web('src/main/java/cn/bamgdam/rankboard/WebDashboard.java')
patch_web('src/mojmap/java/cn/bamgdam/rankboard/WebDashboard.java')

app = load('web/src/App.tsx')
app = one(app, '  earliest?: string;\n};', '  earliest?: string;\n  complete?: boolean;\n  warnings?: string[];\n};', 'frontend type')
app = one(app,
    '              <p className="eyebrow">{period === "custom" && ranking ? `${ranking.from} 至 ${ranking.to}` : periods.find((item) => item.id === period)?.label}</p>',
    '              <p className="eyebrow">{period === "custom" && ranking ? `${ranking.actualStart ?? ranking.from} 至 ${ranking.actualEnd ?? ranking.to}` : periods.find((item) => item.id === period)?.label}</p>', 'frontend range')
app = one(app, '          {error && <div className="notice error glass">{error}</div>}\n',
    '          {error && <div className="notice error glass">{error}</div>}\n          {!error && ranking?.complete === false && (\n            <div className="notice error glass">该范围不是完整统计：{ranking.warnings?.join("；") || "部分玩家数据已排除"}</div>\n          )}\n', 'frontend warning')
save('web/src/App.tsx', app)

gradle = one(load('gradle.properties'), 'mod_version=1.5.5', 'mod_version=1.5.6', 'version')
save('gradle.properties', gradle)

readme = load('README.md')
readme = one(readme, '当前版本：`1.5.5`', '当前版本：`1.5.6`', 'readme version')
note = '''### 1.5.6 数据正确性说明

- 总榜在所有 `world/stats/*.json` 完成权威扫描前不会返回结果，避免旧缓存与部分玩家数据混合。
- 挖掘统计的在线与离线读取统一为当前注册方块集合，排除已删除模组留下的失效统计 ID。
- 玩家退出时先保存内存中的最新统计，再异步核对磁盘文件。
- 日期快照升级为 schema 2；旧版可能被反向补值污染的周期与日期基线会自动废弃。
- 日期范围必须存在准确的开始日期和结束次日零点快照；错过零点或启动后补建的快照会拒绝查询。
- 缺少开始基线或累计统计发生回退的玩家会被排除并返回警告，不再按 0 基线制造巨额数据。
- 升级当天通常不完整；持续运行并跨过下一个零点后，才开始产生可精确查询的新历史。

'''
readme = one(readme, '### 安装\n', note+'### 安装\n', 'readme note')
save('README.md', readme)

for path in ['src/main/java/cn/bamgdam/rankboard/LeaderboardState.java', 'src/mojmap/java/cn/bamgdam/rankboard/LeaderboardState.java']:
    text = load(path)
    if 'backfillMissingMetrics' in text or 'ceilingEntry(' in text:
        raise RuntimeError(path+' still contains unsafe history logic')

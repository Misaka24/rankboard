from pathlib import Path
import re


def load(path): return Path(path).read_text(encoding='utf-8')
def save(path, text): Path(path).write_text(text, encoding='utf-8')

def one(text, old, new, label):
    count = text.count(old)
    print(label, count)
    if count != 1: raise RuntimeError(f'{label}: {count}')
    return text.replace(old, new, 1)


def patch(path, mojmap):
    text = load(path)
    if mojmap:
        text = one(text, 'import net.minecraft.world.item.BlockItem;\n',
                   'import net.minecraft.world.item.BlockItem;\nimport net.minecraft.world.level.block.Block;\n', path+' import')
        player = 'ServerPlayer'; uuid = 'getUUID()'
        stats_dir = 'server.getWorldPath(LevelResource.PLAYER_STATS_DIR)'
        block_loop = ('        for (Block block : BuiltInRegistries.BLOCK) {\n'
                      '            BLOCK_IDS.add(BuiltInRegistries.BLOCK.getKey(block).toString());\n'
                      '        }\n')
    else:
        text = one(text, 'import net.minecraft.item.BlockItem;\n',
                   'import net.minecraft.block.Block;\nimport net.minecraft.item.BlockItem;\n', path+' import')
        player = 'ServerPlayerEntity'; uuid = 'getUuid()'
        stats_dir = 'server.getSavePath(WorldSavePath.STATS)'
        block_loop = ('        for (Block block : Registries.BLOCK) {\n'
                      '            BLOCK_IDS.add(Registries.BLOCK.getId(block).toString());\n'
                      '        }\n')

    text = one(text,
        '    private static final Set<String> BLOCK_ITEMS = ConcurrentHashMap.newKeySet();\n',
        '    private static final Set<String> BLOCK_ITEMS = ConcurrentHashMap.newKeySet();\n'
        '    private static final Set<String> BLOCK_IDS = ConcurrentHashMap.newKeySet();\n', path+' ids')
    text = one(text, 'PERSISTENT_CACHE_SCHEMA = 4', 'PERSISTENT_CACHE_SCHEMA = 5', path+' schema')

    text = one(text, '''        persistentCacheLoaded = loadPersistentCache(server);
        ready = persistentCacheLoaded;
        if (persistentCacheLoaded) {
            server.execute(() -> {
                LeaderboardState.get(server).rollPeriods(server);
                BoardService.refreshAll(server);
            });
        }
        int filesPerSecond = RankBoardConfig.get().historyFilesPerSecond;''',
        '''        persistentCacheLoaded = loadPersistentCache(server);
        // Persisted values are preview-only until every source file is verified.
        int filesPerSecond = RankBoardConfig.get().historyFilesPerSecond;''', path+' startup')

    capture = f'''    static void capturePlayer(MinecraftServer server, {player} player) {{
        StatSnapshot snapshot = fromPlayer(player);
        UUID uuid = player.{uuid};
        CACHE.put(uuid, snapshot);
        Path path = {stats_dir}.resolve(uuid + ".json");
        SOURCE_MODIFIED.put(uuid, modifiedTime(path));
        savePersistentCache(server);
        WebDashboard.invalidateRankings();
    }}

'''
    text = one(text, '    static void reloadPlayer(MinecraftServer server, UUID uuid) {\n',
               capture+'    static void reloadPlayer(MinecraftServer server, UUID uuid) {\n', path+' capture')

    pattern = r'(    static void reloadPlayer\(MinecraftServer server, UUID uuid\) \{.*?)(\n    \}\n\n    static void updateName)'
    match = re.search(pattern, text, re.S)
    if not match: raise RuntimeError(path+' reload method')
    block = one(match.group(1), '                savePersistentCache(server);\n',
                '                savePersistentCache(server);\n                WebDashboard.invalidateRankings();\n', path+' invalidate')
    text = text[:match.start()] + block + match.group(2) + text[match.end():]

    text = one(text, '''            server.execute(() -> {
                LeaderboardState.get(server).rollPeriods(server);
                BoardService.refreshAll(server);
            });''',
        '''            server.execute(() -> {
                LeaderboardState.get(server).rollPeriods(server);
                BoardService.restoreGlobal(server);
                BoardService.refreshAll(server);
                WebDashboard.invalidateRankings();
            });''', path+' completion')

    text = one(text,
        '        FOOD_ITEMS.clear();\n        BLOCK_ITEMS.clear();\n        REDSTONE_COMPONENT_ITEMS.clear();\n',
        '        FOOD_ITEMS.clear();\n        BLOCK_ITEMS.clear();\n        BLOCK_IDS.clear();\n'
        '        REDSTONE_COMPONENT_ITEMS.clear();\n'+block_loop, path+' registries')
    text = one(text, '            case MINED -> sum(stats, "minecraft:mined");',
               '            case MINED -> sumMatching(stats, "minecraft:mined", BLOCK_IDS);', path+' mined')
    save(path, text)

patch('src/main/java/cn/bamgdam/rankboard/StatReader.java', False)
patch('src/mojmap/java/cn/bamgdam/rankboard/StatReader.java', True)

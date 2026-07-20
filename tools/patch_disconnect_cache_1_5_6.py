from pathlib import Path


def patch(path, stats_dir):
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    old = f'''            Map<UUID, String> names = readKnownNames(server);
            Path path = {stats_dir}.resolve(uuid + ".json");
            readSnapshot(path, names).ifPresent(snapshot -> {{
                CACHE.put(uuid, snapshot);
                SOURCE_MODIFIED.put(uuid, modifiedTime(path));
                savePersistentCache(server);
                WebDashboard.invalidateRankings();
            }});'''
    new = f'''            Map<UUID, String> names = readKnownNames(server);
            Path path = {stats_dir}.resolve(uuid + ".json");
            long modified = modifiedTime(path);
            // Keep the disconnect-time in-memory snapshot until the vanilla file
            // actually advances; an unchanged file is older than the captured value.
            if (modified == SOURCE_MODIFIED.getOrDefault(uuid, -1L)) return;
            readSnapshot(path, names).ifPresent(snapshot -> {{
                CACHE.put(uuid, snapshot);
                SOURCE_MODIFIED.put(uuid, modified);
                savePersistentCache(server);
                WebDashboard.invalidateRankings();
            }});'''
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one reload anchor, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


patch("src/main/java/cn/bamgdam/rankboard/StatReader.java", "server.getSavePath(WorldSavePath.STATS)")
patch("src/mojmap/java/cn/bamgdam/rankboard/StatReader.java", "server.getWorldPath(LevelResource.PLAYER_STATS_DIR)")

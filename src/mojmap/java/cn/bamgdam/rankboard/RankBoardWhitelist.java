package cn.bamgdam.rankboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Optional mod-owned player allowlist, separate from the server whitelist. */
final class RankBoardWhitelist {
    private static final String FILE_NAME = "rankboard-whitelist.json";
    private static volatile Rules current = Rules.EMPTY;

    private RankBoardWhitelist() { }

    static synchronized void load(MinecraftServer server) {
        Path path = path(server);
        if (!Files.isRegularFile(path)) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "[\n]\n", StandardCharsets.UTF_8);
            } catch (IOException exception) {
                RankBoardMod.LOGGER.warn("Could not create RankBoard whitelist {}", path, exception);
            }
            current = Rules.EMPTY;
            return;
        }
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) throw new IllegalArgumentException("root must be an array");
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                var object = element.getAsJsonObject();
                if (object.has("uuid")) {
                    try { uuids.add(UUID.fromString(object.get("uuid").getAsString())); }
                    catch (IllegalArgumentException ignored) { }
                }
                if (object.has("name")) {
                    String name = object.get("name").getAsString().strip();
                    if (!name.isEmpty()) names.add(name.toLowerCase(Locale.ROOT));
                }
            }
            current = new Rules(Set.copyOf(uuids), Set.copyOf(names));
        } catch (Exception exception) {
            RankBoardMod.LOGGER.warn("Could not read RankBoard whitelist {}; no players will match while enabled", path, exception);
            current = Rules.EMPTY;
        }
    }

    static void reload(MinecraftServer server) { load(server); }

    static synchronized boolean add(MinecraftServer server, String input) throws IOException {
        String value = input.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("玩家名或 UUID 不能为空");
        Rules rules = current;
        Set<UUID> uuids = new HashSet<>(rules.uuids);
        Set<String> names = new HashSet<>(rules.names);
        try {
            uuids.add(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            names.add(value.toLowerCase(Locale.ROOT));
        }
        boolean changed = !uuids.equals(rules.uuids) || !names.equals(rules.names);
        if (changed) write(server, uuids, names);
        load(server);
        return changed;
    }

    static synchronized boolean remove(MinecraftServer server, String input) throws IOException {
        String value = input.strip();
        Set<UUID> uuids = new HashSet<>(current.uuids);
        Set<String> names = new HashSet<>(current.names);
        boolean changed;
        try { changed = uuids.remove(UUID.fromString(value)); }
        catch (IllegalArgumentException exception) { changed = names.remove(value.toLowerCase(Locale.ROOT)); }
        if (changed) write(server, uuids, names);
        load(server);
        return changed;
    }

    static List<String> entries() {
        List<String> entries = new ArrayList<>();
        current.uuids.stream().map(UUID::toString).sorted().forEach(entries::add);
        current.names.stream().sorted().forEach(entries::add);
        return entries;
    }

    static boolean matches(MinecraftServer server, UUID uuid, String name) {
        Rules rules = current;
        return rules.uuids.contains(uuid)
                || (name != null && rules.names.contains(name.toLowerCase(Locale.ROOT)));
    }

    static Set<UUID> allowedUuids(Map<UUID, String> knownNames) {
        Rules rules = current;
        Set<UUID> result = new HashSet<>(rules.uuids);
        if (!rules.names.isEmpty()) {
            knownNames.forEach((uuid, name) -> {
                if (name != null && rules.names.contains(name.toLowerCase(Locale.ROOT))) result.add(uuid);
            });
        }
        return result;
    }

    static Path path(MinecraftServer server) {
        return RankBoardConfig.configDirectory(server).resolve(FILE_NAME);
    }

    private static void write(MinecraftServer server, Set<UUID> uuids, Set<String> names) throws IOException {
        JsonArray array = new JsonArray();
        uuids.stream().sorted().forEach(uuid -> {
            JsonObject object = new JsonObject();
            object.addProperty("uuid", uuid.toString());
            array.add(object);
        });
        names.stream().sorted().forEach(name -> {
            JsonObject object = new JsonObject();
            object.addProperty("name", name);
            array.add(object);
        });
        Files.writeString(path(server), new GsonBuilder().setPrettyPrinting().create().toJson(array) + "\n",
                StandardCharsets.UTF_8);
    }

    private record Rules(Set<UUID> uuids, Set<String> names) {
        private static final Rules EMPTY = new Rules(Set.of(), Set.of());
    }
}

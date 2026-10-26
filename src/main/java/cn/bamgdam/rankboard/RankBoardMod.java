package cn.bamgdam.rankboard;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;

public final class RankBoardMod implements ModInitializer {
    public static final String MOD_ID = "rankboard";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int REFRESH_INTERVAL_TICKS = 600;
    private static final String MENU_TRIGGER_OBJECTIVE = "rb_menu";
    private static final AtomicInteger NEXT_MENU_ACTION_ID = new AtomicInteger(
            ThreadLocalRandom.current().nextInt(1, 1_000_000_000));
    private static final Map<String, Integer> MENU_ACTION_IDS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> MENU_ACTIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> LOOK_MENU_HELD = new HashSet<>();
    private static final List<String> MENU_GROUPS = List.of("core", "combat", "build", "life", "explore", "fun", "all");
    private static final Set<String> REDSTONE_COMPONENTS = Set.of(
            "redstone", "redstone_torch", "repeater", "comparator", "observer", "piston", "sticky_piston",
            "dispenser", "dropper", "hopper", "lever", "tripwire_hook", "target", "daylight_detector",
            "note_block", "redstone_block", "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod",
            "trapped_chest", "powered_rail", "detector_rail", "activator_rail", "rail", "lectern", "jukebox", "bell",
            "redstone_lamp", "tnt", "big_dripleaf", "crafter", "command_block", "chain_command_block",
            "repeating_command_block");
    private static final List<ColorPreset> COLOR_PRESETS = List.of(
            new ColorPreset("black", "黑色", Formatting.BLACK), new ColorPreset("dark_blue", "深蓝色", Formatting.DARK_BLUE),
            new ColorPreset("dark_green", "深绿色", Formatting.DARK_GREEN), new ColorPreset("dark_aqua", "深青色", Formatting.DARK_AQUA),
            new ColorPreset("dark_red", "深红色", Formatting.DARK_RED), new ColorPreset("dark_purple", "深紫色", Formatting.DARK_PURPLE),
            new ColorPreset("gold", "金色", Formatting.GOLD), new ColorPreset("gray", "灰色", Formatting.GRAY),
            new ColorPreset("dark_gray", "深灰色", Formatting.DARK_GRAY), new ColorPreset("blue", "蓝色", Formatting.BLUE),
            new ColorPreset("green", "绿色", Formatting.GREEN), new ColorPreset("aqua", "青色", Formatting.AQUA),
            new ColorPreset("red", "红色", Formatting.RED), new ColorPreset("light_purple", "粉紫色", Formatting.LIGHT_PURPLE),
            new ColorPreset("yellow", "黄色", Formatting.YELLOW), new ColorPreset("white", "白色", Formatting.WHITE));
    private int ticks;

    @Override
    public void onInitialize() {
        LOGGER.info("RankBoard initialized for Minecraft 1.21.x");
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RuntimeMetricTracker.start(server);
            RankBoardConfig.load(server);
            RankBoardLanguage.load(server);
            RankBoardWhitelist.load(server);
            ensureMenuTriggerObjective(server);
            BoardService.enforceForeignScoreboardPolicy(server);
            StatReader.initialize(server);
            WebDashboard.start(server);
        });
        // QuickBackupMulti can start the restored server from inside SERVER_STOPPED.
        // Clear all server-bound state one phase earlier so the new server cannot
        // reuse stale rbp_/rbo_ objective/session references during its first join.
        ServerLifecycleEvents.SERVER_STOPPING.register(this::cleanupServerState);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            prepareMenuTrigger(player);
            // Keep the UUID-keyed statistic cache aligned with the player's current
            // profile name, including a name changed since the last login.
            StatReader.updateName(player.getUuid(), ProfileCompat.name(player.getGameProfile()));
            LeaderboardState.get(server).ensurePlayer(player);
            RuntimeMetricTracker.join(server, player);
            AvatarCache.cacheOnJoin(server, player);
            BoardService.restore(player);
            sendJoinExperience(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RuntimeMetricTracker.disconnect(server, handler.getPlayer());
            StatReader.capturePlayer(server, handler.getPlayer());
            LOOK_MENU_HELD.remove(handler.getPlayer().getUuid());
            BoardService.disconnect(handler.getPlayer());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RuntimeMetricTracker.tick(server);
            try (var reads = LeaderboardReadScope.open(server)) {
                BoardService.tickCarousel(server);
                BoardService.tickActivity(server);
                handleMenuTriggers(server);
                handleLookUpSneakMenu(server);
                if (++ticks >= REFRESH_INTERVAL_TICKS) {
                    ticks = 0;
                    LeaderboardState.get(server).rollPeriods(server);
                    BoardService.refreshAll(server);
                    BoardService.enforceForeignScoreboardPolicy(server);
                }
            }
        });
    }

    private void cleanupServerState(net.minecraft.server.MinecraftServer server) {
        RuntimeMetricTracker.stop(server);
        PlayerNameColors.clear(server);
        BoardService.clearSessions();
        CacheComparisonService.clear(server);
        WebDashboard.stop();
        StatReader.flushPersistentCache(server);
        StatReader.stopWarmup();
        LOOK_MENU_HELD.clear();
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
                                  CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("leaderboard")
                .requires(source -> CommandPermissionCompat.has(source, 0)).executes(context -> menu(context.getSource()));
        root.then(CommandManager.literal("help").requires(source -> RankBoardConfig.get().helpVisible(source))
                .executes(context -> helpGrouped(context.getSource(), "menu"))
                .then(CommandManager.literal("player").executes(context -> helpGrouped(context.getSource(), "player")))
                .then(CommandManager.literal("scoreboard").executes(context -> helpGrouped(context.getSource(), "scoreboard")))
                .then(CommandManager.literal("web").executes(context -> helpGrouped(context.getSource(), "web")))
                .then(CommandManager.literal("config").requires(source -> CommandPermissionCompat.has(source, 2))
                        .executes(context -> helpGrouped(context.getSource(), "config"))
                        .then(CommandManager.literal("general").executes(context -> helpGrouped(context.getSource(), "config-general")))
                        .then(CommandManager.literal("scoreboard").executes(context -> helpGrouped(context.getSource(), "config-scoreboard")))
                        .then(CommandManager.literal("web").executes(context -> helpGrouped(context.getSource(), "config-web"))))
                .then(CommandManager.literal("admin").requires(source -> CommandPermissionCompat.has(source, 2))
                        .executes(context -> helpGrouped(context.getSource(), "admin"))
                        .then(CommandManager.literal("players").executes(context -> helpGrouped(context.getSource(), "admin-players")))
                        .then(CommandManager.literal("scoreboard").executes(context -> helpGrouped(context.getSource(), "admin-scoreboard")))
                        .then(CommandManager.literal("web").executes(context -> helpGrouped(context.getSource(), "admin-web")))
                        .then(CommandManager.literal("config").executes(context -> helpGrouped(context.getSource(), "admin-config")))));
        LiteralArgumentBuilder<ServerCommandSource> menuRoot = CommandManager.literal("menu")
                .executes(context -> menu(context.getSource()));
        menuRoot.then(CommandManager.literal("home").executes(context -> menu(context.getSource())));
        menuRoot.then(CommandManager.literal("carousel").executes(context -> carouselMenu(context.getSource())));
        menuRoot.then(CommandManager.literal("lookmenu").executes(context -> lookMenu(context.getSource())));

        for (String group : MENU_GROUPS) menuRoot.then(buildMenuGroupCommand(group));
        menuRoot.then(buildPeriodMenuCommands("ranking", 0));
        menuRoot.then(buildPeriodMenuCommands("personal", 1));
        menuRoot.then(buildPeriodMenuCommands("server", 2));
        root.then(menuRoot);
        root.then(buildMineCommands("mine"));
        root.then(CommandManager.literal("intro")
                .executes(context -> metricIntroduction(context.getSource())));
        root.then(CommandManager.literal("language")
                .executes(context -> languageStatus(context.getSource()))
                .then(CommandManager.literal("status").executes(context -> languageStatus(context.getSource())))
                .then(languageDefaultCommand("default"))
                .then(languageDefaultCommand("global"))
                .then(CommandManager.argument("code", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(RankBoardLanguage.codes(), builder))
                        .executes(context -> setLanguage(context.getSource(),
                                StringArgumentType.getString(context, "code").toLowerCase(java.util.Locale.ROOT)))));
        LiteralArgumentBuilder<ServerCommandSource> export = CommandManager.literal("export")
                .requires(source -> CommandPermissionCompat.has(source, 2));
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<ServerCommandSource> periodNode = CommandManager.literal(period.command);
            for (Metric metric : Metric.values()) {
                periodNode.then(CommandManager.literal(metric.command)
                        .executes(context -> exportRanking(context.getSource(), period, metric)));
            }
            export.then(periodNode);
        }
        root.then(export);
        root.then(buildMetricDataCommands());
        root.then(CommandManager.literal("carousel")
                .then(CommandManager.literal("true").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(CommandManager.literal("on").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> BoardService.carouselStatus(context.getSource())))
                .then(CommandManager.literal("color").requires(source -> CommandPermissionCompat.has(source, 2))
                        .then(CommandManager.literal("true").executes(context -> setCarouselColor(context.getSource(), true)))
                        .then(CommandManager.literal("false").executes(context -> setCarouselColor(context.getSource(), false)))
                        .then(CommandManager.literal("status").executes(context -> setCarouselColorStatus(context.getSource())))));
        root.then(CommandManager.literal("webtheme").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("icon").executes(context -> setWebThemeMode(context.getSource(), true)))
                .then(CommandManager.literal("blue").executes(context -> setWebThemeMode(context.getSource(), false)))
                .then(CommandManager.literal("true").executes(context -> setWebThemeMode(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setWebThemeMode(context.getSource(), false)))
                .then(CommandManager.literal("rgb")
                        .then(CommandManager.argument("color", StringArgumentType.word())
                                .executes(context -> setWebThemeRgb(context.getSource(),
                                        StringArgumentType.getString(context, "color")))))
                .then(CommandManager.literal("status").executes(context -> webThemeModeStatus(context.getSource()))));
        root.then(CommandManager.literal("webswitch").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> webSwitchStatus(context.getSource()))
                .then(CommandManager.literal("status").executes(context -> webSwitchStatus(context.getSource())))
                .then(CommandManager.literal("list").executes(context -> webSwitchList(context.getSource())))
                .then(CommandManager.literal("name")
                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                .executes(context -> setConfig(context.getSource(), "web-switcher-name",
                                        StringArgumentType.getString(context, "name")))))
                .then(CommandManager.literal("weight")
                        .then(CommandManager.argument("weight", IntegerArgumentType.integer(1, 10000))
                                .executes(context -> setConfig(context.getSource(), "web-switcher-weight",
                                        Integer.toString(IntegerArgumentType.getInteger(context, "weight"))))))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("address", StringArgumentType.word())
                                .executes(context -> modifyWebSwitchPeer(context.getSource(), true,
                                        StringArgumentType.getString(context, "address")))))
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("address", StringArgumentType.word())
                                .executes(context -> modifyWebSwitchPeer(context.getSource(), false,
                                        StringArgumentType.getString(context, "address"))))));
        root.then(CommandManager.literal("display")
                .then(CommandManager.literal("on").executes(context -> BoardService.enable(context.getSource())))
                .then(CommandManager.literal("off").executes(context -> BoardService.disable(context.getSource()))
                        .then(CommandManager.argument("player", EntityArgumentType.player()).requires(source -> CommandPermissionCompat.has(source, 2))
                                .executes(context -> BoardService.disable(context.getSource(),
                                        EntityArgumentType.getPlayer(context, "player")))))
                .then(buildSelectionCommands(false)));
        root.then(CommandManager.literal("namecolor").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("true").executes(context -> setNameColor(context.getSource(), "true")))
                .then(CommandManager.literal("false").executes(context -> setNameColor(context.getSource(), "false")))
                .then(CommandManager.literal("scoreboard-only").executes(context -> setNameColor(context.getSource(), "scoreboard-only")))
                .then(CommandManager.literal("on").executes(context -> setNameColor(context.getSource(), "true")))
                .then(CommandManager.literal("off").executes(context -> setNameColor(context.getSource(), "false")))
                .then(CommandManager.literal("status").executes(context -> nameColorStatus(context.getSource()))));
        root.then(CommandManager.literal("bedrock").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("true").executes(context -> setConfig(context.getSource(),
                        "bedrock-break-leaderboard-enabled", "true")))
                .then(CommandManager.literal("false").executes(context -> setConfig(context.getSource(),
                        "bedrock-break-leaderboard-enabled", "false")))
                .then(CommandManager.literal("status").executes(context -> getConfig(context.getSource(),
                        "bedrock-break-leaderboard-enabled"))));
        root.then(buildColorCommands());
        root.then(buildLabelCommands());
        root.then(CommandManager.literal("lookmenu")
                .then(CommandManager.literal("true").executes(context -> setLookMenu(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setLookMenu(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> lookMenuStatus(context.getSource())))
                .then(CommandManager.literal("global").requires(source -> CommandPermissionCompat.has(source, 2))
                        .then(CommandManager.literal("true").executes(context -> setGlobalLookMenu(context.getSource(), true)))
                        .then(CommandManager.literal("false").executes(context -> setGlobalLookMenu(context.getSource(), false)))
                        .then(CommandManager.literal("status").executes(context -> globalLookMenuStatus(context.getSource())))));
        root.then(CommandManager.literal("joinmenu")
                .then(CommandManager.literal("true").executes(context -> setJoinMenu(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setJoinMenu(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> joinMenuStatus(context.getSource()))));
        LiteralArgumentBuilder<ServerCommandSource> displayFilter = CommandManager.literal("displayfilter")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> metricToggleMenu(context.getSource()));
        for (Metric metric : Metric.values()) {
            displayFilter.then(CommandManager.literal(metric.command)
                    .then(CommandManager.literal("true").executes(context -> setMetricDisplay(context.getSource(), metric, true)))
                    .then(CommandManager.literal("false").executes(context -> setMetricDisplay(context.getSource(), metric, false)))
                    .then(CommandManager.literal("enable").executes(context -> setMetricDisplay(context.getSource(), metric, true)))
                    .then(CommandManager.literal("disable").executes(context -> setMetricDisplay(context.getSource(), metric, false)))
                    .then(CommandManager.literal("status").executes(context -> metricDisplayStatus(context.getSource(), metric))));
        }
        root.then(displayFilter);
        root.then(CommandManager.literal("scoreboard").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("clear").executes(context -> BoardService.clearVanilla(context.getSource())))
                .then(CommandManager.literal("cleanup").executes(context -> BoardService.clearForeignScoreboards(context.getSource())))
                .then(CommandManager.literal("blocking")
                        .then(CommandManager.literal("true").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), true)))
                        .then(CommandManager.literal("false").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), false)))
                        .then(CommandManager.literal("enable").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), true)))
                        .then(CommandManager.literal("disable").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), false)))
                        .then(CommandManager.literal("status").executes(context -> BoardService.foreignScoreboardBlockingStatus(context.getSource()))))
                .then(buildSelectionCommands(true)));
        root.then(CommandManager.literal("whitelist").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("true").executes(context -> setWhitelistOnly(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setWhitelistOnly(context.getSource(), false)))
                .then(CommandManager.literal("on").executes(context -> setWhitelistOnly(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setWhitelistOnly(context.getSource(), false)))
                .then(CommandManager.literal("setup")
                        .then(CommandManager.literal("server").executes(context -> configureWhitelistMode(context.getSource(), WhitelistMode.SERVER)))
                        .then(CommandManager.literal("mod").executes(context -> configureWhitelistMode(context.getSource(), WhitelistMode.MOD)))
                        .then(CommandManager.literal("none").executes(context -> configureWhitelistMode(context.getSource(), WhitelistMode.NONE)))
                        .then(CommandManager.literal("later").executes(context -> skipWhitelistDataRead(context.getSource()))))
                .then(CommandManager.literal("status").executes(context -> whitelistStatus(context.getSource()))));
        root.then(CommandManager.literal("botfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("true").executes(context -> setBotFilter(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setBotFilter(context.getSource(), false)))
                .then(CommandManager.literal("on").executes(context -> setBotFilter(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setBotFilter(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> botFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("customfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("true").executes(context -> setCustomFilter(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setCustomFilter(context.getSource(), false)))
                .then(CommandManager.literal("on").executes(context -> setCustomFilter(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setCustomFilter(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> customFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("onlinefilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("true").executes(context -> setOnlineFilter(context.getSource(), true)))
                .then(CommandManager.literal("false").executes(context -> setOnlineFilter(context.getSource(), false)))
                .then(CommandManager.literal("on").executes(context -> setOnlineFilter(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setOnlineFilter(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> onlineFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("modwhitelist").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(context -> modifyModWhitelist(context.getSource(), true,
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(context -> modifyModWhitelist(context.getSource(), false,
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("list").executes(context -> listModWhitelist(context.getSource())))
                .then(CommandManager.literal("reload").executes(context -> reloadModWhitelist(context.getSource()))));
        root.then(CommandManager.literal("playerfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> playerFilterMenu(context.getSource()))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        playerFilterCandidates(context.getSource().getServer()), builder))
                                .executes(context -> addFilteredPlayer(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("allow")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        playerFilterCandidates(context.getSource().getServer()), builder))
                                .executes(context -> allowFilteredPlayer(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        playerFilterCandidates(context.getSource().getServer()), builder))
                                .executes(context -> removePlayerFilter(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("list").executes(context -> listPlayerFilters(context.getSource())))
                .then(CommandManager.literal("clear").executes(context -> clearPlayerFilters(context.getSource())))
                .then(CommandManager.literal("status").executes(context -> playerFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("botgroup").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> botGroupMenu(context.getSource()))
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        playerFilterCandidates(context.getSource().getServer()), builder))
                                .executes(context -> addBotGroupPlayer(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        playerFilterCandidates(context.getSource().getServer()), builder))
                                .executes(context -> removeBotGroupPlayer(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(CommandManager.literal("list").executes(context -> listBotGroup(context.getSource())))
                .then(CommandManager.literal("clear").executes(context -> clearBotGroup(context.getSource())))
                .then(CommandManager.literal("mixed")
                        .executes(context -> botGroupMixedStatus(context.getSource()))
                        .then(CommandManager.literal("true").executes(context -> setBotGroupMixed(context.getSource(), true)))
                        .then(CommandManager.literal("false").executes(context -> setBotGroupMixed(context.getSource(), false)))
                        .then(CommandManager.literal("on").executes(context -> setBotGroupMixed(context.getSource(), true)))
                        .then(CommandManager.literal("off").executes(context -> setBotGroupMixed(context.getSource(), false)))
                        .then(CommandManager.literal("status").executes(context -> botGroupMixedStatus(context.getSource())))));
        root.then(CommandManager.literal("bots").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                            "用法：/leaderboard bots <周期> <榜单> [数量]；"
                                    + "/leaderboard bots rate <周期> <榜单> [数量]").formatted(Formatting.GRAY), false);
                    return 1;
                })
                .then(CommandManager.literal("rate")
                        .then(CommandManager.argument("period", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        java.util.Arrays.stream(Period.values()).map(period -> period.command).toList(), builder))
                                .then(CommandManager.argument("metric", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                                java.util.Arrays.stream(Metric.values()).map(metric -> metric.command).toList(), builder))
                                        .executes(context -> showBots(context.getSource(),
                                                periodByCommand(StringArgumentType.getString(context, "period")),
                                                metricByCommand(StringArgumentType.getString(context, "metric")), true, 10))
                                        .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                                                .executes(context -> showBots(context.getSource(),
                                                        periodByCommand(StringArgumentType.getString(context, "period")),
                                                        metricByCommand(StringArgumentType.getString(context, "metric")), true,
                                                        IntegerArgumentType.getInteger(context, "limit")))))))
                .then(CommandManager.argument("period", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                java.util.Arrays.stream(Period.values()).map(period -> period.command).toList(), builder))
                        .then(CommandManager.argument("metric", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        java.util.Arrays.stream(Metric.values()).map(metric -> metric.command).toList(), builder))
                                .executes(context -> showBots(context.getSource(),
                                        periodByCommand(StringArgumentType.getString(context, "period")),
                                        metricByCommand(StringArgumentType.getString(context, "metric")), false, 10))
                                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                                        .executes(context -> showBots(context.getSource(),
                                                periodByCommand(StringArgumentType.getString(context, "period")),
                                                metricByCommand(StringArgumentType.getString(context, "metric")), false,
                                                IntegerArgumentType.getInteger(context, "limit")))))));
        root.then(CommandManager.literal("recipients").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> recipientFilterStatus(context.getSource()))
                .then(CommandManager.literal("status").executes(context -> recipientFilterStatus(context.getSource())))
                .then(CommandManager.literal("fake-only").executes(context -> setRecipientFilter(context.getSource(), "fake-only")))
                .then(CommandManager.literal("false").executes(context -> setRecipientFilter(context.getSource(), "false")))
                .then(CommandManager.literal("whitelist").executes(context -> setRecipientFilter(context.getSource(), "whitelist")))
                .then(CommandManager.literal("blacklist").executes(context -> setRecipientFilter(context.getSource(), "blacklist"))));
        root.then(CommandManager.literal("lookup").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("unknown").executes(context -> MojangNameLookup.lookupUnknown(context.getSource())))
                .then(CommandManager.literal("whitelist").executes(context -> MojangNameLookup.lookupWhitelist(context.getSource())))
                .then(CommandManager.argument("uuid", StringArgumentType.word())
                        .executes(context -> MojangNameLookup.lookupOne(context.getSource(),
                                StringArgumentType.getString(context, "uuid")))));
        root.then(CommandManager.literal("cache").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("status").executes(context -> cacheStatus(context.getSource())))
                .then(CommandManager.literal("reload").executes(context -> reloadCache(context.getSource())))
                .then(cacheCompareCommands())
                .then(CommandManager.literal("threads")
                        .executes(context -> cacheThreadsStatus(context.getSource()))
                        .then(CommandManager.literal("status").executes(context -> cacheThreadsStatus(context.getSource())))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(0, 256))
                                .executes(context -> setCacheThreads(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count"))))));
        root.then(CommandManager.literal("ratelimit").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("clear").executes(context -> clearRateLimits(context.getSource()))));
        root.then(CommandManager.literal("config").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> listConfig(context.getSource()))
                .then(CommandManager.literal("list").executes(context -> listConfig(context.getSource())))
                .then(CommandManager.literal("reload").executes(context -> reloadConfig(context.getSource())))
                .then(CommandManager.literal("get")
                        .then(CommandManager.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        RankBoardConfig.optionKeys(), builder))
                                .executes(context -> getConfig(context.getSource(),
                                        StringArgumentType.getString(context, "key")))))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        RankBoardConfig.optionKeys(), builder))
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(context -> setConfig(context.getSource(),
                                                StringArgumentType.getString(context, "key"),
                                                StringArgumentType.getString(context, "value")))))));
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<ServerCommandSource> periodNode = CommandManager.literal(period.command);
            for (String group : MENU_GROUPS) {
                LiteralArgumentBuilder<ServerCommandSource> groupNode = CommandManager.literal(group);
                for (Metric metric : menuMetrics(group)) {
                    groupNode.then(CommandManager.literal(metric.command)
                            .executes(context -> show(context.getSource(), period, metric, 10))
                            .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                                    .executes(context -> show(context.getSource(), period, metric,
                                            IntegerArgumentType.getInteger(context, "limit")))));
                }
                periodNode.then(groupNode);
            }
            root.then(periodNode);
        }
        dispatcher.register(root);
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildMenuGroupCommand(String group) {
        LiteralArgumentBuilder<ServerCommandSource> node = CommandManager.literal(group)
                .executes(context -> menu(context.getSource(), group));
        for (Metric metric : menuMetrics(group)) {
            LiteralArgumentBuilder<ServerCommandSource> metricNode = CommandManager.literal(metric.command)
                    .executes(context -> browseMetricMenu(context.getSource(), group, metric));
            for (Period period : Period.values()) {
                metricNode.then(browsePeriodNode(period.shortCommand, group, metric, period));
                if (!period.shortCommand.equals(period.command)) {
                    metricNode.then(browsePeriodNode(period.command, group, metric, period));
                }
            }
            node.then(metricNode);
        }
        if (group.equals("fun")) {
            node.then(CommandManager.literal("overview")
                    .executes(context -> funBrowseMetricsMenu(context.getSource(), false)));
            node.then(CommandManager.literal("victims")
                    .executes(context -> funBrowseMetricsMenu(context.getSource(), true)));
        }
        return node;
    }

    private LiteralArgumentBuilder<ServerCommandSource> browsePeriodNode(String command, String group,
                                                                          Metric metric, Period period) {
        LiteralArgumentBuilder<ServerCommandSource> node = CommandManager.literal(command)
                .executes(context -> browseMetricActionMenu(context.getSource(), group, metric, period));
        node.then(CommandManager.literal("players")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> browsePlayersMenu(context.getSource(), group, metric, period))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> browsePlayerMenu(context.getSource(), group, metric, period,
                                EntityArgumentType.getPlayer(context, "player")))));
        return node;
    }
    private LiteralArgumentBuilder<ServerCommandSource> buildPeriodMenuCommands(String command, int mode) {
        LiteralArgumentBuilder<ServerCommandSource> node = CommandManager.literal(command)
                .requires(source -> mode != 2 || CommandPermissionCompat.has(source, 2))
                .executes(context -> periodPickerMenu(context.getSource(), mode));
        for (Period period : Period.values()) {
            node.then(periodMenuNode(period.shortCommand, mode, period));
            if (!period.shortCommand.equals(period.command)) node.then(periodMenuNode(period.command, mode, period));
        }
        return node;
    }

    private LiteralArgumentBuilder<ServerCommandSource> periodMenuNode(String command, int mode, Period period) {
        LiteralArgumentBuilder<ServerCommandSource> node = CommandManager.literal(command)
                .executes(context -> categoryPickerMenu(context.getSource(), mode, period));
        for (String group : MENU_GROUPS) node.then(periodGroupMenuNode(group, mode, period));
        return node;
    }

    private LiteralArgumentBuilder<ServerCommandSource> periodGroupMenuNode(String group, int mode, Period period) {
        LiteralArgumentBuilder<ServerCommandSource> node = CommandManager.literal(group)
                .executes(context -> metricPickerMenu(context.getSource(), mode, period, group));
        for (Metric metric : menuMetrics(group)) {
            LiteralArgumentBuilder<ServerCommandSource> metricNode = CommandManager.literal(metric.command)
                    .executes(context -> completeMenuAction(context.getSource(), mode, period, metric));
            node.then(metricNode);
        }
        if (group.equals("fun")) {
            node.then(CommandManager.literal("overview")
                    .executes(context -> funWizardMetricsMenu(context.getSource(), mode, period, false)));
            node.then(CommandManager.literal("victims")
                    .executes(context -> funWizardMetricsMenu(context.getSource(), mode, period, true)));
        }
        return node;
    }

    private int completeMenuAction(ServerCommandSource source, int mode, Period period, Metric metric) {
        return switch (mode) {
            case 0 -> show(source, period, metric, 10);
            case 1 -> BoardService.enable(source, period, metric);
            default -> BoardService.writeVanilla(source, period, metric);
        };
    }
    private LiteralArgumentBuilder<ServerCommandSource> buildMineCommands(String command) {
        LiteralArgumentBuilder<ServerCommandSource> node = CommandManager.literal(command)
                .executes(context -> showMyScores(context.getSource(), Period.ALL));
        for (Period period : Period.values()) {
            node.then(CommandManager.literal(period.shortCommand)
                    .executes(context -> showMyScores(context.getSource(), period)));
            if (!period.shortCommand.equals(period.command)) {
                node.then(CommandManager.literal(period.command)
                        .executes(context -> showMyScores(context.getSource(), period)));
            }
        }
        return node;
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildSelectionCommands(boolean vanilla) {
        LiteralArgumentBuilder<ServerCommandSource> periods = CommandManager.literal("show");
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<ServerCommandSource> periodNode = CommandManager.literal(period.command);
            for (String group : MENU_GROUPS) {
                LiteralArgumentBuilder<ServerCommandSource> groupNode = CommandManager.literal(group);
                for (Metric metric : menuMetrics(group)) {
                    LiteralArgumentBuilder<ServerCommandSource> metricNode = CommandManager.literal(metric.command)
                            .executes(context -> vanilla
                                    ? BoardService.writeVanilla(context.getSource(), period, metric)
                                    : BoardService.enable(context.getSource(), period, metric));
                    if (!vanilla) {
                        metricNode.then(CommandManager.argument("player", EntityArgumentType.player())
                                .requires(source -> CommandPermissionCompat.has(source, 2))
                                .executes(context -> BoardService.enable(context.getSource(),
                                        EntityArgumentType.getPlayer(context, "player"), period, metric)));
                    }
                    groupNode.then(metricNode);
                }
                periodNode.then(groupNode);
            }
            periods.then(periodNode);
        }
        return periods;
    }
    private LiteralArgumentBuilder<ServerCommandSource> buildColorCommands() {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("color")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("list").executes(context -> listMetricColors(context.getSource())));
        LiteralArgumentBuilder<ServerCommandSource> reset = CommandManager.literal("reset")
                .then(CommandManager.literal("all").executes(context -> resetAllMetricColors(context.getSource())));
        for (Metric metric : orderedMenuMetrics()) {
            root.then(CommandManager.literal(metric.command)
                    .executes(context -> showColorPresets(context.getSource(), metric))
                    .then(CommandManager.argument("value", StringArgumentType.word())
                            .suggests((context, builder) -> suggestColorPresets(builder))
                            .executes(context -> setMetricColor(context.getSource(), metric,
                                    StringArgumentType.getString(context, "value")))));
            reset.then(CommandManager.literal(metric.command)
                    .executes(context -> resetMetricColor(context.getSource(), metric)));
        }
        return root.then(reset);
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildLabelCommands() {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("label")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("list").executes(context -> listMetricLabels(context.getSource())));
        LiteralArgumentBuilder<ServerCommandSource> reset = CommandManager.literal("reset")
                .then(CommandManager.literal("all").executes(context -> resetAllMetricLabels(context.getSource())));
        for (Metric metric : orderedMenuMetrics()) {
            root.then(CommandManager.literal(metric.command)
                    .executes(context -> showMetricLabel(context.getSource(), metric))
                    .then(CommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(context -> setMetricLabel(context.getSource(), metric,
                                    StringArgumentType.getString(context, "name")))));
            reset.then(CommandManager.literal(metric.command)
                    .executes(context -> resetMetricLabel(context.getSource(), metric)));
        }
        return root.then(reset);
    }

    private int helpGrouped(ServerCommandSource source, String group) {
        boolean op = CommandPermissionCompat.has(source, 2);
        if (group.equals("menu")) {
            Text line = clickable("[" + localized(source, "help.player") + "]", Formatting.AQUA, "/leaderboard help player", localized(source, "help.tooltip.player"))
                    .copy().append(Text.literal(" "))
                    .append(clickable("[" + localized(source, "help.scoreboard") + "]", Formatting.YELLOW, "/leaderboard help scoreboard", localized(source, "help.tooltip.scoreboard")))
                    .append(Text.literal(" "))
                    .append(clickable("[" + localized(source, "help.web") + "]", Formatting.GREEN, "/leaderboard help web", localized(source, "help.tooltip.web")));
            if (op) line = line.copy().append(Text.literal(" "))
                    .append(clickable("[" + localized(source, "help.admin") + "]", Formatting.RED, "/leaderboard help admin", localized(source, "help.tooltip.admin")));
            Text menuLine = line;
            source.sendFeedback(() -> menuLine, false);
            sendLanguageHelp(source);
            if (op) source.sendFeedback(() -> whitelistSetupButtons(source, "whitelist.heading"), false);
            return 1;
        }
        source.sendFeedback(() -> clickable("[" + localized(source, "help.back") + "]", Formatting.GRAY,
                "/leaderboard help", localized(source, "help.tooltip.back")), false);
        switch (group) {
            case "player" -> {
                source.sendFeedback(() -> Text.literal(localized(source, "menu.join_compact")
                        + "：" + localized(source, "join.simple_help")), false);
                helpCommand(source, "/leaderboard language <zh|en|status>", "/leaderboard language ",
                        "选择聊天提示语言 / Choose Chinese or English chat prompts");
                if (op) helpCommand(source, "/leaderboard language default <语言>", "/leaderboard language default ",
                        "OP 设置全服默认聊天语言；会更新在线和已记录玩家");
                helpCommand(source, "/leaderboard", "/leaderboard", "打开排行榜菜单");
                helpCommand(source, "/leaderboard mine <周期>", "/leaderboard mine ", "查看指定周期的个人统计");
                helpCommand(source, "/leaderboard mine", "/leaderboard mine", "查询所有个人统计并显示总览");
                helpCommand(source, "/leaderboard intro", "/leaderboard intro", "介绍所有可用榜单");
                helpCommand(source, "/leaderboard mine <all|day|week|month|year>", "/leaderboard mine ", "查询指定周期的个人统计");
                helpCommand(source, "/leaderboard <周期> <分类> <榜单> [数量]", "/leaderboard all all playtime ", "在聊天栏查看排行榜");
                helpCommand(source, "/leaderboard carousel true|false|status", "/leaderboard carousel ", "控制榜单轮播");
                helpCommand(source, "/leaderboard lookmenu true|false|status", "/leaderboard lookmenu ", "关闭或开启自己的抬头蹲起菜单");
                helpCommand(source, "/leaderboard joinmenu true|false|status", "/leaderboard joinmenu ", "关闭或开启自己的进服排行榜菜单");
            }
            case "scoreboard" -> {
                helpCommand(source, "/leaderboard display show <周期> <分类> <榜单>", "/leaderboard display show ", "显示个人单榜计分板");
                helpCommand(source, "/leaderboard display on", "/leaderboard display on", "恢复关闭前的个人计分板");
                helpCommand(source, "/leaderboard display off", "/leaderboard display off", "关闭个人计分板");
                helpCommand(source, "/leaderboard mine", "/leaderboard mine", "显示个人所有榜单总览");
                if (op) {
                    source.sendFeedback(() -> Text.literal("=== " + localized(source, "help.scoreboard_metrics") + " ===")
                            .formatted(Formatting.GRAY), false);
                    helpCommand(source, "/leaderboard displayfilter", "/leaderboard displayfilter",
                            "添加或者减少榜单");
                    helpCommand(source, "/leaderboard displayfilter <榜单> <true|false|status>",
                            "/leaderboard displayfilter ", "按榜单直接设置显示状态");
                    helpCommand(source, "/leaderboard color <榜单> [颜色名|#RRGGBB]", "/leaderboard color ",
                            "设置指定榜单颜色；支持 16 色预选或 RGB");
                    helpCommand(source, "/leaderboard color list", "/leaderboard color list",
                            "列出所有榜单的名称、标识和当前颜色");
                    helpCommand(source, "/leaderboard color reset <榜单|all>", "/leaderboard color reset ",
                            "恢复指定榜单或全部榜单默认颜色");
                    helpCommand(source, "/leaderboard label <榜单> <名称>", "/leaderboard label ",
                            "设置指定榜单显示名称；同步游戏和网页");
                    helpCommand(source, "/leaderboard label list|reset <榜单|all>", "/leaderboard label ",
                            "查看或恢复榜单显示名称");
                    helpCommand(source, "/leaderboard scoreboard cleanup", "/leaderboard scoreboard cleanup", "清理其他模组计分板");
                    helpCommand(source, "/leaderboard scoreboard blocking <true|false|status>",
                            "/leaderboard scoreboard blocking ", "设置其他模组计分板自动屏蔽");
                    helpCommand(source, "/leaderboard carousel color <true|false|status>",
                            "/leaderboard carousel color ", "OP 设置轮播标题是否跟随榜单颜色");
                }
            }
            case "web" -> {
                if (RankBoardConfig.get().websiteButtonEnabled && WebDashboard.isEnabled()) {
                    source.sendFeedback(() -> websiteButton(source), false);
                }
                if (op) helpCommand(source, "/leaderboard config set web-enabled <true|false>",
                        "/leaderboard config set web-enabled ", "开启或关闭网页服务；游戏内修改立即生效，手动编辑后执行配置重载");
                helpCommand(source, "/leaderboard config set web-public-address <地址|auto>",
                        "/leaderboard config set web-public-address ", "设置网站按钮地址，默认 127.0.0.1:8765");
                helpCommand(source, "/leaderboard config set website-button-enabled <true|false>",
                        "/leaderboard config set website-button-enabled ", "显示或隐藏菜单和帮助中的网站按钮");
                if (op) helpCommand(source, "/leaderboard webtheme <icon|blue|rgb #RRGGBB|true|false|status>",
                        "/leaderboard webtheme ", "选择图标自动取色或默认蓝色网页主题");
                if (op) helpCommand(source, "/leaderboard webswitch <name|weight|add|remove|list|status>",
                        "/leaderboard webswitch ", "设置左上角服务器切换按钮名称、排序权重和其他网页地址");
                if (op) helpCommand(source, "/leaderboard config set web-switcher-op-hint-enabled <true|false>",
                        "/leaderboard config set web-switcher-op-hint-enabled ", "开关 OP 进服时的服务器切换名称提醒");
                if (op) helpCommand(source, "/leaderboard config set web-port-fallback-enabled <true|false>",
                        "/leaderboard config set web-port-fallback-enabled ", "端口被占用时启用共享网页端口中继，关闭后第二个服务器不会启动网页");
                helpCommand(source, "/leaderboard config list|get|set|reload", "/leaderboard config ", "查看或修改配置");
                helpCommand(source, "/leaderboard ratelimit clear", "/leaderboard ratelimit clear", "清除网页限流");
                if (op) helpCommand(source, "/leaderboard cache compare [玩家]", "/leaderboard cache compare ",
                        "重新读取榜单数据并对比；可预览差异，确认后仅更新 RankBoard 缓存");
                source.sendFeedback(() -> Text.literal(
                        "配置文件：config/rankboard/rankboard-web.properties"), false);
            }
            case "admin" -> {
                if (!op) return 0;
                Text modules = clickable("[" + localized(source, "help.admin_players") + "]", Formatting.AQUA,
                                "/leaderboard help admin players", localized(source, "help.tooltip.admin_players"))
                        .copy().append(Text.literal(" "))
                        .append(clickable("[" + localized(source, "help.admin_scoreboard") + "]", Formatting.YELLOW,
                                "/leaderboard help admin scoreboard", localized(source, "help.tooltip.admin_scoreboard")))
                        .append(Text.literal(" "))
                        .append(clickable("[" + localized(source, "help.admin_web") + "]", Formatting.GREEN,
                                "/leaderboard help admin web", localized(source, "help.tooltip.admin_web")))
                        .append(Text.literal(" "))
                        .append(clickable("[" + localized(source, "help.admin_config") + "]", Formatting.LIGHT_PURPLE,
                                "/leaderboard help admin config", localized(source, "help.tooltip.admin_config")));
                source.sendFeedback(() -> modules, false);
            }
            case "admin-players" -> {
                if (!op) return 0;
                helpCommand(source, "/leaderboard data <set|add|get|reset> <玩家> <榜单> [数值]",
                        "/leaderboard data ", "测试榜单数值；只覆盖 RankBoard 显示，不修改原版统计，reset 可恢复");
                helpCommand(source, "/leaderboard whitelist <true|false|status>", "/leaderboard whitelist ", "控制服务器白名单筛选");
                helpCommand(source, "/leaderboard whitelist setup <server|mod|none>", "/leaderboard whitelist setup ",
                        "首次安装时选择服务器白名单、模组白名单或无白名单；随后可选择是否读取榜单数据");
                source.sendFeedback(() -> whitelistSetupButtons(source, "whitelist.heading"), false);
                helpCommand(source, "/leaderboard modwhitelist <add|remove|list|reload>", "/leaderboard modwhitelist ", "管理模组自带白名单");
                helpCommand(source, "/leaderboard recipients <fake-only|false|whitelist|blacklist|status>", "/leaderboard recipients ", "控制哪些在线玩家接收个人榜单数据；白名单和黑名单复用模组名单");
                helpCommand(source, "/leaderboard botfilter <true|false|status>", "/leaderboard botfilter ", "筛选 bot_ 前缀玩家；立即生效");
                helpCommand(source, "/leaderboard customfilter <true|false|status>", "/leaderboard customfilter ", "筛选无法识别身份的历史玩家；立即生效");
                helpCommand(source, "/leaderboard onlinefilter <true|false|status>", "/leaderboard onlinefilter ", "只显示在线玩家；立即生效");
                helpCommand(source, "/leaderboard playerfilter add|allow|remove <玩家>", "/leaderboard playerfilter ", "手动隐藏玩家，或允许 bot_/unknown_ 玩家显示");
                helpCommand(source, "/leaderboard playerfilter", "/leaderboard playerfilter", "打开玩家过滤面板");
                helpCommand(source, "/leaderboard botgroup add|remove <玩家>", "/leaderboard botgroup ", "把玩家移入/移出机器人分组");
                helpCommand(source, "/leaderboard bots <周期> <榜单> [数量]", "/leaderboard bots ", "查看机器人分组榜单（不受白名单限制）");
                helpCommand(source, "/leaderboard bots rate <周期> <榜单>", "/leaderboard bots rate ", "按每小时产出查看机器人效率榜");
                helpCommand(source, "/leaderboard botgroup mixed <true|false>", "/leaderboard botgroup mixed ", "让机器人同时出现在普通榜单（带 [BOT] 标记）");
                helpCommand(source, "/leaderboard lookup <uuid|whitelist>", "/leaderboard lookup ", "查询 Mojang 玩家名或批量补全白名单名称");
            }
            case "admin-scoreboard" -> {
                if (!op) return 0;
                helpCommand(source, "/leaderboard displayfilter <榜单> <true|false|status>", "/leaderboard displayfilter ", "管理榜单显示");
                helpCommand(source, "/leaderboard scoreboard show <周期> <分类> <榜单>", "/leaderboard scoreboard show ", "显示全服共享原版侧边栏；不会改变玩家名字颜色");
                helpCommand(source, "/leaderboard scoreboard clear", "/leaderboard scoreboard clear", "关闭 RankBoard 全服共享侧边栏");
                helpCommand(source, "/leaderboard scoreboard cleanup", "/leaderboard scoreboard cleanup", "检测并关闭当前其他模组计分板显示槽");
                helpCommand(source, "/leaderboard scoreboard blocking <true|false|status>",
                        "/leaderboard scoreboard blocking ", "屏蔽其他模组计分板");
                helpCommand(source, "/leaderboard namecolor <true|false|scoreboard-only|status>", "/leaderboard namecolor ",
                        "设置全服名字颜色：全部位置、全部关闭或仅排行榜；立即生效");
                helpCommand(source, "/leaderboard lookmenu global <true|false|status>", "/leaderboard lookmenu global ", "OP 控制全服抬头蹲起菜单");
            }
            case "admin-web" -> {
                if (!op) return 0;
                if (RankBoardConfig.get().websiteButtonEnabled && WebDashboard.isEnabled()) {
                    source.sendFeedback(() -> websiteButton(source), false);
                }
                helpCommand(source, "/leaderboard config set web-enabled <true|false>",
                        "/leaderboard config set web-enabled ", "开启或关闭网页服务；游戏内修改立即生效，手动编辑后执行配置重载");
                helpCommand(source, "/leaderboard config set web-public-address <地址|auto>",
                        "/leaderboard config set web-public-address ", "设置网站按钮地址；重启网页服务后仍保留");
                helpCommand(source, "/leaderboard config set website-button-enabled <true|false>",
                        "/leaderboard config set website-button-enabled ", "显示或隐藏菜单和帮助中的网站按钮");
                helpCommand(source, "/leaderboard webtheme <icon|blue|rgb #RRGGBB|true|false|status>",
                        "/leaderboard webtheme ", "选择图标自动取色或默认蓝色网页主题");
                helpCommand(source, "/leaderboard webswitch <name|weight|add|remove|list|status>",
                        "/leaderboard webswitch ", "管理网页服务器切换列表；权重越小越靠前，1 最先显示");
                helpCommand(source, "/leaderboard config set web-switcher-op-hint-enabled <true|false>",
                        "/leaderboard config set web-switcher-op-hint-enabled ", "开关 OP 进服时的服务器切换名称提醒");
                helpCommand(source, "/leaderboard config set web-port-fallback-enabled <true|false>",
                        "/leaderboard config set web-port-fallback-enabled ", "端口被占用时启用共享网页端口中继，关闭后第二个服务器不会启动网页");
                helpCommand(source, "/leaderboard ratelimit clear", "/leaderboard ratelimit clear", "立即清除全部网页限流记录");
                helpCommand(source, "/leaderboard cache <status|reload>", "/leaderboard cache ",
                        "查看缓存状态；仅首次安装或缓存无效时自动扫描，reload 手动重新读取统计文件");
                helpCommand(source, "/leaderboard export <all|daily|weekly|monthly|yearly> <榜单>",
                        "/leaderboard export all playtime", "导出当前服务器筛选后的排行榜为 Excel 可打开的 CSV 表格");
                helpCommand(source, "/leaderboard cache threads <0-256|status>", "/leaderboard cache threads ",
                        "设置或查看历史扫描线程；0 自动，最多使用 50% 逻辑处理器；下次 cache reload 时生效");
                helpCommand(source, "/leaderboard config set avatar-cache-enabled <true|false>",
                        "/leaderboard config set avatar-cache-enabled ", "开关玩家头像缓存；重新进服时生效");
            }
            case "admin-config" -> {
                if (!op) return 0;
                helpCommand(source, "/leaderboard config set join-message-mode <simple|detailed>",
                        "/leaderboard config set join-message-mode ", localized(source, "join.mode_help"));
                helpCommand(source, "/leaderboard bedrock <true|false|status>", "/leaderboard bedrock ",
                        "开关破基岩榜；默认关闭，启用后记录周围 5 格内消失的基岩");
                helpCommand(source, "/leaderboard config list", "/leaderboard config list", "列出全部配置、当前值和所属文件");
                helpCommand(source, "/leaderboard config get <配置项>", "/leaderboard config get ", "查看配置当前值、用途与生效方式");
                helpCommand(source, "/leaderboard config set <配置项> <值>", "/leaderboard config set ", "修改并保存配置；网页项会重启网页服务");
                helpCommand(source, "/leaderboard config reload", "/leaderboard config reload", "重新读取主配置和网页配置并立即应用");
                source.sendFeedback(() -> clickable("[" + localized(source, "help.open_config") + "]", Formatting.LIGHT_PURPLE,
                        "/leaderboard help config", localized(source, "help.tooltip.config")), false);
            }
            case "config" -> {
                if (!op) return 0;
                Text modules = clickable("[" + localized(source, "help.config_general") + "]", Formatting.GOLD,
                                "/leaderboard help config general", localized(source, "help.tooltip.config_general"))
                        .copy().append(Text.literal(" "))
                        .append(clickable("[" + localized(source, "help.config_scoreboard") + "]", Formatting.YELLOW,
                                "/leaderboard help config scoreboard", localized(source, "help.tooltip.config_scoreboard")))
                        .append(Text.literal(" "))
                        .append(clickable("[" + localized(source, "help.config_web") + "]", Formatting.AQUA,
                                "/leaderboard help config web", localized(source, "help.tooltip.config_web")));
                source.sendFeedback(() -> modules, false);
                helpCommand(source, "/leaderboard config list", "/leaderboard config list", "列出所有配置当前值");
                helpCommand(source, "/leaderboard config get <配置项>", "/leaderboard config get ", "查看单项当前值和用途");
                helpCommand(source, "/leaderboard config reload", "/leaderboard config reload", "重新读取配置并重启网页服务");
                helpCommand(source, "/leaderboard cache reload", "/leaderboard cache reload", "重新扫描历史统计并应用缓存相关修改");
            }
            case "config-general" -> {
                if (!op) return 0;
                configHelpHeader(source);
                configHelp(source, "welcome-enabled");
                configHelp(source, "welcome-name");
                configHelp(source, "default-language");
                configHelp(source, "join-menu-enabled");
                configHelp(source, "join-message-mode");
                configHelp(source, "join-web-hint-enabled");
                configHelp(source, "website-button-enabled");
                configHelp(source, "web-public-address");
                configHelp(source, "help-visibility");
                configHelp(source, "mod-whitelist-enabled");
                configHelp(source, "scoreboard-recipient-filter");
                configHelp(source, "avatar-cache-enabled");
                configHelp(source, "avatar-cache-days");
            }
            case "config-scoreboard" -> {
                if (!op) return 0;
                configHelpHeader(source);
                configHelp(source, "foreign-scoreboard-blocking-mode");
                configHelp(source, "restore-scoreboard-on-join");
                configHelp(source, "look-up-sneak-menu-enabled");
                configHelp(source, "carousel-enabled");
                configHelp(source, "carousel-interval-seconds");
                configHelp(source, "carousel-color-follow-metric");
                configHelp(source, "client-scoreboard-show-zero");
                configHelp(source, "scoreboard-switch-message-enabled");
                configHelp(source, "scoreboard-name-color-enabled");
                configHelp(source, "player-name-color-render-mode");
                for (Metric metric : orderedMenuMetrics()) configHelp(source, "metric-label-" + metric.command);
                for (Metric metric : orderedMenuMetrics()) configHelp(source, "metric-color-" + metric.command);
                configHelp(source, "scoreboard-title-color-enabled");
                configHelp(source, "scoreboard-live-update-enabled");
                configHelp(source, "scoreboard-live-update-window-seconds");
                configHelp(source, "scoreboard-live-update-threshold");
                configHelp(source, "scoreboard-live-update-throttle-seconds");
                configHelp(source, "history-files-per-second");
                configHelp(source, "history-scan-threads");
            }
            case "config-web" -> {
                if (!op) return 0;
                configHelpHeader(source);
                configHelp(source, "web-enabled");
                configHelp(source, "host");
                 configHelp(source, "port");
                 configHelp(source, "web-port-fallback-enabled");
                 configHelp(source, "server-name");
                 configHelp(source, "web-default-language");
                 configHelp(source, "web-switcher-name");
                 configHelp(source, "web-switcher-weight");
                 configHelp(source, "web-switcher-peers");
                 configHelp(source, "web-switcher-op-hint-enabled");
                configHelp(source, "website-icon");
                configHelp(source, "web-data-requests-per-second");
                configHelp(source, "web-icon-request-interval-seconds");
                configHelp(source, "web-ranking-refresh-interval-seconds");
                configHelp(source, "web-theme-follow-icon");
                configHelp(source, "web-theme-base");
                configHelp(source, "web-theme-background");
                configHelp(source, "web-theme-surface");
                configHelp(source, "web-theme-primary");
                configHelp(source, "web-theme-secondary");
                configHelp(source, "web-theme-text");
                configHelp(source, "web-theme-muted");
                configHelp(source, "web-theme-border");
                configHelp(source, "web-theme-success");
                configHelp(source, "web-theme-danger");
            }
        }
        return 1;
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildMetricDataCommands() {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("data")
                .requires(source -> CommandPermissionCompat.has(source, 2));
        root.then(playerMetricCommand("get", false, false));
        root.then(playerMetricCommand("set", true, false));
        root.then(playerMetricCommand("add", true, true));
        LiteralArgumentBuilder<ServerCommandSource> reset = CommandManager.literal("reset");
        com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, EntitySelector> resetPlayer =
                CommandManager.argument("player", EntityArgumentType.player());
        for (Metric metric : Metric.values()) {
            resetPlayer.then(CommandManager.literal(metric.command)
                    .executes(context -> resetMetricData(context.getSource(),
                            EntityArgumentType.getPlayer(context, "player"), metric)));
        }
        resetPlayer.then(CommandManager.literal("all")
                .executes(context -> resetAllMetricData(context.getSource(),
                        EntityArgumentType.getPlayer(context, "player"))));
        reset.then(resetPlayer);
        root.then(reset);
        return root;
    }

    private LiteralArgumentBuilder<ServerCommandSource> playerMetricCommand(String operation,
                                                                             boolean takesValue,
                                                                             boolean add) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal(operation);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, EntitySelector> playerNode =
                CommandManager.argument("player", EntityArgumentType.player());
        for (Metric metric : Metric.values()) {
            LiteralArgumentBuilder<ServerCommandSource> metricNode = CommandManager.literal(metric.command);
            if (takesValue) {
                metricNode.then(CommandManager.argument("value", LongArgumentType.longArg(0L))
                        .executes(context -> setMetricData(context.getSource(),
                                EntityArgumentType.getPlayer(context, "player"), metric,
                                LongArgumentType.getLong(context, "value"), add)));
            } else {
                metricNode.executes(context -> getMetricData(context.getSource(),
                        EntityArgumentType.getPlayer(context, "player"), metric));
            }
            playerNode.then(metricNode);
        }
        root.then(playerNode);
        return root;
    }

    private int getMetricData(ServerCommandSource source, ServerPlayerEntity player, Metric metric) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        long value = state.applyCustomMetrics(StatReader.fromPlayer(player, metric)).value(metric);
        source.sendFeedback(() -> Text.literal(localized(source, "data.get", player.getName().getString(),
                localizedMetric(source, metric), String.format(java.util.Locale.ROOT, "%,d", value), format(metric, value)))
                .formatted(Formatting.AQUA), false);
        return 1;
    }

    private int setMetricData(ServerCommandSource source, ServerPlayerEntity player, Metric metric,
                              long input, boolean add) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        long value = input;
        if (add) {
            long current = state.applyCustomMetrics(StatReader.fromPlayer(player, metric)).value(metric);
            try { value = Math.addExact(current, input); }
            catch (ArithmeticException ignored) { value = Long.MAX_VALUE; }
        }
        state.setMetricOverride(player.getUuid(), metric, value);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        long displayValue = value;
        String feedback = localized(source, add ? "data.add" : "data.set", player.getName().getString(),
                localizedMetric(source, metric), String.format(java.util.Locale.ROOT, "%,d", displayValue),
                format(metric, displayValue), "/leaderboard data reset " + player.getName().getString() + " " + metric.command);
        source.sendFeedback(() -> Text.literal(feedback)
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private int resetMetricData(ServerCommandSource source, ServerPlayerEntity player, Metric metric) {
        boolean removed = LeaderboardState.get(source.getServer()).clearMetricOverride(player.getUuid(), metric);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        String feedback = localized(source, removed ? "data.reset" : "data.reset.none",
                player.getName().getString(), localizedMetric(source, metric));
        source.sendFeedback(() -> Text.literal(feedback)
                .formatted(removed ? Formatting.GREEN : Formatting.YELLOW), true);
        return 1;
    }

    private int resetAllMetricData(ServerCommandSource source, ServerPlayerEntity player) {
        int removed = LeaderboardState.get(source.getServer()).clearMetricOverrides(player.getUuid());
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        String feedback = localized(source, "data.reset.all", player.getName().getString(), removed);
        source.sendFeedback(() -> Text.literal(feedback)
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private static void helpCommand(ServerCommandSource source, String label, String suggestion, String description) {
        if (source.getEntity() instanceof ServerPlayerEntity player) description = RankBoardLanguage.help(player, description);
        String clickHint = source.getEntity() instanceof ServerPlayerEntity player
                ? RankBoardLanguage.text(player, "help.click_to_fill") : "点击填入指令栏";
        String visibleLabel = commandSyntax(source, label);
        String visibleSuggestion = commandSyntax(source, suggestion);
        Text command = Text.literal(visibleLabel).setStyle(TextCompat.suggest(
                Style.EMPTY.withColor(Formatting.WHITE), visibleSuggestion, Text.literal(clickHint)));
        Text annotation = Text.literal(" - " + description).setStyle(TextCompat.suggest(
                Style.EMPTY.withColor(Formatting.GRAY), visibleSuggestion, Text.literal(clickHint)));
        Text line = command.copy().append(annotation);
        source.sendFeedback(() -> line, false);
    }

    private static void configHelpHeader(ServerCommandSource source) {
        source.sendFeedback(() -> clickable("[" + localized(source, "help.back_config") + "]", Formatting.GRAY,
                "/leaderboard help config", localized(source, "help.tooltip.config")), false);
    }

    private static void configHelp(ServerCommandSource source, String key) {
        String effect = switch (key) {
            case "history-files-per-second", "history-scan-threads", "mod-whitelist-enabled" ->
                    "；不会自动读取统计文件，需执行 /leaderboard cache reload";
            case "host", "port", "server-name", "website-icon", "web-data-requests-per-second",
                    "web-icon-request-interval-seconds", "web-ranking-refresh-interval-seconds",
                    "web-switcher-name", "web-switcher-weight", "web-switcher-peers",
                    "web-switcher-op-hint-enabled", "web-port-fallback-enabled", "web-enabled" ->
                    "；修改后执行 /leaderboard config reload";
            default -> "；写入后立即生效";
        };
        helpCommand(source, "/leaderboard config set " + key + " <值>",
                "/leaderboard config set " + key + " ", RankBoardConfig.description(key) + effect);
    }

    private void beginMenu(ServerCommandSource source, String title) {
        source.sendFeedback(() -> Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n◆ " + title
                + " ◆\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.GOLD), false);
    }
    private int menu(ServerCommandSource source) {
        beginMenu(source, "RankBoard 功能菜单");
        Text playerRow = clickable("[查询排行榜]", Formatting.YELLOW, "/leaderboard menu ranking", "选择时间、分类和榜单后，直接在聊天框显示排名")
                .copy().append(Text.literal(" "))
                .append(clickable("[分类浏览榜单]", Formatting.AQUA, "/leaderboard menu core", "浏览榜单并进入个人、全服或指定玩家侧边栏操作"))
                .append(Text.literal(" "))
                .append(clickable("[查询我的统计]", Formatting.GOLD, "/leaderboard mine all", "只查看自己的全部统计，不与其他玩家排名"));
        source.sendFeedback(() -> playerRow, false);

        boolean boardEnabled = false;
        try {
            UUID uuid = source.getEntity() == null ? null : source.getEntity().getUuid();
            LeaderboardState state = LeaderboardState.get(source.getServer());
            LeaderboardState.BoardPreference preference = state.boardPreference(uuid);
            boardEnabled = preference != null && preference.enabled();
        } catch (RuntimeException ignored) { }
        Text actionRow = clickable("[个人侧边栏]", Formatting.GREEN, "/leaderboard menu personal", "选择时间、分类和榜单，设置自己的侧边栏")
                .copy().append(Text.literal(" "))
                .append(clickable(boardEnabled ? "[关闭个人侧边栏]" : "[恢复个人侧边栏]",
                        boardEnabled ? Formatting.RED : Formatting.GREEN,
                        boardEnabled ? "/leaderboard display off" : "/leaderboard display on",
                        boardEnabled ? "立即关闭自己的个人侧边栏" : "立即恢复关闭前的个人侧边栏"));
        source.sendFeedback(() -> actionRow, false);
        Text utilityRow = clickable("[轮播]", Formatting.AQUA, "/leaderboard menu carousel", "选择开启、关闭或查询轮播状态")
                .copy().append(Text.literal(" "))
                .append(clickable("[抬头蹲起菜单]", Formatting.LIGHT_PURPLE, "/leaderboard menu lookmenu", "选择开启、关闭或查询抬头蹲起菜单状态"));
        if (RankBoardConfig.get().websiteButtonEnabled) {
            utilityRow = utilityRow.copy().append(Text.literal(" ")).append(websiteButton(source));
        }
        if (RankBoardConfig.get().helpVisible(source)) {
            utilityRow = utilityRow.copy().append(Text.literal(" "))
                    .append(clickable("[Help]", Formatting.GRAY, "/leaderboard help", "查看全部命令说明"));
        }
        Text finalUtilityRow = utilityRow;
        source.sendFeedback(() -> finalUtilityRow, false);
        if (CommandPermissionCompat.has(source, 2)) {
            Text adminRow = clickable("[全服侧边栏]", Formatting.GOLD, "/leaderboard menu server", "设置或清除全服共享侧边栏")
                    .copy().append(Text.literal(" "))
                    .append(clickable("[清除全服侧边栏]", Formatting.RED, "/leaderboard scoreboard clear", "立即关闭全服共享侧边栏"))
                    .append(Text.literal(" "))
                    .append(clickable("[玩家过滤]", Formatting.AQUA, "/leaderboard playerfilter", "手动隐藏或允许显示玩家"))
                    .append(Text.literal(" "))
                    .append(clickable("[机器人分组]", Formatting.LIGHT_PURPLE, "/leaderboard botgroup", "把玩家移入机器人分组"));
            source.sendFeedback(() -> adminRow, false);
        }

        return 1;
    }

    private int carouselMenu(ServerCommandSource source) {
        beginMenu(source, "轮播");
        Text actions = clickable("[开启轮播]", Formatting.GREEN, "/leaderboard carousel on", "立即开启自己的榜单轮播")
                .copy().append(Text.literal(" "))
                .append(clickable("[关闭轮播]", Formatting.RED, "/leaderboard carousel off", "立即关闭自己的榜单轮播"))
                .append(Text.literal(" "))
                .append(clickable("[查询状态]", Formatting.GRAY, "/leaderboard carousel status", "查询当前轮播状态"));
        source.sendFeedback(() -> actions, false);
        source.sendFeedback(() -> clickable("[返回功能菜单]", Formatting.GRAY, "/leaderboard menu home", "返回功能菜单"), false);
        return 1;
    }

    private int lookMenu(ServerCommandSource source) {
        beginMenu(source, "抬头蹲起菜单");
        Text actions = clickable("[开启]", Formatting.GREEN, "/leaderboard lookmenu true", "开启抬头+蹲起打开菜单")
                .copy().append(Text.literal(" "))
                .append(clickable("[关闭]", Formatting.RED, "/leaderboard lookmenu false", "关闭抬头+蹲起打开菜单"))
                .append(Text.literal(" "))
                .append(clickable("[查询状态]", Formatting.GRAY, "/leaderboard lookmenu status", "查询当前设置"));
        source.sendFeedback(() -> actions, false);
        source.sendFeedback(() -> clickable("[返回功能菜单]", Formatting.GRAY, "/leaderboard menu home", "返回功能菜单"), false);
        return 1;
    }
    private int menu(ServerCommandSource source, String group) {
        boolean boardEnabled = false;
        try {
            LeaderboardState.BoardPreference preference = LeaderboardState.get(source.getServer())
                    .boardPreference(source.getEntity() == null ? null : source.getEntity().getUuid());
            boardEnabled = preference != null && preference.enabled();
        } catch (RuntimeException ignored) { }
        beginMenu(source, "RankBoard 快捷菜单");
        Text statsRow = clickable("[我的总计]", Formatting.GOLD, "/leaderboard mine all", "查看自己的全部统计分数")
                .copy().append(Text.literal(" "))
                .append(clickable("[今日]", Formatting.YELLOW, "/leaderboard mine day", "查看自己的今日统计"))
                .append(Text.literal(" ")).append(clickable("[本周]", Formatting.AQUA, "/leaderboard mine week", "查看自己的本周统计"))
                .append(Text.literal(" ")).append(clickable("[本月]", Formatting.LIGHT_PURPLE, "/leaderboard mine month", "查看自己的本月统计"));
        source.sendFeedback(() -> statsRow, false);
        Text firstRow = clickable(boardEnabled ? "[关闭侧边栏]" : "[恢复侧边栏]",
                boardEnabled ? Formatting.RED : Formatting.GREEN,
                boardEnabled ? "/leaderboard display off" : "/leaderboard display on",
                boardEnabled ? "关闭自己的客户端计分板" : "恢复关闭前的客户端计分板");
        try {
            boolean enabled = LeaderboardState.get(source.getServer()).isLookMenuEnabled(source.getEntity() == null
                    ? null : source.getEntity().getUuid());
            firstRow = firstRow.copy().append(Text.literal(" ")).append(clickable(
                    enabled ? "[关闭抬头蹲起]" : "[开启抬头蹲起]",
                    enabled ? Formatting.RED : Formatting.GREEN,
                    "/leaderboard lookmenu " + !enabled,
                    enabled ? "关闭自己的抬头+蹲起打开菜单" : "开启自己的抬头+蹲起打开菜单"));
        } catch (RuntimeException ignored) { }
        Text finalFirstRow = firstRow;
        source.sendFeedback(() -> finalFirstRow, false);

        Text secondRow = Text.empty();
        boolean hasSecondRowButton = false;
        if (CommandPermissionCompat.has(source, 2)) {
            secondRow = clickable("[设置全局榜单]", Formatting.GOLD, "/leaderboard menu server", "进入全服侧边栏设置菜单")
                    .copy().append(Text.literal(" "))
                    .append(clickable("[清除全局榜单]", Formatting.RED, "/leaderboard scoreboard clear", "清除原版全局侧边栏"));
            hasSecondRowButton = true;
        }
        if (RankBoardConfig.get().carouselEnabled) {
            if (hasSecondRowButton) secondRow = secondRow.copy().append(Text.literal(" "));
            secondRow = secondRow.copy().append(clickable(
                    "[轮播]", Formatting.AQUA, "/leaderboard carousel on", "自动轮播当前周期的榜单"));
            hasSecondRowButton = true;
        }
        if (RankBoardConfig.get().websiteButtonEnabled) {
            if (hasSecondRowButton) secondRow = secondRow.copy().append(Text.literal(" "));
            secondRow = secondRow.copy().append(websiteButton(source));
            hasSecondRowButton = true;
        }
        if (RankBoardConfig.get().helpVisible(source)) {
            if (hasSecondRowButton) secondRow = secondRow.copy().append(Text.literal(" "));
            secondRow = secondRow.copy().append(clickable(
                    "[help]", Formatting.GREEN, "/leaderboard help", "查看 RankBoard 帮助"));
            hasSecondRowButton = true;
        }
        if (hasSecondRowButton) {
            Text finalSecondRow = secondRow;
            source.sendFeedback(() -> finalSecondRow, false);
        }

        sendMenuCategories(source, group);
        if (group.equals("fun")) {
            Text funGroups = clickable("[综合受害榜]", Formatting.GOLD, "/leaderboard menu fun overview",
                            "查看按生物族群合并统计的趣味榜单")
                    .copy().append(Text.literal(" "))
                    .append(clickable("[单种生物受害榜]", Formatting.LIGHT_PURPLE, "/leaderboard menu fun victims",
                            "按每一种可击杀玩家的生物查看受害次数"));
            source.sendFeedback(() -> funGroups, false);
            source.sendFeedback(() -> Text.literal("选择一个二级榜单合集，避免大量趣味榜单挤占常用列表。")
                    .formatted(Formatting.GRAY), false);
            BoardService.sendForeignScoreboardPrompt(source);
            return 1;
        }
        List<Metric> menuMetrics = menuMetrics(group);
        int visible = 0;
        for (int start = 0; start < menuMetrics.size(); start += 4) {
            visible += sendMetricMenuRow(source, group, menuMetrics.subList(
                    start, Math.min(start + 4, menuMetrics.size())).toArray(Metric[]::new));
        }
        if (visible == 0) {
            source.sendFeedback(() -> Text.literal("所有榜单显示均已被 OP 禁用。\n").formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.literal("点击榜单后可选择时间，并进行侧边栏操作。")
                .formatted(Formatting.GRAY), false);
        BoardService.sendForeignScoreboardPrompt(source);
        return 1;
    }

    private int periodPickerMenu(ServerCommandSource source, int mode) {
        String command = menuModeCommand(mode);
        beginMenu(source, menuModeTitle(mode) + " · 选择时间");
        Text line = Text.empty();
        boolean added = false;
        for (Period period : Period.values()) {
            if (added) line = line.copy().append(Text.literal(" "));
            line = line.copy().append(clickable("[" + period.menuLabel + "]", Formatting.AQUA,
                    "/leaderboard menu " + command + " " + period.shortCommand, "选择" + period.label + "统计"));
            added = true;
        }
        Text finalLine = line;
        source.sendFeedback(() -> finalLine, false);
        source.sendFeedback(() -> clickable("[返回功能菜单]", Formatting.GRAY, "/leaderboard menu home", "返回功能菜单"), false);
        return 1;
    }

    private int categoryPickerMenu(ServerCommandSource source, int mode, Period period) {
        String command = menuModeCommand(mode);
        beginMenu(source, menuModeTitle(mode) + " · " + period.label + " · 选择分类");
        Text line = Text.empty();
        boolean added = false;
        String[][] groups = {{"core", "常用"}, {"combat", "战斗"}, {"build", "建造"},
                {"life", "生存"}, {"explore", "探索"}, {"fun", "生物受害"}, {"all", "全部"}};
        for (String[] group : groups) {
            if (added) line = line.copy().append(Text.literal(" "));
            line = line.copy().append(clickable("[" + group[1] + "]",
                    group[0].equals("all") ? Formatting.GOLD : Formatting.AQUA,
                    "/leaderboard menu " + command + " " + period.shortCommand + " " + group[0],
                    "显示" + group[1] + "分类榜单"));
            added = true;
        }
        Text finalLine = line;
        source.sendFeedback(() -> finalLine, false);
        source.sendFeedback(() -> clickable("[返回时间选择]", Formatting.GRAY,
                "/leaderboard menu " + command, "重新选择统计时间"), false);
        return 1;
    }

    private int metricPickerMenu(ServerCommandSource source, int mode, Period period, String group) {
        String command = menuModeCommand(mode);
        beginMenu(source, menuModeTitle(mode) + " · " + period.label + " · 选择榜单");
        if (group.equals("fun")) {
            Text subgroups = clickable("[综合受害榜]", Formatting.GOLD,
                            "/leaderboard menu " + command + " " + period.shortCommand + " fun overview",
                            "选择按族群合并的趣味榜单")
                    .copy().append(Text.literal(" "))
                    .append(clickable("[单种生物受害榜]", Formatting.LIGHT_PURPLE,
                            "/leaderboard menu " + command + " " + period.shortCommand + " fun victims",
                            "选择具体生物的受害者榜单"));
            source.sendFeedback(() -> subgroups, false);
            source.sendFeedback(() -> clickable("[返回分类选择]", Formatting.GRAY,
                    "/leaderboard menu " + command + " " + period.shortCommand, "重新选择分类"), false);
            return 1;
        }
        List<Metric> metrics = menuMetrics(group);
        for (int start = 0; start < metrics.size(); start += 4) {
            Text line = Text.empty();
            int visible = 0;
            for (Metric metric : metrics.subList(start, Math.min(start + 4, metrics.size()))) {
                if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
                if (visible++ > 0) line = line.copy().append(Text.literal(" "));
                line = line.copy().append(clickable("[" + metric.label() + "]", metric,
                        "/leaderboard menu " + command + " " + period.shortCommand + " " + group + " " + metric.command,
                        mode == 0 ? "直接在聊天框显示该排行榜前 10 名"
                                : mode == 1 ? "设为自己的个人侧边栏" : "设为全服共享侧边栏"));
            }
            if (visible > 0) {
                Text finalLine = line;
                source.sendFeedback(() -> finalLine, false);
            }
        }
        source.sendFeedback(() -> clickable("[返回分类选择]", Formatting.GRAY,
                "/leaderboard menu " + command + " " + period.shortCommand, "重新选择分类"), false);
        return 1;
    }

    private int funBrowseMetricsMenu(ServerCommandSource source, boolean victims) {
        String title = victims ? "单种生物受害榜" : "综合受害榜";
        beginMenu(source, "生物受害 · " + title);
        List<Metric> metrics = victims ? funVictimMetrics() : funOverviewMetrics();
        for (int start = 0; start < metrics.size(); start += 4) {
            sendMetricMenuRow(source, "fun", metrics.subList(start, Math.min(start + 4, metrics.size())).toArray(Metric[]::new));
        }
        source.sendFeedback(() -> clickable("[返回生物受害分类]", Formatting.GRAY,
                "/leaderboard menu fun", "返回二级榜单合集选择"), false);
        return 1;
    }

    private int funWizardMetricsMenu(ServerCommandSource source, int mode, Period period, boolean victims) {
        String command = menuModeCommand(mode);
        String title = victims ? "单种生物受害榜" : "综合受害榜";
        beginMenu(source, menuModeTitle(mode) + " · " + period.label + " · " + title);
        List<Metric> metrics = victims ? funVictimMetrics() : funOverviewMetrics();
        for (int start = 0; start < metrics.size(); start += 4) {
            Text line = Text.empty();
            int visible = 0;
            for (Metric metric : metrics.subList(start, Math.min(start + 4, metrics.size()))) {
                if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
                if (visible++ > 0) line = line.copy().append(Text.literal(" "));
                line = line.copy().append(clickable("[" + metric.label() + "]", metric,
                        "/leaderboard menu " + command + " " + period.shortCommand + " fun " + metric.command,
                        mode == 0 ? "直接在聊天框显示该排行榜前 10 名"
                                : mode == 1 ? "设为自己的个人侧边栏" : "设为全服共享侧边栏"));
            }
            if (visible > 0) {
                Text finalLine = line;
                source.sendFeedback(() -> finalLine, false);
            }
        }
        source.sendFeedback(() -> clickable("[返回二级合集]", Formatting.GRAY,
                "/leaderboard menu " + command + " " + period.shortCommand + " fun", "重新选择生物受害合集"), false);
        return 1;
    }

    private int browseMetricMenu(ServerCommandSource source, String group, Metric metric) {
        beginMenu(source, metric.label() + " · 选择时间");
        Text line = Text.empty();
        boolean added = false;
        for (Period period : Period.values()) {
            if (added) line = line.copy().append(Text.literal(" "));
            line = line.copy().append(clickable("[" + period.menuLabel + "]", Formatting.AQUA,
                    "/leaderboard menu " + group + " " + metric.command + " " + period.shortCommand,
                    "打开" + period.label + metric.label() + "操作页"));
            added = true;
        }
        Text finalLine = line;
        source.sendFeedback(() -> finalLine, false);
        source.sendFeedback(() -> clickable("[返回分类榜单]", Formatting.GRAY,
                "/leaderboard menu " + group, "返回当前榜单分类"), false);
        return 1;
    }
    private int browseMetricActionMenu(ServerCommandSource source, String group, Metric metric, Period period) {
        beginMenu(source, period.label + metric.label() + " · 选择操作");
        Text actions = clickable("[设为个人侧边栏]", Formatting.GREEN,
                "/leaderboard display show " + period.command + " " + group + " " + metric.command,
                "把自己的侧边栏切换为该榜单");
        if (CommandPermissionCompat.has(source, 2)) {
            actions = actions.copy().append(Text.literal(" ")).append(clickable("[设为全服侧边栏]", Formatting.GOLD,
                            "/leaderboard scoreboard show " + period.command + " " + group + " " + metric.command,
                            "设为全服共享侧边栏"))
                    .append(Text.literal(" ")).append(clickable("[指定玩家侧边栏]", Formatting.LIGHT_PURPLE,
                            "/leaderboard menu " + group + " " + metric.command + " " + period.shortCommand + " players",
                            "选择在线玩家，为其显示该榜单或关闭其侧边栏"));
        }
        Text finalActions = actions;
        source.sendFeedback(() -> finalActions, false);
        source.sendFeedback(() -> clickable("[返回时间选择]", Formatting.GRAY,
                "/leaderboard menu " + group + " " + metric.command, "重新选择统计时间"), false);
        return 1;
    }

    private int browsePlayersMenu(ServerCommandSource source, String group, Metric metric, Period period) {
        List<ServerPlayerEntity> players = source.getServer().getPlayerManager().getPlayerList().stream()
                .sorted(Comparator.comparing(player -> player.getName().getString(), String.CASE_INSENSITIVE_ORDER)).toList();
        beginMenu(source, "指定玩家 · " + period.label + metric.label());
        if (players.isEmpty()) source.sendFeedback(() -> Text.literal("当前没有在线玩家。").formatted(Formatting.GRAY), false);
        for (int start = 0; start < players.size(); start += 4) {
            Text line = Text.empty();
            for (int index = start; index < Math.min(start + 4, players.size()); index++) {
                ServerPlayerEntity player = players.get(index);
                if (index > start) line = line.copy().append(Text.literal(" "));
                String name = player.getName().getString();
                line = line.copy().append(clickable("[" + name + "]", Formatting.AQUA,
                        "/leaderboard menu " + group + " " + metric.command + " " + period.shortCommand + " players " + name,
                        "管理 " + name + " 的个人侧边栏"));
            }
            Text finalLine = line;
            source.sendFeedback(() -> finalLine, false);
        }
        source.sendFeedback(() -> clickable("[返回榜单操作]", Formatting.GRAY,
                "/leaderboard menu " + group + " " + metric.command + " " + period.shortCommand,
                "返回榜单操作页"), false);
        return 1;
    }

    private int browsePlayerMenu(ServerCommandSource source, String group, Metric metric, Period period,
                                  ServerPlayerEntity player) {
        String name = player.getName().getString();
        beginMenu(source, name + " · 侧边栏操作");
        Text actions = clickable("[显示该榜单]", Formatting.GREEN,
                        "/leaderboard display show " + period.command + " " + group + " " + metric.command + " " + name,
                        "为 " + name + " 显示" + period.label + metric.label())
                .copy().append(Text.literal(" ")).append(clickable("[关闭个人侧边栏]", Formatting.RED,
                        "/leaderboard display off " + name, "关闭 " + name + " 的个人侧边栏"));
        source.sendFeedback(() -> actions, false);
        source.sendFeedback(() -> clickable("[返回玩家列表]", Formatting.GRAY,
                "/leaderboard menu " + group + " " + metric.command + " " + period.shortCommand + " players",
                "返回在线玩家列表"), false);
        return 1;
    }

    private static String menuModeCommand(int mode) {
        return mode == 0 ? "ranking" : mode == 1 ? "personal" : "server";
    }

    private static String menuModeTitle(int mode) {
        return mode == 0 ? "查询排行榜" : mode == 1 ? "个人侧边栏" : "全服侧边栏";
    }
    private void sendMenuCategories(ServerCommandSource source, String selected) {
        Text line = clickable("[主菜单]", Formatting.GRAY, "/leaderboard menu home", "返回功能菜单")
                .copy().append(Text.literal(" 分类 ").formatted(Formatting.GRAY));
        String[][] groups = {
                {"core", "常用"}, {"combat", "战斗"}, {"build", "建造"},
                {"life", "生存"}, {"explore", "探索"}, {"fun", "生物受害"}, {"all", "全部"}
        };
        for (String[] group : groups) {
            line = line.copy().append(clickable("[" + group[1] + "]",
                    group[0].equals(selected) ? Formatting.GOLD : Formatting.AQUA,
                    "/leaderboard menu " + group[0], "只显示" + group[1] + "分类榜单")).append(Text.literal(" "));
        }
        Text finalLine = line;
        source.sendFeedback(() -> finalLine, false);
    }

    private static List<Metric> menuMetrics(String group) {
        return switch (group) {
            case "combat" -> List.of(Metric.KILLS, Metric.PVP_KILLS, Metric.DEATHS, Metric.DAMAGE_TAKEN,
                    Metric.DAMAGE_DEALT, Metric.SHIELD_BLOCKED, Metric.TOTEM_USED, Metric.TARGET_HITS);
            case "build" -> List.of(Metric.MINED, Metric.PLACED, Metric.ORES_MINED, Metric.CRAFTED,
                    Metric.REDSTONE_PLACED, Metric.TOOLS_BROKEN, Metric.BEDROCK_BROKEN);
            case "life" -> List.of(Metric.FOOD, Metric.FISHING, Metric.ANIMALS_BRED, Metric.SLEPT,
                    Metric.TRADES, Metric.ENCHANTED);
            case "fun" -> funMetrics();
            case "fun_overview" -> funOverviewMetrics();
            case "fun_victims" -> funVictimMetrics();
            case "explore" -> List.of(Metric.TRAVEL_DISTANCE, Metric.ELYTRA_DISTANCE, Metric.JUMPS,
                    Metric.PICKED_UP, Metric.DROPPED, Metric.MUSIC_PLAYED);
            case "all" -> orderedMenuMetrics();
            default -> List.of(Metric.PLAY_TIME, Metric.AFK_TIME, Metric.MINED, Metric.PLACED, Metric.KILLS,
                    Metric.DEATHS, Metric.TRAVEL_DISTANCE, Metric.ORES_MINED, Metric.TRADES);
        };
    }

    /** Category used by the web sidebar; the game menu uses the same groups. */
    static String metricCategory(Metric metric) {
        if (metric.isFun()) return "fun";
        return switch (metric) {
            case PLAY_TIME, AFK_TIME, MINED, PLACED, KILLS, PVP_KILLS, DAMAGE_TAKEN, DAMAGE_DEALT, DEATHS, TRAVEL_DISTANCE, ORES_MINED, TRADES -> "core";
            case SHIELD_BLOCKED, TOTEM_USED, TARGET_HITS -> "combat";
            case CRAFTED, REDSTONE_PLACED, TOOLS_BROKEN, BEDROCK_BROKEN -> "build";
            case FOOD, FISHING, ANIMALS_BRED, SLEPT, ENCHANTED -> "life";
            case ELYTRA_DISTANCE, JUMPS, PICKED_UP, DROPPED, MUSIC_PLAYED -> "explore";
            default -> "other";
        };
    }

    private static List<Metric> funOverviewMetrics() {
        return java.util.Arrays.stream(Metric.values())
                .filter(metric -> metric.isFun() && metric.victimEntityId() == null).toList();
    }

    private static List<Metric> funVictimMetrics() {
        return java.util.Arrays.stream(Metric.values())
                .filter(metric -> metric.victimEntityId() != null).toList();
    }

    private static List<Metric> funMetrics() {
        return java.util.Arrays.stream(Metric.values()).filter(Metric::isFun).toList();
    }

    private static List<Metric> orderedMenuMetrics() {
        List<Metric> ordered = new java.util.ArrayList<>(List.of(
                Metric.ELYTRA_DISTANCE, Metric.JUMPS, Metric.MINED, Metric.PLACED,
                Metric.FISHING, Metric.CRAFTED, Metric.TRADES, Metric.PLAY_TIME,
                Metric.KILLS, Metric.PVP_KILLS, Metric.DEATHS, Metric.DAMAGE_TAKEN,
                Metric.DAMAGE_DEALT, Metric.PICKED_UP, Metric.FOOD, Metric.DROPPED,
                Metric.REDSTONE_PLACED));
        for (Metric metric : Metric.values()) {
            if (!metric.isFun() && !ordered.contains(metric)) ordered.add(metric);
        }
        return List.copyOf(ordered);
    }

    private int sendMetricMenuRow(ServerCommandSource source, String group, Metric... metrics) {
        Text line = Text.empty();
        int visible = 0;
        for (Metric metric : metrics) {
            if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
            Text button = clickable("[" + metric.label() + "]", metric,
                    "/leaderboard menu " + group + " " + metric.command,
                    "打开 " + metric.label() + " 的时间与显示操作");
            if (visible > 0) line = line.copy().append(Text.literal(" "));
            line = line.copy().append(button);
            visible++;
        }
        if (visible > 0) {
            Text finalLine = line;
            source.sendFeedback(() -> finalLine, false);
        }
        return visible;
    }

    private int metricIntroduction(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("=== " + localized(source, "menu.intro") + " ===")
                .formatted(Formatting.GOLD), false);
        for (Metric metric : Metric.values()) {
            if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
            String label = localizedMetric(source, metric);
            String description = localized(source, "metric.description." + metric.command);
            Text line = clickable("[" + label + "]", metric, "/leaderboard display show all " + metric.command,
                    localized(source, "menu.tooltip.metric").replace("{0}", label))
                    .copy().append(Text.literal(" " + description).formatted(Formatting.GRAY));
            source.sendFeedback(() -> line, false);
        }
        return 1;
    }

    private int showMyScores(ServerCommandSource source, Period period) {
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            LeaderboardState state = LeaderboardState.get(source.getServer());
            state.rollPeriods(source.getServer());
            BoardService.enableOverview(source, period);
            String label = period == Period.ALL ? "总计" : period.label;
            beginMenu(source, "我的分数 · " + label);
            if (period != Period.ALL && !state.isPeriodComplete(period)) {
                source.sendFeedback(() -> Text.literal("当前周期从首个可信基线开始，部分指标可能暂不可用。")
                        .formatted(Formatting.YELLOW), false);
            }
            for (Metric metric : orderedMenuMetrics()) {
                long raw = LeaderboardReadScope.read(player, metric);
                java.util.OptionalLong delta = state.periodDelta(period, player.getUuid(), metric, raw);
                if (delta.isEmpty()) {
                    source.sendFeedback(() -> RankBoardColors.text(metric.label() + "  ", metric)
                            .append(Text.literal("暂无可信基线").formatted(Formatting.GRAY)), false);
                    continue;
                }
                long score = delta.getAsLong();
                source.sendFeedback(() -> RankBoardColors.text(metric.label() + "  ", metric)
                        .append(Text.literal(format(metric, score)).formatted(Formatting.AQUA)), false);
            }
            Text periods = clickable("[总计]", Formatting.GOLD, "/leaderboard mine all", "查看累计分数")
                    .copy().append(Text.literal(" "))
                    .append(clickable("[本日]", Formatting.YELLOW, "/leaderboard mine day", "查看本日分数"))
                    .append(Text.literal(" "))
                    .append(clickable("[本周]", Formatting.AQUA, "/leaderboard mine week", "查看本周分数"))
                    .append(Text.literal(" "))
                    .append(clickable("[本月]", Formatting.LIGHT_PURPLE, "/leaderboard mine month", "查看本月分数"));
            source.sendFeedback(() -> periods, false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        } catch (RuntimeException exception) {
            source.sendError(Text.literal("个人分数读取失败：" + exception.getMessage()));
            return 0;
        }
    }
    private int listConfig(ServerCommandSource source) {
        beginMenu(source, "RankBoard 配置");
        for (String key : RankBoardConfig.optionKeys()) {
            String value = RankBoardConfig.value(key);
            source.sendFeedback(() -> Text.literal(key + " = " + (value.isEmpty() ? "(空/自动)" : value))
                    .formatted(RankBoardConfig.isWebOption(key) ? Formatting.AQUA : Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.literal("使用 /leaderboard config get <配置项> 查看说明；"
                + "使用 /leaderboard config set <配置项> <值> 修改。")
                .formatted(Formatting.DARK_GRAY), false);
        return RankBoardConfig.optionKeys().size();
    }

    private int getConfig(ServerCommandSource source, String key) {
        if (!RankBoardConfig.isKnownOption(key)) {
            source.sendError(Text.literal("未知配置项：" + key));
            return 0;
        }
        String value = RankBoardConfig.value(key);
        source.sendFeedback(() -> Text.literal(key + " = " + (value.isEmpty() ? "(空/自动)" : value))
                .formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal(RankBoardConfig.description(key)).formatted(Formatting.GRAY), false);
        return 1;
    }

    private int setCarouselColor(ServerCommandSource source, boolean followMetric) {
        try {
            String value = RankBoardConfig.set(source.getServer(), "carousel-color-follow-metric",
                    Boolean.toString(followMetric));
            source.sendFeedback(() -> Text.literal("已设置轮播标题颜色："
                    + (followMetric ? "跟随当前榜单颜色" : "固定青色")
                    + " (carousel-color-follow-metric=" + value + ")").formatted(Formatting.GREEN), true);
            return 1;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            source.sendError(Text.literal("轮播颜色设置失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int setCarouselColorStatus(ServerCommandSource source) {
        boolean followMetric = RankBoardConfig.get().carouselColorFollowMetric;
        source.sendFeedback(() -> Text.literal("轮播标题颜色："
                + (followMetric ? "跟随当前榜单颜色" : "固定青色")
                + " (carousel-color-follow-metric=" + followMetric + ")").formatted(Formatting.GRAY), false);
        return 1;
    }

    private int setWebThemeMode(ServerCommandSource source, boolean followIcon) {
        try {
            RankBoardConfig.set(source.getServer(), "web-theme-follow-icon", Boolean.toString(followIcon));
            if (!followIcon) RankBoardConfig.set(source.getServer(), "web-theme-base", "auto");
            boolean running = WebDashboard.restart(source.getServer());
            source.sendFeedback(() -> Text.literal("网页主题已切换为："
                    + (followIcon ? "读取服务器图标颜色" : "默认蓝色系")).formatted(Formatting.GREEN), true);
            if (!running) source.sendError(Text.literal("配置已保存，但网页服务重启失败。"));
            return running ? 1 : 0;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("网页主题设置失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int setWebThemeRgb(ServerCommandSource source, String color) {
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "web-theme-base", color);
            RankBoardConfig.set(source.getServer(), "web-theme-follow-icon", "false");
            boolean running = WebDashboard.restart(source.getServer());
            source.sendFeedback(() -> Text.literal("网页主题已切换为 RGB 色系：" + normalized)
                    .formatted(Formatting.GREEN), true);
            if (!running) source.sendError(Text.literal("配置已保存，但网页服务重启失败。"));
            return running ? 1 : 0;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("RGB 颜色无效，请使用 #RRGGBB：" + exception.getMessage()));
            return 0;
        }
    }

    private int webThemeModeStatus(ServerCommandSource source) {
        boolean followIcon = Boolean.parseBoolean(RankBoardConfig.value("web-theme-follow-icon"));
        String base = RankBoardConfig.value("web-theme-base");
        String mode = followIcon ? "读取服务器图标颜色" : (base.equalsIgnoreCase("auto") ? "默认蓝色系" : "RGB 色系 " + base);
        source.sendFeedback(() -> Text.literal("网页主题：" + mode
                + " (web-theme-follow-icon=" + followIcon + ")").formatted(Formatting.GRAY), false);
        return 1;
    }

    private int modifyWebSwitchPeer(ServerCommandSource source, boolean add, String address) {
        java.util.LinkedHashSet<String> peers = new java.util.LinkedHashSet<>();
        String configured = RankBoardConfig.value("web-switcher-peers");
        if (!configured.isBlank()) {
            for (String peer : configured.split(",")) if (!peer.isBlank()) peers.add(peer.strip());
        }
        boolean changed = add ? peers.add(address.strip()) : peers.remove(address.strip());
        if (!changed) {
            source.sendFeedback(() -> Text.literal(add ? "该网页地址已经存在。" : "未找到该网页地址。"), false);
            return 0;
        }
        return setConfig(source, "web-switcher-peers", String.join(",", peers));
    }

    private int webSwitchList(ServerCommandSource source) {
        String configured = RankBoardConfig.value("web-switcher-peers");
        source.sendFeedback(() -> Text.literal(configured.isBlank()
                ? "未配置其他 RankBoard 网页。"
                : "其他 RankBoard 网页：" + configured).formatted(Formatting.GRAY), false);
        return configured.isBlank() ? 0 : configured.split(",").length;
    }

    private int webSwitchStatus(ServerCommandSource source) {
        String name = RankBoardConfig.value("web-switcher-name");
        String weight = RankBoardConfig.value("web-switcher-weight");
        String peers = RankBoardConfig.value("web-switcher-peers");
        source.sendFeedback(() -> Text.literal("网页切换：名称=" + name + "，权重=" + weight
                + "，其他网页=" + (peers.isBlank() ? "无" : peers)).formatted(Formatting.GRAY), false);
        return 1;
    }

    private int setConfig(ServerCommandSource source, String key, String value) {
        if (!RankBoardConfig.isKnownOption(key)) {
            source.sendError(Text.literal("未知配置项：" + key));
            return 0;
        }
        try {
            boolean webOption = RankBoardConfig.isWebOption(key);
            String normalized = RankBoardConfig.set(source.getServer(), key, value);
            if (key.equals("mod-whitelist-enabled")) {
                LeaderboardState.get(source.getServer()).setWhitelistModeConfigured();
                BoardService.refreshAll(source.getServer());
            }
            if (key.equals("scoreboard-name-color-enabled") || key.equals("player-name-color-render-mode")
                    || key.startsWith("metric-color-")) refreshColors(source.getServer());
            if (key.equals("bedrock-break-leaderboard-enabled")) {
                BoardService.refreshAll(source.getServer());
                WebDashboard.invalidateRankings();
            }
            if (key.equals("scoreboard-recipient-filter")) BoardService.refreshAll(source.getServer());
            if (key.startsWith("metric-label-")) refreshMetricLabels(source.getServer());
            boolean webRunning = !webOption || WebDashboard.restart(source.getServer());
            source.sendFeedback(() -> Text.literal("已保存配置：" + key + " = "
                    + (normalized.isEmpty() ? "(空/自动)" : normalized)).formatted(Formatting.GREEN), true);
            if (!webRunning) {
                source.sendError(Text.literal("配置已保存，但网页服务重启失败；请检查服务器日志和监听地址。"));
            }
            return webRunning ? 1 : 0;
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("配置值无效：" + exception.getMessage()));
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("配置保存失败：" + exception.getMessage()));
            LOGGER.error("Could not save RankBoard config {}", key, exception);
        }
        return 0;
    }

    private int reloadConfig(ServerCommandSource source) {
        RankBoardConfig.load(source.getServer());
        RankBoardLanguage.load(source.getServer());
        refreshColors(source.getServer());
        BoardService.refreshAll(source.getServer());
        boolean webRunning = WebDashboard.restart(source.getServer());
        if (!webRunning) {
            source.sendError(Text.literal("配置已重载，但网页服务启动失败；请检查服务器日志和网页配置。"));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("RankBoard 主配置与网页配置已重载；历史统计未重新扫描。")
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private int clearRateLimits(ServerCommandSource source) {
        int cleared = WebDashboard.clearRateLimits();
        source.sendFeedback(() -> Text.literal("已清除 " + cleared + " 个网页限流与 API 累计冷却记录。")
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private int setLanguage(ServerCommandSource source, String language) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("此设置仅限玩家使用 / This setting is for players only."));
            return 0;
        }
        if (!RankBoardLanguage.exists(language)) {
            source.sendError(Text.literal("未知语言包 / Unknown language pack: " + language));
            return 0;
        }
        LeaderboardState.get(source.getServer()).setLanguage(player.getUuid(), language);
        source.sendFeedback(() -> Text.literal(RankBoardLanguage.text(player, "language.selected",
                RankBoardLanguage.text(player, "language.name"))), false);
        if (CommandPermissionCompat.has(source, 2)) {
            player.sendMessage(languageDefaultPrompt(player, language), false);
        }
        return 1;
    }

    private LiteralArgumentBuilder<ServerCommandSource> languageDefaultCommand(String literal) {
        return CommandManager.literal(literal)
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.argument("code", StringArgumentType.word())
                        .suggests((context, builder) -> CommandSource.suggestMatching(RankBoardLanguage.codes(), builder))
                        .executes(context -> setDefaultLanguage(context.getSource(),
                                StringArgumentType.getString(context, "code").toLowerCase(java.util.Locale.ROOT))));
    }

    private int setDefaultLanguage(ServerCommandSource source, String language) {
        if (!RankBoardLanguage.exists(language)) {
            source.sendError(Text.literal("未知语言包 / Unknown language pack: " + language));
            return 0;
        }
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "default-language", language);
            int players = LeaderboardState.get(source.getServer()).setLanguageForAll(source.getServer(), normalized);
            BoardService.refreshAll(source.getServer());
            source.sendFeedback(() -> Text.literal("全服默认聊天语言已设置为 " + normalized
                    + "，已更新 " + players + " 位玩家 / Server-wide default chat language set to "
                    + normalized + " for " + players + " players.").formatted(Formatting.GREEN), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("全服默认语言设置失败 / Failed to set server default language: "
                    + exception.getMessage()));
            return 0;
        }
    }

    private static Text languageDefaultPrompt(ServerPlayerEntity player, String language) {
        String label = language.equalsIgnoreCase("en_us") ? "English"
                : language.equalsIgnoreCase("zh_cn") ? "中文" : language;
        return Text.literal(RankBoardLanguage.text(player, "language.default_prompt", label)).formatted(Formatting.GRAY)
                .copy().append(Text.literal(" "))
                .append(clickable("[" + RankBoardLanguage.text(player, "language.default_button") + "]",
                        Formatting.GREEN, "/leaderboard language default " + language,
                        RankBoardLanguage.text(player, "language.default_tooltip")));
    }

    private int languageStatus(ServerCommandSource source) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("此设置仅限玩家使用 / This setting is for players only."));
            return 0;
        }
        LeaderboardState state = LeaderboardState.get(source.getServer());
        String selected = state.needsLanguageChoice(player.getUuid()) ? "未选择 / Not selected" : state.language(player.getUuid());
        source.sendFeedback(() -> Text.literal("聊天提示语言 / Chat prompt language: " + selected)
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private void sendLanguagePrompt(ServerPlayerEntity player) {
        if (LeaderboardState.get(PlayerCompat.server(player)).needsLanguageChoice(player.getUuid())) {
            player.sendMessage(languageButtons("请选择语言 / Select language: "), false);
        }
    }

    private void sendLanguageHelp(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity) {
            source.sendFeedback(() -> languageButtons("语言 / Language: "), false);
        }
    }

    private static Text languageButtons(String heading) {
        return Text.literal(heading).formatted(Formatting.GRAY)
                .copy().append(clickable("[中文]", Formatting.GOLD,
                        "/leaderboard language zh_cn", "使用中文聊天提示 / Use Chinese chat prompts"))
                .append(Text.literal(" "))
                .append(clickable("[English]", Formatting.AQUA,
                        "/leaderboard language en_us", "Use English chat prompts / 使用英文聊天提示"));
    }

    private void sendJoinExperience(ServerPlayerEntity player) {
        if (PlayerCompat.isFake(player)) return;
        RankBoardConfig config = RankBoardConfig.get();
        ServerCommandSource source = PlayerCompat.source(player);
        if (config.simpleJoinMessages) {
            if (config.joinMenuEnabled && LeaderboardState.get(PlayerCompat.server(player)).isJoinMenuEnabled(player.getUuid())) {
                source.sendFeedback(() -> clickable("[" + localized(source, "menu.join_compact") + "]",
                        Formatting.GOLD, "/leaderboard", localized(source, "menu.tooltip.expand_details")), false);
            }
            return;
        }
        boolean op = CommandPermissionCompat.has(source, 2);
        if (op) sendLanguagePrompt(player);
        if (config.welcomeEnabled) {
            player.sendMessage(Text.literal(RankBoardLanguage.text(player, "welcome",
                    config.displayName(PlayerCompat.server(player)))).formatted(Formatting.GRAY)
                    , false);
        }
        if (config.joinWebHintEnabled && WebDashboard.isEnabled()) {
            player.sendMessage(Text.literal(RankBoardLanguage.text(player, "web_hint",
                    config.webAddress(PlayerCompat.server(player))))
                    .formatted(Formatting.AQUA), false);
        }
        if (op
                && Boolean.parseBoolean(RankBoardConfig.value("web-switcher-op-hint-enabled"))
                && RankBoardConfig.value("web-switcher-name").equalsIgnoreCase("auto")
                && WebDashboard.hasSwitcherPeers()) {
            Text switcherHint = Text.literal(RankBoardLanguage.text(player, "web_switcher.op_hint",
                            WebDashboard.switcherDisplayName())).formatted(Formatting.GRAY)
                    .copy().append(Text.literal(" "))
                    .append(Text.literal("[" + RankBoardLanguage.text(player, "web_switcher.set_name") + "]")
                            .setStyle(TextCompat.suggest(Style.EMPTY.withColor(Formatting.AQUA),
                                    "/leaderboard webswitch name ",
                                    Text.literal("/leaderboard webswitch name <名称>"))));
            player.sendMessage(switcherHint, false);
        }
        if (config.joinMenuEnabled
                && LeaderboardState.get(PlayerCompat.server(player)).isJoinMenuEnabled(player.getUuid())) {
            if (op) {
                menu(source);
            } else {
                source.sendFeedback(() -> clickable("[" + localized(source, "menu.expand_details") + "]",
                        Formatting.GOLD, "/leaderboard", localized(source, "menu.tooltip.expand_details")), false);
            }
        }
    }

    private void handleLookUpSneakMenu(net.minecraft.server.MinecraftServer server) {
        if (!RankBoardConfig.get().lookUpSneakMenuEnabled) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (PlayerCompat.isFake(player)) continue;
            if (!LeaderboardState.get(server).isLookMenuEnabled(player.getUuid())) {
                LOOK_MENU_HELD.remove(player.getUuid());
                continue;
            }
            boolean active = player.isSneaking() && player.getPitch() <= -60.0F;
            if (active && LOOK_MENU_HELD.add(player.getUuid())) menu(PlayerCompat.source(player));
            else if (!active) LOOK_MENU_HELD.remove(player.getUuid());
        }
    }

    private static Text clickable(String label, Formatting color, String command, String hover) {
        return Text.literal(label).setStyle(TextCompat.interactive(
                Style.EMPTY.withColor(color), menuClickCommand(command), Text.literal(hover)));
    }

    static String localized(ServerCommandSource source, String key, Object... arguments) {
        return source.getEntity() instanceof ServerPlayerEntity player
                ? RankBoardLanguage.text(player, key, arguments)
                : RankBoardLanguage.defaultText(key, arguments);
    }

    private static String commandSyntax(ServerCommandSource source, String syntax) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) return syntax;
        return syntax
                // Replace the words themselves, so union forms such as <榜单|all>
                // and <地址|auto> are translated as well.
                .replace("周期", RankBoardLanguage.text(player, "help.arg.period"))
                .replace("榜单", RankBoardLanguage.text(player, "help.arg.metric"))
                .replace("数量", RankBoardLanguage.text(player, "help.arg.limit"))
                .replace("地址", RankBoardLanguage.text(player, "help.arg.address"))
                .replace("名称", RankBoardLanguage.text(player, "help.arg.name"))
                .replace("玩家", RankBoardLanguage.text(player, "help.arg.player"))
                .replace("配置项", RankBoardLanguage.text(player, "help.arg.option"))
                .replace("数值", RankBoardLanguage.text(player, "help.arg.value"))
                .replace("值", RankBoardLanguage.text(player, "help.arg.value"))
                .replace("颜色名", RankBoardLanguage.text(player, "help.arg.color"));
    }

    static String localizedMetric(ServerCommandSource source, Metric metric) {
        String configured = metric.label();
        String defaultLabel = RankBoardConfig.defaultValue("metric-label-" + metric.command);
        return configured.equals(defaultLabel) ? localized(source, "metric." + metric.command) : configured;
    }

    /** Resolves a metric label for a player's selected language while preserving custom labels. */
    static String localizedMetric(ServerPlayerEntity player, Metric metric) {
        String configured = metric.label();
        String defaultLabel = RankBoardConfig.defaultValue("metric-label-" + metric.command);
        if (!configured.equals(defaultLabel)) return configured;
        return RankBoardLanguage.text(player, "metric." + metric.command);
    }

    /** Resolves a period label for a player's selected language. */
    static String localizedPeriod(ServerPlayerEntity player, Period period) {
        String key = switch (period) {
            case DAILY -> "period.day";
            case WEEKLY -> "period.week";
            case MONTHLY -> "period.month";
            case YEARLY -> "period.year";
            case ALL -> "period.all";
        };
        return RankBoardLanguage.text(player, key);
    }

    static String localizedPeriod(ServerCommandSource source, Period period) {
        String key = switch (period) {
            case DAILY -> "period.day";
            case WEEKLY -> "period.week";
            case MONTHLY -> "period.month";
            case YEARLY -> "period.year";
            case ALL -> "period.all";
        };
        return localized(source, key);
    }

    private static Text clickable(String label, Metric metric, String command, String hover) {
        return Text.literal(label).setStyle(TextCompat.interactive(
                Style.EMPTY.withColor(RankBoardColors.renderedRgb(metric)), menuClickCommand(command), Text.literal(hover)));
    }

    private static String menuClickCommand(String command) {
        String prefix = "/leaderboard ";
        if (!command.startsWith(prefix)) return command;
        String action = command.substring(prefix.length());
        if (action.isBlank()) return command;
        int id = MENU_ACTION_IDS.computeIfAbsent(action, key -> {
            int next = NEXT_MENU_ACTION_ID.getAndIncrement();
            MENU_ACTIONS.put(next, key);
            return next;
        });
        return "/trigger " + MENU_TRIGGER_OBJECTIVE + " set " + id;
    }

    private static ScoreboardObjective ensureMenuTriggerObjective(net.minecraft.server.MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(MENU_TRIGGER_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(MENU_TRIGGER_OBJECTIVE, ScoreboardCriterion.TRIGGER,
                    Text.literal("RankBoard Menu"), ScoreboardCriterion.RenderType.INTEGER, false, null);
        }
        return objective;
    }

    private static void prepareMenuTrigger(ServerPlayerEntity player) {
        net.minecraft.server.MinecraftServer server = PlayerCompat.server(player);
        ScoreAccess score = server.getScoreboard().getOrCreateScore(
                player, ensureMenuTriggerObjective(server), true);
        score.setScore(0);
        score.unlock();
    }

    private void handleMenuTriggers(net.minecraft.server.MinecraftServer server) {
        ScoreboardObjective objective = ensureMenuTriggerObjective(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (PlayerCompat.isFake(player)) continue;
            ScoreAccess score = server.getScoreboard().getOrCreateScore(player, objective, true);
            int actionId = score.getScore();
            if (actionId <= 0) {
                if (score.isLocked()) score.unlock();
                continue;
            }
            score.setScore(0);
            score.unlock();
            String action = MENU_ACTIONS.get(actionId);
            if (action == null) {
                player.sendMessage(Text.literal("Menu action expired. Please reopen the menu.")
                        .formatted(Formatting.RED), false);
                continue;
            }
            try {
                server.getCommandManager().getDispatcher().execute(
                        "leaderboard " + action, player.getCommandSource());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
                player.sendMessage(Text.literal("Menu action failed: " + exception.getRawMessage().getString())
                        .formatted(Formatting.RED), false);
                LOGGER.warn("Failed to execute menu action '{}' for {}",
                        action, player.getName().getString(), exception);
            }
        }
    }

    private static Text websiteButton(ServerCommandSource source) {
        String address = RankBoardConfig.get().webAddress(source.getServer());
        if (!address.startsWith("http://") && !address.startsWith("https://")) address = "http://" + address;
        try {
            URI uri = URI.create(address);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException();
            return Text.literal("[" + localized(source, "menu.website") + "]").setStyle(TextCompat.openUrl(
                    Style.EMPTY.withColor(Formatting.AQUA), address, Text.literal("打开 RankBoard 网页排行榜")));
        } catch (RuntimeException exception) {
            return Text.literal("[网站地址无效]").formatted(Formatting.DARK_GRAY);
        }
    }

    private int setLookMenu(ServerCommandSource source, boolean enabled) {
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            LeaderboardState.get(source.getServer()).setLookMenuEnabled(player.getUuid(), enabled);
            LOOK_MENU_HELD.remove(player.getUuid());
            source.sendFeedback(() -> Text.literal(enabled
                    ? "已开启自己的抬头+蹲起菜单。"
                    : "已关闭自己的抬头+蹲起菜单；可输入 /leaderboard lookmenu true 重新开启。"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int lookMenuStatus(ServerCommandSource source) {
        try {
            boolean personal = LeaderboardState.get(source.getServer())
                    .isLookMenuEnabled(source.getPlayerOrThrow().getUuid());
            boolean global = RankBoardConfig.get().lookUpSneakMenuEnabled;
            source.sendFeedback(() -> Text.literal("自己的抬头+蹲起菜单："
                    + (personal ? "已开启" : "已关闭") + "；全服功能：" + (global ? "已开启" : "已关闭")), false);
            return personal && global ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int setJoinMenu(ServerCommandSource source, boolean enabled) {
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            LeaderboardState.get(source.getServer()).setJoinMenuEnabled(player.getUuid(), enabled);
            source.sendFeedback(() -> Text.literal(enabled
                    ? "已开启自己的进服排行榜菜单。"
                    : "已关闭自己的进服排行榜菜单；仍可输入 /leaderboard 手动打开。"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int joinMenuStatus(ServerCommandSource source) {
        try {
            boolean personal = LeaderboardState.get(source.getServer())
                    .isJoinMenuEnabled(source.getPlayerOrThrow().getUuid());
            boolean global = RankBoardConfig.get().joinMenuEnabled;
            source.sendFeedback(() -> Text.literal("自己的进服排行榜菜单："
                    + (personal ? "已开启" : "已关闭") + "；全服功能：" + (global ? "已开启" : "已关闭")), false);
            return personal && global ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int setGlobalLookMenu(ServerCommandSource source, boolean enabled) {
        try {
            RankBoardConfig.set(source.getServer(), "look-up-sneak-menu-enabled", Boolean.toString(enabled));
            LOOK_MENU_HELD.clear();
            source.sendFeedback(() -> Text.literal(enabled
                    ? "已开启全服抬头+蹲起菜单；玩家个人关闭状态保持不变。"
                    : "已关闭全服所有玩家的抬头+蹲起菜单。"), true);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("全服抬头蹲起菜单设置保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int globalLookMenuStatus(ServerCommandSource source) {
        boolean enabled = RankBoardConfig.get().lookUpSneakMenuEnabled;
        source.sendFeedback(() -> Text.literal("全服抬头+蹲起菜单：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int setNameColor(ServerCommandSource source, String mode) {
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "scoreboard-name-color-enabled", mode);
            BoardService.refreshAll(source.getServer());
            PlayerNameColors.refreshAll(source.getServer());
            source.sendFeedback(() -> Text.literal("全服玩家名字颜色模式：" + normalized), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("名字颜色模式保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int nameColorStatus(ServerCommandSource source) {
        String mode = RankBoardConfig.get().nameColorMode.serialized;
        String render = RankBoardConfig.get().nameColorRenderMode.serialized;
        source.sendFeedback(() -> Text.literal("全服玩家名字颜色模式：" + mode + "；渲染：" + render), false);
        return RankBoardConfig.get().nameColorMode == RankBoardConfig.NameColorMode.DISABLED ? 0 : 1;
    }

    private int showMetricLabel(ServerCommandSource source, Metric metric) {
        source.sendFeedback(() -> Text.literal(metric.command + " = " + metric.label()), false);
        return 1;
    }

    private int setMetricLabel(ServerCommandSource source, Metric metric, String value) {
        return saveMetricLabel(source, metric, value, "已设置");
    }

    private int resetMetricLabel(ServerCommandSource source, Metric metric) {
        String key = "metric-label-" + metric.command;
        return saveMetricLabel(source, metric, RankBoardConfig.defaultValue(key), "已恢复默认");
    }

    private int resetAllMetricLabels(ServerCommandSource source) {
        try {
            for (Metric metric : orderedMenuMetrics()) {
                String key = "metric-label-" + metric.command;
                RankBoardConfig.set(source.getServer(), key, RankBoardConfig.defaultValue(key));
            }
            refreshMetricLabels(source.getServer());
            source.sendFeedback(() -> Text.literal("已恢复全部榜单默认名称。"), true);
            return orderedMenuMetrics().size();
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("榜单名称保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int listMetricLabels(ServerCommandSource source) {
        for (Metric metric : orderedMenuMetrics()) {
            source.sendFeedback(() -> Text.literal(metric.command + " = " + metric.label()), false);
        }
        return orderedMenuMetrics().size();
    }

    private int saveMetricLabel(ServerCommandSource source, Metric metric, String value, String action) {
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "metric-label-" + metric.command, value);
            refreshMetricLabels(source.getServer());
            source.sendFeedback(() -> Text.literal(action + " " + metric.command + "：" + normalized), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("榜单名称保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private static void refreshMetricLabels(net.minecraft.server.MinecraftServer server) {
        BoardService.refreshAll(server);
        WebDashboard.invalidateRankings();
    }

    private int setMetricColor(ServerCommandSource source, Metric metric, String value) {
        return saveMetricColor(source, metric, presetHex(value), "已设置");
    }

    private int showColorPresets(ServerCommandSource source, Metric metric) {
        String current = RankBoardConfig.value("metric-color-" + metric.command);
        source.sendFeedback(() -> Text.literal(metric.label() + "当前颜色：")
                .append(RankBoardColors.text(current, metric))
                .append(Text.literal("；点击选择预设色：").formatted(Formatting.GRAY)), false);
        for (int row = 0; row < 4; row++) {
            Text line = Text.empty();
            for (int column = 0; column < 4; column++) {
                ColorPreset preset = COLOR_PRESETS.get(row * 4 + column);
                if (column > 0) line = line.copy().append(Text.literal(" "));
                line = line.copy().append(clickable("[" + preset.key + " " + preset.label + "]", preset.formatting,
                        "/leaderboard color " + metric.command + " " + preset.key,
                        "点击设置 " + metric.label() + " 为 " + preset.key + " / " + preset.label));
            }
            Text completed = line;
            source.sendFeedback(() -> completed, false);
        }
        return COLOR_PRESETS.size();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestColorPresets(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (ColorPreset preset : COLOR_PRESETS) {
            if (preset.key.startsWith(remaining)) {
                builder.suggest(preset.key, new LiteralMessage(preset.label));
            }
        }
        return builder.buildFuture();
    }

    private static String presetHex(String value) {
        for (ColorPreset preset : COLOR_PRESETS) {
            if (preset.key.equalsIgnoreCase(value)) return preset.hex();
        }
        return value;
    }

    private int resetMetricColor(ServerCommandSource source, Metric metric) {
        String key = "metric-color-" + metric.command;
        return saveMetricColor(source, metric, RankBoardConfig.defaultValue(key), "已恢复默认");
    }

    private int resetAllMetricColors(ServerCommandSource source) {
        try {
            for (Metric metric : orderedMenuMetrics()) {
                String key = "metric-color-" + metric.command;
                RankBoardConfig.set(source.getServer(), key, RankBoardConfig.defaultValue(key));
            }
            refreshColors(source.getServer());
            source.sendFeedback(() -> Text.literal("已恢复全部榜单默认颜色。"), true);
            return orderedMenuMetrics().size();
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("颜色配置保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int listMetricColors(ServerCommandSource source) {
        for (Metric metric : orderedMenuMetrics()) {
            String value = RankBoardConfig.value("metric-color-" + metric.command);
            Text entry = clickable("[" + metric.label() + " / " + metric.command + "] " + value,
                    metric, "/leaderboard color " + metric.command,
                    "点击打开 " + metric.label() + " 的原版 16 色预选");
            source.sendFeedback(() -> entry, false);
        }
        return orderedMenuMetrics().size();
    }

    private int saveMetricColor(ServerCommandSource source, Metric metric, String value, String action) {
        String key = "metric-color-" + metric.command;
        try {
            String normalized = RankBoardConfig.set(source.getServer(), key, value);
            refreshColors(source.getServer());
            source.sendFeedback(() -> RankBoardColors.text(action + " " + metric.label() + "：" + normalized, metric), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("颜色配置保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private static void refreshColors(net.minecraft.server.MinecraftServer server) {
        BoardService.refreshAll(server);
        PlayerNameColors.refreshAll(server);
    }

    private int setMetricDisplay(ServerCommandSource source, Metric metric, boolean enabled) {
        LeaderboardState.get(source.getServer()).setMetricDisplayEnabled(metric, enabled);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        source.sendFeedback(() -> Text.literal(enabled ? metric.label() + " 已恢复显示。" : metric.label() + " 已禁止显示。"), true);
        return 1;
    }

    /** Displays enabled metrics first and disabled metrics second, with one-click +/- toggles. */
    private int metricToggleMenu(ServerCommandSource source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        source.sendFeedback(() -> Text.literal("=== " + localized(source, "metric.toggle.heading") + " ===")
                .formatted(Formatting.GOLD), false);
        sendMetricToggleSection(source, state, true);
        sendMetricToggleSection(source, state, false);
        return Metric.values().length;
    }

    private void sendMetricToggleSection(ServerCommandSource source, LeaderboardState state, boolean enabled) {
        List<Metric> metrics = java.util.Arrays.stream(Metric.values())
                .filter(metric -> state.isMetricDisplayEnabled(metric) == enabled)
                .toList();
        if (metrics.isEmpty()) return;
        String heading = localized(source, enabled ? "metric.toggle.enabled_heading" : "metric.toggle.disabled_heading");
        source.sendFeedback(() -> Text.literal(heading).formatted(Formatting.GRAY), false);
        for (Metric metric : metrics) {
            String label = localizedMetric(source, metric);
            boolean configurationDisabled = metric == Metric.BEDROCK_BROKEN
                    && !RankBoardConfig.get().bedrockBreakLeaderboardEnabled;
            String toggleCommand = enabled
                    ? "/leaderboard displayfilter " + metric.command + " false"
                    : (configurationDisabled
                    ? "/leaderboard bedrock true"
                    : "/leaderboard displayfilter " + metric.command + " true");
            String tooltipKey = enabled ? "metric.toggle.disable_tooltip" :
                    (configurationDisabled ? "metric.toggle.enable_config_tooltip" : "metric.toggle.enable_tooltip");
            Text row = clickable("[" + label + "]", metric,
                    "/leaderboard display show all " + metric.command,
                    localized(source, "menu.tooltip.metric").replace("{0}", label))
                    .copy().append(Text.literal(" "))
                    .append(clickable(enabled ? "[-]" : "[+]", enabled ? Formatting.RED : Formatting.GREEN,
                            toggleCommand, localized(source, tooltipKey)));
            source.sendFeedback(() -> row, false);
        }
    }

    private int metricDisplayStatus(ServerCommandSource source, Metric metric) {
        boolean enabled = LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric);
        source.sendFeedback(() -> Text.literal(metric.label() + " 显示：" + (enabled ? "已开启" : "已禁用")), false);
        return enabled ? 1 : 0;
    }

    private int setWhitelistOnly(ServerCommandSource source, boolean enabled) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        state.setWhitelistOnly(enabled);
        state.setWhitelistModeConfigured();
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(enabled
                ? "排行榜已仅显示服务器白名单玩家。" : "排行榜已显示所有有统计数据的玩家。"), true);
        return 1;
    }

    private int whitelistStatus(ServerCommandSource source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isWhitelistOnly();
        source.sendFeedback(() -> Text.literal("排行榜白名单过滤：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int configureWhitelistMode(ServerCommandSource source, WhitelistMode mode) {
        try {
            LeaderboardState state = LeaderboardState.get(source.getServer());
            switch (mode) {
                case SERVER -> {
                    state.setWhitelistOnly(true);
                    RankBoardConfig.set(source.getServer(), "mod-whitelist-enabled", "false");
                }
                case MOD -> {
                    state.setWhitelistOnly(false);
                    RankBoardConfig.set(source.getServer(), "mod-whitelist-enabled", "true");
                }
                case NONE -> {
                    state.setWhitelistOnly(false);
                    RankBoardConfig.set(source.getServer(), "mod-whitelist-enabled", "false");
                }
            }
            state.setWhitelistModeConfigured();
            BoardService.refreshAll(source.getServer());
            String description = switch (mode) {
                case SERVER -> "服务器白名单";
                case MOD -> "模组白名单";
                case NONE -> "无白名单";
            };
            source.sendFeedback(() -> Text.literal("已选择：" + description + "。")
                    .formatted(Formatting.GREEN), true);
            sendWhitelistDataReadPrompt(source);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("白名单模式保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int skipWhitelistDataRead(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("已保留当前榜单缓存；需要读取玩家统计文件时输入 /leaderboard cache reload。")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private void sendWhitelistSetupPrompt(ServerCommandSource source) {
        if (!CommandPermissionCompat.has(source, 2)
                || !LeaderboardState.get(source.getServer()).needsWhitelistModeSetup()) return;
        source.sendFeedback(() -> whitelistSetupButtons(source, "whitelist.first_heading"), false);
    }

    private static Text whitelistSetupButtons(ServerCommandSource source, String headingKey) {
        return Text.literal(localized(source, headingKey)).formatted(Formatting.GRAY)
                .copy().append(clickable("[" + localized(source, "whitelist.server") + "]", Formatting.YELLOW,
                        "/leaderboard whitelist setup server", localized(source, "whitelist.server_tooltip")))
                .append(Text.literal(" "))
                .append(clickable("[" + localized(source, "whitelist.mod") + "]", Formatting.AQUA,
                        "/leaderboard whitelist setup mod", localized(source, "whitelist.mod_tooltip")))
                .append(Text.literal(" "))
                .append(clickable("[" + localized(source, "whitelist.none") + "]", Formatting.GREEN,
                        "/leaderboard whitelist setup none", localized(source, "whitelist.none_tooltip")));
    }

    private void sendWhitelistDataReadPrompt(ServerCommandSource source) {
        Text prompt = Text.literal(localized(source, "whitelist.read_prompt")).formatted(Formatting.GRAY)
                .copy().append(clickable("[" + localized(source, "whitelist.read") + "]", Formatting.GOLD,
                        "/leaderboard cache reload", localized(source, "whitelist.read_tooltip")))
                .append(Text.literal(" "))
                .append(clickable("[" + localized(source, "whitelist.later") + "]", Formatting.GRAY,
                        "/leaderboard whitelist setup later", localized(source, "whitelist.later_tooltip")));
        source.sendFeedback(() -> prompt, false);
    }

    private int addFilteredPlayer(ServerCommandSource source, String player) {
        String name = player == null ? "" : player.trim();
        if (name.isEmpty()) {
            source.sendError(Text.literal("玩家名称不能为空。"));
            return 0;
        }
        LeaderboardState state = LeaderboardState.get(source.getServer());
        java.util.UUID uuid = resolvePlayerUuid(source.getServer(), name);
        boolean changed = state.addFilteredPlayer(name, uuid);
        if (changed) BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(changed
                ? "已加入隐藏名单：" + name + (uuid == null ? "（离线名称，出现时匹配）" : "")
                : "该玩家已在隐藏名单：" + name), true);
        return changed ? 1 : 0;
    }

    private int allowFilteredPlayer(ServerCommandSource source, String player) {
        String name = player == null ? "" : player.trim();
        if (name.isEmpty()) {
            source.sendError(Text.literal("玩家名称不能为空。"));
            return 0;
        }
        LeaderboardState state = LeaderboardState.get(source.getServer());
        java.util.UUID uuid = resolvePlayerUuid(source.getServer(), name);
        boolean changed = state.addAllowedPlayer(name, uuid);
        if (changed) BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(changed
                ? "已允许显示：" + name + "（跳过 bot_/unknown_ 前缀过滤）"
                : "该玩家已在允许名单：" + name), true);
        return changed ? 1 : 0;
    }

    private int removePlayerFilter(ServerCommandSource source, String player) {
        String name = player == null ? "" : player.trim();
        boolean changed = LeaderboardState.get(source.getServer()).removePlayerFilter(name);
        if (changed) BoardService.refreshAll(source.getServer());
        if (changed) {
            source.sendFeedback(() -> Text.literal("已从玩家过滤名单移除：" + name), true);
            return 1;
        }
        source.sendError(Text.literal("玩家过滤名单中没有：" + name));
        return 0;
    }

    private int clearPlayerFilters(ServerCommandSource source) {
        int removed = LeaderboardState.get(source.getServer()).clearPlayerFilters();
        if (removed > 0) BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(removed > 0
                ? "已清空玩家过滤名单（" + removed + " 项）。" : "玩家过滤名单已经是空的。"), true);
        return removed > 0 ? 1 : 0;
    }

    private int listPlayerFilters(ServerCommandSource source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        java.util.List<java.util.Map.Entry<String, java.util.UUID>> hidden = state.filteredPlayerEntries();
        java.util.List<java.util.Map.Entry<String, java.util.UUID>> allowed = state.allowedPlayerEntries();
        source.sendFeedback(() -> Text.literal("隐藏名单（" + hidden.size() + "）："
                + (hidden.isEmpty() ? "空" : joinPlayerFilterEntries(hidden))).formatted(Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("允许显示名单（" + allowed.size() + "）："
                + (allowed.isEmpty() ? "空" : joinPlayerFilterEntries(allowed))).formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("使用 /leaderboard playerfilter add|allow|remove <玩家> 管理；"
                + "无参数打开可点击面板。").formatted(Formatting.GRAY), false);
        return hidden.size() + allowed.size();
    }

    private int playerFilterStatus(ServerCommandSource source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        int hidden = state.filteredPlayerEntries().size();
        int allowed = state.allowedPlayerEntries().size();
        source.sendFeedback(() -> Text.literal("玩家过滤：隐藏 " + hidden + " 人，允许显示 " + allowed
                + " 人；自动 bot_ 过滤 " + (state.isBotFilterEnabled() ? "开启" : "关闭")
                + "，unknown_ 过滤 " + (state.isCustomPlayerFilterEnabled() ? "开启" : "关闭") + "。"), false);
        return hidden + allowed;
    }

    private int playerFilterMenu(ServerCommandSource source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        beginMenu(source, "玩家过滤");
        source.sendFeedback(() -> Text.literal("自动过滤 bot_ " + (state.isBotFilterEnabled() ? "开启" : "关闭")
                + " · unknown_ " + (state.isCustomPlayerFilterEnabled() ? "开启" : "关闭")
                + "；手动名单优先。").formatted(Formatting.GRAY), false);

        java.util.List<java.util.Map.Entry<String, java.util.UUID>> hidden = state.filteredPlayerEntries();
        Text hiddenRow = Text.literal("隐藏名单：").formatted(Formatting.RED);
        if (hidden.isEmpty()) {
            hiddenRow = hiddenRow.copy().append(Text.literal("（空）").formatted(Formatting.GRAY));
        } else {
            for (java.util.Map.Entry<String, java.util.UUID> entry : hidden) {
                hiddenRow = hiddenRow.copy().append(clickable("[" + entry.getKey() + "]", Formatting.RED,
                        "/leaderboard playerfilter remove " + entry.getKey(), "从隐藏名单移除"))
                        .append(Text.literal(" "));
            }
        }
        Text finalHiddenRow = hiddenRow;
        source.sendFeedback(() -> finalHiddenRow, false);

        java.util.List<java.util.Map.Entry<String, java.util.UUID>> allowed = state.allowedPlayerEntries();
        Text allowedRow = Text.literal("允许显示：").formatted(Formatting.GREEN);
        if (allowed.isEmpty()) {
            allowedRow = allowedRow.copy().append(Text.literal("（空）").formatted(Formatting.GRAY));
        } else {
            for (java.util.Map.Entry<String, java.util.UUID> entry : allowed) {
                allowedRow = allowedRow.copy().append(clickable("[" + entry.getKey() + "]", Formatting.GREEN,
                        "/leaderboard playerfilter remove " + entry.getKey(), "从允许名单移除"))
                        .append(Text.literal(" "));
            }
        }
        Text finalAllowedRow = allowedRow;
        source.sendFeedback(() -> finalAllowedRow, false);

        Text onlineRow = Text.literal("在线玩家：").formatted(Formatting.GOLD);
        int shown = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (shown >= 12) break;
            String name = ProfileCompat.name(player.getGameProfile());
            if (state.isManuallyFiltered(player.getUuid(), name)) continue;
            onlineRow = onlineRow.copy().append(clickable("[" + name + "]", Formatting.AQUA,
                    "/leaderboard playerfilter add " + name, "把 " + name + " 加入隐藏名单")).append(Text.literal(" "));
            shown++;
        }
        if (shown == 0) onlineRow = onlineRow.copy().append(Text.literal("（无可添加）").formatted(Formatting.GRAY));
        Text finalOnlineRow = onlineRow;
        source.sendFeedback(() -> finalOnlineRow, false);

        Text actions = clickable("[清空手动名单]", Formatting.RED, "/leaderboard playerfilter clear", "清空隐藏与允许名单")
                .copy().append(Text.literal(" "))
                .append(clickable("[返回功能菜单]", Formatting.GRAY, "/leaderboard menu home", "返回功能菜单"));
        Text finalActions = actions;
        source.sendFeedback(() -> finalActions, false);
        source.sendFeedback(() -> Text.literal("添加离线玩家：/leaderboard playerfilter add <玩家名>；"
                + "允许 bot_ 显示：/leaderboard playerfilter allow <玩家名>。").formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    private static String joinPlayerFilterEntries(
            java.util.List<java.util.Map.Entry<String, java.util.UUID>> entries) {
        StringBuilder builder = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.UUID> entry : entries) {
            if (builder.length() > 0) builder.append("，");
            builder.append(entry.getKey());
            if (entry.getValue() != null) builder.append(" (").append(entry.getValue()).append(')');
        }
        return builder.toString();
    }

    private static java.util.UUID resolvePlayerUuid(net.minecraft.server.MinecraftServer server, String name) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (ProfileCompat.name(player.getGameProfile()).equalsIgnoreCase(name)) return player.getUuid();
        }
        for (java.util.Map.Entry<java.util.UUID, String> entry : StatReader.knownNamesForComparison(server).entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
        }
        for (StatSnapshot snapshot : StatReader.readAll(server)) {
            if (snapshot.name().equalsIgnoreCase(name)) return snapshot.uuid();
        }
        try {
            return java.util.UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static java.util.List<String> playerFilterCandidates(net.minecraft.server.MinecraftServer server) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        LeaderboardState state = LeaderboardState.get(server);
        state.filteredPlayerEntries().forEach(entry -> names.add(entry.getKey()));
        state.allowedPlayerEntries().forEach(entry -> names.add(entry.getKey()));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            names.add(ProfileCompat.name(player.getGameProfile()));
        }
        for (String name : StatReader.knownNamesForComparison(server).values()) {
            if (names.size() >= 80) break;
            names.add(name);
        }
        for (StatSnapshot snapshot : StatReader.readAll(server)) {
            if (names.size() >= 80) break;
            if (snapshot.name().startsWith("unknown_")) continue;
            names.add(snapshot.name());
        }
        return java.util.List.copyOf(names);
    }

    private int addBotGroupPlayer(ServerCommandSource source, String player) {
        String name = player == null ? "" : player.trim();
        if (name.isEmpty()) {
            source.sendError(Text.literal("玩家名称不能为空。"));
            return 0;
        }
        LeaderboardState state = LeaderboardState.get(source.getServer());
        java.util.UUID uuid = resolvePlayerUuid(source.getServer(), name);
        boolean changed = state.addBotPlayer(name, uuid);
        if (changed) {
            BoardService.refreshAll(source.getServer());
            WebDashboard.invalidateRankings();
        }
        boolean hasStats = StatReader.readAll(source.getServer()).stream().anyMatch(snapshot ->
                (uuid != null && snapshot.uuid().equals(uuid)) || snapshot.name().equalsIgnoreCase(name));
        source.sendFeedback(() -> Text.literal(changed
                ? "已移入机器人分组：" + name + (uuid == null ? "（未找到 UUID，仅按名称匹配）" : "")
                + (hasStats ? "" : "（当前没有统计数据，请确认机器人已生成 stats 或执行 cache reload）")
                : "该玩家已在机器人分组：" + name), true);
        return changed ? 1 : 0;
    }

    private int removeBotGroupPlayer(ServerCommandSource source, String player) {
        String name = player == null ? "" : player.trim();
        boolean changed = LeaderboardState.get(source.getServer()).removeBotPlayer(name);
        if (changed) {
            BoardService.refreshAll(source.getServer());
            WebDashboard.invalidateRankings();
        }
        if (changed) {
            source.sendFeedback(() -> Text.literal("已从机器人分组移除：" + name), true);
            return 1;
        }
        source.sendError(Text.literal("机器人分组中没有：" + name));
        return 0;
    }

    private int listBotGroup(ServerCommandSource source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        java.util.List<java.util.Map.Entry<String, java.util.UUID>> bots = state.botPlayerEntries();
        java.util.Map<java.util.UUID, String> names = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, BotProfile.Type> types = new java.util.HashMap<>();
        for (StatSnapshot snapshot : StatReader.readAll(source.getServer())) {
            names.put(snapshot.uuid(), snapshot.name());
            types.put(snapshot.uuid(), botType(snapshot));
        }
        String list = bots.stream().map(entry -> {
            String name = names.getOrDefault(entry.getValue(), entry.getKey());
            BotProfile.Type type = types.get(entry.getValue());
            return name + (type == null ? "" : "·" + type.zh);
        }).collect(java.util.stream.Collectors.joining("、"));
        source.sendFeedback(() -> Text.literal("机器人分组（" + bots.size() + "）："
                + (bots.isEmpty() ? "空" : list)).formatted(Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal("另会自动识别 bot_ 前缀玩家；使用 /leaderboard bots <周期> <榜单> 查看机器人榜。")
                .formatted(Formatting.GRAY), false);
        return bots.size();
    }

    private int clearBotGroup(ServerCommandSource source) {
        int removed = LeaderboardState.get(source.getServer()).clearBotPlayers();
        if (removed > 0) {
            BoardService.refreshAll(source.getServer());
            WebDashboard.invalidateRankings();
        }
        source.sendFeedback(() -> Text.literal(removed > 0
                ? "已清空机器人分组（" + removed + " 项）。" : "机器人分组已经是空的。"), true);
        return removed > 0 ? 1 : 0;
    }

    private int setBotGroupMixed(ServerCommandSource source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setBotGroupMixed(enabled);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        source.sendFeedback(() -> Text.literal(enabled
                ? "机器人分组已和普通榜单混合显示（聊天栏与网页带 [BOT] 标记）。"
                : "机器人分组已从普通榜单隐藏。"), true);
        return 1;
    }

    private int botGroupMixedStatus(ServerCommandSource source) {
        boolean mixed = LeaderboardState.get(source.getServer()).isBotGroupMixed();
        source.sendFeedback(() -> Text.literal("机器人混合显示：" + (mixed ? "已开启" : "已关闭")), false);
        return mixed ? 1 : 0;
    }

    private int botGroupMenu(ServerCommandSource source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        beginMenu(source, "机器人分组");
        java.util.List<java.util.Map.Entry<String, java.util.UUID>> bots = state.botPlayerEntries();
        java.util.Map<java.util.UUID, BotProfile.Type> types = new java.util.HashMap<>();
        for (StatSnapshot snapshot : StatReader.readAll(source.getServer())) {
            types.put(snapshot.uuid(), botType(snapshot));
        }
        source.sendFeedback(() -> Text.literal("手动机器人 " + bots.size() + " 人；混合显示 "
                + (state.isBotGroupMixed() ? "开启" : "关闭")
                + "。普通榜单默认隐藏机器人，机器人榜不受白名单限制。")
                .formatted(Formatting.GRAY), false);

        Text botRow = Text.literal("分组名单：").formatted(Formatting.AQUA);
        if (bots.isEmpty()) {
            botRow = botRow.copy().append(Text.literal("（空）").formatted(Formatting.GRAY));
        } else {
            for (java.util.Map.Entry<String, java.util.UUID> entry : bots) {
                BotProfile.Type type = entry.getValue() == null ? null : types.get(entry.getValue());
                String label = entry.getKey() + (type == null ? "" : "·" + type.zh);
                botRow = botRow.copy().append(clickable("[" + label + "]", Formatting.AQUA,
                        "/leaderboard botgroup remove " + entry.getKey(), "移出机器人分组"))
                        .append(Text.literal(" "));
            }
        }
        Text finalBotRow = botRow;
        source.sendFeedback(() -> finalBotRow, false);

        Text onlineRow = Text.literal("在线玩家：").formatted(Formatting.GOLD);
        int shown = 0;
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (shown >= 12) break;
            String name = ProfileCompat.name(player.getGameProfile());
            if (state.isManualBot(player.getUuid(), name)) continue;
            onlineRow = onlineRow.copy().append(clickable("[" + name + "]", Formatting.YELLOW,
                    "/leaderboard botgroup add " + name, "把 " + name + " 移入机器人分组")).append(Text.literal(" "));
            shown++;
        }
        if (shown == 0) onlineRow = onlineRow.copy().append(Text.literal("（无可添加）").formatted(Formatting.GRAY));
        Text finalOnlineRow = onlineRow;
        source.sendFeedback(() -> finalOnlineRow, false);

        Text actions = clickable("[机器人总榜]", Formatting.GOLD, "/leaderboard bots all playtime", "查看机器人分组的总榜")
                .copy().append(Text.literal(" "))
                .append(clickable("[效率榜]", Formatting.AQUA, "/leaderboard bots rate all kills", "查看机器人每小时效率"))
                .append(Text.literal(" "))
                .append(clickable(state.isBotGroupMixed() ? "[关闭混合]" : "[混合显示]", Formatting.LIGHT_PURPLE,
                        "/leaderboard botgroup mixed " + !state.isBotGroupMixed(), "机器人是否与普通榜一起显示"))
                .append(Text.literal(" "))
                .append(clickable("[清空分组]", Formatting.RED, "/leaderboard botgroup clear", "清空手动机器人分组"))
                .append(Text.literal(" "))
                .append(clickable("[返回功能菜单]", Formatting.GRAY, "/leaderboard menu home", "返回功能菜单"));
        Text finalActions = actions;
        source.sendFeedback(() -> finalActions, false);
        source.sendFeedback(() -> Text.literal("机器人榜：/leaderboard bots <周期> <榜单>；"
                + "效率榜：/leaderboard bots rate <周期> <榜单>。").formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    private int showBots(ServerCommandSource source, Period period, Metric metric, boolean rate, int limit) {
        try {
            if (!StatReader.isReady() && period != Period.ALL) {
                source.sendError(Text.literal("历史统计仍在权威扫描（" + StatReader.progress()
                        + "），机器人周期榜暂不可用。"));
                return 0;
            }
            List<Entry> entries = rate
                    ? botRateEntries(source.getServer(), period, metric)
                    : botEntries(source.getServer(), period, metric);
            String title = "=== 机器人 · " + period.label + " " + metric.label()
                    + (rate ? "（每小时）" : "") + " ===";
            source.sendFeedback(() -> Text.literal(title).formatted(Formatting.AQUA), false);
            if (entries.isEmpty()) {
                source.sendFeedback(() -> Text.literal(rate
                        ? "机器人分组还没有可计算效率的数据（需要非零在线时长）。"
                        : "机器人分组还没有可显示的玩家。使用 /leaderboard botgroup add <玩家> 添加。")
                        .formatted(Formatting.GRAY), false);
                return 0;
            }
            java.util.Map<java.util.UUID, BotProfile.Type> types = new java.util.HashMap<>();
            for (StatSnapshot snapshot : StatReader.readAll(source.getServer())) {
                types.put(snapshot.uuid(), botType(snapshot));
            }
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                Entry entry = entries.get(i);
                int rank = i + 1;
                BotProfile.Type type = types.get(entry.uuid());
                String tag = "[BOT" + (type == null ? "" : "·" + type.zh) + "]";
                String value = rate ? formatRate(entry.value()) : format(metric, entry.value());
                source.sendFeedback(() -> Text.literal(rank + " ").formatted(Formatting.YELLOW)
                        .append(RankBoardColors.text(entry.name(), metric))
                        .append(Text.literal(" " + tag + "  ").formatted(Formatting.AQUA))
                        .append(RankBoardColors.text(value, metric)), false);
            }
            return entries.size();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to execute bot leaderboard command: period={}, metric={}",
                    period.command, metric.command, exception);
            source.sendError(Text.literal("机器人榜读取失败：" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : " - " + exception.getMessage())));
            return 0;
        }
    }

    private static Period periodByCommand(String value) {
        for (Period period : Period.values()) {
            if (period.command.equalsIgnoreCase(value) || period.shortCommand.equalsIgnoreCase(value)) return period;
        }
        throw new IllegalArgumentException("未知周期：" + value);
    }

    private static Metric metricByCommand(String value) {
        for (Metric metric : Metric.values()) {
            if (metric.command.equalsIgnoreCase(value)) return metric;
        }
        throw new IllegalArgumentException("未知榜单：" + value);
    }

    private int setBotFilter(ServerCommandSource source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setBotFilterEnabled(enabled);
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(enabled
                ? "排行榜已屏蔽 bot_ 前缀玩家。" : "排行榜已允许显示 bot_ 前缀玩家。"), true);
        return 1;
    }

    private int botFilterStatus(ServerCommandSource source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isBotFilterEnabled();
        source.sendFeedback(() -> Text.literal("bot_ 前缀屏蔽：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int setCustomFilter(ServerCommandSource source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setCustomPlayerFilterEnabled(enabled);
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(enabled
                ? "排行榜已隐藏无法解析身份的历史玩家。" : "排行榜已允许显示 unknown_ 历史玩家。"), true);
        return 1;
    }

    private int customFilterStatus(ServerCommandSource source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isCustomPlayerFilterEnabled();
        source.sendFeedback(() -> Text.literal("未知历史玩家屏蔽：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int setOnlineFilter(ServerCommandSource source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setOnlineOnly(enabled);
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(enabled
                ? "排行榜已仅显示当前在线玩家。" : "排行榜已恢复显示符合其他筛选条件的玩家。"), true);
        return 1;
    }

    private int onlineFilterStatus(ServerCommandSource source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isOnlineOnly();
        source.sendFeedback(() -> Text.literal("仅显示在线玩家：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int modifyModWhitelist(ServerCommandSource source, boolean add, String player) {
        try {
            boolean changed = add ? RankBoardWhitelist.add(source.getServer(), player)
                    : RankBoardWhitelist.remove(source.getServer(), player);
            if (changed) BoardService.refreshAll(source.getServer());
            source.sendFeedback(() -> Text.literal(changed
                    ? (add ? "已添加到模组白名单：" : "已从模组白名单移除：") + player
                    : (add ? "模组白名单中已存在：" : "模组白名单中未找到：") + player), true);
            return changed ? 1 : 0;
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("模组白名单参数无效：" + exception.getMessage()));
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("模组白名单保存失败：" + exception.getMessage()));
        }
        return 0;
    }

    private int listModWhitelist(ServerCommandSource source) {
        List<String> entries = RankBoardWhitelist.entries();
        source.sendFeedback(() -> Text.literal("模组白名单（" + entries.size() + "）："
                + (entries.isEmpty() ? "空" : String.join("，", entries))), false);
        return entries.size();
    }

    private int reloadModWhitelist(ServerCommandSource source) {
        RankBoardWhitelist.reload(source.getServer());
        source.sendFeedback(() -> Text.literal("模组白名单已重新加载，共 "
                + RankBoardWhitelist.entries().size() + " 项。"), true);
        BoardService.refreshAll(source.getServer());
        return 1;
    }

    private int setRecipientFilter(ServerCommandSource source, String mode) {
        return setConfig(source, "scoreboard-recipient-filter", mode);
    }

    private int recipientFilterStatus(ServerCommandSource source) {
        String mode = RankBoardConfig.get().recipientFilter.serialized;
        source.sendFeedback(() -> Text.literal("个人榜单接收过滤：" + mode
                + "；whitelist/blacklist 使用 config/rankboard/rankboard-whitelist.json。")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private int cacheStatus(ServerCommandSource source) {
        String status = StatReader.isChecking() ? "扫描中"
                : StatReader.isLoadedFromPersistentCacheOnly() ? "已从持久缓存加载（启动时未扫描）"
                : StatReader.isReady() ? "已完成" : "等待读取";
        source.sendFeedback(() -> Text.literal("历史统计缓存：" + status + "（" + StatReader.progress()
                        + "，扫描线程 " + StatReader.resolvedScanThreads() + "）")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private int reloadCache(ServerCommandSource source) {
        StatReader.startWarmup(source.getServer());
        source.sendFeedback(() -> Text.literal("已开始读取 world/stats/*.json 并重建历史统计缓存，当前进度 " + StatReader.progress() + "。")
                .formatted(Formatting.GRAY), true);
        return 1;
    }

    private LiteralArgumentBuilder<ServerCommandSource> cacheCompareCommands() {
        return CommandManager.literal("compare")
                .executes(context -> CacheComparisonService.compare(context.getSource(), null))
                .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> CacheComparisonService.compare(context.getSource(),
                                StringArgumentType.getString(context, "player"))))
                .then(CommandManager.literal("list")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> CacheComparisonService.list(context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "page"))))))
                .then(CommandManager.literal("player")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .then(CommandManager.argument("uuid", StringArgumentType.word())
                                        .executes(context -> CacheComparisonService.player(context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                StringArgumentType.getString(context, "uuid"))))))
                .then(CommandManager.literal("apply-all")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .executes(context -> CacheComparisonService.prepareApplyAll(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(CommandManager.literal("apply-player")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .then(CommandManager.argument("uuid", StringArgumentType.word())
                                        .executes(context -> CacheComparisonService.prepareApplyPlayer(
                                                context.getSource(), StringArgumentType.getString(context, "id"),
                                                StringArgumentType.getString(context, "uuid"))))))
                .then(CommandManager.literal("confirm-all")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .executes(context -> CacheComparisonService.confirmAll(context.getSource(),
                                        StringArgumentType.getString(context, "id")))))
                .then(CommandManager.literal("confirm-player")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .then(CommandManager.argument("uuid", StringArgumentType.word())
                                        .executes(context -> CacheComparisonService.confirmPlayer(
                                                context.getSource(), StringArgumentType.getString(context, "id"),
                                                StringArgumentType.getString(context, "uuid"))))))
                .then(CommandManager.literal("cancel")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .executes(context -> CacheComparisonService.cancel(context.getSource(),
                                        StringArgumentType.getString(context, "id")))));
    }

    private int cacheThreadsStatus(ServerCommandSource source) {
        int configured = RankBoardConfig.get().historyScanThreads;
        int resolved = StatReader.resolvedScanThreads();
        source.sendFeedback(() -> Text.literal("历史扫描线程：配置 " + configured + "，实际使用 " + resolved
                        + "（最多为逻辑处理器的 50%），总扫描上限 " + StatReader.effectiveScanRate() + " 文件/秒。")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private int setCacheThreads(ServerCommandSource source, int requested) {
        try {
            String saved = RankBoardConfig.set(source.getServer(), "history-scan-threads", Integer.toString(requested));
            int resolved = StatReader.resolvedScanThreads();
            source.sendFeedback(() -> Text.literal("历史扫描线程已保存为 " + saved + "，实际使用 " + resolved
                            + "（最多为逻辑处理器的 50%），总扫描上限 " + StatReader.effectiveScanRate()
                            + " 文件/秒；下次 /leaderboard cache reload 时生效。")
                    .formatted(Formatting.GREEN), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendError(Text.literal("无法设置历史扫描线程：" + exception.getMessage()));
            return 0;
        }
    }

    private int show(ServerCommandSource source, Period period, Metric metric, int limit) {
        try {
            if (!StatReader.isReady()) {
                if (period != Period.ALL) {
                    source.sendError(Text.literal("历史统计仍在权威扫描（" + StatReader.progress()
                            + "），周期榜暂不可用，请稍后重试。"));
                    return 0;
                }
                source.sendFeedback(() -> Text.literal("权威扫描进行中（" + StatReader.progress()
                        + "），当前显示缓存预览，结果可能变化。").formatted(Formatting.YELLOW), false);
            }
            if (period != Period.ALL
                    && !LeaderboardState.get(source.getServer()).isPeriodComplete(period, metric)) {
                source.sendFeedback(() -> Text.literal(period.label + "统计为部分周期，从首次可信基线开始。")
                        .formatted(Formatting.YELLOW), false);
            }
            List<Entry> entries = entries(source.getServer(), period, metric);
            LeaderboardState state = LeaderboardState.get(source.getServer());
            source.sendFeedback(() -> RankBoardColors.text("=== " + period.label + " " + metric.label() + " ===", metric), false);
            if (entries.isEmpty()) {
                source.sendFeedback(() -> Text.literal("没有可用于排行的玩家统计。 ").formatted(Formatting.GRAY), false);
                return 0;
            }
            java.util.List<Entry> humans = new java.util.ArrayList<>();
            java.util.List<Entry> bots = new java.util.ArrayList<>();
            for (Entry entry : entries) {
                if (state.isBotPlayer(entry.uuid(), entry.name())) bots.add(entry); else humans.add(entry);
            }
            if (bots.isEmpty()) {
                long total = total(entries);
                source.sendFeedback(() -> Text.literal("总和 ").formatted(Formatting.GRAY)
                        .append(RankBoardColors.text(format(metric, total), metric)), false);
            } else {
                long humanTotal = total(humans);
                source.sendFeedback(() -> Text.literal("总和 ").formatted(Formatting.GRAY)
                        .append(RankBoardColors.text(format(metric, humanTotal), metric)), false);
            }
            int rank = 0;
            for (Entry entry : humans) {
                if (rank >= limit) break;
                rank++;
                int shownRank = rank;
                source.sendFeedback(() -> Text.literal(shownRank + " ").formatted(Formatting.YELLOW)
                        .append(RankBoardColors.text(entry.name(), metric))
                        .append(Text.literal("  ").formatted(Formatting.GRAY))
                        .append(RankBoardColors.text(format(metric, entry.value()), metric)), false);
            }
            if (!bots.isEmpty()) {
                source.sendFeedback(() -> Text.literal("──────── BOT 机器人 ────────")
                        .formatted(Formatting.DARK_GRAY), false);
                long botTotal = total(bots);
                source.sendFeedback(() -> Text.literal("BOT 总和 ").formatted(Formatting.GRAY)
                        .append(RankBoardColors.text(format(metric, botTotal), metric)), false);
                int botShown = 0;
                for (Entry entry : bots) {
                    if (botShown >= limit) break;
                    botShown++;
                    rank++;
                    int shownRank = rank;
                    source.sendFeedback(() -> Text.literal(shownRank + " ").formatted(Formatting.YELLOW)
                            .append(RankBoardColors.text(entry.name(), metric))
                            .append(Text.literal(" [BOT]  ").formatted(Formatting.AQUA))
                            .append(RankBoardColors.text(format(metric, entry.value()), metric)), false);
                }
            }
            return entries.size();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to execute leaderboard command: period={}, metric={}", period.command, metric.command, exception);
            source.sendError(Text.literal("排行榜读取失败：" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : " - " + exception.getMessage())));
            return 0;
        }
    }

    private int exportRanking(ServerCommandSource source, Period period, Metric metric) {
        if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) {
            source.sendError(Text.literal(metric.label() + " 已被 OP 禁止显示，无法导出。"));
            return 0;
        }
        List<Entry> rows = entries(source.getServer(), period, metric);
        Path directory = RankBoardConfig.configDirectory(source.getServer()).resolve("exports");
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path file = directory.resolve("rankboard-" + period.command + "-" + metric.command + "-" + timestamp + ".csv");
        try {
            Files.createDirectories(directory);
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                // UTF-8 BOM lets common spreadsheet programs recognise Chinese text immediately.
                writer.write('\uFEFF');
                writer.write("排名,玩家名称,UUID,榜单,周期,数值,显示数值\r\n");
                for (int index = 0; index < rows.size(); index++) {
                    Entry entry = rows.get(index);
                    writer.write((index + 1) + "," + csv(entry.name()) + "," + entry.uuid() + ","
                            + csv(metric.label()) + "," + csv(period.label) + "," + entry.value() + ","
                            + csv(format(metric, entry.value())) + "\r\n");
                }
            }
            source.sendFeedback(() -> Text.literal("已导出 " + rows.size() + " 条排行榜数据："
                    + file.toAbsolutePath()).formatted(Formatting.GREEN), true);
            return rows.size();
        } catch (java.io.IOException exception) {
            source.sendError(Text.literal("导出排行榜失败：" + exception.getMessage()));
            LOGGER.error("Could not export {} {} ranking to {}", period.command, metric.command, file, exception);
            return 0;
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static List<Entry> entries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {
        return entries(server, period, metric, false);
    }

    /** Bot-only view used by the bot group. Bots bypass the whitelist and prefix filters. */
    static List<Entry> botEntries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {
        return entries(server, period, metric, true);
    }

    private static List<Entry> entries(net.minecraft.server.MinecraftServer server, Period period,
                                       Metric metric, boolean botsOnly) {
        try (var reads = LeaderboardReadScope.open(server)) {
            return LeaderboardReadScope.entries(server, period, metric,
                    () -> readEntries(server, period, metric, botsOnly));
        }
    }

    private static List<Entry> readEntries(net.minecraft.server.MinecraftServer server, Period period,
                                           Metric metric, boolean botsOnly) {
        if (!StatReader.isReady() && period != Period.ALL) {
            throw new IllegalStateException("统计文件尚未完成权威扫描（" + StatReader.progress() + "）");
        }
        LeaderboardState state = LeaderboardState.get(server);
        if (!state.isMetricDisplayEnabled(metric)) return List.of();
        state.rollPeriods(server);
        return StatReader.readAll(server, metric).stream()
                .filter(snapshot -> botsOnly
                        ? isBotIncluded(server, state, snapshot.uuid(), snapshot.name())
                        : isIncluded(server, state, snapshot.uuid(), snapshot.name()))
                .flatMap(snapshot -> {
                    java.util.OptionalLong delta = state.periodDelta(
                            period, snapshot.uuid(), metric, snapshot.value(metric));
                    return delta.isPresent()
                            ? java.util.stream.Stream.of(new Entry(snapshot.uuid(), snapshot.name(), delta.getAsLong()))
                            : java.util.stream.Stream.empty();
                })
                .sorted(Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::name))
                .toList();
    }

    static boolean isIncluded(net.minecraft.server.MinecraftServer server, LeaderboardState state,
                              java.util.UUID uuid, String name) {
        if (state.isManuallyFiltered(uuid, name)) return false;
        boolean manualBot = state.isManualBot(uuid, name);
        if (manualBot) {
            return state.isBotGroupMixed() && (!state.isOnlineOnly() || server.getPlayerManager().getPlayer(uuid) != null);
        }
        boolean bypassPrefixFilters = state.isManuallyAllowed(uuid, name);
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return (!state.isWhitelistOnly() || state.isBotPlayer(uuid, name) || PlayerDirectoryCompat.isAllowed(server, uuid, name))
                && (bypassPrefixFilters || !state.isBotFilterEnabled() || !normalized.startsWith("bot_"))
                && (bypassPrefixFilters || !state.isCustomPlayerFilterEnabled() || !normalized.startsWith("unknown_"))
                && (!state.isOnlineOnly() || server.getPlayerManager().getPlayer(uuid) != null);
    }

    /** Bot group inclusion: whitelist and bot prefix filters do not apply here. */
    static boolean isBotIncluded(net.minecraft.server.MinecraftServer server, LeaderboardState state,
                                 java.util.UUID uuid, String name) {
        if (state.isManuallyFiltered(uuid, name)) return false;
        if (!state.isBotPlayer(uuid, name)) return false;
        return !state.isOnlineOnly() || server.getPlayerManager().getPlayer(uuid) != null;
    }

    /** Classifies a bot by its dominant activity; used by the bot leaderboard and web view. */
    static BotProfile.Type botType(StatSnapshot snapshot) {
        return BotProfile.classify(snapshot.values());
    }

    /**
     * Bot-only hourly rates. Values are stored as hundredths per hour so the
     * leaderboard can sort them as longs while still rendering two decimals.
     */
    static List<Entry> botRateEntries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {
        try (var reads = LeaderboardReadScope.open(server)) {
            LeaderboardState state = LeaderboardState.get(server);
            if (!state.isMetricDisplayEnabled(metric)) return List.of();
            state.rollPeriods(server);
            java.util.List<Entry> entries = new java.util.ArrayList<>();
            for (StatSnapshot snapshot : StatReader.readAll(server)) {
                if (!isBotIncluded(server, state, snapshot.uuid(), snapshot.name())) continue;
                java.util.OptionalLong action = state.periodDelta(period, snapshot.uuid(), metric,
                        snapshot.value(metric));
                java.util.OptionalLong time = state.periodDelta(period, snapshot.uuid(), Metric.PLAY_TIME,
                        snapshot.value(Metric.PLAY_TIME));
                if (action.isEmpty() || time.isEmpty() || time.getAsLong() <= 0L) continue;
                double hours = time.getAsLong() / 72000.0;
                long rate = Math.round(action.getAsLong() / hours * 100.0);
                entries.add(new Entry(snapshot.uuid(), snapshot.name(), rate));
            }
            entries.sort(Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::name));
            return entries;
        }
    }

    static String formatRate(long hundredthsPerHour) {
        return String.format(java.util.Locale.ROOT, "%,.2f/h", hundredthsPerHour / 100.0);
    }

    static String format(Metric metric, long value) {
        if (metric == Metric.PLAY_TIME || metric == Metric.AFK_TIME) return (value / 72000) + "h " + ((value / 1200) % 60) + "m";
        if (metric == Metric.ELYTRA_DISTANCE || metric == Metric.TRAVEL_DISTANCE) return String.format(java.util.Locale.ROOT, "%.1f km", value / 100000.0);
        return Long.toString(value);
    }

    static long total(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) {
            try {
                total = Math.addExact(total, entry.value());
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    public enum Metric {
        FOOD("food", "大胃王榜", Formatting.GOLD, RankBoardMod::foodUsed),
        JUMPS("jumps", "跳跃榜", Formatting.LIGHT_PURPLE, p -> custom(p, Stats.JUMP)),
        MINED("mined", "挖掘榜", Formatting.BLUE, RankBoardMod::mined),
        PLACED("placed", "放置榜", Formatting.DARK_AQUA, RankBoardMod::placed),
        KILLS("kills", "击杀榜", Formatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),
        PVP_KILLS("pvp", "PvP榜", Formatting.DARK_RED, p -> custom(p, Stats.PLAYER_KILLS)),
        DEATHS("deaths", "死亡榜", Formatting.DARK_RED, p -> custom(p, Stats.DEATHS)),
        TRADES("trades", "交易榜", Formatting.GREEN, p -> custom(p, Stats.TRADED_WITH_VILLAGER)),
        PLAY_TIME("playtime", "在线榜", Formatting.AQUA, p -> custom(p, Stats.PLAY_TIME)),
        ELYTRA_DISTANCE("elytra", "飞行榜", Formatting.LIGHT_PURPLE, p -> custom(p, Stats.AVIATE_ONE_CM)),
        FISHING("fishing", "钓鱼榜", Formatting.DARK_BLUE, p -> custom(p, Stats.FISH_CAUGHT)),
        DAMAGE_TAKEN("damage", "受伤榜", Formatting.RED, p -> custom(p, Stats.DAMAGE_TAKEN)),
        DAMAGE_DEALT("dealt", "近战伤害输出榜", Formatting.GOLD, p -> custom(p, Stats.DAMAGE_DEALT)),
        DROPPED("dropped", "丢垃圾榜", Formatting.DARK_GRAY, RankBoardMod::dropped),
        PICKED_UP("picked", "拾荒榜", Formatting.GREEN, RankBoardMod::pickedUp),
        CRAFTED("crafted", "合成榜", Formatting.GOLD, RankBoardMod::crafted),
        REDSTONE_PLACED("redstone", "红石大蛇榜", Formatting.RED, RankBoardMod::redstonePlaced),
        ANIMALS_BRED("bred", "动物繁育榜", Formatting.GREEN, p -> custom(p, Stats.ANIMALS_BRED)),
        SHIELD_BLOCKED("shield", "坚盾榜", Formatting.AQUA, p -> custom(p, Stats.DAMAGE_BLOCKED_BY_SHIELD)),
        ENCHANTED("enchanted", "附魔大师榜", Formatting.DARK_PURPLE, p -> custom(p, Stats.ENCHANT_ITEM)),
        SLEPT("slept", "睡神榜", Formatting.BLUE, p -> custom(p, Stats.SLEEP_IN_BED)),
        TOOLS_BROKEN("broken", "工具毁灭者榜", Formatting.DARK_GRAY, RankBoardMod::toolsBroken),
        TRAVEL_DISTANCE("travel", "旅行家榜", Formatting.AQUA, RankBoardMod::travelDistance),
        ORES_MINED("ores", "矿业大亨榜", Formatting.GOLD, RankBoardMod::oresMined),
        TOTEM_USED("totem", "死里逃生榜", Formatting.YELLOW, RankBoardMod::totemsUsed),
        MUSIC_PLAYED("music", "音乐家榜", Formatting.LIGHT_PURPLE, p -> custom(p, Stats.PLAY_RECORD)),
        TARGET_HITS("target", "神射手榜", Formatting.RED, p -> custom(p, Stats.TARGET_HIT)),
        MOB_DEATHS("mob_victims", "怪物大餐榜", Formatting.DARK_RED, RankBoardMod::killedByMobs, true),
        ZOMBIE_DEATHS("zombie_group", "僵尸加餐榜", Formatting.DARK_GREEN, p -> killedBy(p, MetricCatalog.ZOMBIE_KILLERS), true),
        SKELETON_DEATHS("skeleton_group", "骷髅箭靶榜", Formatting.GRAY, p -> killedBy(p, MetricCatalog.SKELETON_KILLERS), true),
        CREEPER_DEATHS("creeper_group", "苦力怕烟花榜", Formatting.GREEN, p -> killedBy(p, MetricCatalog.CREEPER_KILLERS), true),
        ARTHROPOD_DEATHS("arthropod_group", "虫群口粮榜", Formatting.DARK_PURPLE, p -> killedBy(p, MetricCatalog.ARTHROPOD_KILLERS), true),
        RAIDER_DEATHS("raider_group", "灾厄靶子榜", Formatting.GOLD, p -> killedBy(p, MetricCatalog.RAIDER_KILLERS), true),
        NETHER_DEATHS("nether_group", "下界燃料榜", Formatting.RED, p -> killedBy(p, MetricCatalog.NETHER_KILLERS), true),
        END_DEATHS("end_group", "末地祭品榜", Formatting.LIGHT_PURPLE, p -> killedBy(p, MetricCatalog.END_KILLERS), true),
        WARDEN_DEATHS("warden_group", "监守者玩具榜", Formatting.DARK_AQUA, p -> killedBy(p, MetricCatalog.WARDEN_KILLERS), true),
        VICTIM_BLAZE("victim_blaze", "烈焰人受害者榜", Formatting.DARK_RED, "minecraft:blaze"),
        VICTIM_BOGGED("victim_bogged", "沼骸受害者榜", Formatting.DARK_GREEN, "minecraft:bogged"),
        VICTIM_BREEZE("victim_breeze", "旋风人受害者榜", Formatting.AQUA, "minecraft:breeze"),
        VICTIM_CAMEL_HUSK("victim_camel_husk", "骆驼尸壳受害者榜", Formatting.GOLD, "minecraft:camel_husk"),
        VICTIM_CAVE_SPIDER("victim_cave_spider", "洞穴蜘蛛受害者榜", Formatting.DARK_PURPLE, "minecraft:cave_spider"),
        VICTIM_CREAKING("victim_creaking", "嘎枝受害者榜", Formatting.DARK_GRAY, "minecraft:creaking"),
        VICTIM_CREEPER("victim_creeper", "苦力怕受害者榜", Formatting.GREEN, "minecraft:creeper"),
        VICTIM_DROWNED("victim_drowned", "溺尸受害者榜", Formatting.DARK_AQUA, "minecraft:drowned"),
        VICTIM_ELDER_GUARDIAN("victim_elder_guardian", "远古守卫者受害者榜", Formatting.DARK_AQUA, "minecraft:elder_guardian"),
        VICTIM_ENDER_DRAGON("victim_ender_dragon", "末影龙受害者榜", Formatting.DARK_PURPLE, "minecraft:ender_dragon"),
        VICTIM_ENDERMAN("victim_enderman", "末影人受害者榜", Formatting.LIGHT_PURPLE, "minecraft:enderman"),
        VICTIM_ENDERMITE("victim_endermite", "末影螨受害者榜", Formatting.DARK_PURPLE, "minecraft:endermite"),
        VICTIM_EVOKER("victim_evoker", "唤魔者受害者榜", Formatting.DARK_PURPLE, "minecraft:evoker"),
        VICTIM_GHAST("victim_ghast", "恶魂受害者榜", Formatting.RED, "minecraft:ghast"),
        VICTIM_GUARDIAN("victim_guardian", "守卫者受害者榜", Formatting.AQUA, "minecraft:guardian"),
        VICTIM_HOGLIN("victim_hoglin", "疣猪兽受害者榜", Formatting.DARK_RED, "minecraft:hoglin"),
        VICTIM_HUSK("victim_husk", "尸壳受害者榜", Formatting.GOLD, "minecraft:husk"),
        VICTIM_ILLUSIONER("victim_illusioner", "幻术师受害者榜", Formatting.DARK_PURPLE, "minecraft:illusioner"),
        VICTIM_MAGMA_CUBE("victim_magma_cube", "岩浆怪受害者榜", Formatting.RED, "minecraft:magma_cube"),
        VICTIM_PARCHED("victim_parched", "干尸（Parched）受害者榜", Formatting.YELLOW, "minecraft:parched"),
        VICTIM_PHANTOM("victim_phantom", "幻翼受害者榜", Formatting.DARK_BLUE, "minecraft:phantom"),
        VICTIM_PIGLIN("victim_piglin", "猪灵受害者榜", Formatting.GOLD, "minecraft:piglin"),
        VICTIM_PIGLIN_BRUTE("victim_piglin_brute", "猪灵蛮兵受害者榜", Formatting.DARK_RED, "minecraft:piglin_brute"),
        VICTIM_PILLAGER("victim_pillager", "掠夺者受害者榜", Formatting.GRAY, "minecraft:pillager"),
        VICTIM_RAVAGER("victim_ravager", "劫掠兽受害者榜", Formatting.DARK_GRAY, "minecraft:ravager"),
        VICTIM_SHULKER("victim_shulker", "潜影贝受害者榜", Formatting.LIGHT_PURPLE, "minecraft:shulker"),
        VICTIM_SILVERFISH("victim_silverfish", "蠹虫受害者榜", Formatting.GRAY, "minecraft:silverfish"),
        VICTIM_SKELETON("victim_skeleton", "骷髅受害者榜", Formatting.GRAY, "minecraft:skeleton"),
        VICTIM_SLIME("victim_slime", "史莱姆受害者榜", Formatting.GREEN, "minecraft:slime"),
        VICTIM_SPIDER("victim_spider", "蜘蛛受害者榜", Formatting.DARK_PURPLE, "minecraft:spider"),
        VICTIM_STRAY("victim_stray", "流浪者受害者榜", Formatting.AQUA, "minecraft:stray"),
        VICTIM_VEX("victim_vex", "恼鬼受害者榜", Formatting.LIGHT_PURPLE, "minecraft:vex"),
        VICTIM_VINDICATOR("victim_vindicator", "卫道士受害者榜", Formatting.DARK_RED, "minecraft:vindicator"),
        VICTIM_WARDEN("victim_warden", "监守者受害者榜", Formatting.DARK_AQUA, "minecraft:warden"),
        VICTIM_WITCH("victim_witch", "女巫受害者榜", Formatting.DARK_PURPLE, "minecraft:witch"),
        VICTIM_WITHER("victim_wither", "凋灵受害者榜", Formatting.DARK_GRAY, "minecraft:wither"),
        VICTIM_WITHER_SKELETON("victim_wither_skeleton", "凋灵骷髅受害者榜", Formatting.DARK_GRAY, "minecraft:wither_skeleton"),
        VICTIM_ZOGLIN("victim_zoglin", "僵尸疣猪兽受害者榜", Formatting.DARK_RED, "minecraft:zoglin"),
        VICTIM_ZOMBIE("victim_zombie", "僵尸受害者榜", Formatting.DARK_GREEN, "minecraft:zombie"),
        VICTIM_ZOMBIE_NAUTILUS("victim_zombie_nautilus", "僵尸鹦鹉螺受害者榜", Formatting.DARK_AQUA, "minecraft:zombie_nautilus"),
        VICTIM_ZOMBIE_VILLAGER("victim_zombie_villager", "僵尸村民受害者榜", Formatting.DARK_GREEN, "minecraft:zombie_villager"),
        VICTIM_ZOMBIFIED_PIGLIN("victim_zombified_piglin", "僵尸猪灵受害者榜", Formatting.GOLD, "minecraft:zombified_piglin"),
        VICTIM_BEE("victim_bee", "蜜蜂受害者榜", Formatting.YELLOW, "minecraft:bee"),
        VICTIM_DOLPHIN("victim_dolphin", "海豚受害者榜", Formatting.AQUA, "minecraft:dolphin"),
        VICTIM_GOAT("victim_goat", "山羊受害者榜", Formatting.WHITE, "minecraft:goat"),
        VICTIM_IRON_GOLEM("victim_iron_golem", "铁傀儡受害者榜", Formatting.GRAY, "minecraft:iron_golem"),
        VICTIM_LLAMA("victim_llama", "羊驼受害者榜", Formatting.GOLD, "minecraft:llama"),
        VICTIM_PANDA("victim_panda", "熊猫受害者榜", Formatting.WHITE, "minecraft:panda"),
        VICTIM_POLAR_BEAR("victim_polar_bear", "北极熊受害者榜", Formatting.WHITE, "minecraft:polar_bear"),
        VICTIM_PUFFERFISH("victim_pufferfish", "河豚受害者榜", Formatting.YELLOW, "minecraft:pufferfish"),
        VICTIM_RABBIT("victim_rabbit", "杀手兔受害者榜", Formatting.WHITE, "minecraft:rabbit"),
        VICTIM_TRADER_LLAMA("victim_trader_llama", "行商羊驼受害者榜", Formatting.AQUA, "minecraft:trader_llama"),
        VICTIM_WOLF("victim_wolf", "狼受害者榜", Formatting.GRAY, "minecraft:wolf"),
        AFK_TIME("afk", "摸鱼榜", Formatting.AQUA, RankBoardMod::afkTime),
        BEDROCK_BROKEN("bedrock", "破基岩榜", Formatting.BLUE, RankBoardMod::bedrockBroken);

        final String command;
        final String label;
        final Formatting nameColor;
        final Counter counter;
        final boolean fun;
        final String victimEntityId;
        Metric(String command, String label, Formatting nameColor, Counter counter) {
            this(command, label, nameColor, counter, false, null);
        }
        Metric(String command, String label, Formatting nameColor, Counter counter, boolean fun) {
            this(command, label, nameColor, counter, fun, null);
        }
        Metric(String command, String label, Formatting nameColor, String victimEntityId) {
            this(command, label, nameColor, player -> killedBy(player, Set.of(victimEntityId)), true, victimEntityId);
        }
        Metric(String command, String label, Formatting nameColor, Counter counter, boolean fun, String victimEntityId) {
            this.command = command; this.label = label; this.nameColor = nameColor; this.counter = counter;
            this.fun = fun; this.victimEntityId = victimEntityId;
        }
        long read(ServerPlayerEntity player) { return counter.read(player); }
        String label() { return RankBoardConfig.get().metricLabel(this); }
        int defaultRgb() {
            Integer value = nameColor.getColorValue();
            return value == null ? 0xFFFFFF : value;
        }
        boolean isFun() { return fun; }
        String victimEntityId() { return victimEntityId; }
        boolean isCustomMetric() { return this == AFK_TIME || this == BEDROCK_BROKEN || this == ELYTRA_DISTANCE; }
    }

    public enum Period {
        DAILY("daily", "day", "每日", "今日"), WEEKLY("weekly", "week", "每周", "本周"),
        MONTHLY("monthly", "month", "每月", "本月"), YEARLY("yearly", "year", "每年", "本年"),
        ALL("all", "all", "总计", "总计");
        final String command;
        final String shortCommand;
        final String label;
        final String menuLabel;
        Period(String command, String shortCommand, String label, String menuLabel) {
            this.command = command; this.shortCommand = shortCommand; this.label = label; this.menuLabel = menuLabel;
        }
        String key(LocalDate date) {
            return switch (this) {
                case DAILY -> date.toString();
                case WEEKLY -> date.get(WeekFields.ISO.weekBasedYear()) + "-W" + date.get(WeekFields.ISO.weekOfWeekBasedYear());
                case MONTHLY -> date.getYear() + "-" + date.getMonthValue();
                case YEARLY -> Integer.toString(date.getYear());
                case ALL -> "all";
            };
        }
    }

    private static long custom(ServerPlayerEntity player, net.minecraft.util.Identifier stat) {
        try {
            return player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(stat));
        } catch (RuntimeException ignored) {
            // Vanilla only registers the custom statistics that exist in this game version.
            return 0L;
        }
    }
    private static long foodUsed(ServerPlayerEntity player) { return StatReader.foodCounterItems().stream().mapToLong(item -> player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item))).sum(); }
    private static long mined(ServerPlayerEntity player) { return StatReader.counterBlocks().stream().mapToLong(block -> player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(block))).sum(); }
    private static long placed(ServerPlayerEntity player) { return StatReader.blockCounterItems().stream().mapToLong(item -> player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item))).sum(); }
    private static long dropped(ServerPlayerEntity player) { return StatReader.counterItems().stream().mapToLong(item -> player.getStatHandler().getStat(Stats.DROPPED.getOrCreateStat(item))).sum(); }
    private static long pickedUp(ServerPlayerEntity player) { return StatReader.counterItems().stream().mapToLong(item -> player.getStatHandler().getStat(Stats.PICKED_UP.getOrCreateStat(item))).sum(); }
    private static long crafted(ServerPlayerEntity player) { return StatReader.counterItems().stream().mapToLong(item -> player.getStatHandler().getStat(Stats.CRAFTED.getOrCreateStat(item))).sum(); }
    private static long redstonePlaced(ServerPlayerEntity player) { return StatReader.redstoneCounterItems().stream().mapToLong(item -> player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item))).sum(); }
    private static long toolsBroken(ServerPlayerEntity player) { return Registries.ITEM.stream().mapToLong(item -> player.getStatHandler().getStat(Stats.BROKEN.getOrCreateStat(item))).sum(); }
    private static long travelDistance(ServerPlayerEntity player) {
        long total = 0L;
        for (String id : MetricCatalog.TRAVEL_CUSTOM_STATS) {
            try {
                total += custom(player, net.minecraft.util.Identifier.of(id));
            } catch (RuntimeException ignored) {
                // Statistic introduced by a newer Minecraft version; ignore it here.
            }
        }
        return total;
    }
    private static long oresMined(ServerPlayerEntity player) { return Registries.BLOCK.stream().filter(block -> MetricCatalog.ORE_BLOCKS.contains(Registries.BLOCK.getId(block).toString())).mapToLong(block -> player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(block))).sum(); }
    private static long totemsUsed(ServerPlayerEntity player) { return Registries.ITEM.stream().filter(item -> Registries.ITEM.getId(item).toString().equals("minecraft:totem_of_undying")).mapToLong(item -> player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item))).sum(); }
    private static long killedByMobs(ServerPlayerEntity player) { return Registries.ENTITY_TYPE.stream().filter(type -> !Registries.ENTITY_TYPE.getId(type).toString().equals("minecraft:player")).mapToLong(type -> player.getStatHandler().getStat(Stats.KILLED_BY.getOrCreateStat(type))).sum(); }
    private static long killedBy(ServerPlayerEntity player, Set<String> killers) { return Registries.ENTITY_TYPE.stream().filter(type -> killers.contains(Registries.ENTITY_TYPE.getId(type).toString())).mapToLong(type -> player.getStatHandler().getStat(Stats.KILLED_BY.getOrCreateStat(type))).sum(); }
    private static long afkTime(ServerPlayerEntity player) {
        try { return LeaderboardState.get(PlayerCompat.server(player)).customMetric(player.getUuid(), Metric.AFK_TIME); }
        catch (RuntimeException ignored) { return 0L; }
    }
    private static long bedrockBroken(ServerPlayerEntity player) {
        try { return LeaderboardState.get(PlayerCompat.server(player)).customMetric(player.getUuid(), Metric.BEDROCK_BROKEN); }
        catch (RuntimeException ignored) { return 0L; }
    }
    static boolean isRedstoneComponent(Item item) {
        String path = Registries.ITEM.getId(item).getPath();
        return REDSTONE_COMPONENTS.contains(path)
                || path.endsWith("_button")
                || path.endsWith("_pressure_plate")
                || path.endsWith("_door")
                || path.endsWith("_trapdoor")
                || path.endsWith("_fence_gate")
                || path.endsWith("_bulb");
    }

    @FunctionalInterface interface Counter { long read(ServerPlayerEntity player); }
    record Entry(java.util.UUID uuid, String name, long value) { }
    private enum WhitelistMode { SERVER, MOD, NONE }
    private record ColorPreset(String key, String label, Formatting formatting) {
        String hex() { return String.format(java.util.Locale.ROOT, "#%06X", formatting.getColorValue()); }
    }
}

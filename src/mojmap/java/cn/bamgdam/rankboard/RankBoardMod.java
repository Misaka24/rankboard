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
import net.minecraft.world.level.block.Block;
import net.minecraft.core.component.DataComponents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
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
import java.util.Set;
import java.util.UUID;
import java.net.URI;

public final class RankBoardMod implements ModInitializer {
    public static final String MOD_ID = "rankboard";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int REFRESH_INTERVAL_TICKS = 600;
    private static final Set<UUID> LOOK_MENU_HELD = new HashSet<>();
    private static final Set<String> REDSTONE_COMPONENTS = Set.of(
            "redstone", "redstone_torch", "repeater", "comparator", "observer", "piston", "sticky_piston",
            "dispenser", "dropper", "hopper", "lever", "tripwire_hook", "target", "daylight_detector",
            "note_block", "redstone_block", "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod",
            "trapped_chest", "powered_rail", "detector_rail", "activator_rail", "rail", "lectern", "jukebox", "bell",
            "redstone_lamp", "tnt", "big_dripleaf", "crafter", "command_block", "chain_command_block",
            "repeating_command_block");
    private static final List<ColorPreset> COLOR_PRESETS = List.of(
            new ColorPreset("black", "黑色", ChatFormatting.BLACK), new ColorPreset("dark_blue", "深蓝色", ChatFormatting.DARK_BLUE),
            new ColorPreset("dark_green", "深绿色", ChatFormatting.DARK_GREEN), new ColorPreset("dark_aqua", "深青色", ChatFormatting.DARK_AQUA),
            new ColorPreset("dark_red", "深红色", ChatFormatting.DARK_RED), new ColorPreset("dark_purple", "深紫色", ChatFormatting.DARK_PURPLE),
            new ColorPreset("gold", "金色", ChatFormatting.GOLD), new ColorPreset("gray", "灰色", ChatFormatting.GRAY),
            new ColorPreset("dark_gray", "深灰色", ChatFormatting.DARK_GRAY), new ColorPreset("blue", "蓝色", ChatFormatting.BLUE),
            new ColorPreset("green", "绿色", ChatFormatting.GREEN), new ColorPreset("aqua", "青色", ChatFormatting.AQUA),
            new ColorPreset("red", "红色", ChatFormatting.RED), new ColorPreset("light_purple", "粉紫色", ChatFormatting.LIGHT_PURPLE),
            new ColorPreset("yellow", "黄色", ChatFormatting.YELLOW), new ColorPreset("white", "白色", ChatFormatting.WHITE));
    private int ticks;

    @Override
    public void onInitialize() {
        LOGGER.info("RankBoard initialized for Minecraft 26.x");
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RuntimeMetricTracker.start(server);
            RankBoardConfig.load(server);
            RankBoardLanguage.load(server);
            RankBoardWhitelist.load(server);
            BoardService.enforceForeignScoreboardPolicy(server);
            StatReader.initialize(server);
            WebDashboard.start(server);
        });
        // QuickBackupMulti can start the restored server from inside SERVER_STOPPED.
        // Clear all server-bound state one phase earlier so the new server cannot
        // reuse stale rbp_/rbo_ objective/session references during its first join.
        ServerLifecycleEvents.SERVER_STOPPING.register(this::cleanupServerState);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            // Keep the UUID-keyed statistic cache aligned with the player's current
            // profile name, including a name changed since the last login.
            StatReader.updateName(player.getUUID(), player.getScoreboardName());
            LeaderboardState.get(server).ensurePlayer(player);
            RuntimeMetricTracker.join(server, player);
            AvatarCache.cacheOnJoin(server, player);
            BoardService.restore(player);
            sendJoinExperience(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RuntimeMetricTracker.disconnect(server, handler.getPlayer());
            StatReader.capturePlayer(server, handler.getPlayer());
            LOOK_MENU_HELD.remove(handler.getPlayer().getUUID());
            BoardService.disconnect(handler.getPlayer());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RuntimeMetricTracker.tick(server);
            BoardService.tickCarousel(server);
            BoardService.tickActivity(server);
            handleLookUpSneakMenu(server);
            if (++ticks >= REFRESH_INTERVAL_TICKS) {
                ticks = 0;
                LeaderboardState.get(server).rollPeriods(server);
                BoardService.refreshAll(server);
                BoardService.enforceForeignScoreboardPolicy(server);
            }
        });
    }

    private void cleanupServerState(net.minecraft.server.MinecraftServer server) {
        RuntimeMetricTracker.stop(server);
        PlayerNameColors.clear(server);
        BoardService.clearSessions();
        WebDashboard.stop();
        StatReader.stopWarmup();
        LOOK_MENU_HELD.clear();
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                                  CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("leaderboard")
                .requires(source -> CommandPermissionCompat.has(source, 0)).executes(context -> menu(context.getSource()));
        root.then(Commands.literal("help").requires(source -> RankBoardConfig.get().helpVisible(source))
                .executes(context -> helpGrouped(context.getSource(), "menu"))
                .then(Commands.literal("player").executes(context -> helpGrouped(context.getSource(), "player")))
                .then(Commands.literal("scoreboard").executes(context -> helpGrouped(context.getSource(), "scoreboard")))
                .then(Commands.literal("web").executes(context -> helpGrouped(context.getSource(), "web")))
                .then(Commands.literal("config").requires(source -> CommandPermissionCompat.has(source, 2))
                        .executes(context -> helpGrouped(context.getSource(), "config"))
                        .then(Commands.literal("general").executes(context -> helpGrouped(context.getSource(), "config-general")))
                        .then(Commands.literal("scoreboard").executes(context -> helpGrouped(context.getSource(), "config-scoreboard")))
                        .then(Commands.literal("web").executes(context -> helpGrouped(context.getSource(), "config-web"))))
                .then(Commands.literal("admin").requires(source -> CommandPermissionCompat.has(source, 2))
                        .executes(context -> helpGrouped(context.getSource(), "admin"))
                        .then(Commands.literal("players").executes(context -> helpGrouped(context.getSource(), "admin-players")))
                        .then(Commands.literal("scoreboard").executes(context -> helpGrouped(context.getSource(), "admin-scoreboard")))
                        .then(Commands.literal("web").executes(context -> helpGrouped(context.getSource(), "admin-web")))
                        .then(Commands.literal("config").executes(context -> helpGrouped(context.getSource(), "admin-config")))));
        root.then(Commands.literal("intro")
                .executes(context -> metricIntroduction(context.getSource())));
        root.then(Commands.literal("mine")
                .executes(context -> showMyScores(context.getSource(), -1, "总计"))
                .then(Commands.literal("all").executes(context -> showMyScores(context.getSource(), -1, "总计")))
                .then(Commands.literal("day").executes(context -> showMyScores(context.getSource(), 1, "最近一日")))
                .then(Commands.literal("week").executes(context -> showMyScores(context.getSource(), 7, "最近一周")))
                .then(Commands.literal("month").executes(context -> showMyScores(context.getSource(), 30, "最近一月"))));
        root.then(Commands.literal("language")
                .executes(context -> languageStatus(context.getSource()))
                .then(Commands.literal("status").executes(context -> languageStatus(context.getSource())))
                .then(languageDefaultCommand("default"))
                .then(languageDefaultCommand("global"))
                .then(Commands.argument("code", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(RankBoardLanguage.codes(), builder))
                        .executes(context -> setLanguage(context.getSource(),
                                StringArgumentType.getString(context, "code").toLowerCase(java.util.Locale.ROOT)))));
        LiteralArgumentBuilder<CommandSourceStack> export = Commands.literal("export")
                .requires(source -> CommandPermissionCompat.has(source, 2));
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<CommandSourceStack> periodNode = Commands.literal(period.command);
            for (Metric metric : Metric.values()) {
                periodNode.then(Commands.literal(metric.command)
                        .executes(context -> exportRanking(context.getSource(), period, metric)));
            }
            export.then(periodNode);
        }
        root.then(export);
        root.then(buildMetricDataCommands());
        root.then(Commands.literal("carousel")
                .then(Commands.literal("true").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> BoardService.carouselStatus(context.getSource())))
                .then(Commands.literal("color").requires(source -> CommandPermissionCompat.has(source, 2))
                        .then(Commands.literal("true").executes(context -> setCarouselColor(context.getSource(), true)))
                        .then(Commands.literal("false").executes(context -> setCarouselColor(context.getSource(), false)))
                        .then(Commands.literal("status").executes(context -> setCarouselColorStatus(context.getSource())))));
        root.then(Commands.literal("webtheme").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("icon").executes(context -> setWebThemeMode(context.getSource(), true)))
                .then(Commands.literal("blue").executes(context -> setWebThemeMode(context.getSource(), false)))
                .then(Commands.literal("true").executes(context -> setWebThemeMode(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setWebThemeMode(context.getSource(), false)))
                .then(Commands.literal("rgb")
                        .then(Commands.argument("color", StringArgumentType.word())
                                .executes(context -> setWebThemeRgb(context.getSource(),
                                        StringArgumentType.getString(context, "color")))))
                .then(Commands.literal("status").executes(context -> webThemeModeStatus(context.getSource()))));
        root.then(Commands.literal("webswitch").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> webSwitchStatus(context.getSource()))
                .then(Commands.literal("status").executes(context -> webSwitchStatus(context.getSource())))
                .then(Commands.literal("list").executes(context -> webSwitchList(context.getSource())))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> setConfig(context.getSource(), "web-switcher-name",
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("weight")
                        .then(Commands.argument("weight", IntegerArgumentType.integer(1, 10000))
                                .executes(context -> setConfig(context.getSource(), "web-switcher-weight",
                                        Integer.toString(IntegerArgumentType.getInteger(context, "weight"))))))
                .then(Commands.literal("add")
                        .then(Commands.argument("address", StringArgumentType.word())
                                .executes(context -> modifyWebSwitchPeer(context.getSource(), true,
                                        StringArgumentType.getString(context, "address")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("address", StringArgumentType.word())
                                .executes(context -> modifyWebSwitchPeer(context.getSource(), false,
                                        StringArgumentType.getString(context, "address"))))));
        root.then(Commands.literal("display")
                .then(Commands.literal("on").executes(context -> BoardService.enable(context.getSource())))
                .then(Commands.literal("off").executes(context -> BoardService.disable(context.getSource()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(source -> CommandPermissionCompat.has(source, 2))
                                .executes(context -> BoardService.disable(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(buildSelectionCommands(false)));
        root.then(Commands.literal("namecolor").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("true").executes(context -> setNameColor(context.getSource(), "true")))
                .then(Commands.literal("false").executes(context -> setNameColor(context.getSource(), "false")))
                .then(Commands.literal("scoreboard-only").executes(context -> setNameColor(context.getSource(), "scoreboard-only")))
                .then(Commands.literal("on").executes(context -> setNameColor(context.getSource(), "true")))
                .then(Commands.literal("off").executes(context -> setNameColor(context.getSource(), "false")))
                .then(Commands.literal("status").executes(context -> nameColorStatus(context.getSource()))));
        root.then(Commands.literal("bedrock").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("true").executes(context -> setConfig(context.getSource(),
                        "bedrock-break-leaderboard-enabled", "true")))
                .then(Commands.literal("false").executes(context -> setConfig(context.getSource(),
                        "bedrock-break-leaderboard-enabled", "false")))
                .then(Commands.literal("status").executes(context -> getConfig(context.getSource(),
                        "bedrock-break-leaderboard-enabled"))));
        root.then(buildColorCommands());
        root.then(buildLabelCommands());
        root.then(Commands.literal("lookmenu")
                .then(Commands.literal("true").executes(context -> setLookMenu(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setLookMenu(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> lookMenuStatus(context.getSource())))
                .then(Commands.literal("global").requires(source -> CommandPermissionCompat.has(source, 2))
                        .then(Commands.literal("true").executes(context -> setGlobalLookMenu(context.getSource(), true)))
                        .then(Commands.literal("false").executes(context -> setGlobalLookMenu(context.getSource(), false)))
                        .then(Commands.literal("status").executes(context -> globalLookMenuStatus(context.getSource())))));
        root.then(Commands.literal("joinmenu")
                .then(Commands.literal("true").executes(context -> setJoinMenu(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setJoinMenu(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> joinMenuStatus(context.getSource()))));
        LiteralArgumentBuilder<CommandSourceStack> displayFilter = Commands.literal("displayfilter")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> metricToggleMenu(context.getSource()));
        for (Metric metric : Metric.values()) {
            displayFilter.then(Commands.literal(metric.command)
                    .then(Commands.literal("true").executes(context -> setMetricDisplay(context.getSource(), metric, true)))
                    .then(Commands.literal("false").executes(context -> setMetricDisplay(context.getSource(), metric, false)))
                    .then(Commands.literal("enable").executes(context -> setMetricDisplay(context.getSource(), metric, true)))
                    .then(Commands.literal("disable").executes(context -> setMetricDisplay(context.getSource(), metric, false)))
                    .then(Commands.literal("status").executes(context -> metricDisplayStatus(context.getSource(), metric))));
        }
        root.then(displayFilter);
        root.then(Commands.literal("scoreboard").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("clear").executes(context -> BoardService.clearVanilla(context.getSource())))
                .then(Commands.literal("cleanup").executes(context -> BoardService.clearForeignScoreboards(context.getSource())))
                .then(Commands.literal("blocking")
                        .then(Commands.literal("true").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), true)))
                        .then(Commands.literal("false").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), false)))
                        .then(Commands.literal("enable").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), true)))
                        .then(Commands.literal("disable").executes(context -> BoardService.setForeignScoreboardBlocking(context.getSource(), false)))
                        .then(Commands.literal("status").executes(context -> BoardService.foreignScoreboardBlockingStatus(context.getSource()))))
                .then(buildSelectionCommands(true)));
        root.then(Commands.literal("whitelist").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("true").executes(context -> setWhitelistOnly(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setWhitelistOnly(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> setWhitelistOnly(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setWhitelistOnly(context.getSource(), false)))
                .then(Commands.literal("setup")
                        .then(Commands.literal("server").executes(context -> configureWhitelistMode(context.getSource(), WhitelistMode.SERVER)))
                        .then(Commands.literal("mod").executes(context -> configureWhitelistMode(context.getSource(), WhitelistMode.MOD)))
                        .then(Commands.literal("none").executes(context -> configureWhitelistMode(context.getSource(), WhitelistMode.NONE)))
                        .then(Commands.literal("later").executes(context -> skipWhitelistDataRead(context.getSource()))))
                .then(Commands.literal("status").executes(context -> whitelistStatus(context.getSource()))));
        root.then(Commands.literal("botfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("true").executes(context -> setBotFilter(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setBotFilter(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> setBotFilter(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setBotFilter(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> botFilterStatus(context.getSource()))));
        root.then(Commands.literal("customfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("true").executes(context -> setCustomFilter(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setCustomFilter(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> setCustomFilter(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setCustomFilter(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> customFilterStatus(context.getSource()))));
        root.then(Commands.literal("onlinefilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("true").executes(context -> setOnlineFilter(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setOnlineFilter(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> setOnlineFilter(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setOnlineFilter(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> onlineFilterStatus(context.getSource()))));
        root.then(Commands.literal("modwhitelist").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("add")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> modifyModWhitelist(context.getSource(), true,
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> modifyModWhitelist(context.getSource(), false,
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("list").executes(context -> listModWhitelist(context.getSource())))
                .then(Commands.literal("reload").executes(context -> reloadModWhitelist(context.getSource()))));
        root.then(Commands.literal("recipients").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> recipientFilterStatus(context.getSource()))
                .then(Commands.literal("status").executes(context -> recipientFilterStatus(context.getSource())))
                .then(Commands.literal("fake-only").executes(context -> setRecipientFilter(context.getSource(), "fake-only")))
                .then(Commands.literal("false").executes(context -> setRecipientFilter(context.getSource(), "false")))
                .then(Commands.literal("whitelist").executes(context -> setRecipientFilter(context.getSource(), "whitelist")))
                .then(Commands.literal("blacklist").executes(context -> setRecipientFilter(context.getSource(), "blacklist"))));
        root.then(Commands.literal("lookup").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("whitelist").executes(context -> MojangNameLookup.lookupWhitelist(context.getSource())))
                .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(context -> MojangNameLookup.lookupOne(context.getSource(),
                                StringArgumentType.getString(context, "uuid")))));
        root.then(Commands.literal("cache").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("status").executes(context -> cacheStatus(context.getSource())))
                .then(Commands.literal("reload").executes(context -> reloadCache(context.getSource())))
                .then(Commands.literal("threads")
                        .executes(context -> cacheThreadsStatus(context.getSource()))
                        .then(Commands.literal("status").executes(context -> cacheThreadsStatus(context.getSource())))
                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 256))
                                .executes(context -> setCacheThreads(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count"))))));
        root.then(Commands.literal("ratelimit").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("clear").executes(context -> clearRateLimits(context.getSource()))));
        root.then(Commands.literal("config").requires(source -> CommandPermissionCompat.has(source, 2))
                .executes(context -> listConfig(context.getSource()))
                .then(Commands.literal("list").executes(context -> listConfig(context.getSource())))
                .then(Commands.literal("reload").executes(context -> reloadConfig(context.getSource())))
                .then(Commands.literal("get")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        RankBoardConfig.optionKeys(), builder))
                                .executes(context -> getConfig(context.getSource(),
                                        StringArgumentType.getString(context, "key")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        RankBoardConfig.optionKeys(), builder))
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(context -> setConfig(context.getSource(),
                                                StringArgumentType.getString(context, "key"),
                                                StringArgumentType.getString(context, "value")))))));
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<CommandSourceStack> periodNode = Commands.literal(period.command);
            for (Metric metric : Metric.values()) {
                periodNode.then(Commands.literal(metric.command)
                        .executes(context -> show(context.getSource(), period, metric, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> show(context.getSource(), period, metric, IntegerArgumentType.getInteger(context, "limit")))));
            }
            root.then(periodNode);
        }
        dispatcher.register(root);
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildSelectionCommands(boolean vanilla) {
        LiteralArgumentBuilder<CommandSourceStack> periods = Commands.literal("show");
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<CommandSourceStack> periodNode = Commands.literal(period.command);
            for (Metric metric : Metric.values()) {
                LiteralArgumentBuilder<CommandSourceStack> metricNode = Commands.literal(metric.command)
                        .executes(context -> vanilla
                                ? BoardService.writeVanilla(context.getSource(), period, metric)
                                : BoardService.enable(context.getSource(), period, metric));
                if (!vanilla) {
                    metricNode.then(Commands.argument("player", EntityArgument.player())
                            .requires(source -> CommandPermissionCompat.has(source, 2))
                            .executes(context -> BoardService.enable(context.getSource(),
                                    EntityArgument.getPlayer(context, "player"), period, metric)));
                }
                periodNode.then(metricNode);
            }
            periods.then(periodNode);
        }
        return periods;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildColorCommands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("color")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("list").executes(context -> listMetricColors(context.getSource())));
        LiteralArgumentBuilder<CommandSourceStack> reset = Commands.literal("reset")
                .then(Commands.literal("all").executes(context -> resetAllMetricColors(context.getSource())));
        for (Metric metric : Metric.values()) {
            root.then(Commands.literal(metric.command)
                    .executes(context -> showColorPresets(context.getSource(), metric))
                    .then(Commands.argument("value", StringArgumentType.word())
                            .suggests((context, builder) -> suggestColorPresets(builder))
                            .executes(context -> setMetricColor(context.getSource(), metric,
                                    StringArgumentType.getString(context, "value")))));
            reset.then(Commands.literal(metric.command)
                    .executes(context -> resetMetricColor(context.getSource(), metric)));
        }
        return root.then(reset);
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildLabelCommands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("label")
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("list").executes(context -> listMetricLabels(context.getSource())));
        LiteralArgumentBuilder<CommandSourceStack> reset = Commands.literal("reset")
                .then(Commands.literal("all").executes(context -> resetAllMetricLabels(context.getSource())));
        for (Metric metric : Metric.values()) {
            root.then(Commands.literal(metric.command)
                    .executes(context -> showMetricLabel(context.getSource(), metric))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(context -> setMetricLabel(context.getSource(), metric,
                                    StringArgumentType.getString(context, "name")))));
            reset.then(Commands.literal(metric.command)
                    .executes(context -> resetMetricLabel(context.getSource(), metric)));
        }
        return root.then(reset);
    }

    private int helpGrouped(CommandSourceStack source, String group) {
        boolean op = CommandPermissionCompat.has(source, 2);
        if (group.equals("menu")) {
            Component line = clickable("[" + localized(source, "help.player") + "]", ChatFormatting.AQUA, "/leaderboard help player", localized(source, "help.tooltip.player"))
                    .copy().append(Component.literal(" "))
                    .append(clickable("[" + localized(source, "help.scoreboard") + "]", ChatFormatting.YELLOW, "/leaderboard help scoreboard", localized(source, "help.tooltip.scoreboard")))
                    .append(Component.literal(" "))
                    .append(clickable("[" + localized(source, "help.web") + "]", ChatFormatting.GREEN, "/leaderboard help web", localized(source, "help.tooltip.web")));
            if (op) line = line.copy().append(Component.literal(" "))
                    .append(clickable("[" + localized(source, "help.admin") + "]", ChatFormatting.RED, "/leaderboard help admin", localized(source, "help.tooltip.admin")));
            Component menuLine = line;
            source.sendSuccess(() -> menuLine, false);
            sendLanguageHelp(source);
            if (op) source.sendSuccess(() -> whitelistSetupButtons(source, "whitelist.heading"), false);
            return 1;
        }
        source.sendSuccess(() -> clickable("[" + localized(source, "help.back") + "]", ChatFormatting.GRAY,
                "/leaderboard help", localized(source, "help.tooltip.back")), false);
        switch (group) {
            case "player" -> {
                source.sendSuccess(() -> Component.literal(localized(source, "menu.join_compact")
                        + "：" + localized(source, "join.simple_help")), false);
                helpCommand(source, "/leaderboard language <zh|en|status>", "/leaderboard language ",
                        "选择聊天提示语言 / Choose Chinese or English chat prompts");
                if (op) helpCommand(source, "/leaderboard language default <语言>", "/leaderboard language default ",
                        "OP 设置全服默认聊天语言；会更新在线和已记录玩家");
                helpCommand(source, "/leaderboard", "/leaderboard", "打开排行榜菜单");
                helpCommand(source, "/leaderboard mine", "/leaderboard mine", "查询所有个人统计并显示总览");
                helpCommand(source, "/leaderboard intro", "/leaderboard intro", "介绍所有可用榜单");
                helpCommand(source, "/leaderboard mine <all|day|week|month>", "/leaderboard mine ", "查询指定周期的个人统计");
                helpCommand(source, "/leaderboard <周期> <榜单> [数量]", "/leaderboard all playtime ", "查看排行榜");
                helpCommand(source, "/leaderboard carousel true|false|status", "/leaderboard carousel ", "控制榜单轮播");
                helpCommand(source, "/leaderboard lookmenu true|false|status", "/leaderboard lookmenu ", "关闭或开启自己的抬头蹲起菜单");
                helpCommand(source, "/leaderboard joinmenu true|false|status", "/leaderboard joinmenu ", "关闭或开启自己的进服排行榜菜单");
            }
            case "scoreboard" -> {
                helpCommand(source, "/leaderboard display show <周期> <榜单>", "/leaderboard display show ", "显示个人单榜计分板");
                helpCommand(source, "/leaderboard display on", "/leaderboard display on", "恢复关闭前的个人计分板");
                helpCommand(source, "/leaderboard display off", "/leaderboard display off", "关闭个人计分板");
                helpCommand(source, "/leaderboard mine", "/leaderboard mine", "显示个人所有榜单总览");
                if (op) {
                    source.sendSuccess(() -> Component.literal("=== " + localized(source, "help.scoreboard_metrics") + " ===")
                            .withStyle(ChatFormatting.GRAY), false);
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
                    source.sendSuccess(() -> websiteButton(source), false);
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
                source.sendSuccess(() -> Component.literal(
                        "配置文件：config/rankboard/rankboard-web.properties"), false);
            }
            case "admin" -> {
                if (!op) return 0;
                Component modules = clickable("[" + localized(source, "help.admin_players") + "]", ChatFormatting.AQUA,
                                "/leaderboard help admin players", localized(source, "help.tooltip.admin_players"))
                        .copy().append(Component.literal(" "))
                        .append(clickable("[" + localized(source, "help.admin_scoreboard") + "]", ChatFormatting.YELLOW,
                                "/leaderboard help admin scoreboard", localized(source, "help.tooltip.admin_scoreboard")))
                        .append(Component.literal(" "))
                        .append(clickable("[" + localized(source, "help.admin_web") + "]", ChatFormatting.GREEN,
                                "/leaderboard help admin web", localized(source, "help.tooltip.admin_web")))
                        .append(Component.literal(" "))
                        .append(clickable("[" + localized(source, "help.admin_config") + "]", ChatFormatting.LIGHT_PURPLE,
                                "/leaderboard help admin config", localized(source, "help.tooltip.admin_config")));
                source.sendSuccess(() -> modules, false);
            }
            case "admin-players" -> {
                if (!op) return 0;
                helpCommand(source, "/leaderboard data <set|add|get|reset> <玩家> <榜单> [数值]",
                        "/leaderboard data ", "测试榜单数值；只覆盖 RankBoard 显示，不修改原版统计，reset 可恢复");
                helpCommand(source, "/leaderboard whitelist <true|false|status>", "/leaderboard whitelist ", "控制服务器白名单筛选");
                helpCommand(source, "/leaderboard whitelist setup <server|mod|none>", "/leaderboard whitelist setup ",
                        "首次安装时选择服务器白名单、模组白名单或无白名单；随后可选择是否读取榜单数据");
                source.sendSuccess(() -> whitelistSetupButtons(source, "whitelist.heading"), false);
                helpCommand(source, "/leaderboard modwhitelist <add|remove|list|reload>", "/leaderboard modwhitelist ", "管理模组自带白名单");
                helpCommand(source, "/leaderboard recipients <fake-only|false|whitelist|blacklist|status>", "/leaderboard recipients ", "控制哪些在线玩家接收个人榜单数据；白名单和黑名单复用模组名单");
                helpCommand(source, "/leaderboard botfilter <true|false|status>", "/leaderboard botfilter ", "筛选 bot_ 前缀玩家；立即生效");
                helpCommand(source, "/leaderboard customfilter <true|false|status>", "/leaderboard customfilter ", "筛选无法识别身份的历史玩家；立即生效");
                helpCommand(source, "/leaderboard onlinefilter <true|false|status>", "/leaderboard onlinefilter ", "只显示在线玩家；立即生效");
                helpCommand(source, "/leaderboard lookup <uuid|whitelist>", "/leaderboard lookup ", "查询 Mojang 玩家名或批量补全白名单名称");
            }
            case "admin-scoreboard" -> {
                if (!op) return 0;
                helpCommand(source, "/leaderboard scoreboard show <周期> <榜单>", "/leaderboard scoreboard show ", "显示全服共享原版侧边栏；不会改变玩家名字颜色");
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
                    source.sendSuccess(() -> websiteButton(source), false);
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
                source.sendSuccess(() -> clickable("[" + localized(source, "help.open_config") + "]", ChatFormatting.LIGHT_PURPLE,
                        "/leaderboard help config", localized(source, "help.tooltip.config")), false);
            }
            case "config" -> {
                if (!op) return 0;
                Component modules = clickable("[" + localized(source, "help.config_general") + "]", ChatFormatting.GOLD,
                                "/leaderboard help config general", localized(source, "help.tooltip.config_general"))
                        .copy().append(Component.literal(" "))
                        .append(clickable("[" + localized(source, "help.config_scoreboard") + "]", ChatFormatting.YELLOW,
                                "/leaderboard help config scoreboard", localized(source, "help.tooltip.config_scoreboard")))
                        .append(Component.literal(" "))
                        .append(clickable("[" + localized(source, "help.config_web") + "]", ChatFormatting.AQUA,
                                "/leaderboard help config web", localized(source, "help.tooltip.config_web")));
                source.sendSuccess(() -> modules, false);
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
                for (Metric metric : Metric.values()) configHelp(source, "metric-label-" + metric.command);
                for (Metric metric : Metric.values()) configHelp(source, "metric-color-" + metric.command);
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

    private LiteralArgumentBuilder<CommandSourceStack> buildMetricDataCommands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("data")
                .requires(source -> CommandPermissionCompat.has(source, 2));
        root.then(playerMetricCommand("get", false, false));
        root.then(playerMetricCommand("set", true, false));
        root.then(playerMetricCommand("add", true, true));
        LiteralArgumentBuilder<CommandSourceStack> reset = Commands.literal("reset");
        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, EntitySelector> resetPlayer =
                Commands.argument("player", EntityArgument.player());
        for (Metric metric : Metric.values()) {
            resetPlayer.then(Commands.literal(metric.command)
                    .executes(context -> resetMetricData(context.getSource(),
                            EntityArgument.getPlayer(context, "player"), metric)));
        }
        resetPlayer.then(Commands.literal("all")
                .executes(context -> resetAllMetricData(context.getSource(),
                        EntityArgument.getPlayer(context, "player"))));
        reset.then(resetPlayer);
        root.then(reset);
        return root;
    }

    private LiteralArgumentBuilder<CommandSourceStack> playerMetricCommand(String operation,
                                                                             boolean takesValue,
                                                                             boolean add) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(operation);
        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, EntitySelector> playerNode =
                Commands.argument("player", EntityArgument.player());
        for (Metric metric : Metric.values()) {
            LiteralArgumentBuilder<CommandSourceStack> metricNode = Commands.literal(metric.command);
            if (takesValue) {
                metricNode.then(Commands.argument("value", LongArgumentType.longArg(0L))
                        .executes(context -> setMetricData(context.getSource(),
                                EntityArgument.getPlayer(context, "player"), metric,
                                LongArgumentType.getLong(context, "value"), add)));
            } else {
                metricNode.executes(context -> getMetricData(context.getSource(),
                        EntityArgument.getPlayer(context, "player"), metric));
            }
            playerNode.then(metricNode);
        }
        root.then(playerNode);
        return root;
    }

    private int getMetricData(CommandSourceStack source, ServerPlayer player, Metric metric) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        long value = state.applyCustomMetrics(StatReader.fromPlayer(player, metric)).value(metric);
        source.sendSuccess(() -> Component.literal(localized(source, "data.get", player.getName().getString(),
                localizedMetric(source, metric), String.format(java.util.Locale.ROOT, "%,d", value), format(metric, value)))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private int setMetricData(CommandSourceStack source, ServerPlayer player, Metric metric,
                              long input, boolean add) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        long value = input;
        if (add) {
            long current = state.applyCustomMetrics(StatReader.fromPlayer(player, metric)).value(metric);
            try { value = Math.addExact(current, input); }
            catch (ArithmeticException ignored) { value = Long.MAX_VALUE; }
        }
        state.setMetricOverride(player.getUUID(), metric, value);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        long displayValue = value;
        String feedback = localized(source, add ? "data.add" : "data.set", player.getName().getString(),
                localizedMetric(source, metric), String.format(java.util.Locale.ROOT, "%,d", displayValue),
                format(metric, displayValue), "/leaderboard data reset " + player.getName().getString() + " " + metric.command);
        source.sendSuccess(() -> Component.literal(feedback)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private int resetMetricData(CommandSourceStack source, ServerPlayer player, Metric metric) {
        boolean removed = LeaderboardState.get(source.getServer()).clearMetricOverride(player.getUUID(), metric);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        String feedback = localized(source, removed ? "data.reset" : "data.reset.none",
                player.getName().getString(), localizedMetric(source, metric));
        source.sendSuccess(() -> Component.literal(feedback)
                .withStyle(removed ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return 1;
    }

    private int resetAllMetricData(CommandSourceStack source, ServerPlayer player) {
        int removed = LeaderboardState.get(source.getServer()).clearMetricOverrides(player.getUUID());
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        String feedback = localized(source, "data.reset.all", player.getName().getString(), removed);
        source.sendSuccess(() -> Component.literal(feedback)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static void helpCommand(CommandSourceStack source, String label, String suggestion, String description) {
        if (source.getEntity() instanceof ServerPlayer player) description = RankBoardLanguage.help(player, description);
        String clickHint = source.getEntity() instanceof ServerPlayer player
                ? RankBoardLanguage.text(player, "help.click_to_fill") : "点击填入指令栏";
        String visibleLabel = commandSyntax(source, label);
        String visibleSuggestion = commandSyntax(source, suggestion);
        Component command = Component.literal(visibleLabel).setStyle(TextCompat.suggest(
                Style.EMPTY.withColor(ChatFormatting.WHITE), visibleSuggestion, Component.literal(clickHint)));
        Component annotation = Component.literal(" - " + description).setStyle(TextCompat.suggest(
                Style.EMPTY.withColor(ChatFormatting.GRAY), visibleSuggestion, Component.literal(clickHint)));
        Component line = command.copy().append(annotation);
        source.sendSuccess(() -> line, false);
    }

    private static void configHelpHeader(CommandSourceStack source) {
        source.sendSuccess(() -> clickable("[" + localized(source, "help.back_config") + "]", ChatFormatting.GRAY,
                "/leaderboard help config", localized(source, "help.tooltip.config")), false);
    }

    private static void configHelp(CommandSourceStack source, String key) {
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

    private int menu(CommandSourceStack source) {
        if (RankBoardConfig.get().simpleJoinMessages && source.getEntity() instanceof ServerPlayer player
                && CommandPermissionCompat.has(source, 2)) sendLanguagePrompt(player);
        boolean boardEnabled = false;
        try {
            LeaderboardState.BoardPreference preference = LeaderboardState.get(source.getServer())
                    .boardPreference(source.getEntity() == null ? null : source.getEntity().getUUID());
            boardEnabled = preference != null && preference.enabled();
        } catch (RuntimeException ignored) { }
        Component firstRow = clickable("[" + localized(source, "menu.scores") + "]", ChatFormatting.GOLD, "/leaderboard mine all", localized(source, "menu.tooltip.scores"))
                .copy().append(Component.literal(" "))
                .append(clickable("[" + localized(source, boardEnabled ? "menu.close_board" : "menu.open_board") + "]",
                        boardEnabled ? ChatFormatting.RED : ChatFormatting.GREEN,
                        boardEnabled ? "/leaderboard display off" : "/leaderboard display on",
                        localized(source, boardEnabled ? "menu.tooltip.close_board" : "menu.tooltip.open_board")));
        try {
            boolean enabled = LeaderboardState.get(source.getServer()).isLookMenuEnabled(source.getEntity() == null
                    ? null : source.getEntity().getUUID());
            firstRow = firstRow.copy().append(Component.literal(" ")).append(clickable(
                    "[" + localized(source, enabled ? "menu.close_look" : "menu.open_look") + "]",
                    enabled ? ChatFormatting.RED : ChatFormatting.GREEN,
                    "/leaderboard lookmenu " + !enabled,
                    localized(source, enabled ? "menu.tooltip.close_look" : "menu.tooltip.open_look")));
        } catch (RuntimeException ignored) { }
        Component finalFirstRow = firstRow;
        source.sendSuccess(() -> finalFirstRow, false);

        Component secondRow = Component.empty();
        boolean hasSecondRowButton = false;
        if (RankBoardConfig.get().carouselEnabled) {
            secondRow = secondRow.copy().append(clickable(
                    "[" + localized(source, "menu.carousel") + "]", ChatFormatting.AQUA, "/leaderboard carousel on", localized(source, "menu.tooltip.carousel")));
            hasSecondRowButton = true;
        }
        if (RankBoardConfig.get().websiteButtonEnabled && WebDashboard.isEnabled()) {
            if (hasSecondRowButton) secondRow = secondRow.copy().append(Component.literal(" "));
            secondRow = secondRow.copy().append(websiteButton(source));
            hasSecondRowButton = true;
        }
        if (RankBoardConfig.get().helpVisible(source)) {
            if (hasSecondRowButton) secondRow = secondRow.copy().append(Component.literal(" "));
            secondRow = secondRow.copy().append(clickable(
                    "[" + localized(source, "menu.help") + "]", ChatFormatting.GREEN, "/leaderboard help", localized(source, "menu.tooltip.help")));
            hasSecondRowButton = true;
            secondRow = secondRow.copy().append(Component.literal(" ")).append(clickable(
                    "[" + localized(source, "menu.intro") + "]", ChatFormatting.LIGHT_PURPLE,
                    "/leaderboard intro", localized(source, "menu.tooltip.intro")));
        }
        if (hasSecondRowButton) {
            Component finalSecondRow = secondRow;
            source.sendSuccess(() -> finalSecondRow, false);
        }

        int visible = 0;
        visible += sendMetricMenuRow(source, Metric.PLAY_TIME, Metric.PLACED, Metric.MINED, Metric.DEATHS);
        visible += sendMetricMenuRow(source, Metric.ELYTRA_DISTANCE, Metric.FOOD, Metric.JUMPS, Metric.KILLS);
        visible += sendMetricMenuRow(source, Metric.DAMAGE_TAKEN, Metric.DAMAGE_DEALT, Metric.PVP_KILLS, Metric.BREEDING);
        visible += sendMetricMenuRow(source, Metric.TRADES, Metric.FISHING, Metric.DROPPED, Metric.PICKED_UP);
        visible += sendMetricMenuRow(source, Metric.CRAFTED, Metric.REDSTONE_PLACED, Metric.AFK_TIME);
        visible += sendMetricMenuRow(source, Metric.BEDROCK_BROKEN);
        if (visible == 0) {
            String disabledMessage = localized(source, "menu.all_disabled");
            source.sendSuccess(() -> Component.literal(disabledMessage + "\n").withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal(localized(source, "menu.hint"))
                .withStyle(ChatFormatting.GRAY), false);
        BoardService.sendForeignScoreboardPrompt(source);
        sendWhitelistSetupPrompt(source);
        return 1;
    }

    private int sendMetricMenuRow(CommandSourceStack source, Metric... metrics) {
        Component line = Component.empty();
        int visible = 0;
        for (Metric metric : metrics) {
            if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
            String label = localizedMetric(source, metric);
            Component button = clickable("[" + label + "]", metric,
                    "/leaderboard display show all " + metric.command,
                    localized(source, "menu.tooltip.metric").replace("{0}", label));
            if (visible > 0) line = line.copy().append(Component.literal(" "));
            line = line.copy().append(button);
            visible++;
        }
        if (visible > 0) {
            Component finalLine = line;
            source.sendSuccess(() -> finalLine, false);
        }
        return visible;
    }

    private int metricIntroduction(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("=== " + localized(source, "menu.intro") + " ===")
                .withStyle(ChatFormatting.GOLD), false);
        for (Metric metric : Metric.values()) {
            if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
            String label = localizedMetric(source, metric);
            String description = localized(source, "metric.description." + metric.command);
            Component line = clickable("[" + label + "]", metric, "/leaderboard display show all " + metric.command,
                    localized(source, "menu.tooltip.metric").replace("{0}", label))
                    .copy().append(Component.literal(" " + description).withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private int showMyScores(CommandSourceStack source, int days, String label) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            Period selectedPeriod = days < 0 ? Period.ALL
                    : (days <= 1 ? Period.DAILY : (days <= 7 ? Period.WEEKLY : Period.MONTHLY));
            BoardService.enableOverview(source, selectedPeriod);
            LeaderboardState state = LeaderboardState.get(source.getServer());
            String periodText = localizedPeriod(player, selectedPeriod);
            source.sendSuccess(() -> Component.literal("=== " + localized(source, "menu.scores") + " · " + periodText + " ===").withStyle(ChatFormatting.GOLD), false);
            LocalDate today = LocalDate.now();
            for (Metric metric : Metric.values()) {
                if (!state.isMetricDisplayEnabled(metric)) continue;
                long value;
                if (days < 0) value = metric.read(player);
                else value = state.range(source.getServer(), today.minusDays(days - 1L), today, metric)
                        .values().getOrDefault(player.getUUID(), 0L);
                long score = value;
                source.sendSuccess(() -> RankBoardColors.text(localizedMetric(source, metric) + "  ", metric)
                        .append(Component.literal(format(metric, score)).withStyle(ChatFormatting.AQUA)), false);
            }
            Component periods = clickable("[" + localized(source, "period.all") + "]", ChatFormatting.GOLD, "/leaderboard mine all", "查看累计分数")
                    .copy().append(Component.literal(" "))
                    .append(clickable("[" + localized(source, "period.day") + "]", ChatFormatting.YELLOW, "/leaderboard mine day", "查看最近一日分数"))
                    .append(Component.literal(" "))
                    .append(clickable("[" + localized(source, "period.week") + "]", ChatFormatting.AQUA, "/leaderboard mine week", "查看最近一周分数"))
                    .append(Component.literal(" "))
                    .append(clickable("[" + localized(source, "period.month") + "]", ChatFormatting.LIGHT_PURPLE, "/leaderboard mine month", "查看最近一月分数"));
            source.sendSuccess(() -> periods, false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("个人分数读取失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int listConfig(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("=== RankBoard 配置 ===").withStyle(ChatFormatting.GOLD), false);
        for (String key : RankBoardConfig.optionKeys()) {
            String value = RankBoardConfig.value(key);
            source.sendSuccess(() -> Component.literal(key + " = " + (value.isEmpty() ? "(空/自动)" : value))
                    .withStyle(RankBoardConfig.isWebOption(key) ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal("使用 /leaderboard config get <配置项> 查看说明；"
                + "使用 /leaderboard config set <配置项> <值> 修改。")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return RankBoardConfig.optionKeys().size();
    }

    private int getConfig(CommandSourceStack source, String key) {
        if (!RankBoardConfig.isKnownOption(key)) {
            source.sendFailure(Component.literal("未知配置项：" + key));
            return 0;
        }
        String value = RankBoardConfig.value(key);
        source.sendSuccess(() -> Component.literal(key + " = " + (value.isEmpty() ? "(空/自动)" : value))
                .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(RankBoardConfig.description(key)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int setCarouselColor(CommandSourceStack source, boolean followMetric) {
        try {
            String value = RankBoardConfig.set(source.getServer(), "carousel-color-follow-metric",
                    Boolean.toString(followMetric));
            source.sendSuccess(() -> Component.literal("已设置轮播标题颜色："
                    + (followMetric ? "跟随当前榜单颜色" : "固定青色")
                    + " (carousel-color-follow-metric=" + value + ")").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            source.sendFailure(Component.literal("轮播颜色设置失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int setCarouselColorStatus(CommandSourceStack source) {
        boolean followMetric = RankBoardConfig.get().carouselColorFollowMetric;
        source.sendSuccess(() -> Component.literal("轮播标题颜色："
                + (followMetric ? "跟随当前榜单颜色" : "固定青色")
                + " (carousel-color-follow-metric=" + followMetric + ")").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int setWebThemeMode(CommandSourceStack source, boolean followIcon) {
        try {
            RankBoardConfig.set(source.getServer(), "web-theme-follow-icon", Boolean.toString(followIcon));
            if (!followIcon) RankBoardConfig.set(source.getServer(), "web-theme-base", "auto");
            boolean running = WebDashboard.restart(source.getServer());
            source.sendSuccess(() -> Component.literal("网页主题已切换为："
                    + (followIcon ? "读取服务器图标颜色" : "默认蓝色系")).withStyle(ChatFormatting.GREEN), true);
            if (!running) source.sendFailure(Component.literal("配置已保存，但网页服务重启失败。"));
            return running ? 1 : 0;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("网页主题设置失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int setWebThemeRgb(CommandSourceStack source, String color) {
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "web-theme-base", color);
            RankBoardConfig.set(source.getServer(), "web-theme-follow-icon", "false");
            boolean running = WebDashboard.restart(source.getServer());
            source.sendSuccess(() -> Component.literal("网页主题已切换为 RGB 色系：" + normalized)
                    .withStyle(ChatFormatting.GREEN), true);
            if (!running) source.sendFailure(Component.literal("配置已保存，但网页服务重启失败。"));
            return running ? 1 : 0;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("RGB 颜色无效，请使用 #RRGGBB：" + exception.getMessage()));
            return 0;
        }
    }

    private int webThemeModeStatus(CommandSourceStack source) {
        boolean followIcon = Boolean.parseBoolean(RankBoardConfig.value("web-theme-follow-icon"));
        String base = RankBoardConfig.value("web-theme-base");
        String mode = followIcon ? "读取服务器图标颜色" : (base.equalsIgnoreCase("auto") ? "默认蓝色系" : "RGB 色系 " + base);
        source.sendSuccess(() -> Component.literal("网页主题：" + mode
                + " (web-theme-follow-icon=" + followIcon + ")").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int modifyWebSwitchPeer(CommandSourceStack source, boolean add, String address) {
        java.util.LinkedHashSet<String> peers = new java.util.LinkedHashSet<>();
        String configured = RankBoardConfig.value("web-switcher-peers");
        if (!configured.isBlank()) {
            for (String peer : configured.split(",")) if (!peer.isBlank()) peers.add(peer.strip());
        }
        boolean changed = add ? peers.add(address.strip()) : peers.remove(address.strip());
        if (!changed) {
            source.sendSuccess(() -> Component.literal(add ? "该网页地址已经存在。" : "未找到该网页地址。"), false);
            return 0;
        }
        return setConfig(source, "web-switcher-peers", String.join(",", peers));
    }

    private int webSwitchList(CommandSourceStack source) {
        String configured = RankBoardConfig.value("web-switcher-peers");
        source.sendSuccess(() -> Component.literal(configured.isBlank()
                ? "未配置其他 RankBoard 网页。"
                : "其他 RankBoard 网页：" + configured).withStyle(ChatFormatting.GRAY), false);
        return configured.isBlank() ? 0 : configured.split(",").length;
    }

    private int webSwitchStatus(CommandSourceStack source) {
        String name = RankBoardConfig.value("web-switcher-name");
        String weight = RankBoardConfig.value("web-switcher-weight");
        String peers = RankBoardConfig.value("web-switcher-peers");
        source.sendSuccess(() -> Component.literal("网页切换：名称=" + name + "，权重=" + weight
                + "，其他网页=" + (peers.isBlank() ? "无" : peers)).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int setConfig(CommandSourceStack source, String key, String value) {
        if (!RankBoardConfig.isKnownOption(key)) {
            source.sendFailure(Component.literal("未知配置项：" + key));
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
            source.sendSuccess(() -> Component.literal("已保存配置：" + key + " = "
                    + (normalized.isEmpty() ? "(空/自动)" : normalized)).withStyle(ChatFormatting.GREEN), true);
            if (!webRunning) {
                source.sendFailure(Component.literal("配置已保存，但网页服务重启失败；请检查服务器日志和监听地址。"));
            }
            return webRunning ? 1 : 0;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("配置值无效：" + exception.getMessage()));
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("配置保存失败：" + exception.getMessage()));
            LOGGER.error("Could not save RankBoard config {}", key, exception);
        }
        return 0;
    }

    private int reloadConfig(CommandSourceStack source) {
        RankBoardConfig.load(source.getServer());
        RankBoardLanguage.load(source.getServer());
        refreshColors(source.getServer());
        BoardService.refreshAll(source.getServer());
        boolean webRunning = WebDashboard.restart(source.getServer());
        if (!webRunning) {
            source.sendFailure(Component.literal("配置已重载，但网页服务启动失败；请检查服务器日志和网页配置。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("RankBoard 主配置与网页配置已重载；历史统计未重新扫描。")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private int clearRateLimits(CommandSourceStack source) {
        int cleared = WebDashboard.clearRateLimits();
        source.sendSuccess(() -> Component.literal("已清除 " + cleared + " 个网页限流与 API 累计冷却记录。")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private int setLanguage(CommandSourceStack source, String language) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("此设置仅限玩家使用 / This setting is for players only."));
            return 0;
        }
        if (!RankBoardLanguage.exists(language)) {
            source.sendFailure(Component.literal("未知语言包 / Unknown language pack: " + language));
            return 0;
        }
        LeaderboardState.get(source.getServer()).setLanguage(player.getUUID(), language);
        source.sendSuccess(() -> Component.literal(RankBoardLanguage.text(player, "language.selected",
                RankBoardLanguage.text(player, "language.name"))), false);
        if (CommandPermissionCompat.has(source, 2)) {
            player.sendSystemMessage(languageDefaultPrompt(player, language), false);
        }
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> languageDefaultCommand(String literal) {
        return Commands.literal(literal)
                .requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.argument("code", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(RankBoardLanguage.codes(), builder))
                        .executes(context -> setDefaultLanguage(context.getSource(),
                                StringArgumentType.getString(context, "code").toLowerCase(java.util.Locale.ROOT))));
    }

    private int setDefaultLanguage(CommandSourceStack source, String language) {
        if (!RankBoardLanguage.exists(language)) {
            source.sendFailure(Component.literal("未知语言包 / Unknown language pack: " + language));
            return 0;
        }
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "default-language", language);
            int players = LeaderboardState.get(source.getServer()).setLanguageForAll(source.getServer(), normalized);
            BoardService.refreshAll(source.getServer());
            source.sendSuccess(() -> Component.literal("全服默认聊天语言已设置为 " + normalized
                    + "，已更新 " + players + " 位玩家 / Server-wide default chat language set to "
                    + normalized + " for " + players + " players.").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("全服默认语言设置失败 / Failed to set server default language: "
                    + exception.getMessage()));
            return 0;
        }
    }

    private static Component languageDefaultPrompt(ServerPlayer player, String language) {
        String label = language.equalsIgnoreCase("en_us") ? "English"
                : language.equalsIgnoreCase("zh_cn") ? "中文" : language;
        return Component.literal(RankBoardLanguage.text(player, "language.default_prompt", label)).withStyle(ChatFormatting.GRAY)
                .copy().append(Component.literal(" "))
                .append(clickable("[" + RankBoardLanguage.text(player, "language.default_button") + "]",
                        ChatFormatting.GREEN, "/leaderboard language default " + language,
                        RankBoardLanguage.text(player, "language.default_tooltip")));
    }

    private int languageStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("此设置仅限玩家使用 / This setting is for players only."));
            return 0;
        }
        LeaderboardState state = LeaderboardState.get(source.getServer());
        String selected = state.needsLanguageChoice(player.getUUID()) ? "未选择 / Not selected" : state.language(player.getUUID());
        source.sendSuccess(() -> Component.literal("聊天提示语言 / Chat prompt language: " + selected)
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private void sendLanguagePrompt(ServerPlayer player) {
        if (LeaderboardState.get(PlayerCompat.server(player)).needsLanguageChoice(player.getUUID())) {
            player.sendSystemMessage(languageButtons("请选择语言 / Select language: "), false);
        }
    }

    private void sendLanguageHelp(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer) {
            source.sendSuccess(() -> languageButtons("语言 / Language: "), false);
        }
    }

    private static Component languageButtons(String heading) {
        return Component.literal(heading).withStyle(ChatFormatting.GRAY)
                .copy().append(clickable("[中文]", ChatFormatting.GOLD,
                        "/leaderboard language zh_cn", "使用中文聊天提示 / Use Chinese chat prompts"))
                .append(Component.literal(" "))
                .append(clickable("[English]", ChatFormatting.AQUA,
                        "/leaderboard language en_us", "Use English chat prompts / 使用英文聊天提示"));
    }

    private void sendJoinExperience(ServerPlayer player) {
        if (PlayerCompat.isFake(player)) return;
        RankBoardConfig config = RankBoardConfig.get();
        CommandSourceStack source = player.createCommandSourceStack();
        if (config.simpleJoinMessages) {
            if (config.joinMenuEnabled && LeaderboardState.get(PlayerCompat.server(player)).isJoinMenuEnabled(player.getUUID())) {
                source.sendSuccess(() -> clickable("[" + localized(source, "menu.join_compact") + "]",
                        ChatFormatting.GOLD, "/leaderboard", localized(source, "menu.tooltip.expand_details")), false);
            }
            return;
        }
        boolean op = CommandPermissionCompat.has(source, 2);
        if (op) sendLanguagePrompt(player);
        if (config.welcomeEnabled) {
            player.sendSystemMessage(Component.literal(RankBoardLanguage.text(player, "welcome",
                    config.displayName(PlayerCompat.server(player)))).withStyle(ChatFormatting.GRAY)
                    , false);
        }
        if (config.joinWebHintEnabled && WebDashboard.isEnabled()) {
            player.sendSystemMessage(Component.literal(RankBoardLanguage.text(player, "web_hint",
                    config.webAddress(PlayerCompat.server(player))))
                    .withStyle(ChatFormatting.AQUA), false);
        }
        if (op
                && Boolean.parseBoolean(RankBoardConfig.value("web-switcher-op-hint-enabled"))
                && RankBoardConfig.value("web-switcher-name").equalsIgnoreCase("auto")
                && WebDashboard.hasSwitcherPeers()) {
            Component switcherHint = Component.literal(RankBoardLanguage.text(player, "web_switcher.op_hint",
                            WebDashboard.switcherDisplayName())).withStyle(ChatFormatting.GRAY)
                    .copy().append(Component.literal(" "))
                    .append(Component.literal("[" + RankBoardLanguage.text(player, "web_switcher.set_name") + "]")
                            .setStyle(TextCompat.suggest(Style.EMPTY.withColor(ChatFormatting.AQUA),
                                    "/leaderboard webswitch name ",
                                    Component.literal("/leaderboard webswitch name <名称>"))));
            player.sendSystemMessage(switcherHint, false);
        }
        if (config.joinMenuEnabled
                && LeaderboardState.get(PlayerCompat.server(player)).isJoinMenuEnabled(player.getUUID())) {
            if (op) {
                menu(source);
            } else {
                source.sendSuccess(() -> clickable("[" + localized(source, "menu.expand_details") + "]",
                        ChatFormatting.GOLD, "/leaderboard", localized(source, "menu.tooltip.expand_details")), false);
            }
        }
    }

    private void handleLookUpSneakMenu(net.minecraft.server.MinecraftServer server) {
        if (!RankBoardConfig.get().lookUpSneakMenuEnabled) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlayerCompat.isFake(player)) continue;
            if (!LeaderboardState.get(server).isLookMenuEnabled(player.getUUID())) {
                LOOK_MENU_HELD.remove(player.getUUID());
                continue;
            }
            boolean active = player.isShiftKeyDown() && player.getXRot() <= -60.0F;
            if (active && LOOK_MENU_HELD.add(player.getUUID())) menu(player.createCommandSourceStack());
            else if (!active) LOOK_MENU_HELD.remove(player.getUUID());
        }
    }

    private static Component clickable(String label, ChatFormatting color, String command, String hover) {
        return Component.literal(label).setStyle(TextCompat.interactive(Style.EMPTY.withColor(color), command, Component.literal(hover)));
    }

    static String localized(CommandSourceStack source, String key, Object... arguments) {
        return source.getEntity() instanceof ServerPlayer player
                ? RankBoardLanguage.text(player, key, arguments)
                : RankBoardLanguage.defaultText(key, arguments);
    }

    private static String commandSyntax(CommandSourceStack source, String syntax) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return syntax;
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

    static String localizedMetric(CommandSourceStack source, Metric metric) {
        String configured = metric.label();
        String defaultLabel = RankBoardConfig.defaultValue("metric-label-" + metric.command);
        return configured.equals(defaultLabel) ? localized(source, "metric." + metric.command) : configured;
    }

    /** Resolves a metric label for a player's selected language while preserving custom labels. */
    static String localizedMetric(ServerPlayer player, Metric metric) {
        String configured = metric.label();
        String defaultLabel = RankBoardConfig.defaultValue("metric-label-" + metric.command);
        if (!configured.equals(defaultLabel)) return configured;
        return RankBoardLanguage.text(player, "metric." + metric.command);
    }

    /** Resolves a period label for a player's selected language. */
    static String localizedPeriod(ServerPlayer player, Period period) {
        String key = switch (period) {
            case DAILY -> "period.day";
            case WEEKLY -> "period.week";
            case MONTHLY -> "period.month";
            case YEARLY -> "period.year";
            case ALL -> "period.all";
        };
        return RankBoardLanguage.text(player, key);
    }

    static String localizedPeriod(CommandSourceStack source, Period period) {
        String key = switch (period) {
            case DAILY -> "period.day";
            case WEEKLY -> "period.week";
            case MONTHLY -> "period.month";
            case YEARLY -> "period.year";
            case ALL -> "period.all";
        };
        return localized(source, key);
    }

    private static Component clickable(String label, Metric metric, String command, String hover) {
        return Component.literal(label).setStyle(TextCompat.interactive(
                Style.EMPTY.withColor(RankBoardColors.renderedRgb(metric)), command, Component.literal(hover)));
    }

    private static Component websiteButton(CommandSourceStack source) {
        String address = RankBoardConfig.get().webAddress(source.getServer());
        if (!address.startsWith("http://") && !address.startsWith("https://")) address = "http://" + address;
        try {
            URI uri = URI.create(address);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException();
            return Component.literal("[" + localized(source, "menu.website") + "]").setStyle(TextCompat.openUrl(
                    Style.EMPTY.withColor(ChatFormatting.AQUA), address, Component.literal("打开 RankBoard 网页排行榜")));
        } catch (RuntimeException exception) {
            return Component.literal("[网站地址无效]").withStyle(ChatFormatting.DARK_GRAY);
        }
    }

    private int setLookMenu(CommandSourceStack source, boolean enabled) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            LeaderboardState.get(source.getServer()).setLookMenuEnabled(player.getUUID(), enabled);
            LOOK_MENU_HELD.remove(player.getUUID());
            source.sendSuccess(() -> Component.literal(enabled
                    ? "已开启自己的抬头+蹲起菜单。"
                    : "已关闭自己的抬头+蹲起菜单；可输入 /leaderboard lookmenu true 重新开启。"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int lookMenuStatus(CommandSourceStack source) {
        try {
            boolean personal = LeaderboardState.get(source.getServer())
                    .isLookMenuEnabled(source.getPlayerOrException().getUUID());
            boolean global = RankBoardConfig.get().lookUpSneakMenuEnabled;
            source.sendSuccess(() -> Component.literal("自己的抬头+蹲起菜单："
                    + (personal ? "已开启" : "已关闭") + "；全服功能：" + (global ? "已开启" : "已关闭")), false);
            return personal && global ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int setJoinMenu(CommandSourceStack source, boolean enabled) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            LeaderboardState.get(source.getServer()).setJoinMenuEnabled(player.getUUID(), enabled);
            source.sendSuccess(() -> Component.literal(enabled
                    ? "已开启自己的进服排行榜菜单。"
                    : "已关闭自己的进服排行榜菜单；仍可输入 /leaderboard 手动打开。"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int joinMenuStatus(CommandSourceStack source) {
        try {
            boolean personal = LeaderboardState.get(source.getServer())
                    .isJoinMenuEnabled(source.getPlayerOrException().getUUID());
            boolean global = RankBoardConfig.get().joinMenuEnabled;
            source.sendSuccess(() -> Component.literal("自己的进服排行榜菜单："
                    + (personal ? "已开启" : "已关闭") + "；全服功能：" + (global ? "已开启" : "已关闭")), false);
            return personal && global ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int setGlobalLookMenu(CommandSourceStack source, boolean enabled) {
        try {
            RankBoardConfig.set(source.getServer(), "look-up-sneak-menu-enabled", Boolean.toString(enabled));
            LOOK_MENU_HELD.clear();
            source.sendSuccess(() -> Component.literal(enabled
                    ? "已开启全服抬头+蹲起菜单；玩家个人关闭状态保持不变。"
                    : "已关闭全服所有玩家的抬头+蹲起菜单。"), true);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("全服抬头蹲起菜单设置保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int globalLookMenuStatus(CommandSourceStack source) {
        boolean enabled = RankBoardConfig.get().lookUpSneakMenuEnabled;
        source.sendSuccess(() -> Component.literal("全服抬头+蹲起菜单：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int setNameColor(CommandSourceStack source, String mode) {
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "scoreboard-name-color-enabled", mode);
            BoardService.refreshAll(source.getServer());
            PlayerNameColors.refreshAll(source.getServer());
            source.sendSuccess(() -> Component.literal("全服玩家名字颜色模式：" + normalized), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("名字颜色模式保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int nameColorStatus(CommandSourceStack source) {
        String mode = RankBoardConfig.get().nameColorMode.serialized;
        String render = RankBoardConfig.get().nameColorRenderMode.serialized;
        source.sendSuccess(() -> Component.literal("全服玩家名字颜色模式：" + mode + "；渲染：" + render), false);
        return RankBoardConfig.get().nameColorMode == RankBoardConfig.NameColorMode.DISABLED ? 0 : 1;
    }

    private int showMetricLabel(CommandSourceStack source, Metric metric) {
        source.sendSuccess(() -> Component.literal(metric.command + " = " + metric.label()), false);
        return 1;
    }

    private int setMetricLabel(CommandSourceStack source, Metric metric, String value) {
        return saveMetricLabel(source, metric, value, "已设置");
    }

    private int resetMetricLabel(CommandSourceStack source, Metric metric) {
        String key = "metric-label-" + metric.command;
        return saveMetricLabel(source, metric, RankBoardConfig.defaultValue(key), "已恢复默认");
    }

    private int resetAllMetricLabels(CommandSourceStack source) {
        try {
            for (Metric metric : Metric.values()) {
                String key = "metric-label-" + metric.command;
                RankBoardConfig.set(source.getServer(), key, RankBoardConfig.defaultValue(key));
            }
            refreshMetricLabels(source.getServer());
            source.sendSuccess(() -> Component.literal("已恢复全部榜单默认名称。"), true);
            return Metric.values().length;
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("榜单名称保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int listMetricLabels(CommandSourceStack source) {
        for (Metric metric : Metric.values()) {
            source.sendSuccess(() -> Component.literal(metric.command + " = " + metric.label()), false);
        }
        return Metric.values().length;
    }

    private int saveMetricLabel(CommandSourceStack source, Metric metric, String value, String action) {
        try {
            String normalized = RankBoardConfig.set(source.getServer(), "metric-label-" + metric.command, value);
            refreshMetricLabels(source.getServer());
            source.sendSuccess(() -> Component.literal(action + " " + metric.command + "：" + normalized), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("榜单名称保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private static void refreshMetricLabels(net.minecraft.server.MinecraftServer server) {
        BoardService.refreshAll(server);
        WebDashboard.invalidateRankings();
    }

    private int setMetricColor(CommandSourceStack source, Metric metric, String value) {
        return saveMetricColor(source, metric, presetHex(value), "已设置");
    }

    private int showColorPresets(CommandSourceStack source, Metric metric) {
        String current = RankBoardConfig.value("metric-color-" + metric.command);
        source.sendSuccess(() -> Component.literal(metric.label() + "当前颜色：")
                .append(RankBoardColors.text(current, metric))
                .append(Component.literal("；点击选择预设色：").withStyle(ChatFormatting.GRAY)), false);
        for (int row = 0; row < 4; row++) {
            Component line = Component.empty();
            for (int column = 0; column < 4; column++) {
                ColorPreset preset = COLOR_PRESETS.get(row * 4 + column);
                if (column > 0) line = line.copy().append(Component.literal(" "));
                line = line.copy().append(clickable("[" + preset.key + " " + preset.label + "]", preset.formatting,
                        "/leaderboard color " + metric.command + " " + preset.key,
                        "点击设置 " + metric.label() + " 为 " + preset.key + " / " + preset.label));
            }
            Component completed = line;
            source.sendSuccess(() -> completed, false);
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

    private int resetMetricColor(CommandSourceStack source, Metric metric) {
        String key = "metric-color-" + metric.command;
        return saveMetricColor(source, metric, RankBoardConfig.defaultValue(key), "已恢复默认");
    }

    private int resetAllMetricColors(CommandSourceStack source) {
        try {
            for (Metric metric : Metric.values()) {
                String key = "metric-color-" + metric.command;
                RankBoardConfig.set(source.getServer(), key, RankBoardConfig.defaultValue(key));
            }
            refreshColors(source.getServer());
            source.sendSuccess(() -> Component.literal("已恢复全部榜单默认颜色。"), true);
            return Metric.values().length;
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("颜色配置保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int listMetricColors(CommandSourceStack source) {
        for (Metric metric : Metric.values()) {
            String value = RankBoardConfig.value("metric-color-" + metric.command);
            Component entry = clickable("[" + metric.label() + " / " + metric.command + "] " + value,
                    metric, "/leaderboard color " + metric.command,
                    "点击打开 " + metric.label() + " 的原版 16 色预选");
            source.sendSuccess(() -> entry, false);
        }
        return Metric.values().length;
    }

    private int saveMetricColor(CommandSourceStack source, Metric metric, String value, String action) {
        String key = "metric-color-" + metric.command;
        try {
            String normalized = RankBoardConfig.set(source.getServer(), key, value);
            refreshColors(source.getServer());
            source.sendSuccess(() -> RankBoardColors.text(action + " " + metric.label() + "：" + normalized, metric), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("颜色配置保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private static void refreshColors(net.minecraft.server.MinecraftServer server) {
        BoardService.refreshAll(server);
        PlayerNameColors.refreshAll(server);
    }

    private int setMetricDisplay(CommandSourceStack source, Metric metric, boolean enabled) {
        LeaderboardState.get(source.getServer()).setMetricDisplayEnabled(metric, enabled);
        BoardService.refreshAll(source.getServer());
        WebDashboard.invalidateRankings();
        source.sendSuccess(() -> Component.literal(enabled ? metric.label() + " 已恢复显示。" : metric.label() + " 已禁止显示。"), true);
        return 1;
    }

    /** Displays enabled metrics first and disabled metrics second, with one-click +/- toggles. */
    private int metricToggleMenu(CommandSourceStack source) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        source.sendSuccess(() -> Component.literal("=== " + localized(source, "metric.toggle.heading") + " ===")
                .withStyle(ChatFormatting.GOLD), false);
        sendMetricToggleSection(source, state, true);
        sendMetricToggleSection(source, state, false);
        return Metric.values().length;
    }

    private void sendMetricToggleSection(CommandSourceStack source, LeaderboardState state, boolean enabled) {
        List<Metric> metrics = java.util.Arrays.stream(Metric.values())
                .filter(metric -> state.isMetricDisplayEnabled(metric) == enabled)
                .toList();
        if (metrics.isEmpty()) return;
        String heading = localized(source, enabled ? "metric.toggle.enabled_heading" : "metric.toggle.disabled_heading");
        source.sendSuccess(() -> Component.literal(heading).withStyle(ChatFormatting.GRAY), false);
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
            Component row = clickable("[" + label + "]", metric,
                    "/leaderboard display show all " + metric.command,
                    localized(source, "menu.tooltip.metric").replace("{0}", label))
                    .copy().append(Component.literal(" "))
                    .append(clickable(enabled ? "[-]" : "[+]", enabled ? ChatFormatting.RED : ChatFormatting.GREEN,
                            toggleCommand, localized(source, tooltipKey)));
            source.sendSuccess(() -> row, false);
        }
    }

    private int metricDisplayStatus(CommandSourceStack source, Metric metric) {
        boolean enabled = LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric);
        source.sendSuccess(() -> Component.literal(metric.label() + " 显示：" + (enabled ? "已开启" : "已禁用")), false);
        return enabled ? 1 : 0;
    }

    private int setWhitelistOnly(CommandSourceStack source, boolean enabled) {
        LeaderboardState state = LeaderboardState.get(source.getServer());
        state.setWhitelistOnly(enabled);
        state.setWhitelistModeConfigured();
        BoardService.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal(enabled
                ? "排行榜已仅显示服务器白名单玩家。" : "排行榜已显示所有有统计数据的玩家。"), true);
        return 1;
    }

    private int whitelistStatus(CommandSourceStack source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isWhitelistOnly();
        source.sendSuccess(() -> Component.literal("排行榜白名单过滤：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int configureWhitelistMode(CommandSourceStack source, WhitelistMode mode) {
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
            source.sendSuccess(() -> Component.literal("已选择：" + description + "。")
                    .withStyle(ChatFormatting.GREEN), true);
            sendWhitelistDataReadPrompt(source);
            return 1;
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("白名单模式保存失败：" + exception.getMessage()));
            return 0;
        }
    }

    private int skipWhitelistDataRead(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("已保留当前榜单缓存；需要读取玩家统计文件时输入 /leaderboard cache reload。")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private void sendWhitelistSetupPrompt(CommandSourceStack source) {
        if (!CommandPermissionCompat.has(source, 2)
                || !LeaderboardState.get(source.getServer()).needsWhitelistModeSetup()) return;
        source.sendSuccess(() -> whitelistSetupButtons(source, "whitelist.first_heading"), false);
    }

    private static Component whitelistSetupButtons(CommandSourceStack source, String headingKey) {
        return Component.literal(localized(source, headingKey)).withStyle(ChatFormatting.GRAY)
                .copy().append(clickable("[" + localized(source, "whitelist.server") + "]", ChatFormatting.YELLOW,
                        "/leaderboard whitelist setup server", localized(source, "whitelist.server_tooltip")))
                .append(Component.literal(" "))
                .append(clickable("[" + localized(source, "whitelist.mod") + "]", ChatFormatting.AQUA,
                        "/leaderboard whitelist setup mod", localized(source, "whitelist.mod_tooltip")))
                .append(Component.literal(" "))
                .append(clickable("[" + localized(source, "whitelist.none") + "]", ChatFormatting.GREEN,
                        "/leaderboard whitelist setup none", localized(source, "whitelist.none_tooltip")));
    }

    private void sendWhitelistDataReadPrompt(CommandSourceStack source) {
        Component prompt = Component.literal(localized(source, "whitelist.read_prompt")).withStyle(ChatFormatting.GRAY)
                .copy().append(clickable("[" + localized(source, "whitelist.read") + "]", ChatFormatting.GOLD,
                        "/leaderboard cache reload", localized(source, "whitelist.read_tooltip")))
                .append(Component.literal(" "))
                .append(clickable("[" + localized(source, "whitelist.later") + "]", ChatFormatting.GRAY,
                        "/leaderboard whitelist setup later", localized(source, "whitelist.later_tooltip")));
        source.sendSuccess(() -> prompt, false);
    }

    private int setBotFilter(CommandSourceStack source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setBotFilterEnabled(enabled);
        BoardService.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal(enabled
                ? "排行榜已屏蔽 bot_ 前缀玩家。" : "排行榜已允许显示 bot_ 前缀玩家。"), true);
        return 1;
    }

    private int botFilterStatus(CommandSourceStack source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isBotFilterEnabled();
        source.sendSuccess(() -> Component.literal("bot_ 前缀屏蔽：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int setCustomFilter(CommandSourceStack source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setCustomPlayerFilterEnabled(enabled);
        BoardService.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal(enabled
                ? "排行榜已隐藏无法解析身份的历史玩家。" : "排行榜已允许显示 unknown_ 历史玩家。"), true);
        return 1;
    }

    private int customFilterStatus(CommandSourceStack source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isCustomPlayerFilterEnabled();
        source.sendSuccess(() -> Component.literal("未知历史玩家屏蔽：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int setOnlineFilter(CommandSourceStack source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setOnlineOnly(enabled);
        BoardService.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal(enabled
                ? "排行榜已仅显示当前在线玩家。" : "排行榜已恢复显示符合其他筛选条件的玩家。"), true);
        return 1;
    }

    private int onlineFilterStatus(CommandSourceStack source) {
        boolean enabled = LeaderboardState.get(source.getServer()).isOnlineOnly();
        source.sendSuccess(() -> Component.literal("仅显示在线玩家：" + (enabled ? "已开启" : "已关闭")), false);
        return enabled ? 1 : 0;
    }

    private int modifyModWhitelist(CommandSourceStack source, boolean add, String player) {
        try {
            boolean changed = add ? RankBoardWhitelist.add(source.getServer(), player)
                    : RankBoardWhitelist.remove(source.getServer(), player);
            if (changed) BoardService.refreshAll(source.getServer());
            source.sendSuccess(() -> Component.literal(changed
                    ? (add ? "已添加到模组白名单：" : "已从模组白名单移除：") + player
                    : (add ? "模组白名单中已存在：" : "模组白名单中未找到：") + player), true);
            return changed ? 1 : 0;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("模组白名单参数无效：" + exception.getMessage()));
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("模组白名单保存失败：" + exception.getMessage()));
        }
        return 0;
    }

    private int listModWhitelist(CommandSourceStack source) {
        List<String> entries = RankBoardWhitelist.entries();
        source.sendSuccess(() -> Component.literal("模组白名单（" + entries.size() + "）："
                + (entries.isEmpty() ? "空" : String.join("，", entries))), false);
        return entries.size();
    }

    private int reloadModWhitelist(CommandSourceStack source) {
        RankBoardWhitelist.reload(source.getServer());
        source.sendSuccess(() -> Component.literal("模组白名单已重新加载，共 "
                + RankBoardWhitelist.entries().size() + " 项。"), true);
        BoardService.refreshAll(source.getServer());
        return 1;
    }

    private int setRecipientFilter(CommandSourceStack source, String mode) {
        return setConfig(source, "scoreboard-recipient-filter", mode);
    }

    private int recipientFilterStatus(CommandSourceStack source) {
        String mode = RankBoardConfig.get().recipientFilter.serialized;
        source.sendSuccess(() -> Component.literal("个人榜单接收过滤：" + mode
                + "；whitelist/blacklist 使用 config/rankboard/rankboard-whitelist.json。")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int cacheStatus(CommandSourceStack source) {
        String status = StatReader.isChecking() ? "扫描中"
                : StatReader.isLoadedFromPersistentCacheOnly() ? "已从持久缓存加载（启动时未扫描）"
                : StatReader.isReady() ? "已完成" : "等待读取";
        source.sendSuccess(() -> Component.literal("历史统计缓存：" + status + "（" + StatReader.progress()
                        + "，扫描线程 " + StatReader.resolvedScanThreads() + "）")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int reloadCache(CommandSourceStack source) {
        StatReader.startWarmup(source.getServer());
        source.sendSuccess(() -> Component.literal("已开始读取 world/stats/*.json 并重建历史统计缓存，当前进度 " + StatReader.progress() + "。")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private int cacheThreadsStatus(CommandSourceStack source) {
        int configured = RankBoardConfig.get().historyScanThreads;
        int resolved = StatReader.resolvedScanThreads();
        source.sendSuccess(() -> Component.literal("历史扫描线程：配置 " + configured + "，实际使用 " + resolved
                        + "（最多为逻辑处理器的 50%），总扫描上限 " + StatReader.effectiveScanRate() + " 文件/秒。")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int setCacheThreads(CommandSourceStack source, int requested) {
        try {
            String saved = RankBoardConfig.set(source.getServer(), "history-scan-threads", Integer.toString(requested));
            int resolved = StatReader.resolvedScanThreads();
            source.sendSuccess(() -> Component.literal("历史扫描线程已保存为 " + saved + "，实际使用 " + resolved
                            + "（最多为逻辑处理器的 50%），总扫描上限 " + StatReader.effectiveScanRate()
                            + " 文件/秒；下次 /leaderboard cache reload 时生效。")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            source.sendFailure(Component.literal("无法设置历史扫描线程：" + exception.getMessage()));
            return 0;
        }
    }

    private int show(CommandSourceStack source, Period period, Metric metric, int limit) {
        try {
            if (!StatReader.isReady()) {
                source.sendSuccess(() -> Component.literal("权威扫描进行中（" + StatReader.progress()
                        + "），当前显示缓存预览，结果可能变化。").withStyle(ChatFormatting.YELLOW), false);
            }
            if (period != Period.ALL
                    && !LeaderboardState.get(source.getServer()).isPeriodComplete(period, metric)) {
                source.sendSuccess(() -> Component.literal(period.label + "统计为部分周期，从首次可信基线开始。")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
            List<Entry> entries = entries(source.getServer(), period, metric);
            source.sendSuccess(() -> RankBoardColors.text("=== " + period.label + " " + metric.label() + " ===", metric), false);
            if (entries.isEmpty()) {
                source.sendSuccess(() -> Component.literal("没有可用于排行的玩家统计。 ").withStyle(ChatFormatting.GRAY), false);
                return 0;
            }
            long total = total(entries);
            source.sendSuccess(() -> Component.literal("总和 ").withStyle(ChatFormatting.GRAY)
                    .append(RankBoardColors.text(format(metric, total), metric)), false);
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                Entry entry = entries.get(i);
                int rank = i + 1;
                source.sendSuccess(() -> Component.literal(rank + " ").withStyle(ChatFormatting.YELLOW)
                        .append(RankBoardColors.text(entry.name(), metric))
                        .append(Component.literal("  ").withStyle(ChatFormatting.GRAY))
                        .append(RankBoardColors.text(format(metric, entry.value()), metric)), false);
            }
            return entries.size();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to execute leaderboard command: period={}, metric={}", period.command, metric.command, exception);
            source.sendFailure(Component.literal("排行榜读取失败：" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : " - " + exception.getMessage())));
            return 0;
        }
    }

    private int exportRanking(CommandSourceStack source, Period period, Metric metric) {
        if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) {
            source.sendFailure(Component.literal(metric.label() + " 已被 OP 禁止显示，无法导出。"));
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
            source.sendSuccess(() -> Component.literal("已导出 " + rows.size() + " 条排行榜数据："
                    + file.toAbsolutePath()).withStyle(ChatFormatting.GREEN), true);
            return rows.size();
        } catch (java.io.IOException exception) {
            source.sendFailure(Component.literal("导出排行榜失败：" + exception.getMessage()));
            LOGGER.error("Could not export {} {} ranking to {}", period.command, metric.command, file, exception);
            return 0;
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static List<Entry> entries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {
        LeaderboardState state = LeaderboardState.get(server);
        if (!state.isMetricDisplayEnabled(metric)) return List.of();
        state.rollPeriods(server);
        return StatReader.readAll(server, metric).stream()
                .filter(snapshot -> isIncluded(server, state, snapshot.uuid(), snapshot.name()))
                .filter(snapshot -> period == Period.ALL || state.hasBaseline(period, snapshot.uuid(), metric))
                .map(snapshot -> new Entry(snapshot.uuid(), snapshot.name(), Math.max(0,
                        snapshot.value(metric) - (period == Period.ALL ? 0 : state.getBaseline(period, snapshot.uuid(), metric)))))
                .sorted(Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::name))
                .toList();
    }

    static boolean isIncluded(net.minecraft.server.MinecraftServer server, LeaderboardState state,
                              java.util.UUID uuid, String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return (!state.isWhitelistOnly() || PlayerDirectoryCompat.isAllowed(server, uuid, name))
                && (!state.isBotFilterEnabled() || !normalized.startsWith("bot_"))
                && (!state.isCustomPlayerFilterEnabled() || !normalized.startsWith("unknown_"))
                && (!state.isOnlineOnly() || server.getPlayerList().getPlayer(uuid) != null);
    }

    static String format(Metric metric, long value) {
        if (metric == Metric.PLAY_TIME || metric == Metric.AFK_TIME) return (value / 72000) + "h " + ((value / 1200) % 60) + "m";
        if (metric == Metric.ELYTRA_DISTANCE) return String.format(java.util.Locale.ROOT, "%.1f km", value / 100000.0);
        if (metric == Metric.DAMAGE_TAKEN || metric == Metric.DAMAGE_DEALT) {
            return String.format(java.util.Locale.ROOT, "%.1f", value / 10.0);
        }
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
        PLAY_TIME("playtime", "在线榜", ChatFormatting.AQUA, p -> StatCompat.custom(p, "PLAY_TIME")),
        PLACED("placed", "放置榜", ChatFormatting.DARK_AQUA, RankBoardMod::placed),
        MINED("mined", "挖掘榜", ChatFormatting.BLUE, RankBoardMod::mined),
        DEATHS("deaths", "死亡榜", ChatFormatting.DARK_RED, p -> StatCompat.custom(p, "DEATHS")),
        ELYTRA_DISTANCE("elytra", "飞行榜", ChatFormatting.LIGHT_PURPLE, p -> StatCompat.custom(p, "AVIATE_ONE_CM")),
        FOOD("food", "大胃王榜", ChatFormatting.GOLD, RankBoardMod::foodUsed),
        JUMPS("jumps", "跳跃榜", ChatFormatting.LIGHT_PURPLE, p -> StatCompat.custom(p, "JUMP")),
        KILLS("kills", "击杀榜", ChatFormatting.RED, p -> StatCompat.custom(p, "MOB_KILLS") + StatCompat.custom(p, "PLAYER_KILLS")),
        DAMAGE_TAKEN("damage", "受伤榜", ChatFormatting.RED, p -> StatCompat.custom(p, "DAMAGE_TAKEN")),
        DAMAGE_DEALT("dealt", "输出榜", ChatFormatting.GOLD, p -> StatCompat.custom(p, "DAMAGE_DEALT")),
        PVP_KILLS("pvp", "PvP榜", ChatFormatting.DARK_RED, p -> StatCompat.custom(p, "PLAYER_KILLS")),
        BREEDING("breeding", "繁殖榜", ChatFormatting.DARK_AQUA, p -> StatCompat.custom(p, "ANIMALS_BRED")),
        TRADES("trades", "交易榜", ChatFormatting.GREEN, p -> StatCompat.custom(p, "TRADED_WITH_VILLAGER")),
        FISHING("fishing", "钓鱼榜", ChatFormatting.DARK_BLUE, p -> StatCompat.custom(p, "FISH_CAUGHT")),
        DROPPED("dropped", "丢垃圾榜", ChatFormatting.DARK_GRAY, RankBoardMod::dropped),
        PICKED_UP("picked", "拾荒榜", ChatFormatting.GREEN, RankBoardMod::pickedUp),
        CRAFTED("crafted", "合成榜", ChatFormatting.GOLD, RankBoardMod::crafted),
        REDSTONE_PLACED("redstone", "红石大蛇榜", ChatFormatting.RED, RankBoardMod::redstonePlaced),
        AFK_TIME("afk", "摸鱼榜", ChatFormatting.AQUA, RankBoardMod::afkTime),
        BEDROCK_BROKEN("bedrock", "破基岩榜", ChatFormatting.BLUE, RankBoardMod::bedrockBroken);

        final String command;
        final String label;
        final ChatFormatting nameColor;
        final Counter counter;
        Metric(String command, String label, ChatFormatting nameColor, Counter counter) {
            this.command = command; this.label = label; this.nameColor = nameColor; this.counter = counter;
        }
        long read(ServerPlayer player) { return counter.read(player); }
        String label() { return RankBoardConfig.get().metricLabel(this); }
        boolean isCustomMetric() { return this == AFK_TIME || this == BEDROCK_BROKEN || this == ELYTRA_DISTANCE; }
    }

    public enum Period {
        DAILY("daily", "每日"), WEEKLY("weekly", "每周"), MONTHLY("monthly", "每月"), YEARLY("yearly", "每年"), ALL("all", "总计");
        final String command;
        final String label;
        Period(String command, String label) { this.command = command; this.label = label; }
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

    private static long foodUsed(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().filter(item -> ComponentLookupCompat.has(item.components(), DataComponents.FOOD)).mapToLong(item -> player.getStats().getValue(Stats.ITEM_USED.get(item))).sum(); }
    private static long mined(ServerPlayer player) { return BuiltInRegistries.BLOCK.stream().mapToLong(block -> player.getStats().getValue(Stats.BLOCK_MINED.get(block))).sum(); }
    private static long placed(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().filter(BlockItem.class::isInstance).mapToLong(item -> player.getStats().getValue(Stats.ITEM_USED.get(item))).sum(); }
    private static long dropped(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().mapToLong(item -> player.getStats().getValue(Stats.ITEM_DROPPED.get(item))).sum(); }
    private static long pickedUp(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().mapToLong(item -> player.getStats().getValue(Stats.ITEM_PICKED_UP.get(item))).sum(); }
    private static long crafted(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().mapToLong(item -> player.getStats().getValue(Stats.ITEM_CRAFTED.get(item))).sum(); }
    private static long redstonePlaced(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().filter(RankBoardMod::isRedstoneComponent).mapToLong(item -> player.getStats().getValue(Stats.ITEM_USED.get(item))).sum(); }
    static boolean isRedstoneComponent(Item item) {
        String path = RegistryKeyCompat.path(BuiltInRegistries.ITEM, item);
        return REDSTONE_COMPONENTS.contains(path) || path.endsWith("_button") || path.endsWith("_pressure_plate")
                || path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_fence_gate") || path.endsWith("_bulb");
    }

    private static long afkTime(ServerPlayer player) {
        return LeaderboardState.get(PlayerCompat.server(player)).customMetric(player.getUUID(), Metric.AFK_TIME);
    }
    private static long bedrockBroken(ServerPlayer player) {
        return LeaderboardState.get(PlayerCompat.server(player)).customMetric(player.getUUID(), Metric.BEDROCK_BROKEN);
    }
    private enum WhitelistMode { SERVER, MOD, NONE }
    @FunctionalInterface interface Counter { long read(ServerPlayer player); }
    record Entry(UUID uuid, String name, long value) { }
    private record ColorPreset(String key, String label, ChatFormatting formatting) {
        String hex() { return String.format(java.util.Locale.ROOT, "#%06X", RankBoardColors.colorValue(formatting)); }
    }
}

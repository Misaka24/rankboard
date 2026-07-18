package cn.bamgdam.rankboard;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RankBoardMod implements ModInitializer {
    public static final String MOD_ID = "rankboard";
    static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int REFRESH_INTERVAL_TICKS = 600;
    private static final Set<UUID> LOOK_MENU_HELD = new HashSet<>();
    private int ticks;

    @Override
    public void onInitialize() {
        LOGGER.info("RankBoard initialized for Minecraft 1.21.1");
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RankBoardConfig.load(server);
            StatReader.startWarmup(server);
            WebDashboard.start(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BoardService.clearSessions();
            WebDashboard.stop();
            StatReader.stopWarmup();
            LOOK_MENU_HELD.clear();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            LeaderboardState.get(server).ensurePlayer(player);
            AvatarCache.cacheOnJoin(server, player);
            BoardService.restore(player);
            sendJoinExperience(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            StatReader.reloadPlayer(server, handler.getPlayer().getUuid());
            LOOK_MENU_HELD.remove(handler.getPlayer().getUuid());
            BoardService.disconnect(handler.getPlayer());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            BoardService.tickCarousel(server);
            BoardService.tickActivity(server);
            handleLookUpSneakMenu(server);
            if (++ticks >= REFRESH_INTERVAL_TICKS) {
                ticks = 0;
                LeaderboardState.get(server).rollPeriods(server);
                BoardService.refreshAll(server);
            }
        });
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
                                  CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("leaderboard")
                .requires(source -> CommandPermissionCompat.has(source, 0)).executes(context -> menu(context.getSource()));
        root.then(CommandManager.literal("help").requires(source -> RankBoardConfig.get().helpVisible(source))
                .executes(context -> help(context.getSource())));
        root.then(CommandManager.literal("mine")
                .executes(context -> showMyScores(context.getSource(), -1, "总计"))
                .then(CommandManager.literal("all").executes(context -> showMyScores(context.getSource(), -1, "总计")))
                .then(CommandManager.literal("day").executes(context -> showMyScores(context.getSource(), 1, "最近一日")))
                .then(CommandManager.literal("week").executes(context -> showMyScores(context.getSource(), 7, "最近一周")))
                .then(CommandManager.literal("month").executes(context -> showMyScores(context.getSource(), 30, "最近一月"))));
        root.then(CommandManager.literal("carousel")
                .then(CommandManager.literal("on").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> BoardService.carouselStatus(context.getSource()))));
        root.then(CommandManager.literal("display")
                .then(CommandManager.literal("off").executes(context -> BoardService.disable(context.getSource()))
                        .then(CommandManager.argument("player", EntityArgumentType.player()).requires(source -> CommandPermissionCompat.has(source, 2))
                                .executes(context -> BoardService.disable(context.getSource(),
                                        EntityArgumentType.getPlayer(context, "player")))))
                .then(buildSelectionCommands(false)));
        root.then(CommandManager.literal("namecolor")
                .then(CommandManager.literal("on").executes(context -> setNameColor(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setNameColor(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> nameColorStatus(context.getSource()))));
        LiteralArgumentBuilder<ServerCommandSource> displayFilter = CommandManager.literal("displayfilter")
                .requires(source -> CommandPermissionCompat.has(source, 2));
        for (Metric metric : Metric.values()) {
            displayFilter.then(CommandManager.literal(metric.command)
                    .then(CommandManager.literal("enable").executes(context -> setMetricDisplay(context.getSource(), metric, true)))
                    .then(CommandManager.literal("disable").executes(context -> setMetricDisplay(context.getSource(), metric, false)))
                    .then(CommandManager.literal("status").executes(context -> metricDisplayStatus(context.getSource(), metric))));
        }
        root.then(displayFilter);
        root.then(CommandManager.literal("scoreboard").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("clear").executes(context -> BoardService.clearVanilla(context.getSource())))
                .then(buildSelectionCommands(true)));
        root.then(CommandManager.literal("whitelist").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("on").executes(context -> setWhitelistOnly(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setWhitelistOnly(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> whitelistStatus(context.getSource()))));
        root.then(CommandManager.literal("botfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("on").executes(context -> setBotFilter(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setBotFilter(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> botFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("customfilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("on").executes(context -> setCustomFilter(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setCustomFilter(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> customFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("onlinefilter").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("on").executes(context -> setOnlineFilter(context.getSource(), true)))
                .then(CommandManager.literal("off").executes(context -> setOnlineFilter(context.getSource(), false)))
                .then(CommandManager.literal("status").executes(context -> onlineFilterStatus(context.getSource()))));
        root.then(CommandManager.literal("lookup").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("whitelist").executes(context -> MojangNameLookup.lookupWhitelist(context.getSource())))
                .then(CommandManager.argument("uuid", StringArgumentType.word())
                        .executes(context -> MojangNameLookup.lookupOne(context.getSource(),
                                StringArgumentType.getString(context, "uuid")))));
        root.then(CommandManager.literal("cache").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(CommandManager.literal("status").executes(context -> cacheStatus(context.getSource())))
                .then(CommandManager.literal("reload").executes(context -> reloadCache(context.getSource()))));
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
            for (Metric metric : Metric.values()) {
                periodNode.then(CommandManager.literal(metric.command)
                        .executes(context -> show(context.getSource(), period, metric, 10))
                        .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> show(context.getSource(), period, metric, IntegerArgumentType.getInteger(context, "limit")))));
            }
            root.then(periodNode);
        }
        dispatcher.register(root);
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildSelectionCommands(boolean vanilla) {
        LiteralArgumentBuilder<ServerCommandSource> periods = CommandManager.literal("show");
        for (Period period : Period.values()) {
            LiteralArgumentBuilder<ServerCommandSource> periodNode = CommandManager.literal(period.command);
            for (Metric metric : Metric.values()) {
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
                periodNode.then(metricNode);
            }
            periods.then(periodNode);
        }
        return periods;
    }

    private int help(ServerCommandSource source) {
        boolean op = CommandPermissionCompat.has(source, 2);
        source.sendFeedback(() -> Text.literal("RankBoard 排行榜帮助").formatted(Formatting.GOLD), false);
        helpCommand(source, "/leaderboard", "/leaderboard", "打开可点击菜单");
        helpCommand(source, "/leaderboard mine <all|day|week|month>", "/leaderboard mine ", "查看自己的全部分数");
        helpCommand(source, "/leaderboard display show <周期> <榜单>", "/leaderboard display show ", "显示个人原版侧边栏");
        helpCommand(source, "/leaderboard display off", "/leaderboard display off", "关闭个人侧边栏");
        helpCommand(source, "/leaderboard carousel on|off|status", "/leaderboard carousel ", "控制榜单轮播");
        helpCommand(source, "/leaderboard namecolor on|off|status", "/leaderboard namecolor ", "开关自己的榜单名字颜色");
        helpCommand(source, "/leaderboard <周期> <榜单> [数量]", "/leaderboard all playtime ", "在聊天栏查看排名");
        String help = "抬头并按住 Shift：打开 /leaderboard 菜单\n"
                + "网页默认地址：http://服务器地址:8765/\n"
                + "主配置：config/rankboard/rankboard.properties\n"
                + "网页配置：config/rankboard/rankboard-web.properties\n"
                + "可配置 host、port、server-name、website-icon。\n"
                + "个人侧边栏零分玩家：client-scoreboard-show-zero，默认 false。\n"
                + "切换榜单提示：scoreboard-switch-message-enabled，默认 true。\n"
                + "榜单名字颜色：scoreboard-name-color-enabled，默认 true；可通过上方名字颜色指令控制。\n"
                + "计分板标题颜色：scoreboard-title-color-enabled，默认 true，独立于玩家名字颜色。\n"
                + "周期：daily 每日，weekly 每周，monthly 每月，yearly 每年，all 总计\n"
                + "榜单：food 食物，jumps 跳跃，mined 挖掘，placed 放置，kills 击杀，deaths 死亡，"
                + "trades 交易，playtime 在线，elytra 鞘翅，fishing 钓鱼，damage 受伤";
        source.sendFeedback(() -> Text.literal(help).formatted(Formatting.GRAY), false);
        if (op) {
            helpCommand(source, "/leaderboard config list|get|set|reload", "/leaderboard config ", "查询、修改或重载配置");
            helpCommand(source, "/leaderboard config set welcome-enabled false", "/leaderboard config set welcome-enabled false", "关闭欢迎语");
            helpCommand(source, "/leaderboard config set welcome-name <名称|auto>", "/leaderboard config set welcome-name ", "修改欢迎语名称");
            helpCommand(source, "/leaderboard config set web-data-requests-per-second <次数>", "/leaderboard config set web-data-requests-per-second ", "修改网页数据基础限额");
            helpCommand(source, "/leaderboard config set web-icon-requests-per-minute <次数>", "/leaderboard config set web-icon-requests-per-minute ", "修改图标基础限额");
            helpCommand(source, "/leaderboard config set web-ranking-refresh-interval-seconds <秒>", "/leaderboard config set web-ranking-refresh-interval-seconds ", "修改网页整体刷新间隔");
            helpCommand(source, "/leaderboard config set scoreboard-live-update-threshold <次数>", "/leaderboard config set scoreboard-live-update-threshold ", "修改客户端即时刷新高频阈值");
            helpCommand(source, "/leaderboard config set scoreboard-live-update-throttle-seconds <秒>", "/leaderboard config set scoreboard-live-update-throttle-seconds ", "修改高频榜单刷新间隔");
            helpCommand(source, "/leaderboard config set scoreboard-name-color-enabled <true|false>", "/leaderboard config set scoreboard-name-color-enabled ", "全局开关玩家名字颜色");
            helpCommand(source, "/leaderboard config set scoreboard-title-color-enabled <true|false>", "/leaderboard config set scoreboard-title-color-enabled ", "独立开关计分板标题颜色");
            helpCommand(source, "/leaderboard ratelimit clear", "/leaderboard ratelimit clear", "清除全部网页限流和累计冷却");
            helpCommand(source, "/leaderboard displayfilter <榜单> <enable|disable|status>", "/leaderboard displayfilter ", "管理单个榜单是否可显示");
            helpCommand(source, "/leaderboard scoreboard <show|clear>", "/leaderboard scoreboard ", "管理全服原版侧边栏");
            helpCommand(source, "/leaderboard whitelist <on|off|status>", "/leaderboard whitelist ", "控制白名单玩家筛选");
            helpCommand(source, "/leaderboard botfilter <on|off|status>", "/leaderboard botfilter ", "控制 bot 玩家筛选");
            helpCommand(source, "/leaderboard customfilter <on|off|status>", "/leaderboard customfilter ", "控制无法识别身份玩家筛选");
            helpCommand(source, "/leaderboard onlinefilter <on|off|status>", "/leaderboard onlinefilter ", "控制仅在线玩家筛选");
            helpCommand(source, "/leaderboard lookup <uuid|whitelist>", "/leaderboard lookup ", "查询 Mojang 玩家名称");
            helpCommand(source, "/leaderboard cache <status|reload>", "/leaderboard cache ", "查看或重载历史统计缓存");
        }
        return 1;
    }

    private static void helpCommand(ServerCommandSource source, String label, String suggestion, String description) {
        Text line = Text.literal(label + "：" + description).setStyle(TextCompat.suggest(
                Style.EMPTY.withColor(Formatting.AQUA), suggestion, Text.literal("点击填入指令栏")));
        source.sendFeedback(() -> line, false);
    }

    private int menu(ServerCommandSource source) {
        Text header = clickable("[查询我的分数]", Formatting.GOLD, "/leaderboard mine all", "查看自己的全部统计分数")
                .copy().append(Text.literal(" "))
                .append(clickable("[关闭]", Formatting.RED, "/leaderboard display off", "关闭自己的客户端计分板"));
        if (RankBoardConfig.get().carouselEnabled) {
            header = header.copy().append(Text.literal(" "))
                    .append(clickable("[轮播]", Formatting.AQUA, "/leaderboard carousel on", "自动轮播当前周期的榜单"));
        }
        if (RankBoardConfig.get().helpVisible(source)) {
            header = header.copy().append(Text.literal(" "))
                    .append(clickable("[Help]", Formatting.GREEN, "/leaderboard help", "查看 RankBoard 帮助"));
        }
        Text finalHeader = header;
        source.sendFeedback(() -> finalHeader, false);
        Text line = Text.empty();
        int visible = 0;
        for (Metric metric : Metric.values()) {
            if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
            Text button = clickable("[" + metric.label + "]", metric.nameColor,
                    "/leaderboard display show all " + metric.command,
                    "点击显示总计 " + metric.label + " 侧边栏");
            if (visible > 0 && visible % 4 == 0) {
                Text completed = line;
                source.sendFeedback(() -> completed, false);
                line = Text.empty();
            }
            line = line.copy().append(button).append(Text.literal(" "));
            visible++;
        }
        if (visible > 0) {
            Text finalLine = line;
            source.sendFeedback(() -> finalLine, false);
        }
        else source.sendFeedback(() -> Text.literal("所有榜单显示均已被 OP 禁用。\n").formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("点击榜单即可切换自己的原版侧边栏。")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private int showMyScores(ServerCommandSource source, int days, String label) {
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            LeaderboardState state = LeaderboardState.get(source.getServer());
            source.sendFeedback(() -> Text.literal("=== 我的分数 · " + label + " ===").formatted(Formatting.GOLD), false);
            LocalDate today = LocalDate.now();
            for (Metric metric : Metric.values()) {
                long value;
                if (days < 0) value = metric.read(player);
                else value = state.range(source.getServer(), today.minusDays(days - 1L), today, metric)
                        .values().getOrDefault(player.getUuid(), 0L);
                long score = value;
                source.sendFeedback(() -> Text.literal(metric.label + "  ").formatted(metric.nameColor)
                        .append(Text.literal(format(metric, score)).formatted(Formatting.AQUA)), false);
            }
            Text periods = clickable("[总计]", Formatting.GOLD, "/leaderboard mine all", "查看累计分数")
                    .copy().append(Text.literal(" "))
                    .append(clickable("[最近一日]", Formatting.YELLOW, "/leaderboard mine day", "查看最近一日分数"))
                    .append(Text.literal(" "))
                    .append(clickable("[最近一周]", Formatting.AQUA, "/leaderboard mine week", "查看最近一周分数"))
                    .append(Text.literal(" "))
                    .append(clickable("[最近一月]", Formatting.LIGHT_PURPLE, "/leaderboard mine month", "查看最近一月分数"));
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
        source.sendFeedback(() -> Text.literal("=== RankBoard 配置 ===").formatted(Formatting.GOLD), false);
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

    private int setConfig(ServerCommandSource source, String key, String value) {
        if (!RankBoardConfig.isKnownOption(key)) {
            source.sendError(Text.literal("未知配置项：" + key));
            return 0;
        }
        try {
            boolean webOption = RankBoardConfig.isWebOption(key);
            String normalized = RankBoardConfig.set(source.getServer(), key, value);
            if (key.equals("history-files-per-second")) StatReader.startWarmup(source.getServer());
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
        StatReader.startWarmup(source.getServer());
        boolean webRunning = WebDashboard.restart(source.getServer());
        if (!webRunning) {
            source.sendError(Text.literal("配置已重载，但网页服务启动失败；请检查服务器日志和网页配置。"));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("RankBoard 主配置与网页配置已重载。")
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private int clearRateLimits(ServerCommandSource source) {
        int cleared = WebDashboard.clearRateLimits();
        source.sendFeedback(() -> Text.literal("已清除 " + cleared + " 个网页限流与 API 累计冷却记录。")
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private void sendJoinExperience(ServerPlayerEntity player) {
        RankBoardConfig config = RankBoardConfig.get();
        if (config.welcomeEnabled) {
            player.sendMessage(Text.literal("欢迎来到 ").formatted(Formatting.GRAY)
                    .append(Text.literal(config.displayName(PlayerCompat.server(player))).formatted(Formatting.GOLD)), false);
        }
        if (config.joinWebHintEnabled) {
            player.sendMessage(Text.literal("可在 " + config.webAddress(PlayerCompat.server(player)) + " 查看网页排行榜。")
                    .formatted(Formatting.AQUA), false);
        }
        if (config.joinMenuEnabled) menu(player.getCommandSource());
    }

    private void handleLookUpSneakMenu(net.minecraft.server.MinecraftServer server) {
        if (!RankBoardConfig.get().lookUpSneakMenuEnabled) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean active = player.isSneaking() && player.getPitch() <= -60.0F;
            if (active && LOOK_MENU_HELD.add(player.getUuid())) menu(player.getCommandSource());
            else if (!active) LOOK_MENU_HELD.remove(player.getUuid());
        }
    }

    private static Text clickable(String label, Formatting color, String command, String hover) {
        return Text.literal(label).setStyle(TextCompat.interactive(Style.EMPTY.withColor(color), command, Text.literal(hover)));
    }

    private int setNameColor(ServerCommandSource source, boolean enabled) {
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            LeaderboardState.get(source.getServer()).setNameColorEnabled(player.getUuid(), enabled);
            BoardService.refreshAll(source.getServer());
            source.sendFeedback(() -> Text.literal(enabled ? "已开启自己的榜单名字颜色。" : "已关闭自己的榜单名字颜色。"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int nameColorStatus(ServerCommandSource source) {
        try {
            boolean enabled = LeaderboardState.get(source.getServer()).isNameColorEnabled(source.getPlayerOrThrow().getUuid());
            source.sendFeedback(() -> Text.literal("自己的榜单名字颜色：" + (enabled ? "已开启" : "已关闭")), false);
            return enabled ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendError(Text.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int setMetricDisplay(ServerCommandSource source, Metric metric, boolean enabled) {
        LeaderboardState.get(source.getServer()).setMetricDisplayEnabled(metric, enabled);
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(enabled ? metric.label + " 已恢复显示。" : metric.label + " 已禁止显示。"), true);
        return 1;
    }

    private int metricDisplayStatus(ServerCommandSource source, Metric metric) {
        boolean enabled = LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric);
        source.sendFeedback(() -> Text.literal(metric.label + " 显示：" + (enabled ? "已开启" : "已禁用")), false);
        return enabled ? 1 : 0;
    }

    private int setWhitelistOnly(ServerCommandSource source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setWhitelistOnly(enabled);
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

    private int cacheStatus(ServerCommandSource source) {
        String status = StatReader.isReady() ? "已完成" : "加载中";
        source.sendFeedback(() -> Text.literal("历史统计缓存：" + status + "（" + StatReader.progress() + "）")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private int reloadCache(ServerCommandSource source) {
        StatReader.startWarmup(source.getServer());
        source.sendFeedback(() -> Text.literal("已重新开始加载历史统计缓存，当前进度 " + StatReader.progress() + "。")
                .formatted(Formatting.GRAY), true);
        return 1;
    }

    private int show(ServerCommandSource source, Period period, Metric metric, int limit) {
        try {
            if (!StatReader.isReady()) {
                source.sendFeedback(() -> Text.literal("历史统计仍在加载（" + StatReader.progress()
                        + "），当前榜单可能不完整。").formatted(Formatting.GRAY), false);
            }
            List<Entry> entries = entries(source.getServer(), period, metric);
            source.sendFeedback(() -> Text.literal("=== " + period.label + " " + metric.label + " ===").formatted(Formatting.GOLD), false);
            if (entries.isEmpty()) {
                source.sendFeedback(() -> Text.literal("没有可用于排行的玩家统计。 ").formatted(Formatting.GRAY), false);
                return 0;
            }
            long total = total(entries);
            source.sendFeedback(() -> Text.literal("总和 ").formatted(Formatting.GRAY)
                    .append(Text.literal(format(metric, total)).formatted(Formatting.AQUA)), false);
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                Entry entry = entries.get(i);
                int rank = i + 1;
                source.sendFeedback(() -> Text.literal(rank + " ").formatted(Formatting.YELLOW)
                        .append(Text.literal(entry.name())).append(Text.literal("  " + format(metric, entry.value())).formatted(Formatting.AQUA)), false);
            }
            return entries.size();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to execute leaderboard command: period={}, metric={}", period.command, metric.command, exception);
            source.sendError(Text.literal("排行榜读取失败：" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : " - " + exception.getMessage())));
            return 0;
        }
    }

    static List<Entry> entries(net.minecraft.server.MinecraftServer server, Period period, Metric metric) {
        LeaderboardState state = LeaderboardState.get(server);
        state.rollPeriods(server);
        return StatReader.readAll(server, metric).stream()
                .filter(snapshot -> isIncluded(server, state, snapshot.uuid(), snapshot.name()))
                .map(snapshot -> new Entry(snapshot.name(), Math.max(0, snapshot.value(metric) - (period == Period.ALL ? 0 : state.getBaseline(period, snapshot.uuid(), metric)))))
                .sorted(Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::name))
                .toList();
    }

    static boolean isIncluded(net.minecraft.server.MinecraftServer server, LeaderboardState state,
                              java.util.UUID uuid, String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return (!state.isWhitelistOnly() || PlayerDirectoryCompat.isAllowed(server, uuid, name))
                && (!state.isBotFilterEnabled() || !normalized.startsWith("bot_"))
                && (!state.isCustomPlayerFilterEnabled() || !normalized.startsWith("unknown_"))
                && (!state.isOnlineOnly() || server.getPlayerManager().getPlayer(uuid) != null);
    }

    static String format(Metric metric, long value) {
        if (metric == Metric.PLAY_TIME) return (value / 72000) + "h " + ((value / 1200) % 60) + "m";
        if (metric == Metric.ELYTRA_DISTANCE) return String.format(java.util.Locale.ROOT, "%.1f km", value / 100000.0);
        if (metric == Metric.DAMAGE_TAKEN) return String.format(java.util.Locale.ROOT, "%.1f", value / 10.0);
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
        MINED("mined", "挖掘榜", Formatting.GRAY, RankBoardMod::mined),
        PLACED("placed", "放置榜", Formatting.DARK_GREEN, RankBoardMod::placed),
        KILLS("kills", "击杀榜", Formatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),
        DEATHS("deaths", "死亡榜", Formatting.DARK_RED, p -> custom(p, Stats.DEATHS)),
        TRADES("trades", "交易榜", Formatting.AQUA, p -> custom(p, Stats.TRADED_WITH_VILLAGER)),
        PLAY_TIME("playtime", "在线时间榜", Formatting.DARK_AQUA, p -> custom(p, Stats.PLAY_TIME)),
        ELYTRA_DISTANCE("elytra", "鞘翅飞行榜", Formatting.LIGHT_PURPLE, p -> custom(p, Stats.AVIATE_ONE_CM)),
        FISHING("fishing", "钓鱼榜", Formatting.BLUE, p -> custom(p, Stats.FISH_CAUGHT)),
        DAMAGE_TAKEN("damage", "受伤害榜", Formatting.YELLOW, p -> custom(p, Stats.DAMAGE_TAKEN));

        final String command;
        final String label;
        final Formatting nameColor;
        final Counter counter;
        Metric(String command, String label, Formatting nameColor, Counter counter) {
            this.command = command; this.label = label; this.nameColor = nameColor; this.counter = counter;
        }
        long read(ServerPlayerEntity player) { return counter.read(player); }
    }

    public enum Period {
        DAILY("daily", "每日"), WEEKLY("weekly", "每周"), MONTHLY("monthly", "每月"), YEARLY("yearly", "每年"), ALL("all", "总计");
        final String command;
        final String label;
        Period(String command, String label) { this.command = command; this.label = label; }
        String key(LocalDate date) {
            return switch (this) {
                case DAILY -> date.toString();
                case WEEKLY -> date.getYear() + "-W" + date.get(WeekFields.ISO.weekOfWeekBasedYear());
                case MONTHLY -> date.getYear() + "-" + date.getMonthValue();
                case YEARLY -> Integer.toString(date.getYear());
                case ALL -> "all";
            };
        }
    }

    private static long custom(ServerPlayerEntity player, net.minecraft.util.Identifier stat) { return player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(stat)); }
    private static long foodUsed(ServerPlayerEntity player) { return Registries.ITEM.stream().filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null).mapToLong(item -> player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item))).sum(); }
    private static long mined(ServerPlayerEntity player) { return Registries.BLOCK.stream().mapToLong(block -> player.getStatHandler().getStat(Stats.MINED.getOrCreateStat(block))).sum(); }
    private static long placed(ServerPlayerEntity player) { return Registries.ITEM.stream().filter(BlockItem.class::isInstance).mapToLong(item -> player.getStatHandler().getStat(Stats.USED.getOrCreateStat(item))).sum(); }

    @FunctionalInterface interface Counter { long read(ServerPlayerEntity player); }
    record Entry(String name, long value) { }
}


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
import net.minecraft.world.level.block.Block;
import net.minecraft.core.component.DataComponents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
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
            RankBoardWhitelist.load(server);
            BoardService.restoreGlobal(server);
            BoardService.enforceForeignScoreboardPolicy(server);
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
            ServerPlayer player = handler.getPlayer();
            LeaderboardState.get(server).ensurePlayer(player);
            AvatarCache.cacheOnJoin(server, player);
            BoardService.restore(player);
            sendJoinExperience(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            StatReader.reloadPlayer(server, handler.getPlayer().getUUID());
            LOOK_MENU_HELD.remove(handler.getPlayer().getUUID());
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
                BoardService.enforceForeignScoreboardPolicy(server);
            }
        });
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                                  CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("leaderboard")
                .requires(source -> CommandPermissionCompat.has(source, 0)).executes(context -> menu(context.getSource()));
        root.then(Commands.literal("help").requires(source -> RankBoardConfig.get().helpVisible(source))
                .executes(context -> help(context.getSource())));
        root.then(Commands.literal("mine")
                .executes(context -> showMyScores(context.getSource(), -1, "总计"))
                .then(Commands.literal("all").executes(context -> showMyScores(context.getSource(), -1, "总计")))
                .then(Commands.literal("day").executes(context -> showMyScores(context.getSource(), 1, "最近一日")))
                .then(Commands.literal("week").executes(context -> showMyScores(context.getSource(), 7, "最近一周")))
                .then(Commands.literal("month").executes(context -> showMyScores(context.getSource(), 30, "最近一月"))));
        root.then(Commands.literal("carousel")
                .then(Commands.literal("true").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> BoardService.setCarousel(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> BoardService.setCarousel(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> BoardService.carouselStatus(context.getSource()))));
        root.then(Commands.literal("display")
                .then(Commands.literal("off").executes(context -> BoardService.disable(context.getSource()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(source -> CommandPermissionCompat.has(source, 2))
                                .executes(context -> BoardService.disable(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(buildSelectionCommands(false)));
        root.then(Commands.literal("namecolor")
                .then(Commands.literal("true").executes(context -> setNameColor(context.getSource(), true)))
                .then(Commands.literal("false").executes(context -> setNameColor(context.getSource(), false)))
                .then(Commands.literal("on").executes(context -> setNameColor(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setNameColor(context.getSource(), false)))
                .then(Commands.literal("status").executes(context -> nameColorStatus(context.getSource()))));
        LiteralArgumentBuilder<CommandSourceStack> displayFilter = Commands.literal("displayfilter")
                .requires(source -> CommandPermissionCompat.has(source, 2));
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
        root.then(Commands.literal("lookup").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("whitelist").executes(context -> MojangNameLookup.lookupWhitelist(context.getSource())))
                .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(context -> MojangNameLookup.lookupOne(context.getSource(),
                                StringArgumentType.getString(context, "uuid")))));
        root.then(Commands.literal("cache").requires(source -> CommandPermissionCompat.has(source, 2))
                .then(Commands.literal("status").executes(context -> cacheStatus(context.getSource())))
                .then(Commands.literal("reload").executes(context -> reloadCache(context.getSource()))));
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

    private int help(CommandSourceStack source) {
        boolean op = CommandPermissionCompat.has(source, 2);
        source.sendSuccess(() -> Component.literal("RankBoard 排行榜帮助").withStyle(ChatFormatting.GOLD), false);
        helpCommand(source, "/leaderboard", "/leaderboard", "打开可点击菜单");
        helpCommand(source, "/leaderboard mine <all|day|week|month>", "/leaderboard mine ", "查看自己的全部分数");
        helpCommand(source, "/leaderboard display show <周期> <榜单>", "/leaderboard display show ", "显示个人原版侧边栏");
        helpCommand(source, "/leaderboard display off", "/leaderboard display off", "关闭个人侧边栏");
        helpCommand(source, "/leaderboard carousel true|false|status", "/leaderboard carousel ", "控制榜单轮播");
        helpCommand(source, "/leaderboard namecolor true|false|status", "/leaderboard namecolor ", "开关自己的榜单名字颜色");
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
                + "模组白名单：config/rankboard/rankboard-whitelist.json；由 mod-whitelist-enabled 控制，默认关闭。\n"
                + "周期：daily 每日，weekly 每周，monthly 每月，yearly 每年，all 总计\n"
                + "榜单：food 食物，jumps 跳跃，mined 挖掘，placed 放置，kills 击杀，deaths 死亡，"
                + "trades 交易，playtime 在线，elytra 鞘翅，fishing 钓鱼，damage 受伤";
        source.sendSuccess(() -> Component.literal(help).withStyle(ChatFormatting.GRAY), false);
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
            helpCommand(source, "/leaderboard displayfilter <榜单> <true|false|status>", "/leaderboard displayfilter ", "管理单个榜单是否可显示");
            helpCommand(source, "/leaderboard scoreboard <show|clear|cleanup>", "/leaderboard scoreboard ", "管理全服侧边栏并关闭其他模组计分板");
            helpCommand(source, "/leaderboard scoreboard blocking <true|false|status>", "/leaderboard scoreboard blocking ", "设置其他模组计分板自动屏蔽");
            helpCommand(source, "/leaderboard whitelist <true|false|status>", "/leaderboard whitelist ", "控制白名单玩家筛选");
            helpCommand(source, "/leaderboard botfilter <true|false|status>", "/leaderboard botfilter ", "控制 bot 玩家筛选");
            helpCommand(source, "/leaderboard customfilter <true|false|status>", "/leaderboard customfilter ", "控制无法识别身份玩家筛选");
            helpCommand(source, "/leaderboard onlinefilter <true|false|status>", "/leaderboard onlinefilter ", "控制仅在线玩家筛选");
            helpCommand(source, "/leaderboard modwhitelist <add|remove|list|reload>", "/leaderboard modwhitelist ", "管理模组自带白名单");
            helpCommand(source, "/leaderboard lookup <uuid|whitelist>", "/leaderboard lookup ", "查询 Mojang 玩家名称");
            helpCommand(source, "/leaderboard cache <status|reload>", "/leaderboard cache ", "查看或重载历史统计缓存");
        }
        return 1;
    }

    private static void helpCommand(CommandSourceStack source, String label, String suggestion, String description) {
        Component line = Component.literal(label + "：" + description).setStyle(TextCompat.suggest(
                Style.EMPTY.withColor(ChatFormatting.AQUA), suggestion, Component.literal("点击填入指令栏")));
        source.sendSuccess(() -> line, false);
    }

    private int menu(CommandSourceStack source) {
        Component header = clickable("[查询我的分数]", ChatFormatting.GOLD, "/leaderboard mine all", "查看自己的全部统计分数")
                .copy().append(Component.literal(" "))
                .append(clickable("[关闭]", ChatFormatting.RED, "/leaderboard display off", "关闭自己的客户端计分板"));
        if (RankBoardConfig.get().carouselEnabled) {
            header = header.copy().append(Component.literal(" "))
                    .append(clickable("[轮播]", ChatFormatting.AQUA, "/leaderboard carousel true", "自动轮播当前周期的榜单"));
        }
        if (RankBoardConfig.get().helpVisible(source)) {
            header = header.copy().append(Component.literal(" "))
                    .append(clickable("[Help]", ChatFormatting.GREEN, "/leaderboard help", "查看 RankBoard 帮助"));
        }
        Component finalHeader = header;
        source.sendSuccess(() -> finalHeader, false);
        Component line = Component.empty();
        int visible = 0;
        for (Metric metric : Metric.values()) {
            if (!LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric)) continue;
            Component button = clickable("[" + metric.label + "]", metric.nameColor,
                    "/leaderboard display show all " + metric.command,
                    "点击显示总计 " + metric.label + " 侧边栏");
            if (visible > 0 && visible % 4 == 0) {
                Component completed = line;
                source.sendSuccess(() -> completed, false);
                line = Component.empty();
            }
            line = line.copy().append(button).append(Component.literal(" "));
            visible++;
        }
        if (visible > 0) {
            Component finalLine = line;
            source.sendSuccess(() -> finalLine, false);
        }
        else source.sendSuccess(() -> Component.literal("所有榜单显示均已被 OP 禁用。\n").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("点击榜单即可切换自己的原版侧边栏。")
                .withStyle(ChatFormatting.GRAY), false);
        BoardService.sendForeignScoreboardPrompt(source);
        return 1;
    }

    private int showMyScores(CommandSourceStack source, int days, String label) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            LeaderboardState state = LeaderboardState.get(source.getServer());
            source.sendSuccess(() -> Component.literal("=== 我的分数 · " + label + " ===").withStyle(ChatFormatting.GOLD), false);
            LocalDate today = LocalDate.now();
            for (Metric metric : Metric.values()) {
                long value;
                if (days < 0) value = metric.read(player);
                else value = state.range(source.getServer(), today.minusDays(days - 1L), today, metric)
                        .values().getOrDefault(player.getUUID(), 0L);
                long score = value;
                source.sendSuccess(() -> Component.literal(metric.label + "  ").withStyle(metric.nameColor)
                        .append(Component.literal(format(metric, score)).withStyle(ChatFormatting.AQUA)), false);
            }
            Component periods = clickable("[总计]", ChatFormatting.GOLD, "/leaderboard mine all", "查看累计分数")
                    .copy().append(Component.literal(" "))
                    .append(clickable("[最近一日]", ChatFormatting.YELLOW, "/leaderboard mine day", "查看最近一日分数"))
                    .append(Component.literal(" "))
                    .append(clickable("[最近一周]", ChatFormatting.AQUA, "/leaderboard mine week", "查看最近一周分数"))
                    .append(Component.literal(" "))
                    .append(clickable("[最近一月]", ChatFormatting.LIGHT_PURPLE, "/leaderboard mine month", "查看最近一月分数"));
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

    private int setConfig(CommandSourceStack source, String key, String value) {
        if (!RankBoardConfig.isKnownOption(key)) {
            source.sendFailure(Component.literal("未知配置项：" + key));
            return 0;
        }
        try {
            boolean webOption = RankBoardConfig.isWebOption(key);
            String normalized = RankBoardConfig.set(source.getServer(), key, value);

            if (key.equals("history-files-per-second")) StatReader.startWarmup(source.getServer());
            if (key.equals("mod-whitelist-enabled")) {
                RankBoardWhitelist.reload(source.getServer());
                StatReader.startWarmup(source.getServer());
            }
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
        RankBoardWhitelist.reload(source.getServer());
        StatReader.startWarmup(source.getServer());
        boolean webRunning = WebDashboard.restart(source.getServer());
        if (!webRunning) {
            source.sendFailure(Component.literal("配置已重载，但网页服务启动失败；请检查服务器日志和网页配置。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("RankBoard 主配置与网页配置已重载。")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private int modifyModWhitelist(CommandSourceStack source, boolean add, String player) {
        try {
            boolean changed = add ? RankBoardWhitelist.add(source.getServer(), player)
                    : RankBoardWhitelist.remove(source.getServer(), player);
            if (changed && RankBoardConfig.get().modWhitelistEnabled) StatReader.startWarmup(source.getServer());
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
        if (RankBoardConfig.get().modWhitelistEnabled) StatReader.startWarmup(source.getServer());
        return 1;
    }

    private int clearRateLimits(CommandSourceStack source) {
        int cleared = WebDashboard.clearRateLimits();
        source.sendSuccess(() -> Component.literal("已清除 " + cleared + " 个网页限流与 API 累计冷却记录。")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private void sendJoinExperience(ServerPlayer player) {
        RankBoardConfig config = RankBoardConfig.get();
        if (config.welcomeEnabled) {
            player.sendSystemMessage(Component.literal("欢迎来到 ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(config.displayName(PlayerCompat.server(player))).withStyle(ChatFormatting.GOLD)), false);
        }
        if (config.joinWebHintEnabled) {
            player.sendSystemMessage(Component.literal("可在 " + config.webAddress(PlayerCompat.server(player)) + " 查看网页排行榜。")
                    .withStyle(ChatFormatting.AQUA), false);
        }
        if (config.joinMenuEnabled) menu(player.createCommandSourceStack());
    }

    private void handleLookUpSneakMenu(net.minecraft.server.MinecraftServer server) {
        if (!RankBoardConfig.get().lookUpSneakMenuEnabled) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean active = player.isShiftKeyDown() && player.getXRot() <= -60.0F;
            if (active && LOOK_MENU_HELD.add(player.getUUID())) menu(player.createCommandSourceStack());
            else if (!active) LOOK_MENU_HELD.remove(player.getUUID());
        }
    }

    private static Component clickable(String label, ChatFormatting color, String command, String hover) {
        return Component.literal(label).setStyle(TextCompat.interactive(Style.EMPTY.withColor(color), command, Component.literal(hover)));
    }

    private int setNameColor(CommandSourceStack source, boolean enabled) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            LeaderboardState.get(source.getServer()).setNameColorEnabled(player.getUUID(), enabled);
            BoardService.refreshAll(source.getServer());
            source.sendSuccess(() -> Component.literal(enabled ? "已开启自己的榜单名字颜色。" : "已关闭自己的榜单名字颜色。"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int nameColorStatus(CommandSourceStack source) {
        try {
            boolean enabled = LeaderboardState.get(source.getServer()).isNameColorEnabled(source.getPlayerOrException().getUUID());
            source.sendSuccess(() -> Component.literal("自己的榜单名字颜色：" + (enabled ? "已开启" : "已关闭")), false);
            return enabled ? 1 : 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
    }

    private int setMetricDisplay(CommandSourceStack source, Metric metric, boolean enabled) {
        LeaderboardState.get(source.getServer()).setMetricDisplayEnabled(metric, enabled);
        BoardService.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal(enabled ? metric.label + " 已恢复显示。" : metric.label + " 已禁止显示。"), true);
        return 1;
    }

    private int metricDisplayStatus(CommandSourceStack source, Metric metric) {
        boolean enabled = LeaderboardState.get(source.getServer()).isMetricDisplayEnabled(metric);
        source.sendSuccess(() -> Component.literal(metric.label + " 显示：" + (enabled ? "已开启" : "已禁用")), false);
        return enabled ? 1 : 0;
    }

    private int setWhitelistOnly(CommandSourceStack source, boolean enabled) {
        LeaderboardState.get(source.getServer()).setWhitelistOnly(enabled);
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

    private int cacheStatus(CommandSourceStack source) {
        String status = StatReader.isReady() ? "已完成" : "加载中";
        source.sendSuccess(() -> Component.literal("历史统计缓存：" + status + "（" + StatReader.progress() + "）")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private int reloadCache(CommandSourceStack source) {
        StatReader.startWarmup(source.getServer());
        source.sendSuccess(() -> Component.literal("已重新开始加载历史统计缓存，当前进度 " + StatReader.progress() + "。")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private int show(CommandSourceStack source, Period period, Metric metric, int limit) {
        try {
            if (!StatReader.isReady()) {
                source.sendSuccess(() -> Component.literal("历史统计仍在加载（" + StatReader.progress()
                        + "），当前榜单可能不完整。").withStyle(ChatFormatting.GRAY), false);
            }
            List<Entry> entries = entries(source.getServer(), period, metric);
            source.sendSuccess(() -> Component.literal("=== " + period.label + " " + metric.label + " ===").withStyle(ChatFormatting.GOLD), false);
            if (entries.isEmpty()) {
                source.sendSuccess(() -> Component.literal("没有可用于排行的玩家统计。 ").withStyle(ChatFormatting.GRAY), false);

                return 0;
            }
            long total = total(entries);
            source.sendSuccess(() -> Component.literal("总和 ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(format(metric, total)).withStyle(ChatFormatting.AQUA)), false);
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                Entry entry = entries.get(i);
                int rank = i + 1;
                source.sendSuccess(() -> Component.literal(rank + " ").withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(entry.name())).append(Component.literal("  " + format(metric, entry.value())).withStyle(ChatFormatting.AQUA)), false);
            }
            return entries.size();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to execute leaderboard command: period={}, metric={}", period.command, metric.command, exception);
            source.sendFailure(Component.literal("排行榜读取失败：" + exception.getClass().getSimpleName()
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
        return (!RankBoardConfig.get().modWhitelistEnabled || RankBoardWhitelist.matches(server, uuid, name))
                && (!state.isWhitelistOnly() || PlayerDirectoryCompat.isAllowed(server, uuid, name))
                && (!state.isBotFilterEnabled() || !normalized.startsWith("bot_"))
                && (!state.isCustomPlayerFilterEnabled() || !normalized.startsWith("unknown_"))
                && (!state.isOnlineOnly() || server.getPlayerList().getPlayer(uuid) != null);
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
        FOOD("food", "大胃王榜", ChatFormatting.GOLD, RankBoardMod::foodUsed),
        JUMPS("jumps", "跳跃榜", ChatFormatting.LIGHT_PURPLE, p -> custom(p, Stats.JUMP)),
        MINED("mined", "挖掘榜", ChatFormatting.GRAY, RankBoardMod::mined),
        PLACED("placed", "放置榜", ChatFormatting.DARK_GREEN, RankBoardMod::placed),
        KILLS("kills", "击杀榜", ChatFormatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),
        DEATHS("deaths", "死亡榜", ChatFormatting.DARK_RED, p -> custom(p, Stats.DEATHS)),
        TRADES("trades", "交易榜", ChatFormatting.AQUA, p -> custom(p, Stats.TRADED_WITH_VILLAGER)),
        PLAY_TIME("playtime", "在线时间榜", ChatFormatting.DARK_AQUA, p -> custom(p, Stats.PLAY_TIME)),
        ELYTRA_DISTANCE("elytra", "鞘翅飞行榜", ChatFormatting.LIGHT_PURPLE, p -> custom(p, Stats.AVIATE_ONE_CM)),
        FISHING("fishing", "钓鱼榜", ChatFormatting.BLUE, p -> custom(p, Stats.FISH_CAUGHT)),
        DAMAGE_TAKEN("damage", "受伤害榜", ChatFormatting.YELLOW, p -> custom(p, Stats.DAMAGE_TAKEN));

        final String command;
        final String label;
        final ChatFormatting nameColor;
        final Counter counter;
        Metric(String command, String label, ChatFormatting nameColor, Counter counter) {
            this.command = command; this.label = label; this.nameColor = nameColor; this.counter = counter;
        }
        long read(ServerPlayer player) { return counter.read(player); }
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

    private static long custom(ServerPlayer player, net.minecraft.resources.Identifier stat) { return player.getStats().getValue(Stats.CUSTOM.get(stat)); }
    private static long foodUsed(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().filter(item -> item.components().get(DataComponents.FOOD) != null).mapToLong(item -> player.getStats().getValue(Stats.ITEM_USED.get(item))).sum(); }
    private static long mined(ServerPlayer player) { return BuiltInRegistries.BLOCK.stream().mapToLong(block -> player.getStats().getValue(Stats.BLOCK_MINED.get(block))).sum(); }
    private static long placed(ServerPlayer player) { return BuiltInRegistries.ITEM.stream().filter(BlockItem.class::isInstance).mapToLong(item -> player.getStats().getValue(Stats.ITEM_USED.get(item))).sum(); }

    @FunctionalInterface interface Counter { long read(ServerPlayer player); }
    record Entry(String name, long value) { }
}

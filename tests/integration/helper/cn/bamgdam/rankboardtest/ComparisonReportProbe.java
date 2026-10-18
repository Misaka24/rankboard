package cn.bamgdam.rankboardtest;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.Map;

/** Test-only observation of an existing report: never modifies production state. */
public final class ComparisonReportProbe {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rankboard_test_report").executes(context -> {
            try {
                Class<?> service;
                try { service = Class.forName("cn.bamgdam.rankboard.CacheComparisonService"); }
                catch (ClassNotFoundException unifiedLayout) {
                    Class<?> runtime = Class.forName("cn.bamgdam.rankboard.unified.UnifiedRuntime");
                    String namespace = (String) runtime.getMethod("implementationPackage").invoke(null);
                    service = Class.forName(namespace + ".CacheComparisonService");
                }
                var field = service.getDeclaredField("COMPARISONS");
                field.setAccessible(true);
                Object report = ((Map<?, ?>) field.get(null)).get(context.getSource().getServer());
                String message = "NOT_READY";
                if (report != null) {
                    var id = report.getClass().getDeclaredMethod("id");
                    id.setAccessible(true);
                    message = "REPORT_ID " + id.invoke(report);
                }
                String result = message;
                context.getSource().sendSuccess(() -> Component.literal(result), false);
                return report == null ? 0 : 1;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot inspect comparison test report", exception);
            }
        }));
    }
}

package cn.bamgdam.rankboardtest.mixin;

import cn.bamgdam.rankboard.FakePlayerPerformanceTest;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Encloses tickServer plus all Fabric end-tick callbacks, including RankBoard. */
@Mixin(MinecraftServer.class)
public abstract class PerformanceTickMixin {
    @Unique private long rankboardTest$start;
    @Unique private boolean rankboardTest$measuring;

    @Inject(method = "processPacketsAndTick", at = @At("HEAD"))
    private void rankboardTest$before(CallbackInfo callback) {
        rankboardTest$measuring = FakePlayerPerformanceTest.measuringTicks();
        rankboardTest$start = System.nanoTime();
    }

    @Inject(method = "processPacketsAndTick", at = @At("RETURN"))
    private void rankboardTest$after(CallbackInfo callback) {
        if (rankboardTest$measuring) FakePlayerPerformanceTest.recordCompletedTick(System.nanoTime() - rankboardTest$start);
    }
}

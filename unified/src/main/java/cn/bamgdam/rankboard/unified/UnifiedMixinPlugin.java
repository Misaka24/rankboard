package cn.bamgdam.rankboard.unified;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class UnifiedMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { UnifiedRuntime.implementationPackage(); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) { }
    @Override public List<String> getMixins() {
        if (MixinEnvironment.getCurrentEnvironment().getSide() == MixinEnvironment.Side.CLIENT) return List.of();
        String family = UnifiedRuntime.implementationPackage().substring("cn.bamgdam.rankboard.impl.".length());
        return List.of(family + ".PlayerEntityNameMixin", family + ".ServerPlayerListNameMixin");
    }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) { }
}

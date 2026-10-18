package cn.bamgdam.rankboardtest.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ProbeConnectionAccessor {
    @Accessor("channel") void rankboardTestChannel(Channel channel);
    @Accessor("packetListener") void rankboardTestListener(PacketListener listener);
}

package com.github.whitemo.magazine_casing.network;

import com.github.whitemo.magazine_casing.event.CasingSpawnHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求在指定世界位置生成一个弹壳实体（精确到第一人称模型），
 * 并附带客户端算好的抛壳初速度（据枪状态在客户端由 TACZ 状态机判定）。
 */
public class SpawnCasingPacket {

    private final Vec3 pos;
    private final Vec3 velocity;
    private final ResourceLocation gunId;

    public SpawnCasingPacket(Vec3 pos, Vec3 velocity, ResourceLocation gunId) {
        this.pos = pos;
        this.velocity = velocity;
        this.gunId = gunId;
    }

    public static void encode(SpawnCasingPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.pos.x);
        buf.writeDouble(msg.pos.y);
        buf.writeDouble(msg.pos.z);
        buf.writeDouble(msg.velocity.x);
        buf.writeDouble(msg.velocity.y);
        buf.writeDouble(msg.velocity.z);
        buf.writeResourceLocation(msg.gunId);
    }

    public static SpawnCasingPacket decode(FriendlyByteBuf buf) {
        return new SpawnCasingPacket(
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readResourceLocation());
    }

    public static void handle(SpawnCasingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                CasingSpawnHandler.spawnCasingFromClient(player, msg.gunId, msg.pos, msg.velocity);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

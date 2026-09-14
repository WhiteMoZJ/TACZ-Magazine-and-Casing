package com.github.whitemo.magazine_casing.network;

import com.github.whitemo.magazine_casing.event.CasingSpawnHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：请求在指定世界位置生成一个弹壳实体（位置取自第一人称模型），
 * 并附带据枪（斜握）状态。抛壳初速度由服务端依据据枪状态、玩家朝向与移动计算，
 * 不再由客户端发送。
 */
public class SpawnCasingPacket {

    private final Vec3 pos;
    private final boolean slide;
    private final ResourceLocation gunId;

    public SpawnCasingPacket(Vec3 pos, boolean slide, ResourceLocation gunId) {
        this.pos = pos;
        this.slide = slide;
        this.gunId = gunId;
    }

    public static void encode(SpawnCasingPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.pos.x);
        buf.writeDouble(msg.pos.y);
        buf.writeDouble(msg.pos.z);
        buf.writeBoolean(msg.slide);
        buf.writeResourceLocation(msg.gunId);
    }

    public static SpawnCasingPacket decode(FriendlyByteBuf buf) {
        return new SpawnCasingPacket(
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readBoolean(),
                buf.readResourceLocation());
    }

    public static void handle(SpawnCasingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                CasingSpawnHandler.spawnCasingFromClient(player, msg.gunId, msg.pos, msg.slide);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.github.whitemo.magazine_casing.network;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 模组网络通道。用于客户端把弹壳的精确世界生成位置发给服务端。
 */
public final class Networking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MagazineAndCasing.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++,
                SpawnCasingPacket.class,
                SpawnCasingPacket::encode,
                SpawnCasingPacket::decode,
                SpawnCasingPacket::handle);
    }

    private Networking() {
    }
}

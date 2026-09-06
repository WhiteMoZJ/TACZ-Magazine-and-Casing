package com.github.whitemo.magazine_casing;

import com.github.whitemo.magazine_casing.entity.ModEntities;
import com.github.whitemo.magazine_casing.network.Networking;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MagazineAndCasing.MOD_ID)
public class MagazineAndCasing
{
    public static final String MOD_ID = "magazine_casing";

    public MagazineAndCasing() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModConfigs.register();
        Networking.register();
    }
}
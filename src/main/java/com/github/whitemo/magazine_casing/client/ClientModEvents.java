package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.github.whitemo.magazine_casing.entity.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MagazineAndCasing.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientModEvents {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MAGAZINE.get(), MagazineEntityRenderer::new);
        event.registerEntityRenderer(ModEntities.CASING.get(), CasingEntityRenderer::new);
    }
}
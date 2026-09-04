package com.github.whitemo.magazine_casing.entity;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MagazineAndCasing.MOD_ID);

    public static final RegistryObject<EntityType<MagazineEntity>> MAGAZINE = ENTITY_TYPES.register("magazine",
            () -> EntityType.Builder.<MagazineEntity>of(MagazineEntity::new, MobCategory.MISC)
                    .noSave()
                    .noSummon()
                    .sized(0.15F, 0.15F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build(MagazineAndCasing.MOD_ID + ":magazine"));
}
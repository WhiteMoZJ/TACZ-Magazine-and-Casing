package com.github.whitemo.magazine_casing.mixin;

import com.github.whitemo.magazine_casing.event.CasingSpawnHandler;
import com.tacz.guns.entity.shooter.LivingEntityBolt;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在手动上膛（manual_action）枪械拉栓时通知 {@link CasingSpawnHandler} 掉落弹壳。
 */
@Mixin(value = LivingEntityBolt.class, remap = false)
public abstract class LivingEntityBoltMixin {

    @Shadow
    @Final
    private ShooterDataHolder data;

    @Shadow
    @Final
    private LivingEntity shooter;

    @Inject(method = "bolt", at = @At("TAIL"), remap = false)
    private void magazineCasing$onBoltEntry(CallbackInfo ci) {
        CasingSpawnHandler.onBolt(this.shooter, this.data);
    }
}
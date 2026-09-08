package com.github.whitemo.magazine_casing.mixin;

import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.functional.ShellRender;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在射击时，对「没有 shell 字段（初速度）」的枪，用 fallback 初速度继续把弹壳加入队列，
 * 使它们也走 ShellRender 的精确抛壳流程。位置仍由 shell 骨骼节点决定。
 */
@Mixin(value = GunAnimationStateContext.class, remap = false)
public abstract class GunAnimationStateContextMixin {

    @Shadow
    private GunDisplayInstance display;

    @Inject(method = "popShellFrom", at = @At("HEAD"), cancellable = true, remap = false)
    private void magazineCasing$fallbackShellVelocity(int index, CallbackInfo ci) {
        // 有 shell 字段（初速度）时走原逻辑。
        if (display == null || display.getShellEjection() != null) {
            return;
        }

        BedrockGunModel gunModel = display.getGunModel();
        if (gunModel == null) {
            ci.cancel();
            return;
        }

        // fallback 初速度（对应 TACZ 默认 random_velocity [1,1,0.25]），让弹壳进入队列。
        Vector3f randomVelocity = new Vector3f(1.0F, 1.0F, 0.25F);
        ShellRender shellRender = gunModel.getShellRender(index);
        if (shellRender != null) {
            shellRender.addShell(randomVelocity);
        }
        Pair<BedrockGunModel, ResourceLocation> lod = display.getLodModel();
        if (lod != null && lod.getLeft() != null) {
            ShellRender lodShellRender = lod.getLeft().getShellRender(index);
            if (lodShellRender != null) {
                lodShellRender.addShell(randomVelocity);
            }
        }
        ci.cancel();
    }
}
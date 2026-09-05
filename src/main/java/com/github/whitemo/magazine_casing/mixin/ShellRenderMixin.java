package com.github.whitemo.magazine_casing.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.functional.ShellRender;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 禁用 TACZ 原生的弹壳渲染，改由本模组的 CasingEntity 负责弹壳视觉。
 */
@Mixin(value = ShellRender.class, remap = false)
public class ShellRenderMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void magazineCasing$disableShellRender(PoseStack poseStack, VertexConsumer buffer, ItemDisplayContext context,
                                                   int light, int overlay, CallbackInfo ci) {
        ci.cancel();
    }
}
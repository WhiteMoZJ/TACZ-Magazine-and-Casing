package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.BedrockAmmoModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 渲染掉落弹壳：根据弹药类型从 TACZ 获取对应的弹壳模型与贴图。
 */
@OnlyIn(Dist.CLIENT)
public class CasingEntityRenderer extends EntityRenderer<CasingEntity> {

    public CasingEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.05F;
    }

    @Override
    public ResourceLocation getTextureLocation(CasingEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(CasingEntity casing, float yaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int light) {
        ResourceLocation ammoId = casing.getAmmoId();
        if (ammoId == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.0D, casing.getBbHeight() / 2.0D, 0.0D);

        // 物理翻滚（yaw/pitch/roll）。
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, casing.yRotO, casing.getYRot())));
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTicks, casing.xRotO, casing.getXRot())));
        poseStack.mulPose(Axis.ZP.rotationDegrees(casing.getRenderRoll(partialTicks)));

        TimelessAPI.getClientAmmoIndex(ammoId).ifPresent(ammoIndex -> {
            BedrockAmmoModel model = ammoIndex.getShellModel();
            ResourceLocation texture = ammoIndex.getShellTextureLocation();
            if (model == null || texture == null) {
                return;
            }
            poseStack.pushPose();
            poseStack.translate(0.0D, -1.5D, 0.0D);
            model.render(poseStack, ItemDisplayContext.GROUND, RenderType.entityCutoutNoCull(texture), light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        });

        poseStack.popPose();
    }
}
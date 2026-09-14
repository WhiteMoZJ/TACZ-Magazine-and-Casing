package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.event.CameraSetupEvent;
import com.tacz.guns.client.model.BedrockAmmoModel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * 渲染掉落弹壳：根据弹药类型从 TACZ 获取对应的弹壳模型与贴图。
 */
@OnlyIn(Dist.CLIENT)
public class CasingEntityRenderer extends EntityRenderer<CasingEntity> {

    /** 弹壳生成后多少 tick 内改由手部渲染趟绘制。 */
    public static final int HAND_RENDER_TICKS = 5;

    public CasingEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(CasingEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /**
     * 据枪时刚生成的弹壳紧贴枪口，而手部渲染趟在绘制前会清空深度缓冲，
     * 枪械等于画在空白深度上，会与距离无关地盖住世界趟里的实体，看起来像「弹壳被枪吞了」。
     * 这类弹壳不再走世界趟，改由手部渲染趟按世界坐标重新绘制一份（见 {@link #renderInHand}），
     * 与枪械共用同一深度空间，遮挡关系才正确。
     */
    @Override
    public boolean shouldRender(CasingEntity casing, Frustum frustum, double camX, double camY, double camZ) {
        if (shouldRenderInHand(casing)) {
            return false;
        }
        return super.shouldRender(casing, frustum, camX, camY, camZ);
    }

    /**
     * 是否应由手部渲染趟绘制：刚生成（tickCount &lt; {@link #HAND_RENDER_TICKS}）+ 第一人称 + 据枪（slide）。
     * 用存活 tick 而不是距相机的距离来判定「刚生成」：抛壳点未必在相机附近（长枪管、模型差异），
     * 纯几何判定可能整个漏掉生成瞬间，而时间窗口必然覆盖。弹壳初速度有限，10 tick 内飞不出多远，
     * 因此这个窗口天然也是一个很宽松的距离上限。
     * 这里必须与手部渲染趟真正会被执行的条件一致（GameRenderer#renderItemInHand 里
     * hideGui / 旁观者时不渲染手部），否则世界趟被抑制又没有手部趟补绘，弹壳会直接消失。
     */
    public static boolean shouldRenderInHand(CasingEntity casing) {
        if (!ModConfigs.COMMON.enableCasingDrop.get() || !SlideStateTracker.isSliding()) {
            return false;
        }
        if (casing.tickCount >= HAND_RENDER_TICKS) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson() || mc.options.hideGui) {
            return false;
        }
        return mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.SPECTATOR;
    }

    @Override
    public void render(CasingEntity casing, float yaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int light) {
        poseStack.pushPose();
        poseStack.translate(0.0D, casing.getBbHeight() / 2.0D, 0.0D);

        // 物理翻滚（yaw/pitch/roll）。
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, casing.yRotO, casing.getYRot())));
//        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTicks, casing.xRotO, casing.getXRot())));
        poseStack.mulPose(Axis.ZP.rotationDegrees(casing.getRenderRoll(partialTicks)));

        renderShellModel(casing, poseStack, light);

        poseStack.popPose();
    }

    /**
     * 在手部渲染趟（相机空间 PoseStack）里重新绘制弹壳。
     * 位置用 {@code ShellRenderMixin.toWorld} 的逆变换把世界坐标换回相机空间偏移：
     * dx = v·right、dy = v·up、dz = v·(-forward)/fovScale；朝向与世界趟保持一致。
     */
    public static void renderInHand(CasingEntity casing, PoseStack poseStack, float partialTicks, int light) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();

        double vx = Mth.lerp((double) partialTicks, casing.xOld, casing.getX()) - camPos.x;
        double vy = Mth.lerp((double) partialTicks, casing.yOld, casing.getY()) - camPos.y;
        double vz = Mth.lerp((double) partialTicks, casing.zOld, casing.getZ()) - camPos.z;

        // 相机空间：X 右（-left）、Y 上、Z 指向相机后方（-forward）。
        double dx = -(vx * left.x() + vy * left.y() + vz * left.z());
        double dy = vx * up.x() + vy * up.y() + vz * up.z();
        double dz = -(vx * forward.x() + vy * forward.y() + vz * forward.z());

        // 手部渲染趟用独立的 item model FOV，与 world FOV 不一致，深度方向需按 FOV 反向缩放。
        float itemFov = CameraSetupEvent.ITEM_MODEL_FOV_DYNAMICS.get();
        float worldFov = CameraSetupEvent.WORLD_FOV_DYNAMICS.get();
        if (itemFov > 1.0E-3F && worldFov > 1.0E-3F) {
            double fovScale = Math.tan(Math.toRadians(itemFov / 2.0D)) / Math.tan(Math.toRadians(worldFov / 2.0D));
            dz /= fovScale;
        }

        poseStack.pushPose();
        poseStack.translate(dx, dy, dz);
        poseStack.translate(0.0D, casing.getBbHeight() / 2.0D, 0.0D);
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, casing.yRotO, casing.getYRot())));
        poseStack.mulPose(Axis.ZP.rotationDegrees(casing.getRenderRoll(partialTicks)));
        renderShellModel(casing, poseStack, light);
        poseStack.popPose();
    }

    /** 按弹药类型绘制弹壳模型（世界趟与手部趟共用）。 */
    private static void renderShellModel(CasingEntity casing, PoseStack poseStack, int light) {
        ResourceLocation ammoId = casing.getAmmoId();
        if (ammoId == null) {
            return;
        }
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
    }
}

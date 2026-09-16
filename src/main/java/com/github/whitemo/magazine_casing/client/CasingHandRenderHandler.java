package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.client.event.BeforeRenderHandEvent;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 把刚生成的弹壳改到手部渲染趟绘制，避免被枪械无条件盖住（手部渲染趟绘制前会清空深度缓冲，
 * 枪械等于画在空白深度上，与距离无关地遮住世界趟里的实体）。
 * <p>
 * 挂点用 TACZ 在 {@code ItemInHandRenderer.renderHandsWithItems} 头部 post 的
 * {@link BeforeRenderHandEvent}：它携带手部渲染趟的相机空间 PoseStack 且不可取消，
 * 而 Forge 的 RenderHandEvent 在持枪时会被 TACZ 取消（TACZ 用它替换成自己的枪械渲染）。
 */
@Mod.EventBusSubscriber(modid = MagazineAndCasing.MOD_ID, value = Dist.CLIENT)
public class CasingHandRenderHandler {

    /**
     * 候选实体的空间查询半径，只用来限制遍历开销，不是渲染判定（判定是
     * {@link CasingEntityRenderer#shouldRenderInHand} 里的 tick 窗口）。但两者有硬性约束：
     * shouldRender 抑制世界趟时不含任何距离条件，所以凡是落在窗口内、却没被这个 AABB 查到的弹壳
     * 都会「世界趟不画、手部趟也不画」而闪断。弹壳 5 tick 内的位移（横向 0.3~0.375 格/tick，
     * 再叠加枪口相对相机的偏移与玩家自身速度）可达 2 格以上，故取 1.0 留余量。
     */
    private static final double CANDIDATE_RANGE = 1.0D;

    /**
     * 用 HIGHEST 优先级，确保早于 TACZ 的 CameraSetupEvent#applyItemInHandCameraAnimation 执行。
     * 那个处理器会把枪械的相机动画旋转乘进 PoseStack，而弹壳的世界坐标本身已经是在
     * 带该旋转的枪械坐标里取出来的，再套一次旋转就会重复偏移。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBeforeRenderHand(BeforeRenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // 廉价预筛：第三人称不必做实体查询（手部趟的完整条件见 CasingEntityRenderer#isHandPassActive）。
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        List<CasingEntity> casings = mc.level.getEntitiesOfClass(CasingEntity.class,
                new AABB(camPos, camPos).inflate(CANDIDATE_RANGE));
        if (casings.isEmpty()) {
            return;
        }

        float partialTicks = mc.getFrameTime();
        PoseStack poseStack = event.getPoseStack();
        for (CasingEntity casing : casings) {
            if (!CasingEntityRenderer.shouldRenderInHand(casing)) {
                continue;
            }
            int light = mc.getEntityRenderDispatcher().getPackedLightCoords(casing, partialTicks);
            CasingEntityRenderer.renderInHand(casing, camera, poseStack, partialTicks, light);
        }
    }
}

package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.event.CasingSpawnHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端射击抛壳的初速度计算。据枪（斜握）状态直接采用 TACZ 动画状态机的
 * shouldSlide() 评估结果（见 {@link SlideStateTracker}，由 GunAnimationStateContextMixin
 * 在 RETURN 处捕获），与画面中的手臂旋转姿态一致，不使用实体蹲伏
 * （LivingEntity.isCrouching）近似。
 */
public final class CasingVelocity {

    private static final Logger LOGGER = LogManager.getLogger("magazine_casing");

    private CasingVelocity() {
    }

    /**
     * 计算射击抛壳初速度（世界坐标）。由客户端在发送弹壳生成包时调用，服务端直接采用。
     */
    public static Vec3 compute(LocalPlayer player, ResourceLocation gunId) {
        boolean slide = SlideStateTracker.isSliding();
        if (ModConfigs.COMMON.debug.get()) {
            LOGGER.info("[Casing] velocity slide={}", slide);
        }
        return CasingSpawnHandler.computeCasingVelocity(player, gunId, slide);
    }
}
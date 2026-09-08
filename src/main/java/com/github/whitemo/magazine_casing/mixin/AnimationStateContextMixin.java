package com.github.whitemo.magazine_casing.mixin;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.client.SlideStateTracker;
import com.tacz.guns.api.client.animation.statemachine.AnimationStateContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 从 TACZ 状态机读取「据枪（斜握）」状态。
 * 状态机进入据枪态时会播放 slide 动画（slide / slide_idle），退出时播放 slide_back。
 * 这里不再依赖 shouldSlide()（它内部是 isCrouching && canSlide，对非原版据枪不适用），
 * 而是以状态机实际运行的动画为准，与画面中手臂的据枪姿态一致。
 */
@Mixin(value = AnimationStateContext.class, remap = false)
public abstract class AnimationStateContextMixin {

    private static final Logger LOGGER = LogManager.getLogger("magazine_casing");

    @Unique
    private static boolean lastSliding;

    @Inject(method = "runAnimation", at = @At("HEAD"), remap = false)
    private void magazineCasing$detectSlideAnimation(String name, int track, boolean blending, int playType,
                                                     float transitionTime, CallbackInfo ci) {
        if (name == null) {
            return;
        }
        boolean sliding;
        if (name.contains("slide_back")) {
            sliding = false;
        } else if (name.contains("slide")) {
            sliding = true;
        } else {
            return;
        }
        SlideStateTracker.setSliding(sliding);
        if (ModConfigs.COMMON.debug.get() && sliding != lastSliding) {
            lastSliding = sliding;
            LOGGER.info("[Casing] slide anim '{}' -> {}", name, sliding);
        }
    }
}
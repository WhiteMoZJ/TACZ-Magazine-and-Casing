package com.github.whitemo.magazine_casing.client;

/**
 * 记录 TACZ 状态机当前是否处于「据枪（斜握）」状态。
 * 由 AnimationStateContextMixin 在据枪动画（slide）运行时写入，弹壳初速度计算时读取，
 * 直接反映画面中的手臂旋转姿态，不自行用实体蹲伏（isCrouching）等状态近似。
 */
public final class SlideStateTracker {

    private static volatile boolean sliding;

    private SlideStateTracker() {
    }

    public static void setSliding(boolean sliding) {
        SlideStateTracker.sliding = sliding;
    }

    public static boolean isSliding() {
        return sliding;
    }
}
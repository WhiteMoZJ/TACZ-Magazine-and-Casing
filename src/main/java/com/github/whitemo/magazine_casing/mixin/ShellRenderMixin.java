package com.github.whitemo.magazine_casing.mixin;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.client.CasingSpawnGuard;
import com.github.whitemo.magazine_casing.network.Networking;
import com.github.whitemo.magazine_casing.network.SpawnCasingPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.functional.ShellRender;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 在 TACZ 渲染弹壳时：
 * 1. 从 PoseStack 提取 shell 骨骼节点在相机空间的位置，换算成世界坐标发给服务端（精确生成点）。
 * 2. 继续禁用 TACZ 原生弹壳渲染（由本模组的 CasingEntity 负责视觉）。
 */
@Mixin(value = ShellRender.class, remap = false)
public class ShellRenderMixin {

    /** 用于在弹壳对象上标记「已发送」的占位矩阵，避免依赖静态集合（mixin 静态字段在切换人称时会失效）。 */
    private static final Matrix3f SENT_MARK = new Matrix3f();

    private static final Logger LOGGER = LogManager.getLogger("magazine_casing");

    @Shadow
    @Final
    private BedrockGunModel bedrockGunModel;

    @Shadow
    @Final
    private ConcurrentLinkedDeque<ShellRender.Data> SHELL_QUEUE;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void magazineCasing$captureShellPositionAndDisable(PoseStack poseStack, VertexConsumer buffer,
                                                               ItemDisplayContext context, int light, int overlay,
                                                               CallbackInfo ci) {
        magazineAndCasing$captureAndSend(poseStack);
        ci.cancel();
    }

    /**
     * 当队列里存在刚加入、尚未发送过生成请求的新弹壳时，用当前 PoseStack 提取弹壳世界坐标并发包。
     * 由于 render 被 cancel（原生渲染禁用），弹壳的 pose 永远不会被初始化，
     * 因此用 {@link #SENT} 记录已发送的弹壳，保证每个弹壳只发送一次。
     */
    @Unique
    private void magazineAndCasing$captureAndSend(PoseStack poseStack) {
        // 第三人称渲染弹壳时（isSelf=false）不发送，避免切换人称后主模型/LOD 再发一次导致的重复。
        if (!ShellRender.isSelf) {
            return;
        }
        boolean hasNewShell = false;
        for (ShellRender.Data data : SHELL_QUEUE) {
            if (data.pose == null && data.normal == null) {
                hasNewShell = true;
                break;
            }
        }
        if (!hasNewShell) {
            return;
        }

        ItemStack gun = this.bedrockGunModel.getCurrentGunItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(gun);
        if (gunId == null) {
            return;
        }

        Vec3 worldPos = magazineAndCasing$toWorld(poseStack);
        if (worldPos == null) {
            return;
        }

        for (ShellRender.Data data : SHELL_QUEUE) {
            if (data.pose == null && data.normal == null) {
                data.normal = SENT_MARK;
                if (CasingSpawnGuard.tryMark(data.timeStamp)) {
                    if (ModConfigs.COMMON.debug.get()) {
                        LOGGER.info("[Casing] SEND gun={} ts={} isSelf={} queue={}",
                                gunId, data.timeStamp, ShellRender.isSelf, SHELL_QUEUE.size());
                    }
                    Networking.CHANNEL.sendToServer(new SpawnCasingPacket(worldPos, gunId));
                }
            }
        }
    }

    /**
     * 把 PoseStack 当前（已定位到 shell 节点）的平移换算成世界坐标。
     * 第一人称物品渲染的 PoseStack 处于相机空间：原点在相机，X 右、Y 上、Z 指向相机后方。
     */
    @Unique
    private Vec3 magazineAndCasing$toWorld(PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f pose = poseStack.last().pose();
        float dx = pose.m30();
        float dy = pose.m31();
        float dz = pose.m32();

        Vec3 camPos = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();

        // right = -left，Z 轴指向相机后方（-forward）。
        double wx = camPos.x + dx * (-left.x()) + dy * up.x() + dz * (-forward.x());
        double wy = camPos.y + dx * (-left.y()) + dy * up.y() + dz * (-forward.y());
        double wz = camPos.z + dx * (-left.z()) + dy * up.z() + dz * (-forward.z());
        return new Vec3(wx, wy, wz);
    }
}

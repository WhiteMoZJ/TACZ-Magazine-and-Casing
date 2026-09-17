package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.event.CameraSetupEvent;
import com.tacz.guns.client.model.BedrockAmmoModel;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.index.ClientAmmoIndex;
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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渲染掉落弹壳：根据弹药类型从 TACZ 获取对应的弹壳模型与贴图。
 */
@OnlyIn(Dist.CLIENT)
public class CasingEntityRenderer extends EntityRenderer<CasingEntity> {

    /** 弹壳生成后多少 tick 内改由手部渲染趟绘制。 */
    public static final int HAND_RENDER_TICKS = 5;

    /** 弹壳「落地平躺」所需的 roll 补偿角，按弹药类型缓存（只取决于模型，算一次即可）。 */
    private static final Map<ResourceLocation, Float> FLAT_ROLL_OFFSETS = new ConcurrentHashMap<>();

    /** 资源重载会重建弹壳模型，缓存必须失效，否则会沿用旧模型算出的补偿角。 */
    public static void clearCaches() {
        FLAT_ROLL_OFFSETS.clear();
    }

    public CasingEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(CasingEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /**
     * 刚生成的弹壳紧贴枪口，而手部渲染趟在绘制前会清空深度缓冲，枪械等于画在空白深度上，
     * 会与距离无关地盖住世界趟里的实体，看起来像「弹壳被枪吞了」。
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
     * 是否应由手部渲染趟绘制：刚生成（tickCount &lt; {@link #HAND_RENDER_TICKS}）+ 手部渲染趟确实会执行。
     * 用存活 tick 而不是距相机的距离来判定「刚生成」：抛壳点未必在相机附近（长枪管、模型差异），
     * 纯几何判定可能整个漏掉生成瞬间，而时间窗口必然覆盖。
     * 判定必须与手部渲染趟真正会被执行的条件一致（见 {@link #isHandPassActive}），
     * 否则世界趟被抑制又没有手部趟补绘，弹壳会直接消失。
     */
    public static boolean shouldRenderInHand(CasingEntity casing) {
        if (!ModConfigs.COMMON.enableCasingDrop.get()) {
            return false;
        }
        if (casing.tickCount >= HAND_RENDER_TICKS) {
            return false;
        }
        return isHandPassActive();
    }

    /**
     * 手部渲染趟当前是否真的会被绘制。必须与 {@code GameRenderer#renderItemInHand} 的前置条件
     * 严格对齐：hideGui、旁观者、非第一人称时它都不会调用 {@code renderHandsWithItems}，
     * 而 {@link CasingHandRenderHandler} 只挂在那条调用链上，没有补绘的机会。
     */
    public static boolean isHandPassActive() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson() || mc.options.hideGui) {
            return false;
        }
        return mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.SPECTATOR;
    }

    @Override
    public void render(CasingEntity casing, float yaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int light) {
        drawCasing(casing, poseStack, partialTicks, light);
    }

    /**
     * 在手部渲染趟（相机空间 PoseStack）里绘制弹壳。
     * 位置用 {@code ShellRenderMixin.toWorld} 的逆变换把世界坐标换回相机空间偏移：
     * dx = v·right、dy = v·up、dz = v·(-forward)/fovScale。
     * <p>
     * 朝向不能直接在相机空间里施加：{@link #drawCasing} 里的 {@code YP(yaw)} 是世界语义的
     * 偏航，而相机空间的上轴是相机自己的 up，直接套用会让弹壳的朝向随准星一起转（误差正好
     * 等于准星的 yaw），表现为「弹壳黏在屏幕上而不是黏在世界里」。所以平移之后先用
     * {@link #worldToCamera} 把局部坐标架转回世界朝向，后续变换与世界趟完全一致。
     */
    public static void renderInHand(CasingEntity casing, Camera camera, PoseStack poseStack, float partialTicks, int light) {
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
        poseStack.mulPoseMatrix(worldToCamera(camera));
        drawCasing(casing, poseStack, partialTicks, light);
        poseStack.popPose();
    }

    /**
     * 世界坐标 -> 相机空间的变换矩阵，用相机基向量直接拼出，不依赖 {@code Camera#rotation()}
     * 的四元数约定。相机空间为 X 右、Y 上、Z 指向相机后方，故三列分别为 right、up、back
     * （right = -left、back = -forward）。把它乘进相机空间的 PoseStack 之后（世界趟的
     * PoseStack 恰好等于相机空间的 PoseStack 再乘上这个矩阵），局部坐标架即恢复为世界朝向，
     * {@link #drawCasing} 里的 yaw/roll/抬高就都是世界语义，与世界趟行为一致。
     */
    private static Matrix4f worldToCamera(Camera camera) {
        Vector3f right = new Vector3f(camera.getLeftVector()).negate();
        Vector3f up = camera.getUpVector();
        Vector3f back = new Vector3f(camera.getLookVector()).negate();
        Matrix4f matrix = new Matrix4f();
        matrix.setColumn(0, new Vector4f(right.x, right.y, right.z, 0.0F));
        matrix.setColumn(1, new Vector4f(up.x, up.y, up.z, 0.0F));
        matrix.setColumn(2, new Vector4f(back.x, back.y, back.z, 0.0F));
        matrix.setColumn(3, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
        return matrix;
    }

    /**
     * 在给定 PoseStack 上绘制一具弹壳：抬高半个碰撞盒 + 物理朝向与翻滚，世界趟与手部趟共用。
     * 翻滚角额外叠加平躺补偿角（见 {@link #flatRollOffset}），实体停稳后 roll 收敛到 0，
     * 加上补偿角即正好让弹壳躺在地上。
     */
    private static void drawCasing(CasingEntity casing, PoseStack poseStack, float partialTicks, int light) {
        poseStack.pushPose();
        poseStack.translate(0.0D, casing.getBbHeight() / 2.0D, 0.0D);

        // 物理翻滚（yaw/pitch/roll）。
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, casing.yRotO, casing.getYRot())));
//        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTicks, casing.xRotO, casing.getXRot())));
        poseStack.mulPose(Axis.ZP.rotationDegrees(casing.getRenderRoll(partialTicks) + flatRollOffset(casing)));

        renderShellModel(casing, poseStack, light);

        poseStack.popPose();
    }

    /**
     * 弹壳「落地平躺」所需的 roll 补偿角。实体停稳后把 roll 收敛到 0，但弹壳模型的长轴不一定
     * 沿局部 Z 轴，只有补上差额后 roll = 0 才代表平躺。
     */
    private static float flatRollOffset(CasingEntity casing) {
        ResourceLocation ammoId = casing.getAmmoId();
        if (ammoId == null) {
            return 0.0F;
        }
        Float cached = FLAT_ROLL_OFFSETS.get(ammoId);
        if (cached != null) {
            return cached;
        }
        BedrockAmmoModel model = TimelessAPI.getClientAmmoIndex(ammoId)
                .map(ClientAmmoIndex::getShellModel).orElse(null);
        if (model == null) {
            // 模型还没加载好，先不平移补偿，也不写缓存。
            return 0.0F;
        }
        float offset = computeFlatRollOffset(model);
        FLAT_ROLL_OFFSETS.put(ammoId, offset);
        return offset;
    }

    /**
     * roll 是绕模型局部 Z 轴的旋转：长轴沿局部 Z 或 X 的弹壳在 roll = 0 时本来就躺平；
     * 长轴沿局部 Y 的弹壳（57x28 等）在 roll = 0 时会立在原地，需要补 90° 才能躺下。
     * 长轴方向直接取模型包围盒的最长边，因此自定义弹壳模型也能自动适配。
     */
    private static float computeFlatRollOffset(BedrockAmmoModel model) {
        double[] bounds = modelBounds(model);
        if (bounds[0] > bounds[3]) {
            return 0.0F;
        }
        double x = bounds[3] - bounds[0];
        double y = bounds[4] - bounds[1];
        double z = bounds[5] - bounds[2];
        return y > Math.max(x, z) ? 90.0F : 0.0F;
    }

    /** 弹壳模型在自身坐标系里的包围盒（含各级骨骼的位移与旋转）。 */
    private static double[] modelBounds(BedrockAmmoModel model) {
        double[] out = {
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        PoseStack stack = new PoseStack();
        for (BedrockPart root : model.getShouldRender()) {
            collectBounds(root, stack, out);
        }
        return out;
    }

    private static void collectBounds(BedrockPart part, PoseStack stack, double[] out) {
        stack.pushPose();
        part.translateAndRotateAndScale(stack);
        Matrix4f matrix = stack.last().pose();

        for (BedrockCube cube : part.cubes) {
            float[] bounds = cubeBounds(cube);
            if (bounds == null) {
                continue;
            }
            for (int i = 0; i < 8; i++) {
                Vector4f vertex = new Vector4f(
                        ((i & 1) == 0 ? bounds[0] : bounds[3]) / 16.0F,
                        ((i & 2) == 0 ? bounds[1] : bounds[4]) / 16.0F,
                        ((i & 4) == 0 ? bounds[2] : bounds[5]) / 16.0F,
                        1.0F);
                vertex.mul(matrix);
                out[0] = Math.min(out[0], vertex.x);
                out[1] = Math.min(out[1], vertex.y);
                out[2] = Math.min(out[2], vertex.z);
                out[3] = Math.max(out[3], vertex.x);
                out[4] = Math.max(out[4], vertex.y);
                out[5] = Math.max(out[5], vertex.z);
            }
        }

        for (BedrockPart child : part.children) {
            collectBounds(child, stack, out);
        }

        stack.popPose();
    }

    private static float[] cubeBounds(BedrockCube cube) {
        if (cube instanceof BedrockCubeBox box) {
            return new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
        }
        if (cube instanceof BedrockCubePerFace face) {
            return new float[]{face.minX, face.minY, face.minZ, face.maxX, face.maxY, face.maxZ};
        }
        return null;
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

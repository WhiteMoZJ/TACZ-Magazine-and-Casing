package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.MagazineEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a dropped magazine entity using the magazine bone of the gun it came
 * from. The gun model is resolved on the client from the entity's stored gun id
 * and display id, so no custom model files are needed.
 */
@OnlyIn(Dist.CLIENT)
public class MagazineEntityRenderer extends EntityRenderer<MagazineEntity> {

    private static final float DROP_SCALE = 0.5F;

    /** 扩容等级上限，对应 mag_extended_1 / _2 / _3。 */
    private static final int MAX_EXTENDED_LEVEL = 3;

    private record CenterKey(ResourceLocation gunId, ResourceLocation displayId, int level) {
    }

    /** 弹匣几何中心点缓存，避免对已静止的弹匣每帧重算包围盒。 */
    private static final Map<CenterKey, Vector3f> CENTER_CACHE = new ConcurrentHashMap<>();

    private record NodeKey(ResourceLocation gunId, ResourceLocation displayId, int level) {
    }

    /**
     * 弹匣节点解析缓存。解析需要对模型全树做深度优先查找，结果只取决于枪械、显示模型与
     * 扩容等级，缓存后每帧直接命中，不再重复搜索。
     */
    private static final Map<NodeKey, Optional<BedrockPart>> NODE_CACHE = new ConcurrentHashMap<>();

    /** 资源重载会重建枪械模型，缓存必须失效，否则会拿到旧模型里的节点。 */
    public static void clearCaches() {
        NODE_CACHE.clear();
        CENTER_CACHE.clear();
    }

    public MagazineEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(MagazineEntity entity) {
        // The real texture is resolved per-gun in render(); this is only a fallback.
        return TextureAtlas.LOCATION_BLOCKS;
    }

    private static Optional<GunDisplayInstance> resolveDisplay(ResourceLocation gunId, ResourceLocation displayId) {
        if (displayId == null) {
            // Model replacement (or missing display): use the gun's default display.
            return TimelessAPI.getClientGunIndex(gunId).map(ClientGunIndex::getDefaultDisplay);
        }
        return TimelessAPI.getGunDisplay(displayId, gunId);
    }

    @Override
    public void render(MagazineEntity entity, float yaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int light) {
        ResourceLocation gunId = entity.getGunId();
        if (gunId == null) {
            return;
        }
        ResourceLocation displayId = entity.getDisplayId();

        Optional<GunDisplayInstance> displayOpt = resolveDisplay(gunId, displayId);
        if (displayOpt.isEmpty()) {
            return;
        }
        BedrockGunModel model = displayOpt.get().getGunModel();
        if (model == null) {
            return;
        }
        int magazineLevel = entity.getMagazineLevel();
        BedrockPart magazine = resolveMagazineNode(model, gunId, displayId, magazineLevel);
        if (magazine == null) {
            return;
        }

        ResourceLocation texture = displayOpt.get().getModelTexture();
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Ancestry chain (root -> magazine's parent) positions the magazine in gun-model space.
        List<BedrockPart> chain = buildChain(magazine);
        Vector3f center = CENTER_CACHE.computeIfAbsent(
                new CenterKey(gunId, displayId, magazineLevel),
                key -> computeCenter(magazine, chain));

        poseStack.pushPose();

        // Origin is the entity's render position (bottom of the bounding box), so move to the box centre first.
        poseStack.translate(entity.getBbWidth() / 2.0F, entity.getBbHeight() / 2.0F, entity.getBbWidth() / 2.0F);

        // Presentation scale.
        poseStack.scale(DROP_SCALE, DROP_SCALE, DROP_SCALE);

        // Physical tumbling (yaw/pitch/roll) and the fixed orientation flip all
        // pivot on the model centre, because the centring below runs last.
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, entity.yRotO, entity.getYRot())));
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));
        poseStack.mulPose(Axis.ZP.rotationDegrees(entity.getRenderRoll(partialTicks)));
        poseStack.mulPose(Axis.XP.rotationDegrees(180F));

        // Centre the geometry on its own centre, then apply the ancestry chain
        // and render the magazine subtree.
        poseStack.translate(-center.x(), -center.y(), -center.z());
        for (BedrockPart part : chain) {
            part.translateAndRotateAndScale(poseStack);
        }
        renderMagazineOnly(magazine, poseStack, vertexConsumer, light);

        poseStack.popPose();
    }

    /**
     * 解析弹匣骨骼节点：按配置的候选名（magazineNodeNames）直接命中弹匣几何节点本身，
     * 结果按枪械 / 显示模型 / 扩容等级缓存。
     *
     * <p>不再经过 magazine 容器层，因此不会再撞上容器下那些非弹匣的兄弟节点
     * （chain_anim 弹链、bullet_in_mag 弹匣内子弹等），无需再按名字做过滤。</p>
     */
    private static BedrockPart resolveMagazineNode(BedrockGunModel model, ResourceLocation gunId,
                                                   ResourceLocation displayId, int level) {
        return NODE_CACHE.computeIfAbsent(new NodeKey(gunId, displayId, level),
                key -> Optional.ofNullable(searchMagazineNode(model.getRootNode(), key.level()))).orElse(null);
    }

    /** 按候选顺序深度优先查找弹匣节点，全部候选都找不到时返回 null。 */
    private static BedrockPart searchMagazineNode(BedrockPart root, int level) {
        if (root == null) {
            return null;
        }
        for (String name : candidateNames(level)) {
            BedrockPart found = findByName(root, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 候选名顺序：与扩容等级对应的变体最先查找（等级 0 为 mag_standard，等级 N 为
     * mag_extended_N），其余候选按配置顺序兜底（box 弹药箱、Mag / mag 等没有变体的命名）。
     */
    private static List<String> candidateNames(int level) {
        List<? extends String> configured = ModConfigs.COMMON.magazineNodeNames.get();
        if (configured.isEmpty()) {
            return List.of();
        }
        int active = Mth.clamp(level, 0, MAX_EXTENDED_LEVEL);
        String preferred = active == 0 ? "mag_standard" : "mag_extended_" + active;
        List<String> ordered = new ArrayList<>(configured.size());
        if (configured.contains(preferred)) {
            ordered.add(preferred);
        }
        for (String name : configured) {
            if (!name.equals(preferred)) {
                ordered.add(name);
            }
        }
        return ordered;
    }

    /**
     * 深度优先查找指定名字的节点。名字含下划线的候选（mag_standard / mag_extended_N）允许
     * 带枪械前缀的形式，用来兼容没有 magazine 容器层、节点自带前缀的模型（p90 的
     * p90_mag_standard）；其余候选只按精确名匹配，避免误命中 bullet_in_mag、d_mag 之类。
     */
    private static BedrockPart findByName(BedrockPart part, String name) {
        if (matchesName(part.name, name)) {
            return part;
        }
        for (BedrockPart child : part.children) {
            BedrockPart found = findByName(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean matchesName(String partName, String candidate) {
        if (partName == null) {
            return false;
        }
        if (partName.equalsIgnoreCase(candidate)) {
            return true;
        }
        return candidate.indexOf('_') > 0 && partName.toLowerCase().endsWith("_" + candidate.toLowerCase());
    }

    /** 从弹匣节点向上构建祖先链（root -> 弹匣父节点），用于把弹匣定位到枪模型空间。 */
    private static List<BedrockPart> buildChain(BedrockPart magazine) {
        List<BedrockPart> chain = new ArrayList<>();
        for (BedrockPart part = magazine.getParent(); part != null; part = part.getParent()) {
            chain.add(part);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** 计算弹匣几何中心点（枪模型空间），供缓存使用。 */
    private static Vector3f computeCenter(BedrockPart magazine, List<BedrockPart> chain) {
        PoseStack measure = new PoseStack();
        for (BedrockPart part : chain) {
            part.translateAndRotateAndScale(measure);
        }
        double[] bounds = collectBounds(magazine, measure);
        return boundsCenter(bounds);
    }

    /**
     * Centre of the magazine geometry bounding box in gun-model space.
     */
    private static Vector3f boundsCenter(double[] bounds) {
        if (bounds[0] > bounds[3]) {
            return new Vector3f();
        }
        return new Vector3f(
                (float) ((bounds[0] + bounds[3]) / 2.0D),
                (float) ((bounds[1] + bounds[4]) / 2.0D),
                (float) ((bounds[2] + bounds[5]) / 2.0D));
    }

    /**
     * Collects the axis-aligned bounds of the magazine geometry in gun-model
     * space.
     */
    private static double[] collectBounds(BedrockPart part, PoseStack stack) {
        double[] out = {
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY
        };

        stack.pushPose();
        part.translateAndRotateAndScale(stack);
        Matrix4f matrix = stack.last().pose();

        for (BedrockCube cube : part.cubes) {
            float[] bounds = getCubeBounds(cube);
            if (bounds == null) {
                continue;
            }
            for (int i = 0; i < 8; i++) {
                float x = ((i & 1) == 0 ? bounds[0] : bounds[3]) / 16.0F;
                float y = ((i & 2) == 0 ? bounds[1] : bounds[4]) / 16.0F;
                float z = ((i & 4) == 0 ? bounds[2] : bounds[5]) / 16.0F;
                Vector4f v = new Vector4f(x, y, z, 1.0F);
                v.mul(matrix);
                if (v.x < out[0]) out[0] = v.x;
                if (v.y < out[1]) out[1] = v.y;
                if (v.z < out[2]) out[2] = v.z;
                if (v.x > out[3]) out[3] = v.x;
                if (v.y > out[4]) out[4] = v.y;
                if (v.z > out[5]) out[5] = v.z;
            }
        }

        for (BedrockPart child : part.children) {
            double[] childBounds = collectBounds(child, stack);
            if (childBounds[0] < out[0]) out[0] = childBounds[0];
            if (childBounds[1] < out[1]) out[1] = childBounds[1];
            if (childBounds[2] < out[2]) out[2] = childBounds[2];
            if (childBounds[3] > out[3]) out[3] = childBounds[3];
            if (childBounds[4] > out[4]) out[4] = childBounds[4];
            if (childBounds[5] > out[5]) out[5] = childBounds[5];
        }

        stack.popPose();
        return out;
    }

    private static float[] getCubeBounds(BedrockCube cube) {
        if (cube instanceof BedrockCubeBox box) {
            return new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
        }
        if (cube instanceof BedrockCubePerFace face) {
            return new float[]{face.minX, face.minY, face.minZ, face.maxX, face.maxY, face.maxZ};
        }
        return null;
    }

    /**
     * Renders a part subtree without mutating shared model state.
     */
    private static void renderMagazineOnly(BedrockPart part, PoseStack poseStack, VertexConsumer buffer, int light) {
        if (part.cubes.isEmpty() && part.children.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        part.translateAndRotateAndScale(poseStack);
        part.compile(poseStack.last(), buffer, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        for (BedrockPart child : part.children) {
            renderMagazineOnly(child, poseStack, buffer, light);
        }
        poseStack.popPose();
    }
}
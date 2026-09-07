package com.github.whitemo.magazine_casing.client;

import com.github.whitemo.magazine_casing.entity.MagazineEntity;
import com.github.whitemo.magazine_casing.mixin.BedrockGunModelAccessor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Renders a dropped magazine entity using the magazine bone of the gun it came
 * from. The gun model is resolved on the client from the entity's stored gun id
 * and display id, so no custom model files are needed.
 */
@OnlyIn(Dist.CLIENT)
public class MagazineEntityRenderer extends EntityRenderer<MagazineEntity> {

    private static final float DROP_SCALE = 0.5F;
    private static final String BULLET_IN_MAG = "bullet_in_mag";
    private static final String[] MAG_NODES = {"mag_standard", "mag_extended_1", "mag_extended_2", "mag_extended_3"};

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
        BedrockPart magazine = resolveMagazineNode(model);
        if (magazine == null) {
            return;
        }

        Set<String> skip = buildSkipSet(entity.getMagazineLevel());

        ResourceLocation texture = displayOpt.get().getModelTexture();
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));

        // Ancestry chain (root -> magazine's parent) positions the magazine in
        // gun-model space.
        List<BedrockPart> chain = new ArrayList<>();
        for (BedrockPart part = magazine.getParent(); part != null; part = part.getParent()) {
            chain.add(part);
        }
        Collections.reverse(chain);

        // Measure the geometry bounding box so the magazine can be centred on the
        // entity and fitted with a matching collision box.
        PoseStack measure = new PoseStack();
        for (BedrockPart part : chain) {
            part.translateAndRotateAndScale(measure);
        }
        double[] bounds = collectBounds(magazine, measure, skip);
        Vector3f center = boundsCenter(bounds);

        poseStack.pushPose();

        // Origin is the entity's render position (bottom of the bounding box),
        // so move to the box centre first.
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
        renderMagazineOnly(magazine, poseStack, vertexConsumer, light, skip);

        poseStack.popPose();
    }

    /**
     * 解析弹匣骨骼节点。优先取 TACZ 标准名 {@code magazine}；若为 null，
     * 兼容拼写错误的节点名 {@code magzine}（如 ai_awp）。
     */
    private static BedrockPart resolveMagazineNode(BedrockGunModel model) {
        BedrockPart magazine = ((BedrockGunModelAccessor) model).magazineCasing$getMagazineNode();
        if (magazine != null) {
            return magazine;
        }
        BedrockPart root = model.getRootNode();
        return root == null ? null : findByName(root, "magzine");
    }

    private static BedrockPart findByName(BedrockPart part, String name) {
        if (name.equals(part.name)) {
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

    private static Set<String> buildSkipSet(int level) {
        Set<String> skip = new HashSet<>();
        skip.add(BULLET_IN_MAG);
        int active = Math.max(0, Math.min(level, MAG_NODES.length - 1));
        for (int i = 0; i < MAG_NODES.length; i++) {
            if (i != active) {
                skip.add(MAG_NODES[i]);
            }
        }
        return skip;
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
     * space, skipping nodes that are not part of the ejected magazine.
     */
    private static double[] collectBounds(BedrockPart part, PoseStack stack, Set<String> skip) {
        double[] out = {
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        if (part.name != null && skip.contains(part.name)) {
            return out;
        }

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
            double[] childBounds = collectBounds(child, stack, skip);
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
     * Renders a part subtree without mutating shared model state, skipping nodes
     * that are not part of the ejected magazine (in-mag bullets and the
     * non-active magazine variants).
     */
    private static void renderMagazineOnly(BedrockPart part, PoseStack poseStack, VertexConsumer buffer, int light, Set<String> skip) {
        if (part.name != null && skip.contains(part.name)) {
            return;
        }
        if (part.cubes.isEmpty() && part.children.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        part.translateAndRotateAndScale(poseStack);
        part.compile(poseStack.last(), buffer, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        for (BedrockPart child : part.children) {
            renderMagazineOnly(child, poseStack, buffer, light, skip);
        }
        poseStack.popPose();
    }
}
package com.github.whitemo.magazine_casing.event;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.github.whitemo.magazine_casing.entity.ModEntities;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生成弹壳实体。精确位置由客户端渲染时计算并通过网络包发给服务端
 * （见 {@code spawnCasingFromClient}）；换弹掉壳等服务端场景则用枪类型做近似
 * （见 {@code dropCasings}）。
 */
public class CasingSpawnHandler {

    /** 枪类型 -> 抛壳口近似偏移（相对玩家脚部：高度 / 右 / 前），仅用于服务端触发的掉壳。 */
    private record CasingOffset(double height, double right, double forward) {
    }

    private static final Map<String, CasingOffset> OFFSET_BY_TYPE = Map.of(
            "pistol", new CasingOffset(0.90D, 0.25D, 0.25D),
            "smg", new CasingOffset(1.00D, 0.25D, 0.30D),
            "rifle", new CasingOffset(1.10D, 0.30D, 0.35D),
            "sniper", new CasingOffset(1.10D, 0.30D, 0.38D),
            "shotgun", new CasingOffset(1.05D, 0.28D, 0.32D),
            "mg", new CasingOffset(1.00D, 0.30D, 0.35D),
            "rpg", new CasingOffset(1.30D, 0.35D, 0.30D)
    );

    private static final CasingOffset DEFAULT_OFFSET = new CasingOffset(1.05D, 0.28D, 0.32D);

    private static final Map<String, Double> CASING_RIGHT_SPEED = Map.of(
            "pistol", 0.3D,
            "smg", 0.3D,
            "rifle", 0.35D,
            "sniper", 0.35D,
            "shotgun", 0.3D,
            "mg", 0.35D,
            "rpg", 0.3D
    );

    private static final Double DEFAULT_RIGHT_SPEED = 0.3D;

    /** 换弹掉壳的去重标记：记录某玩家最近一次服务端掉壳的 tick 与枪械，用于屏蔽客户端换弹退壳的重复包。 */
    private record ReloadCasingMark(int tick, ResourceLocation gunId) {
    }

    private static final Map<UUID, ReloadCasingMark> RELOAD_CASING_MARKS = new HashMap<>();
    /** 换弹掉壳去重窗口（tick）。窗口内同一把枪的客户端退壳包会被忽略。 */
    private static final int RELOAD_CASING_WINDOW_TICKS = 60;

    /**
     * 客户端发来的精确生成请求（第一人称模型计算出的世界坐标 + 据枪状态）。
     * 抛壳初速度由服务端依据据枪状态与玩家朝向、移动计算。
     */
    public static void spawnCasingFromClient(ServerPlayer player, ResourceLocation gunId, Vec3 worldPos, boolean slide) {
        if (!ModConfigs.COMMON.enableCasingDrop.get()) {
            return;
        }
        if (gunId == null || ModConfigs.COMMON.casingDropBlacklist.get().contains(gunId.toString())) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        // 换弹掉壳去重：服务端已通过 dropCasings 掉过这把枪的壳，忽略窗口内客户端的重复退壳包。
        ReloadCasingMark mark = RELOAD_CASING_MARKS.get(player.getUUID());
        if (mark != null && player.tickCount - mark.tick() <= RELOAD_CASING_WINDOW_TICKS && mark.gunId().equals(gunId)) {
            return;
        }

        ResourceLocation ammoId = resolveAmmoId(gunId);
        if (ammoId == null) {
            return;
        }

        // 弹壳模型替换 + 每次射击抛壳数量（配置只解析一次）
        CasingReplacement replacement = resolveCasingReplacement(gunId);
        ResourceLocation casingAmmoId = resolveCasingAmmoId(replacement, ammoId);
        int count = replacement == null ? 1 : replacement.count();

        // 每颗弹壳单独算一次初速度：随机扰动各不相同，多颗不会完全重叠。
        String gunType = resolveGunType(gunId);
        for (int i = 0; i < count; i++) {
            spawnCasingAt(level, casingAmmoId, worldPos,
                    computeCasingVelocity(player, gunId, gunType, slide), initialCasingYaw(player), count > 1);
        }
    }

    /**
     * 掉落 count 个弹壳（用于换弹掉壳等服务端触发场景，位置用枪类型近似）。
     */
    public static void dropCasings(ServerLevel level, LivingEntity shooter, ResourceLocation gunId, int count) {
        ResourceLocation ammoId = resolveAmmoId(gunId);
        if (ammoId == null) {
            return;
        }

        // 换弹掉壳也使用替换后的弹壳模型；数量仍由 reloadCasingDrops 决定。
        CasingReplacement replacement = resolveCasingReplacement(gunId);
        ResourceLocation casingAmmoId = resolveCasingAmmoId(replacement, ammoId);

        String gunType = resolveGunType(gunId);
        Vec3 look = shooter.getLookAngle();
        Vec3 right = horizontalRight(shooter);
        CasingOffset offset = OFFSET_BY_TYPE.getOrDefault(gunType, DEFAULT_OFFSET);
        Vec3 pos = shooter.position()
                .add(0.0D, offset.height(), 0.0D)
                .add(right.scale(offset.right()))
                .add(look.scale(offset.forward()));

        // 换弹掉壳为服务端触发，拿不到客户端 TACZ 状态机，据枪旋转不适用（slide=false）。
        for (int i = 0; i < count; i++) {
            spawnCasingAt(level, casingAmmoId, pos,
                    computeCasingVelocity(shooter, gunId, gunType, false), initialCasingYaw(shooter), count > 1);
        }

        // 记录去重标记：窗口内同一把枪的客户端换弹退壳包不再重复生成。
        RELOAD_CASING_MARKS.put(shooter.getUUID(), new ReloadCasingMark(shooter.tickCount, gunId));
    }

    /**
     * 计算弹壳初速度（世界坐标）：方向为「玩家右侧 + 视角上方 + 前方」，并叠加玩家的当前速度。
     * 右/上/前构成玩家视角参考系，因此 yaw 决定横向抛出方向、pitch 除影响前向分量外还会
     * 带动「上」分量一起倾斜（俯视时抛壳不再单纯向上，而是沿枪身法线甩出，俯仰越大差异越明显）。
     * {@code slide} 表示玩家是否处于据枪（斜握）状态，此时右/上的初速度分量绕玩家视角方向
     * （左手系 Z 轴）正向旋转 45°，与画面中手臂/枪身的旋转姿态一致。据枪状态由客户端 TACZ
     * 状态机据枪动画（slide）判定后传入。
     * <p>
     * {@code noLateralEjectGuns}（无横向分量抛壳）的枪械只剩前向分量，此时前向方向改用
     * {@link #horizontalLook} 的水平朝向：否则视线的 pitch 会全额参与，俯仰时弹壳被直接带偏。
     */
    private static Vec3 computeCasingVelocity(LivingEntity shooter, ResourceLocation gunId, String gunType, boolean slide) {
        Vec3 look = shooter.getLookAngle();
        Vec3 right = horizontalRight(shooter);
        Vec3 up = viewUp(shooter);
        Vec3 playerVelocity = shooter.getDeltaMovement();
        String gun = gunId.toString();

        boolean noLateral = ModConfigs.COMMON.noLateralEjectGuns.get().contains(gun);
        boolean reverseEject = ModConfigs.COMMON.reverseEjectGuns.get().contains(gun);

        // 无横向分量时前向也取水平方向，让弹壳沿准星的水平朝向平飞，与 pitch 解耦。
        Vec3 forward = noLateral ? horizontalLook(shooter) : look;

        double rightSpeed;
        double upSpeed;

        if (noLateral) {
            rightSpeed = 0.0D;
            upSpeed = 0.0D;
        } else {
            rightSpeed = CASING_RIGHT_SPEED.getOrDefault(gunType, DEFAULT_RIGHT_SPEED) + (shooter.getRandom().nextDouble() - 0.5D) * 0.05D;
            if (reverseEject) {
                rightSpeed = -rightSpeed;
            }
            upSpeed = 0.15D + (shooter.getRandom().nextDouble() - 0.5D) * 0.05D;
        }

        double forwardSpeed = 0.05D + (shooter.getRandom().nextDouble() - 0.5D) * 0.05D;

        if (slide) {
            double rad = Math.toRadians(45.0D);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double newRight = rightSpeed * cos - upSpeed * sin;
            double newUp = rightSpeed * sin + upSpeed * cos;
            rightSpeed = newRight;
            upSpeed = newUp;
        }

        return new Vec3(
                right.x * rightSpeed + up.x * upSpeed + forward.x * forwardSpeed + playerVelocity.x,
                right.y * rightSpeed + up.y * upSpeed + forward.y * forwardSpeed + playerVelocity.y,
                right.z * rightSpeed + up.z * upSpeed + forward.z * forwardSpeed + playerVelocity.z);
    }

    /**
     * 水平右向量，只由视角 yaw 决定。
     * 不能直接拿 {@code getLookAngle()} 的水平分量去推：pitch 接近 ±90°（垂直俯视/仰视）时
     * 水平分量趋近于零，归一化会得到零向量，横向抛壳速度会被整个吞掉。
     */
    private static Vec3 horizontalRight(LivingEntity shooter) {
        double yaw = viewYaw(shooter);
        return new Vec3(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
    }

    /**
     * 水平前向量，只由视角 yaw 决定，即视线在水平面的归一化投影。
     * 供「无横向分量抛壳」的枪械使用：这类枪的初速度只剩前向分量，若直接取
     * {@code getLookAngle()}，pitch 会全额参与合成，俯仰时弹壳方向随之上下甩动。
     * 取水平投影后弹壳沿准星的水平朝向平飞，与 pitch 解耦（俯视/仰视时视线水平分量
     * 退化，此时 {@link #viewYaw} 退回实体 yaw，方向依然有定义）。
     */
    private static Vec3 horizontalLook(LivingEntity shooter) {
        double yaw = viewYaw(shooter);
        return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
    }

    /**
     * 视角上向量，随 pitch 倾斜：水平视角时即世界向上，俯视/仰视时逐渐倒向水平前方。
     * 由右向量与视线叉乘得到，因此隐式跟随 pitch（视线垂直俯仰时也不会退化成零向量），
     * 与 {@link #horizontalRight}、视线共同构成右手系，保证抛壳方向跟随枪身姿态。
     */
    private static Vec3 viewUp(LivingEntity shooter) {
        return horizontalRight(shooter).cross(shooter.getLookAngle());
    }

    /**
     * 由视线反推的视角 yaw。用视线而不是 {@code getYRot()}：玩家横向移动时身体朝向会与头部
     * 朝向（准星方向）分离，抛壳方向应当跟随准星。视线水平分量退化时退回实体 yaw。
     */
    private static double viewYaw(LivingEntity shooter) {
        Vec3 look = shooter.getLookAngle();
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        return horizontal > 1.0E-4D ? Math.atan2(-look.x, look.z) : Math.toRadians(shooter.getYRot());
    }

    /**
     * 弹壳生成时的初始朝向：与玩家准星的水平朝向一致，弹壳长轴因此指向射击方向，
     * 而不是像以前那样随机一个角度。
     * <p>
     * 渲染端直接用 {@code YP(entity.getYRot())} 施加朝向，而绕 Y 轴正向旋转与 MC 的 yaw
     * 方向相反（视线为 {@code (-sin yaw, 0, cos yaw)}），所以基准角取负号；再补 180°：
     * 弹壳模型长轴沿局部 Z，底火（弹壳底部）在 +Z、开口在 -Z，子弹是从开口射出的，
     * 因此让局部 -Z 对准视线，底火朝后。
     * <p>
     * 与抛壳初速度一样跟随准星而非身体：横向移动时两者会分离，弹壳应当跟随准星。
     */
    private static float initialCasingYaw(LivingEntity shooter) {
        return 180.0F - (float) Math.toDegrees(viewYaw(shooter));
    }

    /**
     * 在指定世界位置生成一个弹壳实体，施加给定的初速度与初始朝向。
     */
    private static void spawnCasingAt(ServerLevel level, ResourceLocation ammoId, Vec3 pos, Vec3 velocity,
                                      float yaw, boolean spread) {
        if (spread) {
            // 一次抛多颗时给每颗一点位置偏移，避免完全重叠成一堆。
            pos = pos.add(
                    (level.random.nextDouble() - 0.5D) * 0.06D,
                    (level.random.nextDouble() - 0.5D) * 0.06D,
                    (level.random.nextDouble() - 0.5D) * 0.06D);
        }

        // 限制弹壳最大数量：超出时移除最早的一个。
        int max = ModConfigs.COMMON.maxCasingCount.get();
        List<CasingEntity> existing = level.getEntitiesOfClass(CasingEntity.class,
                new AABB(pos.x, pos.y, pos.z, pos.x, pos.y, pos.z).inflate(256.0D));
        if (existing.size() >= max) {
            CasingEntity oldest = null;
            for (CasingEntity casing : existing) {
                if (oldest == null || casing.tickCount > oldest.tickCount) {
                    oldest = casing;
                }
            }
            if (oldest != null) {
                oldest.discard();
            }
        }

        CasingEntity casing = new CasingEntity(ModEntities.CASING.get(), level);
        casing.setPos(pos.x, pos.y, pos.z);
        casing.setYRot(yaw);
        casing.setAmmoId(ammoId);
        casing.setDeltaMovement(velocity);
        level.addFreshEntity(casing);
    }

    /** 弹壳模型替换配置解析结果：模型枪 ID + 每次射击抛壳数量。 */
    private record CasingReplacement(ResourceLocation modelGunId, int count) {
    }

    private static ResourceLocation resolveAmmoId(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
    }

    /** 应用弹壳模型替换（若配置了替换），返回最终使用的弹壳模型对应的弹药 ID。 */
    private static ResourceLocation resolveCasingAmmoId(CasingReplacement replacement, ResourceLocation ammoId) {
        if (replacement == null) {
            return ammoId;
        }
        ResourceLocation modelAmmoId = resolveAmmoId(replacement.modelGunId());
        return modelAmmoId != null ? modelAmmoId : ammoId;
    }

    /** 枪械类型（pistol / rifle / ...），用于选取抛壳初速度与近似偏移。 */
    private static String resolveGunType(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getPojo().getType())
                .orElse("");
    }

    private static CasingReplacement resolveCasingReplacement(ResourceLocation gunId) {
        String gun = gunId.toString();
        for (String entry : ModConfigs.COMMON.casingModelReplacements.get()) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length != 3) {
                continue;
            }
            if (!parts[0].trim().equals(gun)) {
                continue;
            }
            ResourceLocation modelGunId = ResourceLocation.tryParse(parts[1].trim());
            if (modelGunId == null) {
                continue;
            }
            try {
                int count = Integer.parseInt(parts[2].trim());
                if (count > 0) {
                    return new CasingReplacement(modelGunId, count);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
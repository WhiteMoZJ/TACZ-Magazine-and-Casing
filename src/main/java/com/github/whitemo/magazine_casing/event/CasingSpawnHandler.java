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
            "pistol", 0.25D,
            "smg", 0.25D,
            "rifle", 0.35D,
            "sniper", 0.35D,
            "shotgun", 0.25D,
            "mg", 0.35D,
            "rpg", 0.25D
    );

    private static final Double DEFAULT_RIGHT_SPEED = 0.25D;

    /** 换弹掉壳的去重标记：记录某玩家最近一次服务端掉壳的 tick 与枪械，用于屏蔽客户端换弹退壳的重复包。 */
    private record ReloadCasingMark(int tick, ResourceLocation gunId) {
    }

    private static final Map<UUID, ReloadCasingMark> RELOAD_CASING_MARKS = new HashMap<>();
    /** 换弹掉壳去重窗口（tick）。窗口内同一把枪的客户端退壳包会被忽略。 */
    private static final int RELOAD_CASING_WINDOW_TICKS = 60;

    /**
     * 客户端发来的精确生成请求（第一人称模型计算出的世界坐标与初速度）。
     */
    public static void spawnCasingFromClient(ServerPlayer player, ResourceLocation gunId, Vec3 worldPos, Vec3 velocity) {
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

        // 弹壳模型替换 + 每次射击抛壳数量
        ResourceLocation casingAmmoId = resolveCasingAmmoId(gunId, ammoId);
        CasingReplacement replacement = resolveCasingReplacement(gunId);
        int count = replacement == null ? 1 : replacement.count();

        for (int i = 0; i < count; i++) {
            spawnCasingAt(level, casingAmmoId, worldPos, velocity);
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
        ResourceLocation casingAmmoId = resolveCasingAmmoId(gunId, ammoId);

        String gunType = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getPojo().getType())
                .orElse("");
        Vec3 look = shooter.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        CasingOffset offset = OFFSET_BY_TYPE.getOrDefault(gunType, DEFAULT_OFFSET);
        Vec3 pos = shooter.position()
                .add(0.0D, offset.height(), 0.0D)
                .add(right.scale(offset.right()))
                .add(look.scale(offset.forward()));

        // 换弹掉壳为服务端触发，拿不到客户端 TACZ 状态机，据枪旋转不适用（slide=false）。
        Vec3 velocity = computeCasingVelocity(shooter, gunId, gunType, false);
        for (int i = 0; i < count; i++) {
            spawnCasingAt(level, casingAmmoId, pos, velocity);
        }

        // 记录去重标记：窗口内同一把枪的客户端换弹退壳包不再重复生成。
        RELOAD_CASING_MARKS.put(shooter.getUUID(), new ReloadCasingMark(shooter.tickCount, gunId));
    }

    /**
     * 计算弹壳初速度（世界坐标）：方向为「玩家右侧 + 向上 + 前方」，并叠加玩家的当前速度。
     * {@code slide} 表示玩家是否处于据枪（斜握）状态，此时右/上的初速度分量绕玩家视角方向
     * （左手系 Z 轴）正向旋转 45°，与画面中手臂/枪身的旋转姿态一致。据枪状态由客户端 TACZ
     * 状态机据枪动画（slide）判定后传入。
     */
    public static Vec3 computeCasingVelocity(LivingEntity shooter, ResourceLocation gunId, String gunType, boolean slide) {
        Vec3 look = shooter.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        Vec3 playerVelocity = shooter.getDeltaMovement();
        String gun = gunId.toString();

        boolean noLateral = ModConfigs.COMMON.noLateralEjectGuns.get().contains(gun);
        boolean reverseEject = ModConfigs.COMMON.reverseEjectGuns.get().contains(gun);
        double rightSpeed;
        if (noLateral) {
            rightSpeed = 0.0D;
        } else {
            rightSpeed = CASING_RIGHT_SPEED.getOrDefault(gunType, DEFAULT_RIGHT_SPEED) + (shooter.getRandom().nextDouble() - 0.5D) * 0.10D;
            if (reverseEject) {
                rightSpeed = -rightSpeed;
            }
        }
        double upSpeed = 0.15D + (shooter.getRandom().nextDouble() - 0.5D) * 0.10D;
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
                right.x * rightSpeed + look.x * forwardSpeed + playerVelocity.x,
                upSpeed + playerVelocity.y,
                right.z * rightSpeed + look.z * forwardSpeed + playerVelocity.z);
    }

    /**
     * 在指定世界位置生成一个弹壳实体，并施加给定的初速度。
     */
    private static void spawnCasingAt(ServerLevel level, ResourceLocation ammoId, Vec3 pos, Vec3 velocity) {
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
    private static ResourceLocation resolveCasingAmmoId(ResourceLocation gunId, ResourceLocation ammoId) {
        CasingReplacement replacement = resolveCasingReplacement(gunId);
        if (replacement == null) {
            return ammoId;
        }
        ResourceLocation modelAmmoId = resolveAmmoId(replacement.modelGunId());
        return modelAmmoId != null ? modelAmmoId : ammoId;
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